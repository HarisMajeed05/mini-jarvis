package com.jarvis.personal

import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

// Everything the server asks for ends up here. The server only sends plain names and text,
// I look up contacts and build every intent on the phone myself.
object Actions {
    private const val CC = "92" // my country code, used for numbers saved like 0300...
    private const val WA = "com.whatsapp"

    class Contact(val name: String, val number: String)

    private fun intl(raw: String): String {
        val plus = raw.trim().startsWith("+")
        var d = raw.filter { it.isDigit() }
        if (plus) return d
        if (d.startsWith("00")) d = d.drop(2) else if (d.startsWith("0")) d = CC + d.drop(1)
        return d
    }

    /** Returns the contact, or null after telling me why through say(). */
    private fun pick(ctx: Context, query: String, say: (String) -> Unit): Contact? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("I need the contacts permission first.")
            return null
        }
        val found = LinkedHashMap<String, Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${query.trim()}%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (!found.containsKey(name)) found[name] = Contact(name, intl(num))
            }
        }
        val exact = found.values.firstOrNull { it.name.equals(query.trim(), true) }
        return when {
            exact != null -> exact
            found.size == 1 -> found.values.first()
            found.isEmpty() -> { say("I could not find ${query.trim()} in your contacts."); null }
            else -> { say("Which one? " + found.keys.take(4).joinToString(", ")); null }
        }
    }

    // the phone number of a contact's WhatsApp call row, if WhatsApp synced that contact
    private fun whatsappCallUri(ctx: Context, name: String): Uri? {
        val mime = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.DISPLAY_NAME}=?", arrayOf(mime, name), null
        )?.use { c -> if (c.moveToFirst()) return Uri.parse("content://com.android.contacts/data/${c.getLong(0)}") }
        return null
    }

    private fun chatIntent(c: Contact, text: String? = null): Intent {
        val url = "https://wa.me/${c.number}" + if (text != null) "?text=" + Uri.encode(text) else ""
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(WA)
    }

    private fun l2On(act: Activity) =
        act.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("l2", true) && WhatsAppService.connected

    /** Runs the actions one after another. Calls and messages wait for my yes first. */
    fun run(act: Activity, list: JSONArray, say: (String) -> Unit) {
        fun step(i: Int) {
            if (i >= list.length()) return
            val next = { step(i + 1) }
            try {
                exec(act, list.getJSONObject(i), say, next)
            } catch (e: ActivityNotFoundException) {
                say("That app is not installed.")
                next()
            } catch (e: Exception) {
                say("That action failed.")
                next()
            }
        }
        step(0)
    }

    private fun confirm(act: Activity, msg: String, next: () -> Unit, yes: () -> Unit) {
        if (!act.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("confirm", true)) {
            yes(); next(); return
        }
        AlertDialog.Builder(act).setMessage(msg).setCancelable(false)
            .setPositiveButton("Yes") { _, _ ->
                try { yes() } catch (e: Exception) { Toast.makeText(act, "That action failed", Toast.LENGTH_SHORT).show() }
                next()
            }
            .setNegativeButton("No") { _, _ -> next() }
            .show()
    }

    private fun exec(act: Activity, a: JSONObject, say: (String) -> Unit, next: () -> Unit) {
        when (a.getString("type")) {
            "call" -> {
                val c = pick(act, a.getString("name"), say) ?: return next()
                confirm(act, "Call ${c.name}?", next) {
                    val granted = act.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    // without the call permission I fall back to the dialer, where I press call myself
                    act.startActivity(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:+${c.number}")))
                }
            }
            "whatsapp_message" -> {
                val c = pick(act, a.getString("name"), say) ?: return next()
                val text = a.getString("message")
                confirm(act, "WhatsApp to ${c.name}:\n\n\"$text\"", next) {
                    val auto = l2On(act)
                    if (auto) WhatsAppService.arm(WhatsAppService.Kind.SEND, text)
                    act.startActivity(chatIntent(c, text))
                    if (!auto) say("The message is typed in. Press send.")
                }
            }
            "whatsapp_call" -> {
                val c = pick(act, a.getString("name"), say) ?: return next()
                confirm(act, "WhatsApp call ${c.name}?", next) {
                    val uri = whatsappCallUri(act, c.name)
                    if (uri != null) {
                        act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call").setPackage(WA))
                    } else {
                        // WhatsApp has not synced this contact, so I open the chat and let the helper tap the call icon
                        val auto = l2On(act)
                        if (auto) WhatsAppService.arm(WhatsAppService.Kind.CALL)
                        act.startActivity(chatIntent(c))
                        if (!auto) say("The chat is open. Tap the call icon.")
                    }
                }
            }
            "open_app" -> {
                val i = act.packageManager.getLaunchIntentForPackage(a.getString("package")) ?: throw ActivityNotFoundException()
                act.startActivity(i)
                next()
            }
            "play_music" -> {
                val q = a.getString("query")
                if (a.getString("service") == "youtube") {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                } else {
                    try {
                        act.startActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                            .setPackage("com.spotify.music")
                            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                            .putExtra(SearchManager.QUERY, q))
                    } catch (e: ActivityNotFoundException) {
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))))
                    }
                }
                next()
            }
            "maps" -> {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.getString("query")))))
                next()
            }
            "alarm" -> {
                act.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                next()
            }
            "timer" -> {
                act.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("minutes") * 60)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                next()
            }
            "reminder" -> {
                if (!Reminders.addFromText(act, a.getString("text"), a.getString("at"))) say("I could not set that reminder time.")
                next()
            }
            else -> next()
        }
    }
}
