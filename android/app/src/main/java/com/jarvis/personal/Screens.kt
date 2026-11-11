package com.jarvis.personal

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Practice, Progress and Settings. They are rebuilt every time I open the tab, so the numbers are always fresh.
// No boxes: each page is a big title, then sections of rows on hairlines. Practice gets the one saffron block,
// because today's scene is the thing I should do next.
class Screens(private val a: MainActivity) {

    private fun page(title: String, sub: String, back: Boolean = false): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(24), a.dp(28), a.dp(24), a.dp(40))
        }
        if (back) {
            val b = FrameLayout(a).apply {
                contentDescription = "Back"
                addView(IconView(a, IconView.Kind.BACK, C.TEXT), FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER))
            }
            tappable(b) { a.back() }
            col.addView(b, lp(a.dp(48), a.dp(48)).apply { bottomMargin = a.dp(8); leftMargin = -a.dp(12) })
        }
        col.addView(a.heading(title))
        col.addView(a.label(sub, T.BODY, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(8) })
        val sv = ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }
        return sv to col
    }

    private fun section(parent: LinearLayout, s: String) {
        parent.addView(a.heading(s, T.H3), lp(-1, -2).apply { topMargin = a.dp(44); bottomMargin = a.dp(8) })
    }

    private fun dialog() = AlertDialog.Builder(a, C.dialogTheme())

    // a tappable row has to show it is tappable, so every one gets a ripple and the caller adds a chevron
    private fun tappable(v: View, onClick: () -> Unit) {
        v.background = a.ripple(ColorDrawable(Color.TRANSPARENT), 0)
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener { onClick() }
    }

    // an empty state says what goes here and offers the way to fill it
    private fun empty(parent: LinearLayout, head: String, body: String, action: String?, go: () -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(16), 0, a.dp(8)) }
        box.addView(a.label(head, T.BODY, C.TEXT, true))
        box.addView(a.label(body, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
        if (action != null) box.addView(a.button(action, 1) { go() }, lp(-2, -2).apply { topMargin = a.dp(16) })
        parent.addView(box, lp(-1, -2))
    }

    // ---------------- Practice ----------------

    fun practice(): View {
        val (sv, col) = page("Practice", "Rehearse real conversations with a partner who never judges.")
        val pick = Coach.todayPick(a)

        // today's scene is the one bold block on this page
        val today = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = a.roundBg(C.SAFFRON, 28)
            setPadding(a.dp(24), a.dp(24), a.dp(24), a.dp(24))
        }
        val meta = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        meta.addView(a.label("Today, level ${pick.level} of 3", T.SEC, C.ON_SAFFRON, true), lp(0, -2, 1f))
        meta.addView(LevelMark(a, pick.level, C.ON_SAFFRON, Color.argb(60, 36, 24, 0)))
        today.addView(meta)
        today.addView(a.heading(pick.title, T.H2, C.ON_SAFFRON), lp(-1, -2).apply { topMargin = a.dp(28) })
        today.addView(a.label(pick.blurb, T.BODY, C.ON_SAFFRON), lp(-1, -2).apply { topMargin = a.dp(8) })
        today.addView(a.button("Start practice", 3) { a.startPractice(pick) }, lp(-1, -2).apply { topMargin = a.dp(24) })
        col.addView(today, lp(-1, -2).apply { topMargin = a.dp(28) })

        // grouped by how hard they feel, so I can pick my own step
        for ((level, name) in listOf(1 to "Easy", 2 to "A bit harder", 3 to "Brave")) {
            val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(a.heading(name, T.H3), lp(0, -2, 1f))
            head.addView(LevelMark(a, level, C.TEXT, C.LINE))
            col.addView(head, lp(-1, -2).apply { topMargin = a.dp(44); bottomMargin = a.dp(8) })
            val list = a.rows()
            for (s in Coach.ALL.filter { it.level == level }) {
                val row = LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = a.dp(76)
                    setPadding(0, a.dp(14), 0, a.dp(14))
                }
                val mid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                mid.addView(a.voice(s.title, T.H3))
                mid.addView(a.label(s.blurb, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(2) })
                row.addView(mid, lp(0, -2, 1f))
                row.addView(IconView(a, IconView.Kind.CHEVRON, C.MUTED), lp(a.dp(24), a.dp(24)).apply { leftMargin = a.dp(12) })
                tappable(row) { a.startPractice(s) }
                list.item(row)
            }
            col.addView(list, lp(-1, -2))
        }
        return sv
    }

    // ---------------- Progress ----------------

    fun progress(): View {
        val (sv, col) = page("Progress", "Small steps, counted honestly.")
        val all = Coach.sessions(a)
        val zone = ZoneId.systemDefault()

        // the last seven days as squares, filled where I practised, so a streak is something I can see
        val days = all.map { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }.toSet()
        val week = LinearLayout(a)
        val letter = DateTimeFormatter.ofPattern("EEEEE", Locale.ENGLISH)
        for (back in 6 downTo 0) {
            val d = LocalDate.now().minusDays(back.toLong())
            val done = d in days
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                contentDescription = d.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) + if (done) ", practised" else ", no practice"
            }
            cell.addView(View(a).apply { background = if (done) a.roundBg(C.YOU, 10) else a.roundBg(Color.TRANSPARENT, 10, C.LINE) },
                lp(-1, a.dp(40)))
            cell.addView(a.label(d.format(letter), T.META, if (back == 0) C.TEXT else C.MUTED, back == 0).apply { gravity = Gravity.CENTER },
                lp(-2, -2).apply { topMargin = a.dp(8) })
            week.addView(cell, lp(0, -2, 1f).apply { leftMargin = a.dp(3); rightMargin = a.dp(3) })
        }
        col.addView(week, lp(-1, -2).apply { topMargin = a.dp(32) })

        // the numbers as one sentence instead of a row of tiles
        val streak = Coach.streak(a)
        val summary = if (all.isEmpty()) "No sessions yet. Your first one starts the streak."
        else {
            val avg = String.format(Locale.US, "%.1f", all.takeLast(10).map { it.avg }.average())
            "$streak day streak. ${all.size} ${if (all.size == 1) "session" else "sessions"}, averaging $avg out of 10."
        }
        col.addView(a.voice(summary, T.H3), lp(-1, -2).apply { topMargin = a.dp(20) })

        // the real life step is the part that matters most
        section(col, "Real world today")
        col.addView(a.voice(Coach.todayChallenge(), T.VOICE), lp(-1, -2).apply { topMargin = a.dp(4) })
        val streakLabel = a.label("", T.SEC, C.MUTED)
        fun realText() = "Real world streak: ${Coach.realStreak(a)} days"
        streakLabel.text = realText()
        val done = Coach.realDone(a, Coach.today())
        val tick = Toggle(a, done)
        val doneRow = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = a.dp(56) }
        doneRow.addView(a.label("I did it", T.BODY, C.TEXT, true), lp(0, -2, 1f))
        doneRow.addView(tick)
        fun describe() { doneRow.contentDescription = "I did it, " + if (tick.on) "on" else "off" }
        describe()
        tappable(doneRow) {
            tick.on = !tick.on
            Coach.setReal(a, Coach.today(), tick.on)
            streakLabel.text = realText()
            describe()
        }
        col.addView(doneRow, lp(-1, -2).apply { topMargin = a.dp(12) })
        col.addView(streakLabel)

        planSection(col)

        section(col, "Your skills")
        if (all.isEmpty()) {
            empty(col, "No scores yet", "Finish a practice session and your scores show up here.", "Start practice") { a.go(1) }
            return sv
        }
        val last = all.takeLast(10)
        fun mean(f: (Coach.Session) -> Int) = last.map(f).average().toInt().coerceIn(1, 10)
        col.addView(bar("Clarity", mean { it.clarity }), lp(-1, -2).apply { topMargin = a.dp(8) })
        col.addView(bar("Curiosity", mean { it.curiosity }), lp(-1, -2).apply { topMargin = a.dp(16) })
        col.addView(bar("Warmth", mean { it.warmth }), lp(-1, -2).apply { topMargin = a.dp(16) })
        col.addView(bar("Confidence", mean { it.confidence }), lp(-1, -2).apply { topMargin = a.dp(16) })

        section(col, "Last sessions")
        val chart = LinearLayout(a).apply { gravity = Gravity.BOTTOM }
        val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
        for (s in all.takeLast(7)) {
            val colm = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
            colm.addView(a.label(String.format(Locale.US, "%.1f", s.avg), T.META, C.TEXT, true))
            colm.addView(View(a).apply { background = a.roundBg(C.YOU, 6) },
                lp(a.dp(22), maxOf(a.dp(8), (a.dp(112) * s.avg / 10f).toInt())).apply { topMargin = a.dp(6); bottomMargin = a.dp(8) })
            colm.addView(a.label(Instant.ofEpochMilli(s.ts).atZone(zone).format(dayFmt), T.META, C.MUTED))
            chart.addView(colm, lp(0, a.dp(168), 1f))
        }
        col.addView(chart, lp(-1, -2))
        return sv
    }

    // a skill score: name and number on one line, a thick track under it
    private fun bar(name: String, v: Int, ink: Int = C.TEXT, track: Int = C.LINE): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; contentDescription = "$name $v out of 10" }
        val top = LinearLayout(a)
        top.addView(a.label(name, T.SEC, ink), lp(0, -2, 1f))
        top.addView(a.label("$v", T.SEC, ink, true))
        box.addView(top)
        val bg = LinearLayout(a).apply { background = a.roundBg(track, 5); weightSum = 10f }
        bg.addView(View(a).apply { background = a.roundBg(C.YOU, 5) }, LinearLayout.LayoutParams(0, a.dp(10), v.toFloat()))
        box.addView(bg, lp(-1, a.dp(10)).apply { topMargin = a.dp(6) })
        return box
    }

    /** The feedback card that appears in the chat when I finish a practice session. It is the one card in the app. */
    fun feedbackCard(f: Brain.Feedback, s: Coach.Scenario): View {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = a.roundBg(C.RAISED, 28)
            setPadding(a.dp(20), a.dp(24), a.dp(20), a.dp(24))
        }
        c.addView(a.label("Feedback", T.SEC, C.MUTED, true))
        c.addView(a.heading(s.title, T.H2), lp(-1, -2).apply { topMargin = a.dp(4) })
        c.addView(bar("Clarity", f.clarity), lp(-1, -2).apply { topMargin = a.dp(20) })
        c.addView(bar("Curiosity", f.curiosity), lp(-1, -2).apply { topMargin = a.dp(14) })
        c.addView(bar("Warmth", f.warmth), lp(-1, -2).apply { topMargin = a.dp(14) })
        c.addView(bar("Confidence", f.confidence), lp(-1, -2).apply { topMargin = a.dp(14) })
        fun part(head: String, color: Int) = c.addView(a.label(head, T.BODY, color, true), lp(-1, -2).apply { topMargin = a.dp(24) })
        fun point(t: String) = c.addView(a.label(t, T.BODY, C.TEXT), lp(-1, -2).apply { topMargin = a.dp(6) })
        if (f.delivery.isNotBlank()) { part("How you sounded", C.TEXT); point(f.delivery) }
        if (f.wins.isNotEmpty()) { part("What went well", C.GOOD); f.wins.forEach { point(it) } }
        if (f.improve.isNotEmpty()) { part("Try next time", C.YOU); f.improve.forEach { point(it) } }
        if (f.better.isNotBlank()) {
            part("Say it like this", C.TEXT)
            c.addView(a.voice("“${f.better}”", T.H3), lp(-1, -2).apply { topMargin = a.dp(8) })
        }
        return c
    }

    // ---------------- Settings ----------------

    private fun row(title: String, sub: String = "", subColor: Int = C.MUTED, trailing: View? = null): LinearLayout {
        val r = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(64)
            setPadding(0, a.dp(12), 0, a.dp(12))
        }
        val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        t.addView(a.label(title, T.BODY, C.TEXT))
        if (sub.isNotEmpty()) t.addView(a.label(sub, T.SEC, subColor), lp(-1, -2).apply { topMargin = a.dp(2) })
        r.addView(t, lp(0, -2, 1f))
        if (trailing != null) r.addView(trailing, lp(-2, -2).apply { leftMargin = a.dp(16) })
        return r
    }

    // the whole row is the tap target, the toggle only shows the state
    private fun switchRow(name: String, sub: String, on: Boolean, change: (Boolean) -> Unit): View {
        val t = Toggle(a, on)
        val r = row(name, sub, C.MUTED, t)
        fun describe() { r.contentDescription = "$name, ${if (t.on) "on" else "off"}. $sub" }
        describe()
        tappable(r) { t.on = !t.on; describe(); change(t.on) }
        return r
    }

    // a setting with a few named choices, title on top and the choices under it
    private fun choice(group: LinearLayout, title: String, sub: String, options: List<Pair<String, String>>, current: String, pick: (String) -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(12), 0, a.dp(8)) }
        box.addView(a.label(title, T.BODY, C.TEXT))
        if (sub.isNotEmpty()) box.addView(a.label(sub, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(2) })
        box.addView(a.segments(options, current) { pick(it); a.go(3, true) }, lp(-1, -2).apply { topMargin = a.dp(8) })
        group.item(box)
    }

    private fun group(col: LinearLayout) = a.rows().also { col.addView(it, lp(-1, -2)) }

    fun settings(): View {
        val (sv, col) = page("Settings", "Everything stays on this phone, except what Groq needs to answer.")

        section(col, "Connection")
        val saved = a.apiKey().isNotBlank()
        group(col).item(row("Groq API key",
            if (saved) "Saved and encrypted on this phone" else "Missing. Jarvis cannot answer without it.",
            if (saved) C.GOOD else C.BAD,
            a.button(if (saved) "Change key" else "Add key", if (saved) 0 else 1) { keyDialog() }))

        voiceSection(col)

        section(col, "Safety")
        val safe = group(col)
        safe.item(switchRow("Ask before calls and messages", "Whisper can mishear a name, so I confirm first",
            a.prefs.getBoolean("confirm", true)) { a.prefs.edit().putBoolean("confirm", it).apply() })
        safe.item(switchRow("Let the WhatsApp helper tap Send and Call", "Only inside WhatsApp, only after I confirm",
            a.prefs.getBoolean("l2", true)) { a.prefs.edit().putBoolean("l2", it).apply() })
        val on = WhatsAppService.connected
        safe.item(row("WhatsApp helper", if (on) "On in Android settings" else "Off in Android settings", if (on) C.GOOD else C.MUTED,
            a.button("Open settings") { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }))

        section(col, "Language and look")
        val look = group(col)
        choice(look, "Jarvis replies in", "", MainActivity.STYLES, a.style()) {
            a.prefs.edit().putString("style", it).apply()
            a.applyVoice()
        }
        val cur = a.prefs.getString("theme", "dark") ?: "dark"
        val themeBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(12), 0, a.dp(8)) }
        themeBox.addView(a.label("Theme", T.BODY, C.TEXT))
        themeBox.addView(a.segments(listOf("system" to "System", "dark" to "Dark", "light" to "Light"), cur) {
            a.prefs.edit().putString("theme", it).apply()
            a.recreate()
        }, lp(-1, -2).apply { topMargin = a.dp(8) })
        look.item(themeBox)

        section(col, "Daily check-in")
        val ci = group(col)
        ci.item(switchRow("Ask how I am each day", "A notification, tap it to talk",
            Reminders.checkinOn(a)) { Reminders.setCheckin(a, it, Reminders.checkinHour(a), Reminders.checkinMinute(a)) })
        val time = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        time.addView(a.heading(String.format(Locale.US, "%02d:%02d", Reminders.checkinHour(a), Reminders.checkinMinute(a)), T.H3))
        time.addView(IconView(a, IconView.Kind.CHEVRON, C.MUTED), lp(a.dp(24), a.dp(24)).apply { leftMargin = a.dp(4) })
        val timeRow = row("Time", "", C.MUTED, time)
        tappable(timeRow) {
            TimePickerDialog(a, { _, h, m ->
                Reminders.setCheckin(a, true, h, m)
                a.go(3, true)
            }, Reminders.checkinHour(a), Reminders.checkinMinute(a), true).show()
        }
        ci.item(timeRow)

        section(col, "Reminders")
        val rem = group(col)
        if (Build.VERSION.SDK_INT >= 31) {
            val exact = Reminders.canExact(a)
            rem.item(row("Exact reminder times",
                if (exact) "On, reminders ring on the minute" else "Off, Android may delay reminders by a few minutes",
                if (exact) C.GOOD else C.MUTED,
                if (exact) null else a.button("Allow") {
                    a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.packageName)))
                }))
        }
        val items = Reminders.list(a).filter { !it.daily }
        if (items.isEmpty()) {
            empty(col, "No reminders yet", "Say \"remind me tomorrow at 9 to call Ali\" and it shows up here.", "Talk to Jarvis") { a.go(0) }
        }
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
        for (r in items) {
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
            rem.item(row(r.text, Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).format(fmt), C.MUTED, del))
        }

        section(col, "Your data")
        val data = group(col)
        fun link(name: String, sub: String, open: () -> Unit): View {
            val chev = IconView(a, IconView.Kind.CHEVRON, C.MUTED).also { it.layoutParams = LinearLayout.LayoutParams(a.dp(24), a.dp(24)) }
            val r = row(name, sub, C.MUTED, chev)
            tappable(r) { open() }
            return r
        }
        data.item(link("Chat history", "Search and read past chats") { a.push(history()) })
        data.item(link("What Jarvis remembers", "See each fact and forget any of them") { a.push(memory()) })
        data.item(row("Export backup", "Encrypted with a passphrase only you know", C.MUTED, a.button("Export") { a.exportBackup() }))
        data.item(row("Import backup", "Replaces chats, progress and reminders", C.MUTED, a.button("Import") { a.importBackup() }))

        section(col, "Privacy")
        col.addView(a.label("Your voice is sent to Groq to be turned into text. Chats, memory, contacts and progress never leave this phone.", T.BODY, C.MUTED))
        col.addView(a.button("Delete chat memory", 2) {
            dialog().setTitle("Delete chat memory?")
                .setMessage("Jarvis will forget your chats, history, weekly plan and what it remembered about you. Your progress and reminders stay.")
                .setPositiveButton("Delete memory") { _, _ -> Brain.reset(a); a.clearChat() }
                .setNegativeButton("Cancel", null).create().showThemed(a, true)
        }, lp(-2, -2).apply { topMargin = a.dp(16) })
        return sv
    }

    // ---------------- Voice: wake word, continuous talk, speech recognition ----------------

    private fun voiceSection(col: LinearLayout) {
        section(col, "Voice")
        val v = group(col)
        if (WakeService.modelInApk(a)) {
            v.item(switchRow("Wake word", "Jarvis listens for the phrase on this phone, even with the app closed. It uses some battery.",
                WakeService.enabled(a)) { a.setWake(it) })
        } else {
            v.item(row("Wake word", "Not part of this build. Build the APK with the GitHub workflow to add it."))
        }
        val pop = WakeService.canPopUp(a)
        v.item(row("Open when it hears you", if (pop) "Allowed" else "Off. You get a notification to tap instead.", if (pop) C.GOOD else C.MUTED,
            if (pop) null else a.button("Allow") {
                a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.packageName)))
            }))
        v.item(switchRow("Answer on the lock screen", "Talking works while locked. Calls, messages and apps wait until you unlock.",
            a.prefs.getBoolean("lock", false)) { a.prefs.edit().putBoolean("lock", it).apply() })
        v.item(switchRow("Keep the conversation going", "Jarvis listens again after each reply, no tap needed",
            a.continuous()) { a.prefs.edit().putBoolean("cont", it).apply() })
        choice(v, "Wake phrase", "Pick another one if yours is missed", WakeService.PHRASES.map { it to it.replaceFirstChar { c -> c.uppercase() } },
            WakeService.phrase(a)) { a.prefs.edit().putString("wake_phrase", it).apply(); a.restartWake() }
        choice(v, "I speak", "Telling Jarvis your language makes it hear you better",
            listOf("mix" to "Urdu + English", "en" to "English", "auto" to "Detect"), Brain.speech(a)) { a.prefs.edit().putString("speech", it).apply() }
        choice(v, "Hearing", "Accurate takes a moment longer",
            listOf("best" to "Most accurate", "fast" to "Fastest"), a.prefs.getString("stt", "best") ?: "best") { a.prefs.edit().putString("stt", it).apply() }
    }

    // ---------------- Weekly plan (planner agent, then a critic agent) ----------------

    private fun planSection(col: LinearLayout) {
        section(col, "Weekly plan")
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        fun draw(error: String? = null, busy: Boolean = false) {
            box.removeAllViews()
            val p = Brain.plan(a)
            if (p == null) {
                box.addView(a.label("No plan yet", T.BODY, C.TEXT, true))
                box.addView(a.label("A planner drafts your week from your scores, then a second agent checks it.", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
            } else {
                box.addView(a.voice(p.focus, T.VOICE))
                if (p.why.isNotBlank()) box.addView(a.label(p.why, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(6) })
                val days = a.rows()
                p.days.forEachIndexed { i, d ->
                    val r = LinearLayout(a).apply { setPadding(0, a.dp(12), 0, a.dp(12)) }
                    r.addView(a.label(names.getOrElse(i) { "" }, T.SEC, C.YOU, true), lp(a.dp(52), -2))
                    r.addView(a.label(d, T.SEC, C.TEXT), lp(0, -2, 1f))
                    days.item(r)
                }
                box.addView(days, lp(-1, -2).apply { topMargin = a.dp(12) })
            }
            if (error != null) {
                box.addView(a.label(error, T.SEC, C.BAD), lp(-1, -2).apply { topMargin = a.dp(12) })
            }
            val b = a.button(if (busy) "Planning" else if (p == null) "Make my plan" else "Update plan", if (p == null) 1 else 0) {
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
            box.addView(b, lp(-2, -2).apply { topMargin = a.dp(16) })
        }
        draw()
        col.addView(box, lp(-1, -2))
    }

    // ---------------- History (List page) ----------------

    fun history(): View {
        val (sv, col) = page("History", "Everything you and Jarvis said, kept on this phone.", true)
        val all = Brain.entries(a)
        if (all.isEmpty()) {
            empty(col, "No chats yet", "Your conversations show up here so you can search them later.", "Talk to Jarvis") { a.go(0) }
            return sv
        }
        val q = EditText(a).apply {
            hint = "Search your chats"
            contentDescription = "Search your chats"
            textSize = T.BODY
            typeface = T.text
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            minHeight = a.dp(52)
            background = a.roundBg(C.RAISED, 16)
            setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(8))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        col.addView(q, lp(-1, -2).apply { topMargin = a.dp(28) })
        val holder = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(holder, lp(-1, -2))
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        fun fill(query: String) {
            holder.removeAllViews()
            val list = (if (query.isBlank()) all else all.filter { it.text.contains(query.trim(), true) }).take(200)
            if (list.isEmpty()) {
                val none = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(24), 0, 0) }
                none.addView(a.label("No chats match", T.BODY, C.TEXT, true))
                none.addView(a.label("Try fewer words or clear the search.", T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
                none.addView(a.button("Clear search") { q.setText("") }, lp(-2, -2).apply { topMargin = a.dp(16) })
                holder.addView(none, lp(-1, -2))
                return
            }
            var shown: LocalDate? = null
            var group: LinearLayout? = null
            for (e in list) {
                val at = Instant.ofEpochMilli(e.ts).atZone(ZoneId.systemDefault())
                val day = at.toLocalDate()
                if (day != shown) {
                    shown = day
                    val name = when (day) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> day.format(dayFmt) }
                    holder.addView(a.heading(name, T.H3), lp(-1, -2).apply { topMargin = a.dp(36); bottomMargin = a.dp(4) })
                    group = a.rows().also { holder.addView(it, lp(-1, -2)) }
                }
                val r = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(12), 0, a.dp(12)) }
                r.addView(a.label((if (e.mine) "You" else "Jarvis") + (if (e.practice) ", practice" else "") + ", " + at.format(timeFmt),
                    T.META, if (e.mine) C.YOU else C.MUTED, true))
                r.addView(if (e.mine) a.label(e.text, T.BODY, C.TEXT) else a.voice(e.text, T.H3), lp(-1, -2).apply { topMargin = a.dp(4) })
                group?.item(r)
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
        }, lp(-2, -2).apply { topMargin = a.dp(40) })
        return sv
    }

    // ---------------- Memory ----------------

    fun memory(): View {
        val (sv, col) = page("What Jarvis remembers", "Facts you told it. Forget any one of them.", true)
        val facts = Brain.memory(a)
        if (facts.isEmpty()) {
            empty(col, "Nothing remembered yet", "Say \"remember that my sister is called Sara\" and it appears here.", "Talk to Jarvis") { a.go(0) }
            return sv
        }
        val list = a.rows()
        facts.forEachIndexed { i, f ->
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
            val r = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = a.dp(64); setPadding(0, a.dp(12), 0, a.dp(12)) }
            r.addView(a.voice(f, T.H3), lp(0, -2, 1f))
            r.addView(del, lp(-2, -2).apply { leftMargin = a.dp(12) })
            list.item(r)
        }
        col.addView(list, lp(-1, -2).apply { topMargin = a.dp(28) })
        return sv
    }

    // ---------------- First run ----------------

    fun onboarding(start: () -> Unit): View {
        val (sv, col) = page("Meet\nJarvis.", "A voice friend that helps you get easier with people.")
        val points = a.rows()
        listOf(
            "Talk any time" to "Tap the mic or say the wake word. Jarvis answers out loud and remembers what matters to you.",
            "Practice real moments" to "Rehearse a hello or a phone call, then get honest, kind feedback on how it went.",
            "You stay in control" to "Calls and messages always wait for your yes. Your key and chats never leave this phone."
        ).forEach { (t, d) ->
            val r = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(18), 0, a.dp(18)) }
            r.addView(a.voice(t, T.H3))
            r.addView(a.label(d, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
            points.item(r)
        }
        col.addView(points, lp(-1, -2).apply { topMargin = a.dp(36) })
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
