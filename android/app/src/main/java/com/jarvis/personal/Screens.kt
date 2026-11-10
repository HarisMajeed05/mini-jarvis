package com.jarvis.personal

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Practice, Progress and Settings. They are rebuilt every time I open the tab, so the numbers are always fresh.
// Practice is a list page, Progress is a dashboard page and Settings is a settings page.
class Screens(private val a: MainActivity) {

    private fun page(title: String, sub: String): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16), a.dp(24), a.dp(16), a.dp(32))
        }
        col.addView(a.label(title, T.H1, C.TEXT, true))
        col.addView(a.label(sub, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
        val sv = ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }
        return sv to col
    }

    private fun section(parent: LinearLayout, s: String) {
        parent.addView(a.label(s, T.BODY, C.TEXT, true), lp(-1, -2).apply { topMargin = a.dp(32); bottomMargin = a.dp(8) })
    }

    private fun dialog() = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)

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
        today.addView(a.label(pick.title, T.H2, C.TEXT, true))
        today.addView(a.label(pick.blurb, T.SEC, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(4) })
        today.addView(a.label("Level ${pick.level} of 3", T.META, C.MUTED), lp(-1, -2).apply { topMargin = a.dp(8) })
        today.addView(a.button("Start practice", 1) { a.startPractice(pick) }, lp(-1, -2).apply { topMargin = a.dp(16) })
        col.addView(today, lp(-1, -2))

        section(col, "All scenarios")
        val list = a.panel()
        Coach.ALL.forEachIndexed { i, s ->
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
            row.addView(a.label("Level ${s.level}", T.META, C.MUTED).apply { setPadding(a.dp(8), 0, a.dp(4), 0) })
            row.addView(IconView(a, IconView.Kind.CHEVRON, C.MUTED), lp(a.dp(24), a.dp(24)))
            tappable(row) { a.startPractice(s) }
            list.addView(row, lp(-1, -2))
        }
        col.addView(list, lp(-1, -2))
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
            trackTintList = tint(Color.parseColor("#6B5528"), C.STROKE)
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

        // privacy
        section(col, "Privacy")
        val pr = pad(a.panel())
        pr.addView(a.label("Your voice is sent to Groq to be turned into text. Chats, memory, contacts and progress never leave this phone.", T.SEC, C.MUTED))
        pr.addView(a.button("Delete chat memory", 2) {
            dialog().setTitle("Delete chat memory?")
                .setMessage("Jarvis will forget your chats and what it remembered about you. Your progress and reminders stay.")
                .setPositiveButton("Delete memory") { _, _ -> Brain.reset(a); a.clearChat() }
                .setNegativeButton("Cancel", null).create().showThemed(a, true)
        }, lp(-1, -2).apply { topMargin = a.dp(16) })
        col.addView(pr, lp(-1, -2))
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
