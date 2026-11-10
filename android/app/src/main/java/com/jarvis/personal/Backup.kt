package com.jarvis.personal

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// My encrypted backup. One file, locked with a passphrase I choose (PBKDF2 + AES-256-GCM).
// The Groq key is never part of it, and a restore only accepts the settings names I list here.
object Backup {
    private const val MAGIC = "JVB1"
    private const val MAX = 4 * 1024 * 1024
    private val ALLOWED = mapOf(
        "brain" to setOf("history", "practice", "memory", "log", "plan", "pmetrics"),
        "coach" to setOf("sessions", "real"),
        "reminders" to setOf("list"),
        "checkin" to setOf("on", "h", "m"),
        "jarvis" to setOf("confirm", "l2", "style", "theme", "onboarded")
    )

    private fun key(pass: CharArray, salt: ByteArray): SecretKeySpec {
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass, salt, 150_000, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun write(c: Context, uri: Uri, pass: CharArray) {
        val root = JSONObject()
        for ((file, keys) in ALLOWED) {
            val o = JSONObject()
            for ((k, v) in c.getSharedPreferences(file, Context.MODE_PRIVATE).all) if (k in keys && v != null) o.put(k, v)
            root.put(file, o)
        }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        val body = cipher.doFinal(root.toString().toByteArray())
        c.contentResolver.openOutputStream(uri, "wt")?.use { it.write(MAGIC.toByteArray() + salt + iv + body) }
            ?: throw Exception("Could not open the file")
    }

    fun read(c: Context, uri: Uri, pass: CharArray) {
        val data = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("Could not open the file")
        if (data.size > MAX || data.size < 4 + 16 + 12 + 16 || String(data, 0, 4) != MAGIC) throw Exception("This is not a Jarvis backup")
        val salt = data.copyOfRange(4, 20)
        val iv = data.copyOfRange(20, 32)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
            cipher.doFinal(data, 32, data.size - 32)
        } catch (e: AEADBadTagException) {
            throw Exception("Wrong passphrase or damaged file")
        }
        val root = JSONObject(String(plain))
        // check everything first, then write, so a bad file changes nothing
        for ((file, keys) in ALLOWED) {
            val o = root.optJSONObject(file) ?: continue
            for (k in keys) if (o.has(k)) {
                val v = o.get(k)
                val ok = when (k) {
                    "on", "confirm", "l2", "onboarded" -> v is Boolean
                    "h", "m" -> v is Int
                    "style", "theme" -> v is String && v.length < 12
                    else -> v is String && v.length < MAX && (try { if (k == "plan") JSONObject(v) else JSONArray(v); true } catch (e: Exception) { false })
                }
                if (!ok) throw Exception("The backup has a value I do not recognise")
            }
        }
        for ((file, keys) in ALLOWED) {
            val o = root.optJSONObject(file) ?: continue
            val e = c.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            for (k in keys) if (o.has(k)) when (val v = o.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is String -> e.putString(k, v)
            }
            e.apply()
        }
        Reminders.rescheduleAll(c)
        Reminders.ensureCheckin(c)
    }
}
