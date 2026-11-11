package com.jarvis.personal

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

// My colors. The idea is a quiet evening room: a deep teal canvas, warm paper text, and two voices.
// Jarvis speaks in lamp light (ACCENT), I speak in mint (YOU). Nothing else gets a color.
object C {
    var light = false
    var BG = 0        // canvas, most of every screen
    var SURFACE = 0   // panels, one step up
    var SURFACE2 = 0  // inputs and tracks
    var STROKE = 0    // 1dp borders and hairlines
    var TEXT = 0
    var MUTED = 0
    var ACCENT = 0    // Jarvis's voice and the main action
    var ACCENT_SOFT = 0
    var INK = 0       // text on top of the accent
    var YOU = 0       // my voice
    var YOU_SOFT = 0
    var GOOD = 0
    var BAD = 0

    init { palette(false) }

    private fun c(s: String) = Color.parseColor(s)

    private fun palette(l: Boolean) {
        light = l
        if (!l) {
            BG = c("#0F1A1D"); SURFACE = c("#15252A"); SURFACE2 = c("#1C3036"); STROKE = c("#2F474E")
            TEXT = c("#F1ECE2"); MUTED = c("#9DB0B3")
            ACCENT = c("#FFB48A"); ACCENT_SOFT = c("#3A2A22"); INK = c("#2A1408")
            YOU = c("#7FE0CF"); YOU_SOFT = c("#17383A")
            GOOD = c("#8FD694"); BAD = c("#FF9A93")
        } else {
            // the pale lamp and mint would vanish on white, so the light theme uses their deep versions
            BG = c("#EEF3F1"); SURFACE = c("#FFFFFF"); SURFACE2 = c("#E2EAE7"); STROKE = c("#C3D0CC")
            TEXT = c("#122226"); MUTED = c("#4F6367")
            ACCENT = c("#A8431A"); ACCENT_SOFT = c("#F8E4D9"); INK = c("#FFFFFF")
            YOU = c("#0B6B62"); YOU_SOFT = c("#D8ECE8")
            GOOD = c("#176B44"); BAD = c("#B3261E")
        }
    }

    fun apply(ctx: Context) {
        val pick = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("theme", "dark")
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        palette(when (pick) { "light" -> true; "system" -> !night; else -> false })
    }

    fun dialogTheme() =
        if (light) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert
}

// My type scale. Sans for the interface, serif for Jarvis's voice and page titles. Nothing below 12.
object T {
    const val META = 12f
    const val SEC = 14f
    const val BODY = 16f
    const val H2 = 20f
    const val VOICE = 22f
    const val H1 = 30f

    val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val serif: Typeface = Typeface.create("serif", Typeface.NORMAL)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

// people who turn animations off in Android should get a still screen
fun Context.reduceMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

fun Context.roundBg(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(r).toFloat()
    if (stroke != 0) setStroke(dp(1), stroke)
}

fun Context.ripple(bg: Drawable, r: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(if (C.light) Color.argb(30, 0, 0, 0) else Color.argb(40, 255, 255, 255)), bg, roundBg(Color.WHITE, r))

fun clearBg(): Drawable = ColorDrawable(Color.TRANSPARENT)

fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

fun Context.label(s: String, size: Float = T.BODY, color: Int = C.TEXT, medium: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = if (medium) T.medium else T.sans
    // titles sit tight, reading text gets room
    setLineSpacing(0f, if (size >= T.H2) 1.1f else 1.3f)
}

// Jarvis's words and the page titles. Size carries the hierarchy, the serif has one weight.
fun Context.voice(s: String, size: Float = T.VOICE, color: Int = C.TEXT) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = T.serif
    setLineSpacing(0f, if (size >= T.H1) 1.1f else 1.25f)
}

// kind: 0 normal, 1 primary, 2 danger
fun Context.button(s: String, kind: Int = 0, onClick: () -> Unit) = TextView(this).apply {
    text = s
    textSize = T.SEC
    gravity = Gravity.CENTER
    typeface = T.medium
    minHeight = dp(48)
    setPadding(dp(20), dp(8), dp(20), dp(8))
    setTextColor(when (kind) { 1 -> C.INK; 2 -> C.BAD; else -> C.TEXT })
    val bg = when (kind) {
        1 -> roundBg(C.ACCENT, 24)
        2 -> roundBg(C.SURFACE, 24, C.BAD)
        else -> roundBg(C.SURFACE, 24, C.STROKE)
    }
    background = ripple(bg, 24)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

// a chip is a choice, the picked one gets a border, the accent color and a heavier weight, so it never relies on color alone
fun Context.styleChip(v: TextView, selected: Boolean) {
    v.setTextColor(if (selected) C.ACCENT else C.TEXT)
    v.typeface = if (selected) T.medium else T.sans
    v.background = ripple(if (selected) roundBg(C.ACCENT_SOFT, 12, C.ACCENT) else roundBg(C.SURFACE, 12, C.STROKE), 12)
}

fun Context.chip(s: String, selected: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
    text = s
    textSize = T.SEC
    gravity = Gravity.CENTER
    minHeight = dp(48)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    isClickable = true
    isFocusable = true
    styleChip(this, selected)
    setOnClickListener { onClick() }
}

// a framed block, the same surface for every list and group
fun Context.panel() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = roundBg(C.SURFACE, 20, C.STROKE)
    clipToOutline = true // rows with a press ripple stay inside the rounded corners
}

fun Context.hairline() = View(this).apply { setBackgroundColor(C.STROKE) }

fun AlertDialog.showThemed(ctx: Context, danger: Boolean = false) {
    window?.setBackgroundDrawable(ctx.roundBg(C.SURFACE, 20, C.STROKE))
    show()
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(if (danger) C.BAD else C.ACCENT)
    // Cancel gets the focus, so a stray Enter never confirms
    getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(C.TEXT); requestFocus() }
}

