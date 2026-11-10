package com.jarvis.personal

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// My reminders and the daily check in. Stored on the phone, rescheduled after a reboot.
object Reminders {
    private const val CHECKIN_ID = 1
    private const val CHECKIN_TEXT = "How was your day? Tap to talk."

    class Item(val id: Int, val text: String, val at: Long, val daily: Boolean)

    private fun prefs(c: Context) = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private fun cfg(c: Context) = c.getSharedPreferences("checkin", Context.MODE_PRIVATE)
    private fun load(c: Context) = JSONArray(prefs(c).getString("list", "[]"))
    private fun save(c: Context, a: JSONArray) = prefs(c).edit().putString("list", a.toString()).apply()

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun list(c: Context): List<Item> {
        val a = load(c)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.getInt("id"), o.getString("text"), o.getLong("at"), o.getBoolean("daily"))
        }.sortedBy { it.at }
    }

    /** at is 'YYYY-MM-DD' (I default that to 9 am) or 'YYYY-MM-DDTHH:MM'. Returns false for bad or past times. */
    fun addFromText(c: Context, text: String, at: String): Boolean {
        val t = try {
            if (at.length == 10) LocalDate.parse(at).atTime(9, 0) else LocalDateTime.parse(at)
        } catch (e: Exception) {
            return false
        }
        val ms = millis(t)
        if (ms <= System.currentTimeMillis()) return false
        add(c, text, ms, false, (System.currentTimeMillis() % 1_000_000_000L).toInt() + 10)
        return true
    }

    private fun add(c: Context, text: String, at: Long, daily: Boolean, id: Int) {
        val list = load(c)
        val kept = JSONArray()
        for (i in 0 until list.length()) if (list.getJSONObject(i).getInt("id") != id) kept.put(list.getJSONObject(i))
        kept.put(JSONObject().put("id", id).put("text", text).put("at", at).put("daily", daily))
        save(c, kept)
        schedule(c, id, text, at, daily)
    }

    fun remove(c: Context, id: Int) {
        val list = load(c)
        val kept = JSONArray()
        for (i in 0 until list.length()) if (list.getJSONObject(i).getInt("id") != id) kept.put(list.getJSONObject(i))
        save(c, kept)
        val pi = PendingIntent.getBroadcast(c, id, Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        if (pi != null) {
            c.getSystemService(AlarmManager::class.java).cancel(pi)
            pi.cancel()
        }
    }

    fun checkinOn(c: Context) = cfg(c).getBoolean("on", true)
    fun checkinHour(c: Context) = cfg(c).getInt("h", 20) // I want the daily "how was your day" at 8 pm by default
    fun checkinMinute(c: Context) = cfg(c).getInt("m", 0)

    fun setCheckin(c: Context, on: Boolean, hour: Int, minute: Int) {
        cfg(c).edit().putBoolean("on", on).putInt("h", hour).putInt("m", minute).apply()
        if (!on) { remove(c, CHECKIN_ID); return }
        var t = LocalDate.now().atTime(hour, minute)
        if (millis(t) <= System.currentTimeMillis()) t = t.plusDays(1)
        add(c, CHECKIN_TEXT, millis(t), true, CHECKIN_ID)
    }

    fun ensureCheckin(c: Context) {
        if (!checkinOn(c)) return
        if (list(c).any { it.id == CHECKIN_ID }) return
        setCheckin(c, true, checkinHour(c), checkinMinute(c))
    }

    fun canExact(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun schedule(c: Context, id: Int, text: String, at: Long, daily: Boolean) {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("id", id).putExtra("text", text).putExtra("daily", daily)
        val pi = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = c.getSystemService(AlarmManager::class.java)
        // exact when I allowed it in Android settings, otherwise Android may delay it a few minutes
        if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun rescheduleAll(c: Context) {
        val list = load(c)
        val kept = JSONArray()
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            var at = r.getLong("at")
            val daily = r.getBoolean("daily")
            while (daily && at <= System.currentTimeMillis()) at += 86_400_000L
            if (!daily && at <= System.currentTimeMillis()) continue // missed while the phone was off
            r.put("at", at)
            kept.put(r)
            schedule(c, r.getInt("id"), r.getString("text"), at, daily)
        }
        save(c, kept)
    }

    fun fire(c: Context, id: Int, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(c, MainActivity::class.java)
            .putExtra("checkin", id == CHECKIN_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id, Notification.Builder(c, "jarvis")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
        rescheduleAll(c) // moves the daily one to tomorrow and drops the one shot that just fired
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.fire(context, intent.getIntExtra("id", 0), intent.getStringExtra("text") ?: "")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.rescheduleAll(context)
    }
}
