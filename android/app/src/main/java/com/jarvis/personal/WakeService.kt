package com.jarvis.personal

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

// The wake word. A small offline recognizer (Vosk) listens for my phrase on the phone itself.
// Nothing it hears is saved or sent anywhere, the audio only reaches Groq after the phrase is heard
// and the normal conversation starts. Android shows a notification the whole time it is on.
class WakeService : Service() {

    companion object {
        const val CHANNEL = "jarvis_wake"
        private const val NOTE_ID = 7
        private const val HEARD_ID = 8
        val PHRASES = listOf("hey jarvis", "jarvis", "computer")

        @Volatile var running = false
        @Volatile var capturing = false     // true while this service holds the microphone
        @Volatile private var held = false  // the app is talking or listening itself, so I stay quiet
        @Volatile var appVisible = false
        var onWake: (() -> Unit)? = null    // set by the activity while it is on screen

        fun hold(on: Boolean) { held = on }

        fun enabled(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("wake", false)

        fun phrase(c: Context): String {
            val p = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("wake_phrase", PHRASES[0])
            return if (p in PHRASES) p!! else PHRASES[0]
        }

        /** The model is put into the APK by my GitHub build. A build without it cannot do wake words. */
        fun modelInApk(c: Context) = try { (c.assets.list("model") ?: emptyArray()).isNotEmpty() } catch (e: Exception) { false }

        fun canPopUp(c: Context) = Settings.canDrawOverlays(c)

        /** Only works while the app is on screen, Android does not let a microphone service start from the background. */
        fun start(c: Context): Boolean {
            if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            return try { c.startForegroundService(Intent(c, WakeService::class.java)); true } catch (e: Exception) { false }
        }

        fun stop(c: Context) { c.stopService(Intent(c, WakeService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("wake", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 70, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val off = PendingIntent.getService(this, 71, Intent(this, WakeService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Jarvis is listening for \"${phrase(this)}\"")
                .setContentText("Only the wake word is checked, on this phone.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Turn off", off).build())
                .build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTE_ID, n)
        } catch (e: Exception) {
            // Android refused, for example because the app was not on screen
            stopSelf()
            return START_NOT_STICKY
        }
        if (!alive) {
            alive = true
            running = true
            thread(name = "jarvis-wake") { listen() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alive = false
        running = false
        capturing = false
        super.onDestroy()
    }

    // the model ships inside the APK, Vosk needs it as plain files, so I copy it out once per app version
    private fun modelDir(): File? {
        val dir = File(filesDir, "wake-model")
        val mark = File(dir, ".ready")
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (mark.exists() && mark.readText() == version) return dir
        dir.deleteRecursively()
        fun copy(path: String, out: File): Boolean {
            val kids = assets.list(path) ?: return false
            if (kids.isEmpty()) {
                out.parentFile?.mkdirs()
                assets.open(path).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            } else {
                for (k in kids) if (!copy("$path/$k", File(out, k))) return false
            }
            return true
        }
        if (!modelInApk(this) || !copy("model", dir)) return null
        mark.writeText(version)
        return dir
    }

    private fun listen() {
        var model: Model? = null
        try {
            val dir = modelDir() ?: run { fail("The wake word model is missing from this build."); return }
            model = Model(dir.absolutePath)
            val phrase = phrase(this)
            // a tiny grammar: my phrase or "anything else", so normal talk does not trigger it
            val grammar = "[\"$phrase\", \"[unk]\"]"
            val rate = 16000
            val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8000)
            val buf = ShortArray(1600) // 100 ms

            while (alive) {
                if (held) { Thread.sleep(150); continue }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { fail("The microphone permission was removed."); return }
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); Thread.sleep(2000); continue }
                val rz = Recognizer(model, rate.toFloat(), grammar)
                var hit = false
                try {
                    rec.startRecording()
                    capturing = true
                    while (alive && !held && !hit) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) { Thread.sleep(50); continue }
                        val text = if (rz.acceptWaveForm(buf, n)) JSONObject(rz.result).optString("text")
                        else JSONObject(rz.partialResult).optString("partial")
                        hit = " $text ".contains(" $phrase ")
                    }
                } finally {
                    capturing = false
                    try { rec.stop() } catch (e: Exception) { /* was never started */ }
                    rec.release()
                    rz.close()
                }
                if (hit && alive) {
                    held = true // the microphone is free now, the conversation takes over
                    main.post { fire() }
                }
            }
        } catch (e: Throwable) {
            fail("The wake word stopped. Turn it on again in Settings.")
        } finally {
            capturing = false
            model?.close()
        }
    }

    private fun fail(msg: String) {
        main.post {
            getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("wake", false).apply()
            note(msg)
            stopSelf()
        }
    }

    private fun note(msg: String) {
        val open = PendingIntent.getActivity(this, 72, Intent(this, MainActivity::class.java).putExtra("mic", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(HEARD_ID, Notification.Builder(this, "jarvis").setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Jarvis").setContentText(msg).setContentIntent(open).setAutoCancel(true).build())
    }

    // I heard the phrase. On screen the app starts listening right away. From the background I may only
    // open the app if I allowed "display over other apps", otherwise a notification asks me to tap.
    private fun fire() {
        val wake = onWake
        if (appVisible && wake != null) { wake(); return }
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked && !prefs.getBoolean("lock", false)) {
            note("I heard you. Unlock the phone and tap to talk.")
        } else if (canPopUp(this)) {
            try {
                startActivity(Intent(this, MainActivity::class.java).putExtra("mic", true).putExtra("wake", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) {
                note("I heard you. Tap to talk.")
            }
        } else {
            note("I heard you. Tap to talk.")
        }
        // if the app did not come up, go back to listening for the phrase
        main.postDelayed({ if (!appVisible) held = false }, 6000)
    }
}
