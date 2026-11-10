package com.jarvis.personal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var prefs: SharedPreferences
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var mic: Button
    private lateinit var input: EditText

    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var recorder: MediaRecorder? = null
    private var busy = false

    // recording state for the "stop when I go quiet" logic
    private var startedAt = 0L
    private var lastVoice = 0L
    private var heardVoice = false

    private val styles = listOf("roman" to "Roman Urdu", "urdu" to "اردو", "english" to "English")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        buildUi()
        tts = TextToSpeech(this, this)

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)

        Reminders.ensureCheckin(this)
        if (apiKey().isBlank()) showSettings() else handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // tapping the daily notification starts the check in by itself
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("checkin", false) == true) {
            i.removeExtra("checkin")
            send("", null, true)
        }
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) applyVoice()
    }

    private fun style() = prefs.getString("style", "roman") ?: "roman"

    private fun applyVoice() {
        // roman urdu is read best by the Indian English voice, real Urdu needs an Urdu voice installed
        tts?.language = when (style()) {
            "urdu" -> Locale("ur", "PK")
            "english" -> Locale.US
            else -> Locale("en", "IN")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "jv")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(16)) }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "Jarvis"; textSize = 22f }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Button(this).apply { text = "Settings"; setOnClickListener { showSettings() } })
        root.addView(top)

        log = TextView(this).apply { textSize = 16f; setPadding(0, dp(8), 0, dp(8)) }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply { text = "Tap the mic and talk"; gravity = Gravity.CENTER; alpha = 0.7f }
        root.addView(status)

        mic = Button(this).apply { text = "🎙  Talk"; textSize = 20f; setOnClickListener { toggleMic() } }
        root.addView(mic, LinearLayout.LayoutParams(-1, dp(72)))

        input = EditText(this).apply {
            hint = "or type here"
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ ->
                val t = text.toString().trim()
                if (t.isNotEmpty() && !busy) { text.clear(); send(t, null, false) }
                true
            }
        }
        root.addView(input)
        setContentView(root)
    }

    private fun add(who: String, text: String) {
        log.append("$who: $text\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) { status.text = s }

    // ---------- microphone ----------

    @Suppress("DEPRECATION")
    private fun newRecorder() = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun toggleMic() {
        if (recorder != null) { stopRecording(); return }
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        tts?.stop()
        val r = newRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(File(cacheDir, "voice.m4a").absolutePath)
        r.prepare()
        r.start()
        recorder = r
        startedAt = System.currentTimeMillis()
        lastVoice = startedAt
        heardVoice = false
        mic.text = "⏹  Listening..."
        setStatus("Listening...")
        ui.postDelayed(poll, 100)
    }

    // stops by itself once I stop talking, so I do not need a second tap
    private val poll = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
            val now = System.currentTimeMillis()
            if (amp > 1800) { heardVoice = true; lastVoice = now }
            val quiet = heardVoice && now - lastVoice > 1400
            if (quiet || now - startedAt > 30000 || (!heardVoice && now - startedAt > 8000)) {
                stopRecording()
                return
            }
            ui.postDelayed(this, 100)
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (e: RuntimeException) { heardVoice = false } // stop fails if nothing was captured
        r.release()
        mic.text = "🎙  Talk"
        if (heardVoice) send("", File(cacheDir, "voice.m4a"), false) else setStatus("Did not hear anything")
    }

    // ---------- talking to Groq (straight from the phone, no server) ----------

    private fun apiKey(): String = try { Secret.open(prefs.getString("gkey", "") ?: "") } catch (e: Exception) { "" }

    private fun send(text: String, audio: File?, opened: Boolean) {
        val key = apiKey()
        if (key.isBlank()) { setStatus("Open Settings and add your Groq key"); return }
        busy = true
        setStatus("Thinking...")
        val st = style()
        thread {
            var reply: Brain.Reply? = null
            var error = ""
            try { reply = Brain.talk(this, key, text, audio, opened, st) } catch (e: Exception) { error = e.message ?: "error" }
            audio?.delete()
            val r = reply
            val err = error
            ui.post {
                busy = false
                if (r == null) { setStatus("Failed: $err"); return@post }
                if (r.heard.isNotEmpty()) add("Me", r.heard)
                add("Jarvis", r.text)
                speak(r.text)
                setStatus("Tap the mic and talk")
                Actions.run(this, r.actions) { msg -> add("Jarvis", msg); speak(msg) }
            }
        }
    }

    // ---------- settings ----------

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        // I never show the saved key again, leaving this empty keeps the old one
        val key = EditText(this).apply {
            hint = if (apiKey().isBlank()) "Groq API key" else "Groq API key (saved, leave empty to keep)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirm = CheckBox(this).apply { text = "Ask before calls and messages"; isChecked = prefs.getBoolean("confirm", true) }
        val l2 = CheckBox(this).apply { text = "Let Jarvis tap Send/Call in WhatsApp"; isChecked = prefs.getBoolean("l2", true) }
        val a11y = Button(this).apply {
            text = if (WhatsAppService.connected) "WhatsApp helper: ON" else "Turn on WhatsApp helper"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val lang = Button(this).apply {
            text = "Language: " + styles.first { it.first == style() }.second
            setOnClickListener {
                val next = styles[(styles.indexOfFirst { it.first == style() } + 1) % styles.size]
                prefs.edit().putString("style", next.first).apply()
                applyVoice()
                text = "Language: " + next.second
            }
        }
        val forget = Button(this).apply {
            text = "Forget everything Jarvis knows"
            setOnClickListener { Brain.reset(this@MainActivity); setStatus("Memory cleared") }
        }
        for (v in listOf(key, confirm, l2, a11y, lang, forget)) box.addView(v)

        AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val k = key.text.toString().trim()
                prefs.edit()
                    .putBoolean("confirm", confirm.isChecked)
                    .putBoolean("l2", l2.isChecked)
                    .apply { if (k.isNotEmpty()) putString("gkey", Secret.seal(k)) }
                    .apply()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
