package uk.newbroman.dune

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ======================================================================= Tapo and Kasa, on your Wi-Fi

/**
 * TP-Link's local "KLAP" protocol (Tapo plugs and bulbs, and newer Kasa): a two-step handshake with
 * your TP-Link account, then AES-encrypted JSON. Pure Kotlin so it can be checked against python-kasa.
 */
object Klap {
    private fun sha256(vararg b: ByteArray) = MessageDigest.getInstance("SHA-256").run { b.forEach { update(it) }; digest() }
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b)
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)

    /** Tapo (KLAP v2) and Kasa (KLAP v1) hash the account differently. */
    fun authV2(user: String, pass: String) = sha256(sha1(user.toByteArray()), sha1(pass.toByteArray()))
    fun authV1(user: String, pass: String) = md5(md5(user.toByteArray()) + md5(pass.toByteArray()))
    fun serverHashV2(local: ByteArray, remote: ByteArray, auth: ByteArray) = sha256(local, remote, auth)
    fun serverHashV1(local: ByteArray, @Suppress("UNUSED_PARAMETER") remote: ByteArray, auth: ByteArray) = sha256(local, auth)
    fun clientHashV2(local: ByteArray, remote: ByteArray, auth: ByteArray) = sha256(remote, local, auth)
    fun clientHashV1(@Suppress("UNUSED_PARAMETER") local: ByteArray, remote: ByteArray, auth: ByteArray) = sha256(remote, auth)

    /** The encryption for one session: key, IV and signature all come from both seeds and the account hash. */
    class Crypto(local: ByteArray, remote: ByteArray, auth: ByteArray) {
        private val key = sha256("lsk".toByteArray(), local, remote, auth).copyOf(16)
        private val fullIv = sha256("iv".toByteArray(), local, remote, auth)
        private val ivBase = fullIv.copyOf(12)
        private val sig = sha256("ldk".toByteArray(), local, remote, auth).copyOf(28)
        var seq: Int = ((fullIv[28].toInt() and 255) shl 24) or ((fullIv[29].toInt() and 255) shl 16) or ((fullIv[30].toInt() and 255) shl 8) or (fullIv[31].toInt() and 255)
            private set
        private fun seqBytes(s: Int) = byteArrayOf((s ushr 24).toByte(), (s ushr 16).toByte(), (s ushr 8).toByte(), s.toByte())
        private fun cipher(mode: Int, s: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ivBase + seqBytes(s)))
        }
        /** Signature (32 bytes) then the ciphertext; and the sequence number to send with it. */
        fun encrypt(msg: String): Pair<ByteArray, Int> {
            seq += 1
            val ct = cipher(Cipher.ENCRYPT_MODE, seq).doFinal(msg.toByteArray())
            return (sha256(sig, seqBytes(seq), ct) + ct) to seq
        }
        fun decrypt(body: ByteArray, s: Int = seq): String = String(cipher(Cipher.DECRYPT_MODE, s).doFinal(body, 32, body.size - 32))
    }

    // ---- A tiny HTTP client over a plain socket: devices on your own network speak plain HTTP,
    // which Android blocks for ordinary connections.
    class Reply(val code: Int, val headers: Map<String, List<String>>, val body: ByteArray)
    fun post(host: String, path: String, body: ByteArray, cookie: String? = null, timeout: Int = 4000): Reply {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 80), timeout); s.soTimeout = timeout
            val head = buildString {
                append("POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/octet-stream\r\nAccept: */*\r\n")
                append("Content-Length: ${body.size}\r\nConnection: close\r\n")
                if (cookie != null) append("Cookie: $cookie\r\n")
                append("\r\n")
            }
            s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
            // Read the headers, then exactly Content-Length bytes (devices may keep the connection open).
            val inp = s.getInputStream(); val buf = ByteArrayOutputStream()
            var split = -1
            while (split < 0) {
                val b = inp.read(); if (b < 0) break
                buf.write(b)
                val a = buf.toByteArray(); val n = a.size
                if (n >= 4 && a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) split = n - 4
            }
            if (split < 0) return Reply(0, emptyMap(), ByteArray(0))
            val headText = String(buf.toByteArray(), 0, split)
            val len = Regex("(?im)^content-length:\\s*(\\d+)").find(headText)?.groupValues?.get(1)?.toIntOrNull()
            val rest = if (len != null) ByteArray(len).also { java.io.DataInputStream(inp).readFully(it) } else inp.readBytes()
            val all = buf.toByteArray() + rest
            val lines = headText.split("\r\n")
            val code = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }
                .groupBy({ it.first }, { it.second })
            var data = all.copyOfRange(split + 4, all.size)
            if (headers["transfer-encoding"]?.any { it.contains("chunked", true) } == true) data = unchunk(data)
            return Reply(code, headers, data)
        }
    }
    private fun unchunk(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); var i = 0
        while (i < b.size) {
            val e = (i until b.size - 1).firstOrNull { b[it] == '\r'.code.toByte() && b[it + 1] == '\n'.code.toByte() } ?: break
            val n = String(b, i, e - i).trim().substringBefore(';').toIntOrNull(16) ?: break
            if (n == 0) break
            out.write(b, e + 2, n); i = e + 2 + n + 2
        }
        return out.toByteArray()
    }

    /** A logged-in connection to one device. [smart] is Tapo's protocol; otherwise Kasa's. */
    class Session(val host: String, val smart: Boolean, private val crypto: Crypto, private val cookie: String?, private val made: Long = System.currentTimeMillis()) {
        val fresh get() = System.currentTimeMillis() - made < 20 * 60_000
        @Synchronized fun send(json0: String): JSONObject {
            // Tapo requests carry a timestamp and a client id, as the official app sends.
            val json = if (smart) JSONObject(json0).put("request_time_milis", System.currentTimeMillis()).put("terminal_uuid", TERMINAL).toString() else json0
            val (payload, seq) = crypto.encrypt(json)
            val r = post(host, "/app/request?seq=$seq", payload, cookie)
            if (r.code != 200) throw IllegalStateException("device said ${r.code}")
            val o = JSONObject(crypto.decrypt(r.body, seq))
            if (smart && o.optInt("error_code", 0) != 0) throw DeviceError(o.optInt("error_code"))
            return o
        }
    }
    class DeviceError(code: Int) : Exception("device error $code")
    private val TERMINAL = java.util.Base64.getEncoder().encodeToString(java.util.UUID.randomUUID().toString().toByteArray())

    class LoginFailed : Exception("Wrong TP-Link email or password for this device")

    /** Handshake with [host]; null if it isn't a KLAP device at all. */
    fun connect(host: String, user: String, pass: String): Session? {
        val local = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val r1 = runCatching { post(host, "/app/handshake1", local, timeout = 2500) }.getOrNull() ?: return null
        if (r1.code != 200 || r1.body.size < 48) return null
        val remote = r1.body.copyOf(16); val server = r1.body.copyOfRange(16, 48)
        val cookie = r1.headers["set-cookie"]?.firstOrNull { it.startsWith("TP_SESSIONID") }?.substringBefore(';')
        // Which account hash does the device recognise? (Also the factory-blank one, for unclaimed devices.)
        // Your account first, then the factory accounts unclaimed devices answer to (as python-kasa does), then blank.
        val accounts = listOf(user to pass, "kasa@tp-link.net" to "kasaSetup", "test@tp-link.net" to "test", "" to "")
        val (auth, smart) = accounts.firstNotNullOfOrNull { (u, p) ->
            val v2 = authV2(u, p); val v1 = authV1(u, p)
            when {
                serverHashV2(local, remote, v2).contentEquals(server) -> v2 to true
                serverHashV1(local, remote, v1).contentEquals(server) -> v1 to false
                else -> null
            }
        } ?: throw LoginFailed()
        val h2 = if (smart) clientHashV2(local, remote, auth) else clientHashV1(local, remote, auth)
        val r2 = post(host, "/app/handshake2", h2, cookie)
        if (r2.code != 200) throw LoginFailed()
        return Session(host, smart, Crypto(local, remote, auth), cookie)
    }

    // ---- Older Kasa plugs: plain JSON on port 9999, "encrypted" with a simple running XOR.
    fun xor(plain: ByteArray): ByteArray { var k = 171; return ByteArray(plain.size) { i -> val c = (plain[i].toInt() xor k); k = c and 255; c.toByte() } }
    fun unxor(enc: ByteArray): ByteArray { var k = 171; return ByteArray(enc.size) { i -> val b = enc[i].toInt() and 255; val p = b xor k; k = b; p.toByte() } }
    fun legacy(host: String, json: String, timeout: Int = 2500): JSONObject {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 9999), timeout); s.soTimeout = timeout
            val e = xor(json.toByteArray()); val n = e.size
            s.getOutputStream().apply { write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + e); flush() }
            val inp = java.io.DataInputStream(s.getInputStream()); val len = inp.readInt()
            val buf = ByteArray(len); inp.readFully(buf)
            return JSONObject(String(unxor(buf)))
        }
    }
}

