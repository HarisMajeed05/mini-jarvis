package com.jarvis.personal

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Practice, Progress and Settings. They are rebuilt every time I open the tab, so the numbers are always fresh.
// Practice is a list page, Progress is a dashboard page and Settings is a settings page.
class Screens(private val a: MainActivity) {

    private fun page(title: String, sub: String, back: Boolean = false): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16), a.dp(24), a.dp(16), a.dp(32))
        }
        if (back) {
            val b = FrameLayout(a).apply {
                contentDescription = "Back"
                addView(IconView(a, IconView.Kind.BACK, C.TEXT), FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER))
            }
            tappable(b) { a.back() }
            col.addView(b, lp(a.dp(48), a.dp(48)).apply { bottomMargin = a.dp(8) })
        }
        col.addView(a.voice(title, T.H1))
        col.addView(a.label(sub, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
        val sv = ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }
        return sv to col
    }

    private fun section(parent: LinearLayout, s: String) {
        parent.addView(a.label(s, T.BODY, C.TEXT, true), lp(-1, -2).apply { topMargin = a.dp(32); bottomMargin = a.dp(8) })
    }

    private fun dialog() = AlertDialog.Builder(a, C.dialogTheme())

    private fun pad(p: LinearLayout): LinearLayout = p.apply { setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16)) }

    // a tappable row has to show it is tappable, so every one gets a ripple and the caller adds a chevron
    private fun tappable(v: View, onClick: () -> Unit) {
        v.background = a.ripple(ColorDrawable(Color.TRANSPARENT), 0)
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener { onClick() }
    }

    // ---------------- Practice ----------------

    fun practice(): View {
        val (sv, col) = page("Practice", "Rehearse real conversations with a partner who never judges.")
        val pick = Coach.todayPick(a)

        section(col, "Today")
        val today = pad(a.panel())
        today.addView(a.voice(pick.title, T.H2))
        today.addView(a.label(pick.blurb, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
        today.addView(a.label("Level ${pick.level} of 3", T.META, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(8) })
        today.addView(a.button("Start practice", 1) { a.startPractice(pick) }, lp(-1, -2).apply { topMargin = a.dp(16) })
        col.addView(today, lp(-1, -2))

        // grouped by how hard they feel, so I can pick my own step
        for ((level, name) in listOf(1 to "Easy", 2 to "A bit harder", 3 to "Brave")) {
        section(col, name)
        val list = a.panel()
        Coach.ALL.filter { it.level == level }.forEachIndexed { i, s ->
            if (i > 0) list.addView(a.hairline(), lp(-1, 1))
            val row = LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = a.dp(72)
                setPadding(a.dp(16), a.dp(12), a.dp(8), a.dp(12))
            }
            val mid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(a.label(s.title, T.BODY, C.TEXT, true))
            mid.addView(a.label(s.blurb, T.SEC, C.MUTED))
            row.addView(mid, lp(0, -2, 1f))
            row.addView(IconView(a, IconView.Kind.CHEVRON, C.MUTED), lp(a.dp(24), a.dp(24)).apply { leftMargin = a.dp(8) })
            tappable(row) { a.startPractice(s) }
            list.addView(row, lp(-1, -2))
        }
        col.addView(list, lp(-1, -2))
        }
        return sv
    }

    // ---------------- Progress ----------------

    fun progress(): View {
        val (sv, col) = page("Progress", "Small steps, counted honestly.")
        val all = Coach.sessions(a)

        val avg = if (all.isEmpty()) "-" else String.format(Locale.US, "%.1f", all.takeLast(10).map { it.avg }.average())
        val strip = a.panel().apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(big: String, small: String, color: Int) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(a.dp(8), a.dp(16), a.dp(8), a.dp(16))
            addView(a.label(big, T.H1, color, true).apply { gravity = Gravity.CENTER })
            addView(a.label(small, T.META, C.MUTED).apply { gravity = Gravity.CENTER })
        }
        strip.addView(stat("${Coach.streak(a)}", "day streak", C.ACCENT), lp(0, -2, 1f))
        strip.addView(a.hairline(), lp(1, -1))
        strip.addView(stat("${all.size}", "sessions", C.TEXT), lp(0, -2, 1f))
        strip.addView(a.hairline(), lp(1, -1))
        strip.addView(stat(avg, "average score", C.TEXT), lp(0, -2, 1f))
        col.addView(strip, lp(-1, -2).apply { topMargin = a.dp(24) })

        // the real life step is the part that matters most
        section(col, "Real world today")
        val rw = pad(a.panel())
        rw.addView(a.label(Coach.todayChallenge(), T.BODY, C.TEXT))
        val streakLabel = a.label("Real world streak: ${Coach.realStreak(a)} days", T.META, C.MUTED)
        val box = CheckBox(a).apply {
            text = "I did it"
            textSize = T.BODY
            minHeight = a.dp(48)
            setTextColor(C.TEXT)
            buttonTintList = ColorStateList.valueOf(C.ACCENT)
            isChecked = Coach.realDone(a, Coach.today())
            setOnCheckedChangeListener { _, on ->
                Coach.setReal(a, Coach.today(), on)
                streakLabel.text = "Real world streak: ${Coach.realStreak(a)} days"
            }
        }
        rw.addView(box, lp(-2, -2).apply { topMargin = a.dp(8) })
        rw.addView(streakLabel)
        col.addView(rw, lp(-1, -2))

        planSection(col)

        section(col, "Your skills")
        if (all.isEmpty()) {
            val empty = pad(a.panel())
            empty.addView(a.label("No sessions yet", T.BODY, C.TEXT, true))
            empty.addView(a.label("Finish a practice session and your scores show up here.", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
            empty.addView(a.button("Start practice", 1) { a.go(1) }, lp(-1, -2).apply { topMargin = a.dp(16) })
            col.addView(empty, lp(-1, -2))
            return sv
        }

        val skills = pad(a.panel())
        val last = all.takeLast(10)
        fun mean(f: (Coach.Session) -> Int) = last.map(f).average().toInt().coerceIn(1, 10)
        skills.addView(bar("Clarity", mean { it.clarity }))
        skills.addView(bar("Curiosity", mean { it.curiosity }), lp(-1, -2).apply { topMargin = a.dp(16) })
        skills.addView(bar("Warmth", mean { it.warmth }), lp(-1, -2).apply { topMargin = a.dp(16) })
        skills.addView(bar("Confidence", mean { it.confidence }), lp(-1, -2).apply { topMargin = a.dp(16) })
        col.addView(skills, lp(-1, -2))

        section(col, "Last sessions")
        val chart = a.panel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(12))
        }
        val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
        for (s in all.takeLast(7)) {
            val colm = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
            colm.addView(a.label(String.format(Locale.US, "%.1f", s.avg), T.META, C.MUTED))
            colm.addView(View(a).apply { background = a.roundBg(C.ACCENT, 4) },
                lp(a.dp(16), maxOf(a.dp(8), (a.dp(96) * s.avg / 10f).toInt())).apply { topMargin = a.dp(4); bottomMargin = a.dp(4) })
            colm.addView(a.label(Instant.ofEpochMilli(s.ts).atZone(ZoneId.systemDefault()).format(dayFmt), T.META, C.MUTED))
            chart.addView(colm, lp(0, a.dp(148), 1f))
        }
        col.addView(chart, lp(-1, -2))
        return sv
    }

    private fun bar(name: String, v: Int): View {
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(a.label(name, T.SEC, C.MUTED), lp(a.dp(96), -2))
        val track = LinearLayout(a).apply { background = a.roundBg(C.SURFACE2, 4); weightSum = 10f }
        track.addView(View(a).apply { background = a.roundBg(C.ACCENT, 4) }, LinearLayout.LayoutParams(0, a.dp(8), v.toFloat()))
        row.addView(track, lp(0, a.dp(8), 1f))
        row.addView(a.label("$v", T.SEC, C.TEXT, true).apply { setPadding(a.dp(12), 0, 0, 0) })
        return row
    }

    /** The feedback card that appears in the chat when I finish a practice session. */
    fun feedbackCard(f: Brain.Feedback, s: Coach.Scenario): View {
        val c = pad(a.panel())
        c.addView(a.label("${s.title}: feedback", T.BODY, C.TEXT, true))
        c.addView(bar("Clarity", f.clarity), lp(-1, -2).apply { topMargin = a.dp(16) })
        c.addView(bar("Curiosity", f.curiosity), lp(-1, -2).apply { topMargin = a.dp(12) })
        c.addView(bar("Warmth", f.warmth), lp(-1, -2).apply { topMargin = a.dp(12) })
        c.addView(bar("Confidence", f.confidence), lp(-1, -2).apply { topMargin = a.dp(12) })
        if (f.delivery.isNotBlank()) {
            c.addView(a.label("How you sounded", T.SEC, C.TEXT, true), lp(-1, -2).apply { topMargin = a.dp(24) })
            c.addView(a.label(f.delivery, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
        }
        if (f.wins.isNotEmpty()) {
            c.addView(a.label("What went well", T.SEC, C.GOOD, true), lp(-1, -2).apply { topMargin = a.dp(24) })
            for (w in f.wins) c.addView(a.label("•  $w", T.SEC, C.TEXT), lp(-1, -2).apply { topMargin = a.dp(4) })
        }
        if (f.improve.isNotEmpty()) {
            c.addView(a.label("Try next time", T.SEC, C.ACCENT, true), lp(-1, -2).apply { topMargin = a.dp(16) })
            for (w in f.improve) c.addView(a.label("•  $w", T.SEC, C.TEXT), lp(-1, -2).apply { topMargin = a.dp(4) })
        }
        if (f.better.isNotBlank()) {
            c.addView(a.label("Say it like this", T.SEC, C.TEXT, true), lp(-1, -2).apply { topMargin = a.dp(16) })
            c.addView(a.label("\"${f.better}\"", T.SEC, C.TEXT).apply {
                setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
                background = a.roundBg(C.SURFACE2, 12)
            }, lp(-1, -2).apply { topMargin = a.dp(8) })
        }
        return c
    }

    // ---------------- Settings ----------------

    private fun row(title: String, sub: String = "", subColor: Int = C.MUTED, trailing: View? = null): LinearLayout {
        val r = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(56)
            setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        }
        val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        t.addView(a.label(title, T.BODY, C.TEXT))
        if (sub.isNotEmpty()) t.addView(a.label(sub, T.SEC, subColor))
        r.addView(t, lp(0, -2, 1f))
        if (trailing != null) r.addView(trailing, lp(-2, -2).apply { leftMargin = a.dp(12) })
        return r
    }

    private fun tint(on: Int, off: Int) =
        ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off))

    // the whole row is the tap target, the switch only shows the state
    private fun switchRow(name: String, sub: String, on: Boolean, change: (Boolean) -> Unit): View {
        val sw = Switch(a).apply {
            isChecked = on
            isClickable = false
            isFocusable = false
            thumbTintList = tint(C.ACCENT, C.MUTED)
            trackTintList = tint(C.ACCENT_SOFT, C.SURFACE2)
            setOnCheckedChangeListener { _, b -> change(b) }
        }
        val r = row(name, sub, C.MUTED, sw)
        tappable(r) { sw.toggle() }
        return r
    }

    fun settings(): View {
        val (sv, col) = page("Settings", "Everything stays on this phone, except what Groq needs to answer.")

        // connection
        section(col, "Connection")
        val saved = a.apiKey().isNotBlank()
        val conn = a.panel()
        conn.addView(row("Groq API key",
            if (saved) "Saved and encrypted on this phone" else "Missing. Jarvis cannot answer without it.",
            if (saved) C.GOOD else C.BAD,
            a.button(if (saved) "Change key" else "Add key", if (saved) 0 else 1) { keyDialog() }))
        col.addView(conn, lp(-1, -2))

        voiceSection(col)

        // which model answers me
        section(col, "Brain")
        val brain = a.panel()
        brain.addView(row("Chat model", Brain.model(a), C.MUTED, a.button("Change") { a.pickModel() }))
        col.addView(brain, lp(-1, -2))

        // safety
        section(col, "Safety")
        val safe = a.panel()
        safe.addView(switchRow("Ask before calls and messages", "Whisper can mishear a name, so I confirm first",
            a.prefs.getBoolean("confirm", true)) { a.prefs.edit().putBoolean("confirm", it).apply() })
        safe.addView(a.hairline(), lp(-1, 1))
        safe.addView(switchRow("Let the WhatsApp helper tap Send and Call", "Only inside WhatsApp, only after I confirm",
            a.prefs.getBoolean("l2", true)) { a.prefs.edit().putBoolean("l2", it).apply() })
        safe.addView(a.hairline(), lp(-1, 1))
        val on = WhatsAppService.connected
        safe.addView(row("WhatsApp helper", if (on) "On in Android settings" else "Off in Android settings", if (on) C.GOOD else C.MUTED,
            a.button("Open settings") { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }))
        col.addView(safe, lp(-1, -2))

        // language
        section(col, "Language")
        val lang = a.panel().apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8)) }
        for ((id, name) in MainActivity.STYLES) {
            lang.addView(a.chip(name, a.style() == id) {
                a.prefs.edit().putString("style", id).apply()
                a.applyVoice()
                a.go(3, true)
            }, lp(0, -2, 1f).apply { leftMargin = a.dp(4); rightMargin = a.dp(4) })
        }
        col.addView(lang, lp(-1, -2))

        // theme
        section(col, "Appearance")
        val look = a.panel().apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8)) }
        val cur = a.prefs.getString("theme", "dark")
        for ((id, name) in listOf("system" to "System", "dark" to "Dark", "light" to "Light")) {
            look.addView(a.chip(name, cur == id) {
                a.prefs.edit().putString("theme", id).apply()
                a.recreate()
            }, lp(0, -2, 1f).apply { leftMargin = a.dp(4); rightMargin = a.dp(4) })
        }
        col.addView(look, lp(-1, -2))

        // daily check in
        section(col, "Daily check-in")
        val ci = a.panel()
        ci.addView(switchRow("Ask how I am each day", "A notification, tap it to talk",
            Reminders.checkinOn(a)) { Reminders.setCheckin(a, it, Reminders.checkinHour(a), Reminders.checkinMinute(a)) })
        ci.addView(a.hairline(), lp(-1, 1))
        val time = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        time.addView(a.label(String.format(Locale.US, "%02d:%02d", Reminders.checkinHour(a), Reminders.checkinMinute(a)), T.BODY, C.TEXT, true))
        time.addView(IconView(a, IconView.Kind.CHEVRON, C.MUTED), lp(a.dp(24), a.dp(24)).apply { leftMargin = a.dp(4) })
        val timeRow = row("Time", "", C.MUTED, time)
        tappable(timeRow) {
            TimePickerDialog(a, { _, h, m ->
                Reminders.setCheckin(a, true, h, m)
                a.go(3, true)
            }, Reminders.checkinHour(a), Reminders.checkinMinute(a), true).show()
        }
        ci.addView(timeRow)
        col.addView(ci, lp(-1, -2))

        // reminders
        section(col, "Reminders")
        if (Build.VERSION.SDK_INT >= 31) {
            val exact = Reminders.canExact(a)
            val ex = a.panel()
            ex.addView(row("Exact reminder times",
                if (exact) "On, reminders ring on the minute" else "Off, Android may delay reminders by a few minutes",
                if (exact) C.GOOD else C.MUTED,
                if (exact) null else a.button("Allow") {
                    a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.packageName)))
                }))
            col.addView(ex, lp(-1, -2).apply { bottomMargin = a.dp(12) })
        }
        val rem = a.panel()
        val items = Reminders.list(a).filter { !it.daily }
        if (items.isEmpty()) {
            val empty = pad(a.panel().apply { background = null })
            empty.addView(a.label("No reminders yet", T.BODY, C.TEXT, true))
            empty.addView(a.label("Say \"remind me tomorrow at 9 to call Ali\" and it shows up here.", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
            empty.addView(a.button("Talk to Jarvis", 1) { a.go(0) }, lp(-1, -2).apply { topMargin = a.dp(16) })
            rem.addView(empty, lp(-1, -2))
        }
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
        items.forEachIndexed { i, r ->
            if (i > 0) rem.addView(a.hairline(), lp(-1, 1))
            val del = FrameLayout(a).apply {
                minimumWidth = a.dp(48)
                minimumHeight = a.dp(48)
                contentDescription = "Delete reminder"
                addView(IconView(a, IconView.Kind.CLOSE, C.MUTED), FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER))
            }
            tappable(del) {
                dialog().setTitle("Delete reminder \"${r.text.take(40)}\"?")
                    .setMessage("It will not ring.")
                    .setPositiveButton("Delete reminder") { _, _ -> Reminders.remove(a, r.id); a.go(3, true) }
                    .setNegativeButton("Cancel", null).create().showThemed(a, true)
            }
            rem.addView(row(r.text, Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).format(fmt), C.MUTED, del))
        }
        col.addView(rem, lp(-1, -2))

        // my data
        section(col, "Your data")
        val data = a.panel()
        fun link(name: String, sub: String, open: () -> Unit): View {
            val chev = IconView(a, IconView.Kind.CHEVRON, C.MUTED).also { it.layoutParams = LinearLayout.LayoutParams(a.dp(24), a.dp(24)) }
            val r = row(name, sub, C.MUTED, chev)
            tappable(r) { open() }
            return r
        }
        data.addView(link("Chat history", "Search and read past chats") { a.push(history()) })
        data.addView(a.hairline(), lp(-1, 1))
        data.addView(link("What Jarvis remembers", "See each fact and forget any of them") { a.push(memory()) })
        data.addView(a.hairline(), lp(-1, 1))
        data.addView(row("Export backup", "Encrypted with a passphrase only you know", C.MUTED, a.button("Export") { a.exportBackup() }))
        data.addView(a.hairline(), lp(-1, 1))
        data.addView(row("Import backup", "Replaces chats, progress and reminders", C.MUTED, a.button("Import") { a.importBackup() }))
        col.addView(data, lp(-1, -2))

        // privacy
        section(col, "Privacy")
        val pr = pad(a.panel())
        pr.addView(a.label("Your voice is sent to Groq to be turned into text. Chats, memory, contacts and progress never leave this phone.", T.SEC, C.MUTED))
        pr.addView(a.button("Delete chat memory", 2) {
            dialog().setTitle("Delete chat memory?")
                .setMessage("Jarvis will forget your chats, history, weekly plan and what it remembered about you. Your progress and reminders stay.")
                .setPositiveButton("Delete memory") { _, _ -> Brain.reset(a); a.clearChat() }
                .setNegativeButton("Cancel", null).create().showThemed(a, true)
        }, lp(-1, -2).apply { topMargin = a.dp(16) })
        col.addView(pr, lp(-1, -2))
        return sv
    }

    // ---------------- Voice: wake word, continuous talk, speech recognition ----------------

    private fun choice(col: LinearLayout, title: String, sub: String, options: List<Pair<String, String>>, current: String, pick: (String) -> Unit) {
        val box = a.panel()
        box.addView(row(title, sub))
        val chips = LinearLayout(a).apply { setPadding(a.dp(8), 0, a.dp(8), a.dp(8)) }
        for ((id, name) in options) {
            chips.addView(a.chip(name, current == id) { pick(id); a.go(3, true) }, lp(0, -2, 1f).apply { leftMargin = a.dp(4); rightMargin = a.dp(4) })
        }
        box.addView(chips, lp(-1, -2))
        col.addView(box, lp(-1, -2).apply { topMargin = a.dp(12) })
    }

    private fun voiceSection(col: LinearLayout) {
        section(col, "Voice")
        val v = a.panel()
        if (WakeService.modelInApk(a)) {
            v.addView(switchRow("Wake word", "Jarvis listens for the phrase on this phone, even with the app closed. It uses some battery.",
                WakeService.enabled(a)) { a.setWake(it) })
        } else {
            v.addView(row("Wake word", "Not part of this build. Build the APK with the GitHub workflow to add it."))
        }
        v.addView(a.hairline(), lp(-1, 1))
        val pop = WakeService.canPopUp(a)
        v.addView(row("Open when it hears you", if (pop) "Allowed" else "Off. You get a notification to tap instead.", if (pop) C.GOOD else C.MUTED,
            if (pop) null else a.button("Allow") {
                a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.packageName)))
            }))
        v.addView(a.hairline(), lp(-1, 1))
        v.addView(switchRow("Answer on the lock screen", "Talking works while locked. Calls, messages and apps wait until you unlock.",
            a.prefs.getBoolean("lock", false)) { a.prefs.edit().putBoolean("lock", it).apply() })
        v.addView(a.hairline(), lp(-1, 1))
        v.addView(switchRow("Keep the conversation going", "Jarvis listens again after each reply, no tap needed",
            a.continuous()) { a.prefs.edit().putBoolean("cont", it).apply() })
        col.addView(v, lp(-1, -2))

        choice(col, "Wake phrase", "Pick another one if yours is missed", WakeService.PHRASES.map { it to it.replaceFirstChar { c -> c.uppercase() } },
            WakeService.phrase(a)) { a.prefs.edit().putString("wake_phrase", it).apply(); a.restartWake() }
        choice(col, "I speak", "Telling Jarvis your language makes it hear you better",
            listOf("mix" to "Urdu + English", "en" to "English", "auto" to "Detect"), Brain.speech(a)) { a.prefs.edit().putString("speech", it).apply() }
        choice(col, "Hearing", "Accurate takes a moment longer",
            listOf("best" to "Most accurate", "fast" to "Fastest"), a.prefs.getString("stt", "best") ?: "best") { a.prefs.edit().putString("stt", it).apply() }
    }

    // ---------------- Weekly plan (planner agent, then a critic agent) ----------------

    private fun planSection(col: LinearLayout) {
        section(col, "Weekly plan")
        val box = pad(a.panel())
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        fun draw(error: String? = null, busy: Boolean = false) {
            box.removeAllViews()
            val p = Brain.plan(a)
            if (p == null) {
                box.addView(a.label("No plan yet", T.BODY, C.TEXT, true))
                box.addView(a.label("A planner drafts your week from your scores, then a second agent checks it.", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
            } else {
                box.addView(a.label("Focus: ${p.focus}", T.BODY, C.TEXT, true))
                if (p.why.isNotBlank()) box.addView(a.label(p.why, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
                p.days.forEachIndexed { i, d ->
                    val r = LinearLayout(a)
                    r.addView(a.label(names.getOrElse(i) { "" }, T.SEC, C.ACCENT, true), lp(a.dp(48), -2))
                    r.addView(a.label(d, T.SEC, C.TEXT), lp(0, -2, 1f))
                    box.addView(r, lp(-1, -2).apply { topMargin = a.dp(12) })
                }
            }
            if (error != null) {
                box.addView(a.label(error, T.SEC, C.BAD), lp(-1, -2).apply { topMargin = a.dp(12) })
            }
            val b = a.button(if (busy) "Planning" else if (p == null) "Make my plan" else "Update plan", 1) {
                val key = a.apiKey()
                if (key.isBlank()) { draw("Add your Groq key in Settings first."); return@button }
                draw(null, true)
                Thread {
                    var err: String? = null
                    try { Brain.makePlan(a, key, a.style()) } catch (e: Exception) { err = "Could not make the plan. Check your connection and try again." }
                    a.runOnUiThread { draw(err) }
                }.start()
            }
            b.isEnabled = !busy
            b.alpha = if (busy) 0.6f else 1f
            b.contentDescription = if (busy) "Planning, please wait" else null
            box.addView(b, lp(-1, -2).apply { topMargin = a.dp(16) })
        }
        draw()
        col.addView(box, lp(-1, -2))
    }

    // ---------------- History (List page) ----------------

    fun history(): View {
        val (sv, col) = page("History", "Everything you and Jarvis said, kept on this phone.", true)
        val all = Brain.entries(a)
        if (all.isEmpty()) {
            val empty = pad(a.panel())
            empty.addView(a.label("No chats yet", T.BODY, C.TEXT, true))
            empty.addView(a.label("Your conversations show up here so you can search them later.", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
            empty.addView(a.button("Talk to Jarvis", 1) { a.go(0) }, lp(-1, -2).apply { topMargin = a.dp(16) })
            col.addView(empty, lp(-1, -2).apply { topMargin = a.dp(24) })
            return sv
        }
        col.addView(a.label("Search", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(24) })
        val q = EditText(a).apply {
            hint = "Words from a chat"
            textSize = T.BODY
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            minHeight = a.dp(48)
            background = a.roundBg(C.SURFACE2, 12, C.STROKE)
            setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        col.addView(q, lp(-1, -2).apply { topMargin = a.dp(4) })
        val holder = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(holder, lp(-1, -2))
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        fun fill(query: String) {
            holder.removeAllViews()
            val list = (if (query.isBlank()) all else all.filter { it.text.contains(query.trim(), true) }).take(200)
            if (list.isEmpty()) {
                val none = pad(a.panel())
                none.addView(a.label("No chats match", T.BODY, C.TEXT, true))
                none.addView(a.label("Try fewer words or clear the search.", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
                none.addView(a.button("Clear search") { q.setText("") }, lp(-1, -2).apply { topMargin = a.dp(16) })
                holder.addView(none, lp(-1, -2).apply { topMargin = a.dp(16) })
                return
            }
            var shown: LocalDate? = null
            var panel: LinearLayout? = null
            for (e in list) {
                val at = Instant.ofEpochMilli(e.ts).atZone(ZoneId.systemDefault())
                val day = at.toLocalDate()
                if (day != shown) {
                    shown = day
                    val name = when (day) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> day.format(dayFmt) }
                    holder.addView(a.label(name, T.SEC, C.MUTED, true), lp(-1, -2).apply { topMargin = a.dp(24); bottomMargin = a.dp(8) })
                    panel = a.panel().also { holder.addView(it, lp(-1, -2)) }
                } else {
                    panel?.addView(a.hairline(), lp(-1, 1))
                }
                val r = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }
                r.addView(a.label((if (e.mine) "You" else "Jarvis") + (if (e.practice) ", practice" else "") + "  " + at.format(timeFmt),
                    T.META, if (e.mine) C.ACCENT else C.MUTED, true))
                r.addView(a.label(e.text, T.BODY, C.TEXT), lp(-1, -2).apply { topMargin = a.dp(2) })
                panel?.addView(r, lp(-1, -2))
            }
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fill(s?.toString() ?: "") }
        })
        fill("")
        col.addView(a.button("Clear history", 2) {
            dialog().setTitle("Clear all ${all.size} saved messages?")
                .setMessage("The history list is emptied. What Jarvis remembers about you stays.")
                .setPositiveButton("Clear history") { _, _ -> Brain.clearLog(a); a.push(history()) }
                .setNegativeButton("Cancel", null).create().showThemed(a, true)
        }, lp(-1, -2).apply { topMargin = a.dp(32) })
        return sv
    }

    // ---------------- Memory ----------------

    fun memory(): View {
        val (sv, col) = page("What Jarvis remembers", "Facts you told it. Forget any one of them.", true)
        val facts = Brain.memory(a)
        if (facts.isEmpty()) {
            val empty = pad(a.panel())
            empty.addView(a.label("Nothing remembered yet", T.BODY, C.TEXT, true))
            empty.addView(a.label("Say \"remember that my sister is called Sara\" and it appears here.", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
            empty.addView(a.button("Talk to Jarvis", 1) { a.go(0) }, lp(-1, -2).apply { topMargin = a.dp(16) })
            col.addView(empty, lp(-1, -2).apply { topMargin = a.dp(24) })
            return sv
        }
        val list = a.panel()
        facts.forEachIndexed { i, f ->
            if (i > 0) list.addView(a.hairline(), lp(-1, 1))
            val del = FrameLayout(a).apply {
                minimumWidth = a.dp(48)
                minimumHeight = a.dp(48)
                contentDescription = "Forget this fact"
                addView(IconView(a, IconView.Kind.CLOSE, C.MUTED), FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER))
            }
            tappable(del) {
                dialog().setTitle("Forget \"${f.take(40)}\"?")
                    .setMessage("Jarvis will no longer use this fact.")
                    .setPositiveButton("Forget fact") { _, _ -> Brain.forget(a, i); a.push(memory()) }
                    .setNegativeButton("Cancel", null).create().showThemed(a, true)
            }
            list.addView(row(f, "", C.MUTED, del))
        }
        col.addView(list, lp(-1, -2).apply { topMargin = a.dp(24) })
        return sv
    }

    // ---------------- First run ----------------

    fun onboarding(start: () -> Unit): View {
        val (sv, col) = page("Meet Jarvis", "A voice friend that helps you get easier with people.")
        val points = a.panel()
        listOf(
            "Talk any time" to "Tap the mic or say the wake word. Jarvis answers out loud and remembers what matters to you.",
            "Practice real moments" to "Rehearse a hello or a phone call, then get honest, kind feedback on how it went.",
            "You stay in control" to "Calls and messages always wait for your yes. Your key and chats never leave this phone."
        ).forEachIndexed { i, (t, d) ->
            if (i > 0) points.addView(a.hairline(), lp(-1, 1))
            val r = pad(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL })
            r.addView(a.label(t, T.BODY, C.TEXT, true))
            r.addView(a.label(d, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
            points.addView(r, lp(-1, -2))
        }
        col.addView(points, lp(-1, -2).apply { topMargin = a.dp(32) })
        col.addView(a.label("Next Android asks for the microphone, contacts and notifications. You can change each one later.", T.SEC, C.MUTED),
            lp(-1, -2).apply { topMargin = a.dp(24) })
        col.addView(a.button("Allow access and start", 1) { start() }, lp(-1, -2).apply { topMargin = a.dp(16) })
        return sv
    }

    private fun keyDialog() {
        val e = EditText(a).apply {
            hint = "gsk_..."
            textSize = T.BODY
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val frame = FrameLayout(a).apply { setPadding(a.dp(24), a.dp(8), a.dp(24), 0); addView(e) }
        dialog().setTitle("Groq API key")
            .setMessage("Paste the key from console.groq.com. It is encrypted on this phone.")
            .setView(frame)
            .setPositiveButton("Save key") { _, _ ->
                val k = e.text.toString().trim()
                if (k.isNotEmpty()) a.prefs.edit().putString("gkey", Secret.seal(k)).apply()
                a.go(3, true)
            }
            .setNegativeButton("Cancel", null).create().showThemed(a)
    }
}
