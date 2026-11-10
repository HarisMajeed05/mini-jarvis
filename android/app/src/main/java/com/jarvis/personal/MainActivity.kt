package com.jarvis.personal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    companion object {
        val STYLES = listOf("roman" to "Roman Urdu", "urdu" to "اردو", "english" to "English")
    }

    lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    // shell
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout

    // talk screen, built once so the chat survives tab changes
    private lateinit var talk: LinearLayout
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var modeLabel: TextView
    private lateinit var liveChip: TextView
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var emptyView: View
    private lateinit var promptRow: HorizontalScrollView
    private lateinit var practiceBar: LinearLayout
    private lateinit var input: EditText

    private var tts: TextToSpeech? = null
    private var recorder: MediaRecorder? = null
    private var busy = false
    private var live = false      // hands free: listen again after Jarvis finishes talking
    private var holdLive = false  // a call or message needs my yes, so do not open the mic
    private var spoken = 0
    private var mode = "friend"   // or "practice:<scenario id>"
    private var scenario: Coach.Scenario? = null

    private var tab = 0
    private var sub = false          // a page opened from Settings, back returns to the tab
    private var lastExact = true
    private var streamTv: TextView? = null
    private var lastUser: TextView? = null
    private val streamText = StringBuilder()
    private val sentence = StringBuilder()
    private var pendingPass: CharArray? = null

    private var startedAt = 0L
    private var lastVoice = 0L
    private var heardVoice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        C.apply(this)
        setTheme(if (C.light) android.R.style.Theme_DeviceDefault_Light_NoActionBar else android.R.style.Theme_DeviceDefault_NoActionBar)
        paintBars()
        buildShell()
        buildTalk()
        tts = TextToSpeech(this, this)
        lastExact = Reminders.canExact(this)
        Reminders.ensureCheckin(this)

        // first run: explain before asking for anything, people who already added a key skip it
        if (!prefs.getBoolean("onboarded", false) && apiKey().isBlank()) {
            showOnboarding()
        } else {
            askPermissions()
            go(savedInstanceState?.getInt("tab") ?: if (apiKey().isBlank()) 3 else 0)
        }
        handleIntent(intent)
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun showOnboarding() {
        tabBar.visibility = View.GONE
        content.removeAllViews()
        content.addView(Screens(this).onboarding {
            prefs.edit().putBoolean("onboarded", true).apply()
            tabBar.visibility = View.VISIBLE
            askPermissions()
            go(3)
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        // if I just allowed exact alarms in Android settings, move every reminder onto the exact clock
        if (Build.VERSION.SDK_INT >= 31) {
            val now = Reminders.canExact(this)
            if (now != lastExact) { lastExact = now; Reminders.rescheduleAll(this) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sub) back() else super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    private fun paintBars() {
        window.statusBarColor = C.BG
        window.navigationBarColor = C.SURFACE
        // dark icons on the light theme, light icons on the dark one
        val bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val f = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = if (C.light) f or bits else f and bits.inv()
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
            go(0)
            send("", null, Brain.CHECKIN)
        }
        // the home screen widget and the launcher shortcut open Jarvis already listening
        if (i?.getBooleanExtra("mic", false) == true) {
            i.removeExtra("mic")
            if (apiKey().isNotBlank()) {
                go(0)
                if (!busy && recorder == null) { holdLive = false; startRecording() }
            }
        }
    }

    override fun onPause() {
        // never keep the mic open in the background
        setLive(false)
        abortRecording()
        tts?.stop()
        spoken = 0
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- shell and tabs ----------

    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        content = FrameLayout(this)
        root.addView(content, lp(-1, 0, 1f))
        root.addView(hairline(), lp(-1, 1))

        tabBar = LinearLayout(this).apply { setBackgroundColor(C.SURFACE) }
        val items = listOf(
            Triple(IconView.Kind.MIC, "Talk", "Talk to Jarvis"),
            Triple(IconView.Kind.PRACTICE, "Practice", "Practice conversations"),
            Triple(IconView.Kind.PROGRESS, "Progress", "Your progress"),
            Triple(IconView.Kind.SETTINGS, "Settings", "Settings")
        )
        items.forEachIndexed { i, item ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = dp(64)
                contentDescription = item.third
                background = ripple(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { go(i) }
            }
            // a small gold bar marks the open tab, so the state is not only a color change
            col.addView(View(this).apply { background = roundBg(C.ACCENT, 2) }, lp(dp(24), dp(3)))
            col.addView(IconView(this, item.first, C.MUTED), lp(dp(24), dp(24)).apply { topMargin = dp(8) })
            col.addView(label(item.second, T.META, C.MUTED).apply { gravity = Gravity.CENTER }, lp(-2, -2).apply { topMargin = dp(4) })
            tabBar.addView(col, lp(0, -2, 1f))
        }
        root.addView(tabBar, lp(-1, -2))
        setContentView(root)
    }

    // keepScroll is for settings changes, so the page does not jump back to the top
    fun go(i: Int, keepScroll: Boolean = false) {
        tab = i
        sub = false
        val oldY = (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        content.removeAllViews()
        val v = when (i) {
            0 -> talk
            1 -> Screens(this).practice()
            2 -> Screens(this).progress()
            else -> Screens(this).settings()
        }
        content.addView(v)
        if (keepScroll && v is ScrollView) v.post { v.scrollTo(0, oldY) }
        for (k in 0 until tabBar.childCount) {
            val col = tabBar.getChildAt(k) as LinearLayout
            val open = k == i
            col.getChildAt(0).visibility = if (open) View.VISIBLE else View.INVISIBLE
            (col.getChildAt(1) as IconView).tint = if (open) C.ACCENT else C.MUTED
            val name = col.getChildAt(2) as TextView
            name.setTextColor(if (open) C.ACCENT else C.MUTED)
            name.typeface = android.graphics.Typeface.create(if (open) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
        }
    }

    // a page opened from a tab, like history or memory, back returns to that tab
    fun push(v: View) {
        content.removeAllViews()
        content.addView(v)
        sub = true
    }

    fun back() = go(tab)

    // ---------- the talk screen ----------

    private fun buildTalk() {
        talk = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), 0) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Jarvis", T.H2, C.TEXT, true))
        modeLabel = label("Friend chat", T.META, C.MUTED)
        titles.addView(modeLabel)
        top.addView(titles, lp(0, -2, 1f))
        val hist = FrameLayout(this).apply {
            contentDescription = "Chat history"
            background = ripple(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), 24)
            isClickable = true
            isFocusable = true
            addView(IconView(this@MainActivity, IconView.Kind.HISTORY, C.TEXT), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { push(Screens(this@MainActivity).history()) }
        }
        top.addView(hist, lp(dp(48), dp(48)).apply { rightMargin = dp(8) })
        liveChip = chip("Hands-free off") { setLive(!live) }.apply { contentDescription = "Hands-free mode" }
        top.addView(liveChip)
        talk.addView(top, lp(-1, -2))

        orb = OrbView(this).apply {
            contentDescription = "Talk to Jarvis. Double tap to start or stop listening."
            isClickable = true
            setOnClickListener { toggleMic() }
        }
        talk.addView(orb, lp(-1, dp(176)))
        status = label("Tap the orb and talk", T.SEC, C.MUTED).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        talk.addView(status, lp(-1, -2))

        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        // first use: say what this screen is for and give one way in
        val intro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(24), dp(16), 0) }
        intro.addView(label("Start a conversation", T.BODY, C.TEXT, true).apply { gravity = Gravity.CENTER })
        intro.addView(label("Tap the orb and speak, or pick a prompt below.", T.SEC, C.MUTED).apply { gravity = Gravity.CENTER },
            lp(-1, -2).apply { topMargin = dp(4) })
        emptyView = intro
        chatBox.addView(emptyView)
        chatScroll = ScrollView(this).apply { addView(chatBox); isVerticalScrollBarEnabled = false }
        talk.addView(chatScroll, lp(-1, 0, 1f))

        // only visible while I am in a practice scene
        practiceBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE }
        practiceBar.addView(button("Finish and get feedback", 1) { finishPractice() }, lp(0, -2, 1f))
        practiceBar.addView(button("Exit practice") { exitPractice() }, lp(-2, -2).apply { leftMargin = dp(8) })
        talk.addView(practiceBar, lp(-1, -2))

        val prompts = listOf(
            "Ask about my day" to "Ask me how my day went.",
            "Tell a story" to "Tell me a short story.",
            "Get a challenge" to "Give me one small social challenge for today.",
            "Warm up" to "Help me warm up with a few easy conversation starters.",
            "Play music" to "Play something relaxing on Spotify."
        )
        val chips = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        for ((name, text) in prompts) chips.addView(chip(name) { if (!busy) send(text, null, null) }, lp(-2, -2).apply { rightMargin = dp(8) })
        promptRow = HorizontalScrollView(this).apply { addView(chips); isHorizontalScrollBarEnabled = false }
        talk.addView(promptRow, lp(-1, -2))

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        input = EditText(this).apply {
            hint = "Type a message"
            textSize = T.BODY
            setHintTextColor(C.MUTED)
            setTextColor(C.TEXT)
            minHeight = dp(48)
            background = roundBg(C.SURFACE2, 24, C.STROKE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        bar.addView(input, lp(0, -2, 1f))
        val sendBtn = FrameLayout(this).apply {
            contentDescription = "Send message"
            background = ripple(roundBg(C.ACCENT, 24), 24)
            isClickable = true
            isFocusable = true
            addView(IconView(this@MainActivity, IconView.Kind.SEND, C.INK), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { submit() }
        }
        bar.addView(sendBtn, lp(dp(48), dp(48)).apply { leftMargin = dp(8) })
        talk.addView(bar, lp(-1, -2))
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        input.setText("")
        send(t, null, null)
    }

    // my words sit in a bubble on the right, Jarvis talks as plain text on the left like a transcript
    private fun bubble(mine: Boolean, msg: String): TextView {
        emptyView.visibility = View.GONE
        val tv = label(msg, T.BODY, C.TEXT).apply {
            maxWidth = (resources.displayMetrics.widthPixels * (if (mine) 0.8f else 0.92f)).toInt()
            if (mine) {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = bubbleBg(C.SURFACE2)
            } else {
                setPadding(0, dp(4), 0, dp(4))
            }
            if (!reduceMotion()) {
                alpha = 0f
                translationY = dp(8).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            }
        }
        chatBox.addView(tv, lp(-2, -2).apply { gravity = if (mine) Gravity.END else Gravity.START; topMargin = dp(12) })
        while (chatBox.childCount > 80) chatBox.removeViewAt(1)
        scrollDown()
        return tv
    }

    // a failed request says what happened, what to do next, and keeps the details for support
    private fun errorRow(title: String, next: String, details: String, retry: (() -> Unit)?) {
        emptyView.visibility = View.GONE
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, T.BODY, C.BAD, true))
        col.addView(label(next, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = dp(4) })
        val actions = LinearLayout(this)
        if (retry != null) actions.addView(button("Try again", 1) { chatBox.removeView(col); retry() }, lp(-2, -2).apply { rightMargin = dp(8) })
        actions.addView(button("Copy details") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Jarvis error", details))
            Toast.makeText(this, "Details copied", Toast.LENGTH_SHORT).show()
        })
        col.addView(actions, lp(-2, -2).apply { topMargin = dp(12) })
        chatBox.addView(col, lp(-1, -2).apply { topMargin = dp(12) })
        scrollDown()
    }

    private fun scrollDown() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun clearChat() {
        chatBox.removeAllViews()
        chatBox.addView(emptyView)
        emptyView.visibility = View.VISIBLE
    }

    private fun setStatus(s: String) { status.text = s }

    private fun idle() {
        orb.state = OrbView.S.IDLE
        setStatus(if (live) "Hands-free is on, I listen after I speak" else "Tap the orb and talk")
    }

    private fun setLive(on: Boolean) {
        live = on
        liveChip.text = if (on) "Hands-free on" else "Hands-free off"
        styleChip(liveChip, on)
        if (on && !busy && recorder == null && spoken == 0) startRecording()
    }

    // ---------- voice out ----------

    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) return
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { ui.post { spokeOne() } }
            // every spoken word gives the orb a small beat, Android does not expose the real waveform
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { ui.post { orb.pulse() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { ui.post { spokeOne() } }
        })
        applyVoice()
    }

    fun style() = prefs.getString("style", "roman") ?: "roman"

    fun applyVoice() {
        // roman urdu is read best by the Indian English voice, real Urdu needs an Urdu voice installed
        tts?.language = when (style()) {
            "urdu" -> Locale("ur", "PK")
            "english" -> Locale.US
            else -> Locale("en", "IN")
        }
    }

    private fun speak(text: String) {
        spoken++
        orb.state = OrbView.S.SPEAK
        setStatus("Speaking")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "jv" + System.nanoTime())
    }

    // one sentence finished, when the queue is empty the orb relaxes and live mode listens again
    private fun spokeOne() {
        spoken = maxOf(0, spoken - 1)
        if (spoken > 0 || busy) return // while the reply is still streaming the orb keeps its shape
        idle()
        if (live && !busy && !holdLive && recorder == null) startRecording()
    }

    // ---------- microphone ----------

    @Suppress("DEPRECATION")
    private fun newRecorder() = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun toggleMic() {
        if (recorder != null) { stopRecording(); return }
        if (busy) return
        holdLive = false
        startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        tts?.stop()
        spoken = 0
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(File(cacheDir, "voice.m4a").absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            setStatus("Could not start the microphone. Close other apps that use it and try again.")
            return
        }
        recorder = r
        startedAt = System.currentTimeMillis()
        lastVoice = startedAt
        heardVoice = false
        orb.state = OrbView.S.LISTEN
        orb.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setStatus("Listening")
        ui.postDelayed(poll, 100)
    }

    // stops by itself once I stop talking, and the orb follows how loud I am
    private val poll = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
            val now = System.currentTimeMillis()
            orb.level = (amp / 9000f).coerceIn(0f, 1f)
            if (amp > 1800) { heardVoice = true; lastVoice = now }
            val quiet = heardVoice && now - lastVoice > 1400
            if (quiet || now - startedAt > 30000 || (!heardVoice && now - startedAt > 8000)) {
                stopRecording()
                return
            }
            ui.postDelayed(this, 100)
        }
    }

    private fun abortRecording() {
        val r = recorder ?: return
        recorder = null
        ui.removeCallbacks(poll)
        try { r.stop() } catch (e: RuntimeException) { /* nothing captured */ }
        r.release()
        orb.level = 0f
        idle()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        ui.removeCallbacks(poll)
        try { r.stop() } catch (e: RuntimeException) { heardVoice = false } // stop fails if nothing was captured
        r.release()
        orb.level = 0f
        if (heardVoice) {
            send("", File(cacheDir, "voice.m4a"), null)
        } else {
            setLive(false) // silence ends live mode so it never loops on an empty room
            idle()
            setStatus("Did not hear anything. Tap the orb and try again.")
        }
    }

    // ---------- talking to Groq (straight from the phone, no server) ----------

    fun apiKey(): String = try { Secret.open(prefs.getString("gkey", "") ?: "") } catch (e: Exception) { "" }

    // tokens arrive while Jarvis is still thinking, so the reply is written live and each sentence is spoken as soon as it is whole
    private fun feed(tok: String) {
        streamText.append(tok)
        val tv = streamTv
        if (tv == null) streamTv = bubble(false, streamText.toString()) else { tv.text = streamText; scrollDown() }
        sentence.append(tok)
        val s = sentence.toString()
        val cut = s.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' || it == '\u06D4' }
        if (cut >= 15) {
            speak(s.substring(0, cut + 1).trim())
            sentence.delete(0, cut + 1)
        }
    }

    private fun send(text: String, audio: File?, hint: String?) {
        val key = apiKey()
        if (key.isBlank()) { setStatus("Add your Groq key to start"); go(3); return }
        busy = true
        orb.state = OrbView.S.THINK
        setStatus("Thinking")
        val st = style()
        val md = mode
        streamTv = null
        lastUser = null
        streamText.setLength(0)
        sentence.setLength(0)
        thread {
            var reply: Brain.Reply? = null
            var error = ""
            try {
                reply = Brain.talk(this, key, text, audio, st, md, hint,
                    { h -> ui.post { lastUser = bubble(true, h) } },
                    { t -> ui.post { feed(t) } })
            } catch (e: Exception) { error = e.message ?: "error" }
            audio?.delete()
            val r = reply
            val err = error
            ui.post {
                busy = false
                if (r == null) {
                    setLive(false)
                    tts?.stop()
                    spoken = 0
                    idle()
                    // typed simple commands still work with no internet
                    val off = if (text.isNotEmpty()) Actions.offline(text) else null
                    if (off != null) {
                        streamTv?.let { chatBox.removeView(it) }
                        holdLive = true
                        bubble(false, "I cannot reach Groq, so I did that on the phone.")
                        Actions.run(this, off) { msg -> bubble(false, msg); speak(msg) }
                        return@post
                    }
                    val half = streamTv
                    val user = lastUser
                    if (half != null) chatBox.removeView(half)
                    val again: (() -> Unit)? = if (text.isNotEmpty() || hint != null) ({
                        if (user != null) chatBox.removeView(user)
                        send(text, null, hint)
                    }) else null
                    errorRow("Jarvis could not answer",
                        if (again != null) "Check your connection and your Groq key in Settings, then try again."
                        else "Check your connection and your Groq key in Settings, then tap the orb and speak again.",
                        err, again)
                    return@post
                }
                holdLive = r.actions.length() > 0
                val tv = streamTv
                if (tv == null) {
                    if (r.text.isNotBlank()) { bubble(false, r.text); speak(r.text) }
                } else {
                    if (r.text.isNotBlank()) tv.text = r.text
                    val rest = sentence.toString().trim()
                    if (rest.isNotEmpty()) speak(rest)
                }
                Actions.run(this, r.actions) { msg -> bubble(false, msg); speak(msg) }
                // nothing left to say, so go back to rest, or listen again in hands-free mode
                if (spoken == 0 && !holdLive) {
                    idle()
                    if (live && recorder == null) startRecording()
                }
            }
        }
    }

    // ---------- encrypted backup ----------

    private fun askPass(title: String, msg: String, ok: String, min: Int, done: (CharArray) -> Unit) {
        val e = EditText(this).apply {
            hint = "Passphrase"
            textSize = T.BODY
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val frame = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(e) }
        AlertDialog.Builder(this, C.dialogTheme()).setTitle(title).setMessage(msg).setView(frame)
            .setPositiveButton(ok) { _, _ ->
                val p = CharArray(e.text.length).also { e.text.getChars(0, e.text.length, it, 0) }
                e.setText("")
                if (p.size < min) { p.fill('\u0000'); Toast.makeText(this, "Use at least $min characters", Toast.LENGTH_SHORT).show() }
                else done(p)
            }
            .setNegativeButton("Cancel", null).create().showThemed(this)
    }

    fun exportBackup() {
        askPass("Export backup", "Choose a passphrase of at least 8 characters. You need it to restore, and it is not saved anywhere.", "Choose file", 8) { p ->
            pendingPass = p
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "jarvis-backup.jvb"), 41)
        }
    }

    fun importBackup() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 42)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = data?.data
        if (requestCode == 41) {
            val p = pendingPass
            pendingPass = null
            if (p == null) return
            if (resultCode != RESULT_OK || uri == null) { p.fill('\u0000'); return }
            thread {
                var err = ""
                try { Backup.write(this, uri, p) } catch (e: Exception) { err = "Could not save the backup. Try another location." }
                p.fill('\u0000')
                ui.post { Toast.makeText(this, if (err.isEmpty()) "Backup saved" else err, Toast.LENGTH_LONG).show() }
            }
        } else if (requestCode == 42 && resultCode == RESULT_OK && uri != null) {
            askPass("Import backup", "Enter the passphrase you used when you exported. This replaces your current chats, progress and reminders.", "Import backup", 1) { p ->
                thread {
                    var err = ""
                    try { Backup.read(this, uri, p) } catch (e: Exception) { err = e.message ?: "Could not read the backup" }
                    p.fill('\u0000')
                    ui.post {
                        Toast.makeText(this, if (err.isEmpty()) "Backup restored" else err, Toast.LENGTH_LONG).show()
                        if (err.isEmpty()) recreate()
                    }
                }
            }
        }
    }

    // ---------- practice mode ----------

    private fun applyMode() {
        val s = scenario
        modeLabel.text = if (s == null) "Friend chat" else "Practice: ${s.title}"
        practiceBar.visibility = if (s == null) View.GONE else View.VISIBLE
        promptRow.visibility = if (s == null) View.VISIBLE else View.GONE
    }

    fun startPractice(s: Coach.Scenario) {
        if (busy) return
        abortRecording()
        tts?.stop()
        spoken = 0
        mode = "practice:" + s.id
        scenario = s
        Brain.clearPractice(this)
        clearChat()
        applyMode()
        go(0)
        bubble(false, "${s.title}: ${s.blurb}. Talk like it is real, then tap Finish and get feedback.")
        send("", null, "[Start the scene now with your first line, fully in character.]")
    }

    private fun exitPractice() {
        abortRecording()
        tts?.stop()
        spoken = 0
        mode = "friend"
        scenario = null
        Brain.clearPractice(this)
        applyMode()
        clearChat()
        idle()
    }

    private fun finishPractice() {
        val s = scenario ?: return
        if (busy) return
        abortRecording()
        tts?.stop()
        spoken = 0
        busy = true
        orb.state = OrbView.S.THINK
        setStatus("Analysing your session")
        val key = apiKey()
        val st = style()
        thread {
            var fb: Brain.Feedback? = null
            var error = ""
            try { fb = Brain.evaluate(this, key, s.title, st) } catch (e: Exception) { error = e.message ?: "error" }
            val f = fb
            val err = error
            ui.post {
                busy = false
                idle()
                if (f == null) {
                    errorRow("Could not analyse this session", "Check your connection, then try again. Your chat is still here.", err) { finishPractice() }
                    return@post
                }
                Coach.addSession(this, Coach.Session(System.currentTimeMillis(), s.id, f.clarity, f.curiosity, f.warmth, f.confidence))
                chatBox.addView(Screens(this).feedbackCard(f, s), lp(-1, -2).apply { topMargin = dp(12) })
                scrollDown()
                mode = "friend"
                scenario = null
                Brain.clearPractice(this)
                applyMode()
                Toast.makeText(this, "Session saved to Progress", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