/** A plug or bulb on your Wi-Fi. kind: "tapo", "kasa" (KLAP) or "kasa-old" (port 9999). */
data class Plug(val ip: String, val name: String, val model: String, val kind: String, val bulb: Boolean = false,
                val on: Boolean? = null, val watts: Double? = null, val error: String? = null)

object Plugs {
    private val sessions = HashMap<String, Klap.Session>()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dune", 0)
    fun account(ctx: Context): Pair<String, String>? {
        val u = Secure.get(ctx, "tplink_user") ?: return null; val p = Secure.get(ctx, "tplink_pass") ?: return null
        return u to p
    }
    fun setAccount(ctx: Context, user: String?, pass: String?) { Secure.put(ctx, "tplink_user", user?.trim()); Secure.put(ctx, "tplink_pass", pass); synchronized(sessions) { sessions.clear() } }

    fun saved(ctx: Context): List<Plug> = runCatching {
        val a = JSONArray(prefs(ctx).getString("plugs", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Plug(it.getString("ip"), it.getString("name"), it.optString("model"), it.getString("kind"), it.optBoolean("bulb")) }
    }.getOrDefault(emptyList())
    fun forget(ctx: Context, ip: String) = save(ctx, saved(ctx).filter { it.ip != ip })
    fun save(ctx: Context, list: List<Plug>) = prefs(ctx).edit().putString("plugs", JSONArray(list.map {
        JSONObject().put("ip", it.ip).put("name", it.name).put("model", it.model).put("kind", it.kind).put("bulb", it.bulb) }).toString()).apply()
    /** The last states seen, for Home's summary line (no network needed). */
    fun lastStates(ctx: Context): Map<String, Boolean> = runCatching {
        JSONObject(prefs(ctx).getString("plugStates", "{}")).let { o -> o.keys().asSequence().associateWith { o.getBoolean(it) } }
    }.getOrDefault(emptyMap())
    private fun keepStates(ctx: Context, list: List<Plug>) {
        val o = JSONObject(); list.forEach { p -> p.on?.let { o.put(p.ip, it) } }; prefs(ctx).edit().putString("plugStates", o.toString()).apply()
    }

    private fun session(ctx: Context, ip: String): Klap.Session? {
        synchronized(sessions) { sessions[ip]?.takeIf { it.fresh }?.let { return it } }
        val (u, p) = account(ctx) ?: throw IllegalStateException("Add your TP-Link account first")
        val s = Klap.connect(ip, u, p) ?: return null
        synchronized(sessions) { sessions[ip] = s }
        return s
    }
    private fun <T> withSession(ctx: Context, ip: String, f: (Klap.Session) -> T): T {
        val s = session(ctx, ip) ?: throw IllegalStateException("not answering")
        return runCatching { f(s) }.getOrElse {
            if (it is Klap.DeviceError) throw it
            // An old session: log in again once.
            synchronized(sessions) { sessions.remove(ip) }
            f(session(ctx, ip) ?: throw it)
        }
    }
    private fun isBulb(i: JSONObject) = i.has("light_state") || i.optString("mic_type").contains("BULB", true) || i.optString("type").contains("BULB", true)
    private fun b64(s: String) = runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) }.getOrDefault(s)

    /** What a device is and whether it's on. */
    fun read(ctx: Context, p: Plug): Plug = runCatching {
        when (p.kind) {
            "kasa-old" -> Klap.legacy(p.ip, """{"system":{"get_sysinfo":{}}}""").getJSONObject("system").getJSONObject("get_sysinfo").let { i ->
                p.copy(name = i.optString("alias", p.name), model = i.optString("model", p.model), bulb = isBulb(i),
                    on = i.optInt("relay_state", i.optJSONObject("light_state")?.optInt("on_off") ?: 0) == 1, error = null) }
            else -> withSession(ctx, p.ip) { s ->
                if (s.smart) {
                    val r = s.send("""{"method":"get_device_info"}""").getJSONObject("result")
                    val watts = if (r.optString("model").startsWith("P11")) runCatching {
                        s.send("""{"method":"get_energy_usage"}""").getJSONObject("result").optDouble("current_power") / 1000.0 }.getOrNull() else null
                    p.copy(name = b64(r.optString("nickname")).ifBlank { p.name }, model = r.optString("model", p.model), kind = "tapo",
                        bulb = r.optString("type").contains("BULB"), on = r.optBoolean("device_on"), watts = watts, error = null)
                } else {
                    val i = s.send("""{"system":{"get_sysinfo":{}}}""").getJSONObject("system").getJSONObject("get_sysinfo")
                    p.copy(name = i.optString("alias", p.name), model = i.optString("model", p.model), kind = "kasa", bulb = isBulb(i),
                        on = i.optInt("relay_state", i.optJSONObject("light_state")?.optInt("on_off") ?: 0) == 1, error = null)
                }
            }
        }
    }.getOrElse { p.copy(error = it.message ?: "not answering") }

    fun set(ctx: Context, p: Plug, on: Boolean): Plug {
        runCatching {
            when (p.kind) {
                "kasa-old" -> Klap.legacy(p.ip, if (p.bulb) """{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":${if (on) 1 else 0}}}}"""
                    else """{"system":{"set_relay_state":{"state":${if (on) 1 else 0}}}}""")
                else -> withSession(ctx, p.ip) { s ->
                    if (s.smart) s.send("""{"method":"set_device_info","params":{"device_on":$on}}""")
                    else s.send(if (p.bulb) """{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":${if (on) 1 else 0}}}}"""
                        else """{"system":{"set_relay_state":{"state":${if (on) 1 else 0}}}}""")
                }
            }
        }.onFailure { return p.copy(error = it.message ?: "didn't answer") }
        return read(ctx, p).also { r -> r.on?.let { on2 ->
            val o = runCatching { JSONObject(prefs(ctx).getString("plugStates", "{}")) }.getOrDefault(JSONObject())
            prefs(ctx).edit().putString("plugStates", o.put(r.ip, on2).toString()).apply() } }
    }

    suspend fun readAll(ctx: Context, list: List<Plug>): List<Plug> = withContext(Dispatchers.IO) {
        coroutineScope { list.map { p -> async { read(ctx, p) } }.awaitAll() }.also { keepStates(ctx, it) }
    }

    /** Looks across your Wi-Fi for Tapo and Kasa devices (every address, a few seconds). */
    suspend fun discover(ctx: Context, progress: (Int, Int) -> Unit): List<Plug> = withContext(Dispatchers.IO) {
        val w = Net.wifi(ctx)
        val me = w.ip ?: throw IllegalStateException("Connect to your home Wi-Fi first")
        val (u, pw) = account(ctx) ?: throw IllegalStateException("Add your TP-Link account first")
        val hosts = Lan.hosts(me, w.prefix)
        val gate = Semaphore(48); var done = 0
        val found = coroutineScope {
            hosts.map { h -> async {
                gate.withPermit {
                    val r = runCatching {
                        val open80 = runCatching { Socket().use { it.connect(InetSocketAddress(h, 80), 300) }; true }.getOrDefault(false)
                        val open9999 = runCatching { Socket().use { it.connect(InetSocketAddress(h, 9999), 300) }; true }.getOrDefault(false)
                        val viaKlap = if (open80) runCatching { Klap.connect(h, u, pw) }.fold(
                            { s -> s?.let { synchronized(sessions) { sessions[h] = it }; read(ctx, Plug(h, h, "", if (it.smart) "tapo" else "kasa")) } },
                            { e -> if (e is Klap.LoginFailed) Plug(h, "TP-Link device at $h", "", "tapo", error = e.message) else null }) else null
                        viaKlap ?: if (open9999) read(ctx, Plug(h, h, "", "kasa-old")).takeIf { it.error == null } else null
                    }.getOrNull()
                    synchronized(this@coroutineScope) { done++; progress(done, hosts.size) }
                    r
                }
            } }.awaitAll().filterNotNull()
        }
        // Add what was found to what was already saved – a device that missed this scan stays (it may be asleep).
        val byIp = saved(ctx).associateBy { it.ip }.toMutableMap()
        found.forEach { f -> byIp[f.ip] = f }
        val missed = byIp.keys - found.map { it.ip }.toSet()
        val all = byIp.values.map { if (it.ip in missed) it.copy(error = "not found in the last scan") else it }.sortedBy { it.name.lowercase() }
        save(ctx, all); keepStates(ctx, found); all
    }
}

