package com.jarvis.personal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Practice scenarios, my session history, streaks, and the adaptive daily pick.
object Coach {
    class Scenario(val id: String, val title: String, val level: Int, val blurb: String, val role: String)

    val ALL = listOf(
        Scenario("hello", "Say hello", 1, "Greet a coworker you pass in the hallway",
            "a friendly coworker the user meets in a hallway. Start with a casual hello."),
        Scenario("smalltalk", "Small talk", 1, "Chat with a new classmate before class starts",
            "a friendly new classmate waiting for class to start. Open with a light comment."),
        Scenario("intro", "Introduce yourself", 2, "Meet someone new at a small event",
            "a friendly person at a small meetup who just met the user."),
        Scenario("question", "Ask a question", 2, "Ask your professor something after class",
            "a professor packing up after class, approachable and patient."),
        Scenario("call", "Phone call", 2, "Call a shop to ask about a product",
            "a shopkeeper answering the phone, polite and a little busy. Start with a phone greeting."),
        Scenario("meeting", "Speak in a meeting", 3, "Give a short update to your team",
            "a team lead running a standup who asks the user for a short project update, then one follow up question."),
        Scenario("group", "Join a group", 3, "Walk up to a group chatting at a gathering",
            "a friendly member of a small group chatting at a gathering, who notices the user joining."),
        Scenario("disagree", "Disagree politely", 3, "Share a different opinion without conflict",
            "a colleague who proposes an idea the user may want to politely disagree with.")
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    private val CHALLENGES = listOf(
        "Say hi to one person you do not know well.",
        "Ask one person one real question about their day.",
        "Give one genuine compliment.",
        "Stay in a group conversation for two minutes.",
        "Smile and make eye contact with three people.",
        "Message an old friend and ask how they are.",
        "Order something yourself and add one friendly sentence."
    )

    fun todayChallenge() = CHALLENGES[LocalDate.now().dayOfYear % CHALLENGES.size]

    class Session(val ts: Long, val scenario: String, val clarity: Int, val curiosity: Int, val warmth: Int, val confidence: Int) {
        val avg: Float get() = (clarity + curiosity + warmth + confidence) / 4f
    }

    private fun p(c: Context) = c.getSharedPreferences("coach", Context.MODE_PRIVATE)

    fun sessions(c: Context): List<Session> {
        val a = JSONArray(p(c).getString("sessions", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Session(o.getLong("ts"), o.getString("sc"), o.getInt("cl"), o.getInt("cu"), o.getInt("wa"), o.getInt("co"))
        }
    }

    fun addSession(c: Context, s: Session) {
        val a = JSONArray(p(c).getString("sessions", "[]"))
        a.put(JSONObject().put("ts", s.ts).put("sc", s.scenario).put("cl", s.clarity).put("cu", s.curiosity)
            .put("wa", s.warmth).put("co", s.confidence))
        val kept = JSONArray()
        for (i in maxOf(0, a.length() - 90) until a.length()) kept.put(a.get(i))
        p(c).edit().putString("sessions", kept.toString()).apply()
    }

    private fun day(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun consecutive(days: Set<LocalDate>): Int {
        var d = LocalDate.now()
        if (!days.contains(d)) d = d.minusDays(1) // today is still open, do not break the streak yet
        var n = 0
        while (days.contains(d)) { n++; d = d.minusDays(1) }
        return n
    }

    fun streak(c: Context) = consecutive(sessions(c).map { day(it.ts) }.toSet())

    // the real life step, this is the part that actually matters
    private fun real(c: Context): Set<String> {
        val a = JSONArray(p(c).getString("real", "[]"))
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    fun today(): String = LocalDate.now().toString()

    fun realDone(c: Context, d: String) = real(c).contains(d)

    fun setReal(c: Context, d: String, on: Boolean) {
        val s = real(c).toMutableSet()
        if (on) s.add(d) else s.remove(d)
        p(c).edit().putString("real", JSONArray(s.sorted().takeLast(120)).toString()).apply()
    }

    fun realStreak(c: Context) = consecutive(real(c).map { LocalDate.parse(it) }.toSet())

    /** Difficulty follows my last three sessions, so it gets harder only when I am ready. */
    fun todayPick(c: Context): Scenario {
        val last = sessions(c).takeLast(3)
        val avg = if (last.size < 3) 0.0 else last.map { it.avg.toDouble() }.average()
        val level = when {
            last.size < 3 -> 1
            avg >= 7.5 -> 3
            avg >= 6.0 -> 2
            else -> 1
        }
        val pool = ALL.filter { it.level <= level }
        return pool[LocalDate.now().dayOfYear % pool.size]
    }
}
