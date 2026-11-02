package uk.newbroman.dune

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup and restore of everything Dune keeps: settings, hubs, tasks, notes, groups, scenes, shop
 * lists, map layers, survey, tracks and scan history – and, if you give a password, the saved keys
 * (AI, tado, TP-Link, Tuya, GitHub, L14, Coinbase), encrypted with that password. One .zip in
 * Downloads/Dune. Photos and recordings stay where they are (they're in your gallery already).
 */
object Backup {
    private const val PREFS = "dune"
    private val FILES = listOf("gis", "tracks", "scans.json", "webapps.json")
    private const val MAX_FILE = 30L * 1024 * 1024

    // ---- the password-protected part
    fun seal(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return salt + c.iv + c.doFinal(plain)
    }
    fun open(blob: ByteArray, password: String): ByteArray {
        val salt = blob.copyOf(16); val iv = blob.copyOfRange(16, 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)) }
        return c.doFinal(blob, 28, blob.size - 28)
    }
    private fun derive(password: String, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).encoded, "AES")

    // ---- settings as typed JSON
    fun prefsJson(all: Map<String, *>): JSONObject = JSONObject().apply {
        all.forEach { (k, v) ->
            when (v) {
                is Boolean -> put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> put(k, JSONObject().put("t", "i").put("v", v))
                is Long -> put(k, JSONObject().put("t", "l").put("v", v))
                is Float -> put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                is String -> put(k, JSONObject().put("t", "s").put("v", v))
                is Set<*> -> put(k, JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() })))
            }
        }
    }
    private fun restorePrefs(ctx: Context, o: JSONObject) {
        val e = ctx.getSharedPreferences(PREFS, 0).edit().clear()
        o.keys().forEach { k ->
            val x = o.getJSONObject(k)
            when (x.getString("t")) {
                "b" -> e.putBoolean(k, x.getBoolean("v")); "i" -> e.putInt(k, x.getInt("v")); "l" -> e.putLong(k, x.getLong("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat()); "s" -> e.putString(k, x.getString("v"))
                "ss" -> e.putStringSet(k, x.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        e.commit()
    }

    /** Writes the backup; returns where it went and a one-line summary. */
    suspend fun export(ctx: Context, password: String?): Pair<Uri, String> = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        var files = 0; var secrets = 0
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("dune.json", JSONObject().put("version", 1).put("made", System.currentTimeMillis())
                .put("prefs", prefsJson(ctx.getSharedPreferences(PREFS, 0).all)).toString(1).toByteArray())
            if (!password.isNullOrEmpty()) {
                val s = JSONObject()
                ctx.getSharedPreferences("dune-secure", 0).all.keys.forEach { k ->
                    if (k == "cb") Coinbase.load(ctx)?.let { s.put("cb", JSONObject().put("name", it.name).put("pem", it.pem)) }
                    else Secure.get(ctx, k)?.let { s.put(k, it) }
                }
                secrets = s.length()
                put("keys.sealed", seal(s.toString().toByteArray(), password))
            }
            FILES.map { File(ctx.filesDir, it) }.filter { it.exists() }.forEach { root ->
                root.walkTopDown().filter { it.isFile && it.length() < MAX_FILE }.forEach { f ->
                    put("files/" + f.relativeTo(ctx.filesDir).path, f.readBytes()); files++
                }
            }
        }
        val name = "Dune-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.UK).format(java.util.Date()) + ".zip"
        val u = Gis.saveExport(ctx, name, "application/zip", out.toByteArray()) ?: throw IllegalStateException("Couldn't save to Downloads")
        u to "$name · settings, $files files" + (if (secrets > 0) ", $secrets saved keys (password-protected)" else ", no saved keys")
    }

    /** Is there a password-protected keys part in this backup? */
    suspend fun hasKeys(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        ZipInputStream(ctx.contentResolver.openInputStream(uri)!!).use { z ->
            generateSequence { z.nextEntry }.any { it.name == "keys.sealed" }
        }
    }

    /** Replaces Dune's settings and files with the backup's. Dune restarts afterwards. */
    suspend fun restore(ctx: Context, uri: Uri, password: String?): String = withContext(Dispatchers.IO) {
        var prefs: JSONObject? = null; var sealed: ByteArray? = null
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open that file")).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                val bytes = z.readBytes()
                when {
                    e.name == "dune.json" -> prefs = JSONObject(String(bytes)).getJSONObject("prefs")
                    e.name == "keys.sealed" -> sealed = bytes
                    e.name.startsWith("files/") && !e.name.contains("..") -> files[e.name.removePrefix("files/")] = bytes
                }
            }
        }
        val p = prefs ?: throw IllegalStateException("That isn't a Dune backup")
        // Check the password before changing anything.
        val keys = sealed?.let { s ->
            if (password.isNullOrEmpty()) null
            else runCatching { JSONObject(String(open(s, password))) }.getOrElse { throw IllegalStateException("Wrong password for the saved keys") }
        }
        restorePrefs(ctx, p)
        files.forEach { (path, bytes) ->
            val f = File(ctx.filesDir, path)
            if (f.canonicalPath.startsWith(ctx.filesDir.canonicalPath + File.separator)) { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
        }
        keys?.let { k ->
            k.keys().forEach { name ->
                if (name == "cb") k.getJSONObject("cb").let { Coinbase.save(ctx, Coinbase.ApiKey(it.getString("name"), it.getString("pem"))) }
                else Secure.put(ctx, name, k.getString(name))
            }
            // Flags that say a key exists live in the plain settings; make them match.
            Brain.PROVIDERS.forEach { pr -> ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("aiHasKey_" + pr.id, Secure.get(ctx, "ai_" + pr.id) != null).apply() }
        }
        "Restored settings and ${files.size} files" + (keys?.let { ", ${it.length()} saved keys" } ?: if (sealed != null) " (keys skipped – no password)" else "")
    }
}