// ======================================================================= tado° heating

/**
 * tado°'s web API. You sign in once on tado's own page (a code shown in Dune); Dune keeps the
 * refresh token encrypted. tado allows only about 100 calls a day without its subscription,
 * so readings are cached and only refreshed every ten minutes unless you pull.
 */
object Tado {
    private const val CLIENT = "1bb50063-6b0c-4d11-bd99-387f4a91cc46"
    private const val AUTH = "https://login.tado.com/oauth2"
    private const val API = "https://my.tado.com/api/v2"
    private const val HOPS = "https://hops.tado.com"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dune", 0)

    data class Zone(val id: Int, val name: String, val type: String, val inside: Double?, val humidity: Double?,
                    val target: Double?, val on: Boolean, val manual: Boolean, val heatingPct: Int?)
    data class State(val home: String, val zones: List<Zone>, val away: Boolean?, val at: Long, val callsLeft: Int?, val locked: Boolean = false)
    data class Login(val userCode: String, val url: String, val device: String, val interval: Int, val expires: Long)

    fun linked(ctx: Context) = Secure.get(ctx, "tado_refresh") != null
    fun unlink(ctx: Context) { Secure.put(ctx, "tado_refresh", null); prefs(ctx).edit().remove("tadoState").remove("tadoHome").remove("tadoX").apply(); access = null }

