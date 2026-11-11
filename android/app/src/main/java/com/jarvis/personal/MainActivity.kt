package com.jarvis.personal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.net.Uri
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
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.time.LocalTime
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
    private var tab = 0
    private var sub = false // a page opened from a tab, back returns to the tab

    // talk screen, built once so the conversation survives tab changes
    private lateinit var talk: LinearLayout
    private lateinit var line: VoiceLine
    private lateinit var micBtn: FrameLayout
    private lateinit var micIcon: IconView
    private lateinit var status: TextView
    private lateinit var floorBg: GradientDrawable
    private var floorColor = 0
    private lateinit var who: TextView     // the big word on the floor: who has it right now
    private lateinit var sendIcon: IconView
    private lateinit var modeLabel: TextView
    private lateinit var histBtn: View
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var emptyView: View
    private lateinit var practiceBar: LinearLayout
    private lateinit var input: EditText
    private lateinit var stage: View
    private var typing = false     // the keyboard is up, so the voice line and tabs step aside

    // voice out
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var spoken = 0   // sentences still in the speech queue
    private var gen = 0      // goes up every time I cut Jarvis off, so late callbacks are ignored

    // voice in
    private var recorder: MediaRecorder? = null
    private var starting = false   // waiting a moment for the wake word service to let go of the mic
    private var followUp = false   // this listen came by itself after a reply, so silence is not an error
    private var startedAt = 0L
    private var lastVoice = 0L
    private var heardVoice = false
    private var floor = 0          // how loud the room is, measured as the recording starts
    private var misses = 0

    // conversation
    private var busy = false
    private var holdLive = false   // a call or message needs my yes, so do not open the mic
    private var turnOpen = false
    private var voiceTurn = false  // only a spoken turn makes Jarvis listen again by itself
    private var resumed = false
    private var mode = "friend"    // or "practice:<scenario id>"
    private var scenario: Coach.Scenario? = null
    private var streamTv: TextView? = null
    private var lastUser: TextView? = null
    private val streamText = StringBuilder()
    private val sentence = StringBuilder()

    private var lastExact = true
    private var pendingPass: CharArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        C.apply(this)
        T.init(this)
        setTheme(if (C.light) android.R.style.Theme_DeviceDefault_Light_NoActionBar else android.R.style.Theme_DeviceDefault_NoActionBar)
        overLock(intent)
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
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
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
        resumed = true
        WakeService.appVisible = true
        WakeService.onWake = { wake() }
        // the wake word service can only be started while I am on screen
        if (WakeService.enabled(this) && !WakeService.running) WakeService.start(this)
        // if I just allowed exact alarms in Android settings, move every reminder onto the exact clock
        if (Build.VERSION.SDK_INT >= 31) {
            val now = Reminders.canExact(this)
            if (now != lastExact) { lastExact = now; Reminders.rescheduleAll(this) }
        }
        applyLock()
        if (tab == 3 && !sub) go(3, true) // permissions may have changed while I was in Android settings
        if (recorder == null && !busy && spoken == 0 && !starting) idle()
    }

    override fun onPause() {
        // never keep my own mic open in the background, the wake word service takes over again
        resumed = false
        WakeService.appVisible = false
        WakeService.onWake = null
        ui.removeCallbacks(beginRun)
        starting = false
        abortRecording()
        stopSpeech()
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(false)
        syncWake()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sub) back() else super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    private fun paintBars() {
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        // dark icons on the light theme, light icons on the dark one
        val bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val f = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = if (C.light) f or bits else f and bits.inv()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        overLock(intent)
        handleIntent(intent)
    }

    // ---------- lock screen ----------

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // Only when the wake word opened me and I switched this on in Settings. Talking works over the lock
    // screen, but the tabs stay hidden and every phone action waits until I unlock.
    private fun overLock(i: Intent?) {
        if (Build.VERSION.SDK_INT >= 27 && i?.getBooleanExtra("wake", false) == true && prefs.getBoolean("lock", false)) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun applyLock() {
        val l = locked()
        if (l && (tab != 0 || sub)) go(0)
        tabBar.visibility = if (l || typing || (!prefs.getBoolean("onboarded", false) && apiKey().isBlank())) View.GONE else View.VISIBLE
        stage.visibility = if (typing) View.GONE else View.VISIBLE
        status.visibility = if (typing || status.text.isEmpty()) View.GONE else View.VISIBLE
        histBtn.visibility = if (l) View.INVISIBLE else View.VISIBLE
    }

    private fun runActions(list: JSONArray) {
        if (list.length() == 0) return
        val say: (String) -> Unit = { msg -> bubble(false, msg); speak(msg) }
        if (!locked()) { Actions.run(this, list, say); return }
        say("Unlock the phone and I will do that.")
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { ui.post { applyLock(); Actions.run(this@MainActivity, list, say) } }
        })
    }

    // ---------- intents: daily check in, widget, shortcut, wake word ----------

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("checkin", false) == true) {
            i.removeExtra("checkin")
            go(0)
            send("", null, Brain.CHECKIN)
        }
        if (i?.getBooleanExtra("mic", false) == true) {
            i.removeExtra("mic")
            i.removeExtra("wake")
            ui.post { wake() } // posted, so it runs once the screen is really up
        }
    }

    /** The wake word, the widget and the shortcut all land here: open the talk screen already listening. */
    private fun wake() {
        if (apiKey().isBlank() || busy || recorder != null || starting) { syncWake(); return }
        if (tab != 0 || sub) go(0)
        holdLive = false
        misses = 0
        startRecording(false)
    }

    // ---------- shell and tabs ----------

    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        content = FrameLayout(this)
        root.addView(content, lp(-1, 0, 1f))

        // the tabs are just words, the open one is bold with a saffron bar over it
        tabBar = LinearLayout(this).apply { setBackgroundColor(C.BG); setPadding(dp(12), 0, dp(12), 0) }
        listOf("Talk", "Practice", "Progress", "Settings").forEachIndexed { i, name ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = dp(60)
                contentDescription = name
                background = ripple(clearBg(), 12)
                isClickable = true
                isFocusable = true
                setOnClickListener { go(i) }
            }
            col.addView(View(this), lp(dp(28), dp(3)))
            col.addView(label(name, T.SEC, C.MUTED).apply { gravity = Gravity.CENTER }, lp(-2, -2).apply { topMargin = dp(14) })
            tabBar.addView(col, lp(0, -2, 1f))
        }
        root.addView(tabBar, lp(-1, -2))
        setContentView(root)
        // with the keyboard up there is no room for everything, so the chat and the text field get the space
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            root.getWindowVisibleDisplayFrame(r)
            val up = root.rootView.height - r.bottom > dp(160)
            if (up != typing) { typing = up; applyLock(); if (up) scrollDown() }
        }
    }

    // keepScroll is for settings changes, so the page does not jump back to the top
    fun go(want: Int, keepScroll: Boolean = false) {
        val i = want
        tab = if (i != 0 && locked()) 0 else i // over the lock screen only the talk screen exists
        sub = false
        val oldY = (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        content.removeAllViews()
        val v = when (tab) {
            0 -> talk
            1 -> Screens(this).practice()
            2 -> Screens(this).progress()
            else -> Screens(this).settings()
        }
        content.addView(v)
        if (keepScroll && v is ScrollView) v.post { v.scrollTo(0, oldY) }
        // coming back to the talk screen shows the current state, for example that the wake word is now on
        if (tab == 0 && recorder == null && !busy && spoken == 0 && !starting) idle()
        for (k in 0 until tabBar.childCount) {
            val col = tabBar.getChildAt(k) as LinearLayout
            val open = k == tab
            col.getChildAt(0).background = if (open) roundBg(C.YOU, 2) else null
            val name = col.getChildAt(1) as TextView
            name.setTextColor(if (open) C.TEXT else C.MUTED)
            name.typeface = if (open) T.strong else T.text
            col.isSelected = open
        }
    }

    // a page opened from a tab, like history or memory
    fun push(v: View) {
        content.removeAllViews()
        content.addView(v)
        sub = true
    }

    fun back() = go(tab)

    // ---------- the talk screen ----------
    // It reads like a script, not a chat app: my lines are small and saffron, Jarvis's lines are large.
    // Under them is the floor, a block whose color says who has it, readable from across the room:
    // quiet night while resting, saffron while I talk, the inverse field while Jarvis talks.

    private fun buildTalk() {
        talk = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(16), dp(12), 0) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(heading("Jarvis", T.H3))
        modeLabel = label("Friend chat", T.META, C.MUTED)
        titles.addView(modeLabel)
        top.addView(titles, lp(0, -2, 1f))
        histBtn = FrameLayout(this).apply {
            contentDescription = "Open chat history"
            background = ripple(clearBg(), 24)
            isClickable = true
            isFocusable = true
            addView(IconView(this@MainActivity, IconView.Kind.HISTORY, C.TEXT), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { push(Screens(this@MainActivity).history()) }
        }
        top.addView(histBtn, lp(dp(48), dp(48)))
        talk.addView(top, lp(-1, -2))

        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        emptyView = buildEmpty()
        chatBox.addView(emptyView)
        chatScroll = ScrollView(this).apply { addView(chatBox); isVerticalScrollBarEnabled = false; isFillViewport = true }
        talk.addView(chatScroll, lp(-1, 0, 1f))

        // only visible while I am in a practice scene
        practiceBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(12)); visibility = View.GONE }
        practiceBar.addView(button("Get feedback", 1) { finishPractice() }, lp(0, -2, 1f))
        practiceBar.addView(button("Exit practice") { exitPractice() }, lp(-2, -2).apply { leftMargin = dp(8) })
        talk.addView(practiceBar, lp(-1, -2))

        // the floor: who has it, the voice line, a status line, and the text field at the bottom
        floorBg = GradientDrawable().apply {
            val r = dp(32).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        val floor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = floorBg
            setPadding(dp(24), dp(16), dp(16), dp(10))
        }
        val stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        this.stage = stage
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        who = heading("", T.H2).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        head.addView(who, lp(0, -2, 1f))
        // the mic sits on the right, where my thumb already is
        micIcon = IconView(this, IconView.Kind.MIC, C.ON_SAFFRON)
        micBtn = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            addView(micIcon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            setOnClickListener { toggleMic() }
        }
        head.addView(micBtn, lp(dp(64), dp(64)))
        stage.addView(head, lp(-1, -2))
        line = VoiceLine(this)
        stage.addView(line, lp(-1, dp(52)).apply { topMargin = dp(2); rightMargin = dp(8) })
        floor.addView(stage, lp(-1, -2))

        status = label("", T.SEC, C.MUTED).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        floor.addView(status, lp(-1, -2).apply { topMargin = dp(4); rightMargin = dp(8) })

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Or type a message"
            textSize = T.BODY
            typeface = T.text
            minHeight = dp(52)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        bar.addView(input, lp(0, -2, 1f))
        sendIcon = IconView(this, IconView.Kind.SEND, C.TEXT)
        val sendBtn = FrameLayout(this).apply {
            contentDescription = "Send message"
            background = ripple(clearBg(), 26)
            isClickable = true
            isFocusable = true
            addView(sendIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { submit() }
        }
        bar.addView(sendBtn, lp(dp(52), dp(52)).apply { leftMargin = dp(4) })
        floor.addView(bar, lp(-1, -2).apply { topMargin = dp(8) })
        talk.addView(floor, lp(-1, -2))

        // bring back the last lines, so opening the app continues the same conversation
        val past = Brain.entries(this).filter { !it.practice }.take(10).reversed()
        for (e in past) bubble(e.mine, e.text, false)
        if (past.isNotEmpty()) for (k in 0 until chatBox.childCount) chatBox.getChildAt(k).alpha = 0.55f
        setMic(VoiceLine.S.IDLE)
    }

    // first use: a greeting and three ways in
    private fun buildEmpty(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(32), 0, 0) }
        val hour = LocalTime.now().hour
        col.addView(heading(when { hour < 12 -> "Good\nmorning."; hour < 17 -> "Good\nafternoon."; else -> "Good\nevening." }, 48f))
        col.addView(label("Say anything, or start with one of these.", T.BODY, C.MUTED), lp(-1, -2).apply { topMargin = dp(12) })
        val list = rows()
        val prompts = listOf(
            "Ask about my day" to "Ask me how my day went.",
            "Tell me a story" to "Tell me a short story.",
            "Give me one small challenge" to "Give me one small social challenge for today."
        )
        for ((name, text) in prompts) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                background = ripple(clearBg(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { if (!busy) send(text, null, null) }
            }
            row.addView(voice(name, T.H3), lp(0, -2, 1f))
            row.addView(IconView(this, IconView.Kind.CHEVRON, C.YOU), lp(dp(24), dp(24)))
            list.item(row)
        }
        col.addView(list, lp(-1, -2).apply { topMargin = dp(28) })
        return col
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        input.setText("")
        abortRecording()
        stopSpeech()
        send(t, null, null)
    }

    // every new turn pushes the older lines back, so the eye stays on what is being said now
    private fun bubble(mine: Boolean, msg: String, animate: Boolean = true): TextView {
        emptyView.visibility = View.GONE
        if (animate && (mine || !turnOpen)) {
            for (k in 0 until chatBox.childCount) (chatBox.getChildAt(k) as? TextView)?.alpha = 0.55f
            turnOpen = true
        }
        val tv = if (mine) label(msg, T.BODY, C.YOU, true) else voice(msg)
        chatBox.addView(tv, lp(-1, -2).apply { topMargin = dp(if (mine) 28 else 10) })
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
        chatBox.addView(col, lp(-1, -2).apply { topMargin = dp(24) })
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

    // an empty status takes no room on the floor
    private fun setStatus(s: String) {
        status.text = s
        status.visibility = if (typing || s.isEmpty()) View.GONE else View.VISIBLE
    }

    // the line and the mic button always change together, and the button says what a tap will do
    private fun setMic(s: VoiceLine.S) {
        line.state = s
        val listening = s == VoiceLine.S.LISTEN
        val thinking = s == VoiceLine.S.THINK
        val speaking = s == VoiceLine.S.SPEAK
        // the field and its ink: saffron is me, the inverse is Jarvis, raised night is nobody
        val field = when { listening -> C.SAFFRON; speaking -> C.INVERSE; else -> C.RAISED }
        val ink = when { listening -> C.ON_SAFFRON; speaking -> C.ON_INVERSE; else -> C.TEXT }
        fade(floorBg, if (floorColor == 0) field else floorColor, field)
        floorColor = field
        who.text = when (s) {
            VoiceLine.S.LISTEN -> "Listening"
            VoiceLine.S.THINK -> "Thinking"
            VoiceLine.S.SPEAK -> "Jarvis"
            else -> "Your turn"
        }
        who.setTextColor(ink)
        status.setTextColor(ink)
        status.alpha = 0.72f
        line.ink = if (listening || speaking) ink else C.MUTED
        input.setTextColor(ink)
        input.setHintTextColor(Color.argb(170, Color.red(ink), Color.green(ink), Color.blue(ink)))
        input.background = roundBg(Color.argb(if (C.light && !speaking) 14 else 26, Color.red(ink), Color.green(ink), Color.blue(ink)), 16)
        sendIcon.tint = ink
        // the mic is the one saffron thing at rest, on a colored field it turns to that field's ink
        val micFill = when { listening || speaking -> ink; thinking -> Color.TRANSPARENT; else -> C.SAFFRON }
        micBtn.background = ripple(roundBg(micFill, 36, if (thinking) C.MUTED else 0), 36, !listening && !speaking)
        micIcon.kind = if (listening) IconView.Kind.STOP else IconView.Kind.MIC
        micIcon.tint = when { listening || speaking -> field; thinking -> C.MUTED; else -> C.ON_SAFFRON }
        micBtn.contentDescription = when (s) {
            VoiceLine.S.LISTEN -> "Stop listening"
            VoiceLine.S.THINK -> "Jarvis is thinking"
            VoiceLine.S.SPEAK -> "Interrupt Jarvis and talk"
            else -> "Start talking"
        }
    }

    private fun idle(note: String? = null) {
        val armed = WakeService.running
        setMic(if (armed) VoiceLine.S.STANDBY else VoiceLine.S.IDLE)
        line.level = 0f
        setStatus(note ?: if (armed) "Say \"${WakeService.phrase(this)}\" or tap the mic" else "Tap the mic and talk")
        syncWake()
        applyLock()
    }

    // the wake word service must stay quiet while I use the mic or Jarvis is talking, or it would hear itself
    private fun syncWake() {
        WakeService.hold(resumed && (recorder != null || busy || spoken > 0 || starting))
    }

    fun continuous() = prefs.getBoolean("cont", true)

    fun setWake(on: Boolean) {
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            Toast.makeText(this, "Allow the microphone, then turn the wake word on", Toast.LENGTH_LONG).show()
            ui.post { go(3, true) }
            return
        }
        prefs.edit().putBoolean("wake", on).apply()
        if (on) WakeService.start(this) else WakeService.stop(this)
    }

    fun restartWake() {
        if (!WakeService.running) return
        WakeService.stop(this)
        ui.postDelayed({ if (resumed && WakeService.enabled(this)) WakeService.start(this) }, 600)
    }

    // ---------- voice out ----------

    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { ui.post { spokeOne(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { ui.post { spokeOne(id) } }
            // every spoken word gives the line a small beat, Android does not expose the real waveform
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { ui.post { line.pulse() } }
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
        if (text.isBlank() || !ttsReady || !resumed) return
        val ok = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "$gen:" + System.nanoTime())
        if (ok != TextToSpeech.SUCCESS) return
        spoken++
        setMic(VoiceLine.S.SPEAK)
        setStatus("Tap the mic to interrupt")
        syncWake()
    }

    private fun stopSpeech() {
        gen++
        spoken = 0
        tts?.stop()
    }

    // one sentence finished, when the queue is empty the turn goes back to me
    private fun spokeOne(id: String?) {
        if (id == null || !id.startsWith("$gen:")) return
        spoken = maxOf(0, spoken - 1)
        if (spoken > 0 || busy) return // while the reply is still streaming the line keeps its shape
        afterSpeech()
    }

    // the continuous flow: Jarvis finished, so listen again without a tap, unless a confirm dialog is open
    private fun afterSpeech() {
        if (resumed && voiceTurn && continuous() && !holdLive && !typing && recorder == null && !busy) startRecording(true) else idle()
    }

    // ---------- microphone ----------

    @Suppress("DEPRECATION")
    private fun newRecorder() = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun toggleMic() {
        if (starting) return
        if (recorder != null) { followUp = false; stopRecording(); return }
        if (busy) return
        holdLive = false
        misses = 0
        startRecording(false)
    }

    private val beginRun = Runnable { starting = false; begin() }

    private fun startRecording(follow: Boolean) {
        if (starting || recorder != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            idle("Allow the microphone, then tap the mic")
            return
        }
        stopSpeech()
        followUp = follow
        starting = true
        syncWake()
        // the wake word service needs a moment to let go of the microphone
        if (WakeService.capturing) ui.postDelayed(beginRun, 250) else beginRun.run()
    }

    private fun begin() {
        if (!resumed) { syncWake(); return }
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(File(cacheDir, "voice.m4a").absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            idle("Could not start the microphone. Close other apps that use it and try again.")
            return
        }
        recorder = r
        startedAt = System.currentTimeMillis()
        lastVoice = startedAt
        heardVoice = false
        floor = 0
        setMic(VoiceLine.S.LISTEN)
        if (!followUp) micBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setStatus("I stop listening when you pause")
        syncWake()
        ui.postDelayed(poll, 100)
    }

    // stops by itself once I stop talking, and the line follows how loud I am
    private val poll = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
            val now = System.currentTimeMillis()
            line.level = (amp / 9000f).coerceIn(0f, 1f)
            // the first moment tells me how noisy the room is, my voice has to be clearly above that
            if (now - startedAt < 250) floor = maxOf(floor, amp)
            val gate = (floor * 2).coerceIn(1500, 3500)
            if (amp > gate) { heardVoice = true; lastVoice = now }
            val done = heardVoice && now - lastVoice > 1200
            val nobody = !heardVoice && now - startedAt > (if (followUp) 6000 else 8000)
            if (done || nobody || now - startedAt > 45000) { stopRecording(); return }
            ui.postDelayed(this, 100)
        }
    }

    private fun release(): Boolean {
        val r = recorder ?: return false
        recorder = null
        ui.removeCallbacks(poll)
        var ok = true
        try { r.stop() } catch (e: RuntimeException) { ok = false } // stop fails if nothing was captured
        r.release()
        line.level = 0f
        return ok
    }

    private fun abortRecording() {
        if (release()) File(cacheDir, "voice.m4a").delete()
    }

    private fun stopRecording() {
        if (recorder == null) return
        val ok = release()
        if (ok && heardVoice) {
            send("", File(cacheDir, "voice.m4a"), null)
        } else if (followUp) {
            idle() // I simply did not answer, that is fine
        } else {
            idle("I did not hear anything. Tap the mic and try again.")
        }
    }

    // ---------- talking to Groq (straight from the phone, no server) ----------

    fun apiKey(): String = try { Secret.open(prefs.getString("gkey", "") ?: "") } catch (e: Exception) { "" }

    // tokens arrive while Jarvis is still thinking, so the reply is written live and each sentence is spoken as soon as it is whole
    private fun feed(tok: String) {
        streamText.append(tok)
        val tv = streamTv
        if (tv == null) streamTv = bubble(false, streamText.toString()) else { tv.text = streamText.toString(); scrollDown() }
        sentence.append(tok)
        val s = sentence.toString()
        val cut = s.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' || it == '۔' }
        if (cut >= 15) {
            speak(s.substring(0, cut + 1).trim())
            sentence.delete(0, cut + 1)
        }
    }

    private fun send(text: String, audio: File?, hint: String?) {
        val key = apiKey()
        if (key.isBlank()) { idle("Add your Groq key in Settings to start"); go(3); return }
        busy = true
        turnOpen = false
        voiceTurn = audio != null || hint != null
        setMic(VoiceLine.S.THINK)
        setStatus("")
        syncWake()
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
                if (r == null) { failed(text, hint, audio != null, err); return@post }
                misses = 0
                holdLive = r.actions.length() > 0
                val tv = streamTv
                if (tv == null) {
                    if (r.text.isNotBlank()) { bubble(false, r.text); speak(r.text) }
                } else {
                    if (r.text.isNotBlank()) tv.text = r.text
                    speak(sentence.toString().trim())
                }
                runActions(r.actions)
                if (spoken == 0) afterSpeech()
            }
            // the memory agent tidies up after the reply is already on screen
            if (r != null && !md.startsWith("practice:")) try { Brain.compact(this, key) } catch (e: Exception) { /* next turn tries again */ }
        }
    }

    private fun failed(text: String, hint: String?, spokenTurn: Boolean, err: String) {
        stopSpeech()
        streamTv?.let { chatBox.removeView(it) }
        // Whisper heard only noise. In a flowing conversation I just listen once more instead of showing an error.
        if (err == Brain.NOTHING) {
            misses++
            if (resumed && continuous() && !typing && misses < 2) { setStatus("I did not catch that"); startRecording(true) }
            else idle("I did not catch that. Tap the mic and say it again.")
            return
        }
        // typed simple commands still work with no internet
        val off = if (text.isNotEmpty()) Actions.offline(text) else null
        if (off != null) {
            holdLive = true
            idle()
            bubble(false, "I cannot reach Groq, so I did that on the phone.")
            runActions(off)
            return
        }
        idle()
        val user = lastUser
        val again: (() -> Unit)? = if (text.isNotEmpty() || hint != null) ({
            if (user != null) chatBox.removeView(user)
            send(text, null, hint)
        }) else null
        errorRow("Jarvis could not answer",
            if (spokenTurn) "Check your connection and your Groq key in Settings, then tap the mic and say it again."
            else "Check your connection and your Groq key in Settings, then try again.",
            err, again)
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
    }

    fun startPractice(s: Coach.Scenario) {
        if (busy) return
        abortRecording()
        stopSpeech()
        mode = "practice:" + s.id
        scenario = s
        Brain.clearPractice(this)
        clearChat()
        applyMode()
        go(0)
        turnOpen = true
        bubble(false, "${s.title}. ${s.blurb}. Talk like it is real, then tap Get feedback.").apply {
            typeface = T.text
            textSize = T.SEC
            setTextColor(C.MUTED)
        }
        send("", null, "[Start the scene now with your first line, fully in character.]")
    }

    private fun exitPractice() {
        abortRecording()
        stopSpeech()
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
        stopSpeech()
        busy = true
        setMic(VoiceLine.S.THINK)
        setStatus("Analysing your session")
        syncWake()
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
                if (f == null && err == Brain.TOO_SHORT) {
                    idle("Say a few more lines first, then finish.")
                    return@post
                }
                if (f == null) {
                    errorRow("Could not analyse this session", "Check your connection, then try again. Your chat is still here.", err) { finishPractice() }
                    return@post
                }
                Coach.addSession(this, Coach.Session(System.currentTimeMillis(), s.id, f.clarity, f.curiosity, f.warmth, f.confidence))
                chatBox.addView(Screens(this).feedbackCard(f, s), lp(-1, -2).apply { topMargin = dp(24) })
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
