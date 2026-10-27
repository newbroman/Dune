package uk.newbroman.dune

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Holding(val currency: String, val amount: Double, val valueGbp: Double?)
data class Portfolio(val holdings: List<Holding>, val totalGbp: Double, val at: Long)

/**
 * Coinbase balances through a Coinbase Developer Platform API key (ECDSA / ES256).
 * The key should be created with View permission only. It is stored encrypted with a key
 * held in the phone's Android Keystore.
 */
object Coinbase {
    private const val HOST = "api.coinbase.com"

    // ---------- key handling (pure Kotlin, unit-tested)

    data class ApiKey(val name: String, val pem: String)

    /** Accepts the downloaded key JSON, or "name" + PEM pasted separately. */
    fun parseKey(nameField: String, secretField: String): ApiKey? {
        val s = secretField.trim()
        if (s.startsWith("{")) runCatching {
            val o = JSONObject(s)
            val name = o.optString("name").ifBlank { o.optString("id") }.ifBlank { nameField.trim() }
            val pem = o.optString("privateKey")
            if (name.isNotBlank() && pem.isNotBlank()) return ApiKey(name, pem.replace("\\n", "\n"))
        }
        if (nameField.isBlank() || !s.contains("PRIVATE KEY")) return null
        return ApiKey(nameField.trim(), s.replace("\\n", "\n"))
    }

    private val P256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    /** "BEGIN EC PRIVATE KEY" (SEC1) or "BEGIN PRIVATE KEY" (PKCS#8) → a P-256 private key. */
    fun privateKey(pem: String): PrivateKey {
        val b64 = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        val der = java.util.Base64.getMimeDecoder().decode(b64)
        val kf = KeyFactory.getInstance("EC")
        if (pem.contains("BEGIN PRIVATE KEY")) return kf.generatePrivate(PKCS8EncodedKeySpec(der))
        // SEC1: SEQUENCE { INTEGER 1, OCTET STRING d, [0] params, [1] public key }
        var i = 0
        fun readLen(): Int {
            var len = der[i++].toInt() and 0xff
            if (len and 0x80 != 0) { val n = len and 0x7f; len = 0; repeat(n) { len = (len shl 8) or (der[i++].toInt() and 0xff) } }
            return len
        }
        require(der[i++] == 0x30.toByte()) { "Not an EC private key" }; readLen()
        require(der[i++] == 0x02.toByte()); val vLen = readLen(); i += vLen   // version (read length first: i moves)
        require(der[i++] == 0x04.toByte()) { "Not an EC private key" }
        val n = readLen()
        val d = BigInteger(1, der.copyOfRange(i, i + n))
        return kf.generatePrivate(ECPrivateKeySpec(d, P256))
    }

    private fun b64url(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** JOSE wants the raw 64-byte r||s, Java gives DER. */
    fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun int(): ByteArray {
            require(der[i++] == 0x02.toByte()); val len = der[i++].toInt()
            val v = der.copyOfRange(i, i + len); i += len
            val t = v.dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - t.size) + t
        }
        return int() + int()
    }

    /** A fresh JWT for one request, valid two minutes. */
    fun jwt(key: ApiKey, method: String, path: String, now: Long = System.currentTimeMillis() / 1000): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val header = JSONObject().put("alg", "ES256").put("kid", key.name).put("nonce", nonce).put("typ", "JWT")
        val payload = JSONObject().put("sub", key.name).put("iss", "cdp").put("nbf", now).put("exp", now + 120)
            .put("uri", "$method $HOST$path")
        val input = b64url(header.toString().toByteArray()) + "." + b64url(payload.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey(key.pem)); update(input.toByteArray()) }.sign()
        return input + "." + b64url(derToRaw(sig))
    }

    // ---------- network

    private fun http(url: String, bearer: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "DuneLauncher")
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        try {
            val code = c.responseCode
            if (code == 401) throw IllegalStateException("Coinbase refused the key – check the key name and that it's an ECDSA key")
            if (code !in 200..299) throw IllegalStateException("Coinbase error $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    suspend fun fetch(key: ApiKey): Portfolio = withContext(Dispatchers.IO) {
        val balances = HashMap<String, Double>()
        var cursor = ""
        do {
            val path = "/api/v3/brokerage/accounts"
            val q = "?limit=250" + if (cursor.isNotEmpty()) "&cursor=$cursor" else ""
            val o = JSONObject(http("https://$HOST$path$q", jwt(key, "GET", path)))
            val acc = o.optJSONArray("accounts")
            if (acc != null) for (k in 0 until acc.length()) {
                val a = acc.getJSONObject(k)
                val cur = a.optString("currency")
                val avail = a.optJSONObject("available_balance")?.optString("value")?.toDoubleOrNull() ?: 0.0
                val hold = a.optJSONObject("hold")?.optString("value")?.toDoubleOrNull() ?: 0.0
                if (avail + hold > 0) balances[cur] = (balances[cur] ?: 0.0) + avail + hold
            }
            cursor = if (o.optBoolean("has_next")) o.optString("cursor") else ""
        } while (cursor.isNotEmpty())

        // One public call prices everything: 1 GBP = rate units of each currency.
        val rates = JSONObject(http("https://$HOST/v2/exchange-rates?currency=GBP")).getJSONObject("data").getJSONObject("rates")
        val holdings = balances.map { (cur, amt) ->
            val r = rates.optString(cur).toDoubleOrNull()
            Holding(cur, amt, if (cur == "GBP") amt else if (r != null && r > 0) amt / r else null)
        }.sortedByDescending { it.valueGbp ?: 0.0 }
        Portfolio(holdings, holdings.sumOf { it.valueGbp ?: 0.0 }, System.currentTimeMillis())
    }

    // ---------- secure storage

    private const val ALIAS = "dune-coinbase"

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun save(ctx: Context, key: ApiKey?) {
        val p = ctx.getSharedPreferences("dune-secure", Context.MODE_PRIVATE)
        if (key == null) { p.edit().clear().apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val plain = JSONObject().put("name", key.name).put("pem", key.pem).toString().toByteArray()
        val blob = c.iv + c.doFinal(plain)
        p.edit().putString("cb", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun load(ctx: Context): ApiKey? = runCatching {
        val s = ctx.getSharedPreferences("dune-secure", Context.MODE_PRIVATE).getString("cb", null) ?: return null
        val blob = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, blob, 0, 12)) }
        val o = JSONObject(String(c.doFinal(blob, 12, blob.size - 12)))
        ApiKey(o.getString("name"), o.getString("pem"))
    }.getOrNull()
}