    private fun form(url: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 12_000; c.readTimeout = 15_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(params.entries.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }.toByteArray()) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: "{}"
        return code to runCatching { JSONObject(body) }.getOrDefault(JSONObject())
    }

    /** Step 1 of signing in: a code to confirm on tado's website. */
    suspend fun startLogin(): Login = withContext(Dispatchers.IO) {
        val (code, o) = form("$AUTH/device_authorize", mapOf("client_id" to CLIENT, "scope" to "offline_access"))
        if (code !in 200..299) throw IllegalStateException("tado said $code: " + o.optString("error_description", o.optString("error")))
        Login(o.getString("user_code"), o.optString("verification_uri_complete", o.optString("verification_uri")), o.getString("device_code"),
            o.optInt("interval", 5), System.currentTimeMillis() + o.optInt("expires_in", 300) * 1000L)
    }
    /** Step 2: true once you've confirmed (call every [Login.interval] seconds); false while waiting. */
    class SlowDown : Exception()
    suspend fun pollLogin(ctx: Context, l: Login): Boolean = withContext(Dispatchers.IO) {
        val (code, o) = form("$AUTH/token", mapOf("client_id" to CLIENT, "device_code" to l.device, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        if (code in 200..299) { keep(ctx, o); true }
        else when (o.optString("error")) { "authorization_pending" -> false; "slow_down" -> throw SlowDown(); else -> throw IllegalStateException(o.optString("error_description", "tado sign-in failed")) }
    }

    @Volatile private var access: Pair<String, Long>? = null
    private fun keep(ctx: Context, o: JSONObject) {
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { Secure.put(ctx, "tado_refresh", it) }   // tado replaces it every time
        access = o.getString("access_token") to System.currentTimeMillis() + (o.optLong("expires_in", 600) - 30) * 1000
    }
    @Synchronized private fun token(ctx: Context): String {
        access?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val r = Secure.get(ctx, "tado_refresh") ?: throw IllegalStateException("Sign in to tado first")
        val (code, o) = form("$AUTH/token", mapOf("client_id" to CLIENT, "grant_type" to "refresh_token", "refresh_token" to r))
        if (code !in 200..299) {
            if (code == 400 || code == 401) { Secure.put(ctx, "tado_refresh", null); throw IllegalStateException("tado sign-in has expired – sign in again") }
            throw IllegalStateException("tado isn't answering ($code) – try again later")
        }
        keep(ctx, o); return access!!.first
    }

    private var callsLeft: Int? = null
    private class Unauthorised : Exception()
    private fun call(ctx: Context, method: String, url: String, body: JSONObject? = null): String =
        try { call1(ctx, method, url, body) } catch (e: Unauthorised) { access = null; call1(ctx, method, url, body) }
    private fun call1(ctx: Context, method: String, url: String, body: JSONObject? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 12_000; c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Bearer " + token(ctx))
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        // "RateLimit: "perday";r=93;t=…" – how many calls are left today.
        c.getHeaderField("ratelimit")?.let { h -> Regex("r=(\\d+)").find(h)?.groupValues?.get(1)?.toIntOrNull()?.let { callsLeft = it } }
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        if (code == 429) throw IllegalStateException("tado's daily limit is used up – try again tomorrow")
        if (code == 401) throw Unauthorised()
        if (code !in 200..299) throw IllegalStateException("tado said $code")
        return text
    }

    private fun home(ctx: Context): Pair<Long, Boolean> {
        val p = prefs(ctx)
        val id = p.getLong("tadoHome", 0)
        if (id > 0) return id to p.getBoolean("tadoX", false)
        val me = JSONObject(call(ctx, "GET", "$API/me"))
        val h = me.getJSONArray("homes").getJSONObject(0).getLong("id")
        val x = runCatching { JSONObject(call(ctx, "GET", "$API/homes/$h")).optString("generation") == "LINE_X" }.getOrDefault(false)
        p.edit().putLong("tadoHome", h).putBoolean("tadoX", x).apply()
        return h to x
    }

    fun cached(ctx: Context): State? = runCatching { parseCache(JSONObject(prefs(ctx).getString("tadoState", null) ?: return null)) }.getOrNull()
    private fun parseCache(o: JSONObject): State {
        val a = o.getJSONArray("zones")
        return State(o.optString("home"), (0 until a.length()).map { a.getJSONObject(it) }.map { z ->
            Zone(z.getInt("id"), z.getString("name"), z.optString("type"), z.optD("inside"), z.optD("humidity"), z.optD("target"),
                z.optBoolean("on"), z.optBoolean("manual"), z.optInt("pct", -1).takeIf { it >= 0 })
        }, if (o.has("away")) o.getBoolean("away") else null, o.getLong("at"), o.optInt("left", -1).takeIf { it >= 0 }, o.optBoolean("locked"))
    }
    private fun JSONObject.optD(k: String) = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun save(ctx: Context, s: State) {
        prefs(ctx).edit().putString("tadoState", JSONObject().put("home", s.home).put("at", s.at).put("left", s.callsLeft ?: -1).put("locked", s.locked).apply { s.away?.let { put("away", it) } }
            .put("zones", JSONArray(s.zones.map { z -> JSONObject().put("id", z.id).put("name", z.name).put("type", z.type).put("inside", z.inside)
                .put("humidity", z.humidity).put("target", z.target).put("on", z.on).put("manual", z.manual).put("pct", z.heatingPct ?: -1) })).toString()).apply()
    }

    /** Rooms and their temperatures; from the cache unless it's over ten minutes old or [force]. */
    suspend fun state(ctx: Context, force: Boolean = false): State = withContext(Dispatchers.IO) {
        cached(ctx)?.takeIf { !force && System.currentTimeMillis() - it.at < 10 * 60_000 }?.let { return@withContext it }
        val (h, x) = home(ctx)
        val zones = if (x) {
            val a = JSONArray(call(ctx, "GET", "$HOPS/homes/$h/rooms"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
                val sd = r.optJSONObject("sensorDataPoints"); val st = r.optJSONObject("setting")
                Zone(r.getInt("id"), r.optString("name"), "HEATING", sd?.optJSONObject("insideTemperature")?.optD("value"),
                    sd?.optJSONObject("humidity")?.optD("percentage"), st?.optJSONObject("temperature")?.optD("value"),
                    st?.optString("power") == "ON", !r.isNull("manualControlTermination") && r.has("manualControlTermination"),
                    r.optJSONObject("heatingPower")?.optInt("percentage"))
            }
        } else {
            val names = JSONArray(call(ctx, "GET", "$API/homes/$h/zones")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val states = JSONObject(call(ctx, "GET", "$API/homes/$h/zoneStates")).getJSONObject("zoneStates")
            names.filter { it.optString("type") != "AIR_CONDITIONING" }.map { z ->
                val s = states.optJSONObject(z.getInt("id").toString())
                val set = s?.optJSONObject("setting"); val sd = s?.optJSONObject("sensorDataPoints")
                Zone(z.getInt("id"), z.optString("name"), z.optString("type"), sd?.optJSONObject("insideTemperature")?.optD("celsius"),
                    sd?.optJSONObject("humidity")?.optD("percentage"), set?.optJSONObject("temperature")?.optD("celsius"),
                    set?.optString("power") == "ON", s?.has("overlay") == true && !s.isNull("overlay"),
                    s?.optJSONObject("activityDataPoints")?.optJSONObject("heatingPower")?.optD("percentage")?.toInt())
            }
        }
        val st = runCatching { JSONObject(call(ctx, "GET", "$API/homes/$h/state")) }.getOrNull()
        State("", zones, st?.optString("presence")?.let { it == "AWAY" }, System.currentTimeMillis(), callsLeft, st?.optBoolean("presenceLocked") == true).also { save(ctx, it) }
    }

    /** Set a room: a temperature (null = off) until the next scheduled change. */
    suspend fun set(ctx: Context, z: Zone, celsius: Double?, on: Boolean = celsius != null) = withContext(Dispatchers.IO) {
        val (h, x) = home(ctx)
        if (x) call(ctx, "POST", "$HOPS/homes/$h/rooms/${z.id}/manualControl", JSONObject()
            .put("setting", JSONObject().put("power", if (on) "ON" else "OFF").apply { celsius?.let { put("temperature", JSONObject().put("value", it)) } })
            .put("termination", JSONObject().put("type", "NEXT_TIME_BLOCK")))
        else call(ctx, "PUT", "$API/homes/$h/zones/${z.id}/overlay", JSONObject()
            .put("setting", JSONObject().put("type", z.type.ifBlank { "HEATING" }).put("power", if (on) "ON" else "OFF")
                .apply { if (on) celsius?.let { put("temperature", JSONObject().put("celsius", it)) } })
            .put("termination", JSONObject().put("typeSkillBasedApp", "NEXT_TIME_BLOCK")))
        patch(ctx, z.id) { it.copy(target = celsius ?: it.target, on = on, manual = true) }
    }
    /** Back to the room's schedule. */
    suspend fun resume(ctx: Context, z: Zone) = withContext(Dispatchers.IO) {
        val (h, x) = home(ctx)
        if (x) call(ctx, "POST", "$HOPS/homes/$h/rooms/${z.id}/resumeSchedule")
        else call(ctx, "DELETE", "$API/homes/$h/zones/${z.id}/overlay")
        patch(ctx, z.id) { it.copy(manual = false) }
    }
    /** Away (true), home (false) or back to automatic geofencing (null). */
    suspend fun presence(ctx: Context, away: Boolean?) = withContext(Dispatchers.IO) {
        val (h, _) = home(ctx)
        if (away == null) call(ctx, "DELETE", "$API/homes/$h/presenceLock")
        else call(ctx, "PUT", "$API/homes/$h/presenceLock", JSONObject().put("homePresence", if (away) "AWAY" else "HOME"))
        cached(ctx)?.let { save(ctx, it.copy(away = away ?: it.away, locked = away != null, callsLeft = callsLeft ?: it.callsLeft)) }
    }
    @Synchronized private fun patch(ctx: Context, id: Int, f: (Zone) -> Zone) {
        cached(ctx)?.let { s -> save(ctx, s.copy(zones = s.zones.map { if (it.id == id) f(it) else it }, callsLeft = callsLeft ?: s.callsLeft)) }
    }
}

// ======================================================================= scenes

/** A scene: plugs on or off, rooms set or back to schedule, tado home/away – saved from how things are now. */
data class Scene(val name: String, val acts: List<SceneAct>)
data class SceneAct(val kind: String, val target: String, val value: String)   // kind: plug | room | away

object Scenes {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dune", 0)
    fun all(ctx: Context): List<Scene> = runCatching {
        val a = JSONArray(prefs(ctx).getString("scenes", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
            val acts = s.getJSONArray("acts").let { b -> (0 until b.length()).map { b.getJSONObject(it) }.map { SceneAct(it.getString("k"), it.getString("t"), it.getString("v")) } }
            Scene(s.getString("name"), acts)
        }
    }.getOrDefault(emptyList())
    fun save(ctx: Context, list: List<Scene>) = prefs(ctx).edit().putString("scenes", JSONArray(list.map { s ->
        JSONObject().put("name", s.name).put("acts", JSONArray(s.acts.map { JSONObject().put("k", it.kind).put("t", it.target).put("v", it.value) })) }).toString()).apply()

    /** Runs a scene; returns a short report of anything that didn't work. */
    suspend fun run(ctx: Context, s: Scene): String? = withContext(Dispatchers.IO) {
        val problems = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val plugs = Plugs.saved(ctx).associateBy { it.ip }
        val rooms = if (s.acts.any { it.kind == "room" }) runCatching { Tado.cached(ctx)?.zones }.getOrNull()?.associateBy { it.id.toString() } else null
        coroutineScope {
            // Plugs all at once (they're on your Wi-Fi); tado one call after another.
            val plugJobs = s.acts.filter { it.kind == "plug" }.map { a -> async {
                runCatching { plugs[a.target]?.let { p -> Plugs.set(ctx, p, a.value == "on").error?.let { problems += "${p.name}: $it" } } }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; problems += (it.message ?: "failed") }
            } }
            s.acts.filter { it.kind != "plug" }.forEach { a ->
                runCatching {
                    when (a.kind) {
                        "room" -> rooms?.get(a.target)?.let { z -> when (a.value) {
                            "auto" -> if (z.manual) Tado.resume(ctx, z)   // already on its schedule: no call needed
                            "off" -> Tado.set(ctx, z, null)
                            "on" -> Tado.set(ctx, z, null, on = true)
                            else -> Tado.set(ctx, z, a.value.toDouble()) } }
                        "away" -> Tado.presence(ctx, when (a.value) { "away" -> true; "home" -> false; else -> null })
                    }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; problems += (it.message ?: "failed") }
            }
            plugJobs.awaitAll()
        }
        problems.distinct().takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