// Small line icons I draw myself on a 24 unit grid, so every icon has the same stroke and no emoji is needed.
class IconView(ctx: Context, kind: IconView.Kind, tint: Int) : View(ctx) {
    enum class Kind { MIC, STOP, PRACTICE, PROGRESS, SETTINGS, SEND, CLOSE, CHEVRON, BACK, HISTORY }

    var kind: Kind = kind
        set(v) { field = v; invalidate() }
    var tint: Int = tint
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val k = width / 24f
        p.color = tint
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * k
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1 * k, y1 * k, x2 * k, y2 * k, p)
        fun poly(vararg pts: Float) {
            path.reset()
            path.moveTo(pts[0] * k, pts[1] * k)
            var i = 2
            while (i < pts.size) { path.lineTo(pts[i] * k, pts[i + 1] * k); i += 2 }
            canvas.drawPath(path, p)
        }
        when (kind) {
            Kind.MIC -> {
                canvas.drawRoundRect(RectF(9f * k, 3f * k, 15f * k, 13f * k), 3f * k, 3f * k, p)
                canvas.drawArc(RectF(6f * k, 4f * k, 18f * k, 16f * k), 0f, 180f, false, p)
                line(12f, 16f, 12f, 20f)
            }
            Kind.STOP -> {
                p.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(7f * k, 7f * k, 17f * k, 17f * k), 2f * k, 2f * k, p)
            }
            Kind.PRACTICE -> {
                // two speech shapes facing each other
                poly(4f, 6f, 14f, 6f, 14f, 12f, 8f, 12f, 4f, 15f, 4f, 6f)
                poly(17f, 10f, 20f, 10f, 20f, 19f, 16f, 16f, 10f, 16f)
            }
            Kind.PROGRESS -> {
                line(6f, 20f, 6f, 14f)
                line(12f, 20f, 12f, 4f)
                line(18f, 20f, 18f, 10f)
            }
            Kind.SETTINGS -> {
                line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f)
                p.style = Paint.Style.FILL
                canvas.drawCircle(9f * k, 7f * k, 2.6f * k, p)
                canvas.drawCircle(15f * k, 17f * k, 2.6f * k, p)
            }
            Kind.SEND -> { line(12f, 19f, 12f, 5f); poly(6f, 11f, 12f, 5f, 18f, 11f) }
            Kind.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Kind.CHEVRON -> poly(9f, 6f, 15f, 12f, 9f, 18f)
            Kind.BACK -> poly(15f, 6f, 9f, 12f, 15f, 18f)
            Kind.HISTORY -> {
                canvas.drawCircle(12f * k, 12f * k, 9f * k, p)
                line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f)
            }
        }
    }
}

// The voice line. One line runs across the whole screen and shows who has the floor:
// flat while resting, mint and loud with my voice, a quick ripple while Jarvis thinks,
// lamp colored and beating with every word Jarvis says. With animations off it holds a still shape.
class VoiceLine(ctx: Context) : View(ctx) {
    enum class S { IDLE, STANDBY, LISTEN, THINK, SPEAK }

    var state = S.IDLE
        set(v) { field = v; invalidate() }
    var level = 0f // 0..1 mic loudness, set by the activity
        set(v) { field = v; if (still) invalidate() }

    private val still = ctx.reduceMotion()
    private var smooth = 0f
    private var kick = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val start = System.nanoTime()

    /** a short beat for every spoken word */
    fun pulse() { kick = 1f; if (still) invalidate() }

    override fun onDraw(canvas: Canvas) {
        val t = if (still) 0f else (System.nanoTime() - start) / 1_000_000_000f
        val w = width.toFloat()
        val mid = height / 2f
        val target = when (state) {
            S.LISTEN -> 0.15f + level * 0.85f
            S.SPEAK -> if (still) 0.5f else 0.25f + 0.1f * (sin(t * 5f) * 0.5f + 0.5f) + 0.55f * kick
            S.THINK -> 0.16f
            S.STANDBY -> if (still) 0.05f else 0.05f + 0.03f * (sin(t * 1.4f) * 0.5f + 0.5f)
            S.IDLE -> 0.02f
        }
        kick *= 0.86f
        smooth = if (still) target else smooth + (target - smooth) * 0.2f
        val color = when (state) { S.LISTEN -> C.YOU; S.SPEAK -> C.ACCENT; S.THINK -> C.MUTED; else -> C.STROKE }
        val amp = mid * 0.9f * smooth
        val speed = when (state) { S.THINK -> 5f; S.LISTEN -> 7f; S.SPEAK -> 6f; else -> 1.2f }

        // three strands, the back ones are thinner and fainter
        for (strand in 2 downTo 0) {
            path.reset()
            val n = 64
            for (i in 0..n) {
                val x = i / n.toFloat()
                // the line is pinned at both edges and loudest in the middle
                val window = sin(x * PI.toFloat())
                val y = mid + amp * window * (1f - strand * 0.28f) *
                    sin(x * (8f + strand * 3f) + t * speed * (1f + strand * 0.35f) + strand * 1.7f)
                if (i == 0) path.moveTo(0f, y) else path.lineTo(x * w, y)
            }
            paint.color = color
            paint.alpha = when (strand) { 0 -> 255; 1 -> 120; else -> 60 }
            paint.strokeWidth = context.dp(if (strand == 0) 2 else 1).toFloat()
            canvas.drawPath(path, paint)
        }
        // resting draws once, every other state keeps moving
        if (!still && (state != S.IDLE || smooth > 0.03f)) postInvalidateOnAnimation()
    }
}
