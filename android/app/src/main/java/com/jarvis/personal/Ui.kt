package com.jarvis.personal

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// My colors. A quiet ink canvas with one warm gold accent, so the voice orb is the only thing that glows.
// The palette is swapped by apply() before any screen is built, dark by default, light or follow the system.
object C {
    var light = false
    var BG = 0        // canvas, about 60% of every screen
    var SURFACE = 0   // panels, one step up
    var SURFACE2 = 0  // inputs and my own messages
    var STROKE = 0    // 1dp borders and hairlines
    var TEXT = 0
    var MUTED = 0
    var ACCENT = 0    // the only accent, actions and active states
    var ACCENT_SOFT = 0
    var INK = 0       // text on top of the accent
    var GOOD = 0
    var BAD = 0
    var TRACK_ON = 0  // switch track when on
    val ORB = Color.parseColor("#E6B65C") // the orb keeps its glow in both themes

    init { palette(false) }

    private fun palette(l: Boolean) {
        light = l
        if (!l) {
            BG = Color.parseColor("#101417"); SURFACE = Color.parseColor("#171C21"); SURFACE2 = Color.parseColor("#1F262C")
            STROKE = Color.parseColor("#2B353D"); TEXT = Color.parseColor("#E8ECEF"); MUTED = Color.parseColor("#93A0AA")
            ACCENT = Color.parseColor("#E6B65C"); ACCENT_SOFT = Color.parseColor("#2B2415"); INK = Color.parseColor("#1A1405")
            GOOD = Color.parseColor("#6CCB9B"); BAD = Color.parseColor("#F0837A"); TRACK_ON = Color.parseColor("#6B5528")
        } else {
            // gold is too pale for text on white, so the light accent is a deeper amber that keeps 4.5:1
            BG = Color.parseColor("#F3F5F7"); SURFACE = Color.parseColor("#FFFFFF"); SURFACE2 = Color.parseColor("#E9EDF0")
            STROKE = Color.parseColor("#D3DAE0"); TEXT = Color.parseColor("#151A1E"); MUTED = Color.parseColor("#56636D")
            ACCENT = Color.parseColor("#8A5F00"); ACCENT_SOFT = Color.parseColor("#F6EBD0"); INK = Color.parseColor("#FFFFFF")
            GOOD = Color.parseColor("#1E7A4F"); BAD = Color.parseColor("#B3261E"); TRACK_ON = Color.parseColor("#D9B96A")
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

// My type scale. Three sizes for reading, two steps up for titles. Nothing below 12.
object T {
    const val META = 12f
    const val SEC = 14f
    const val BODY = 16f
    const val H2 = 20f
    const val H1 = 28f
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

// my own message bubble, the small corner sits where the message comes from
fun Context.bubbleBg(color: Int) = GradientDrawable().apply {
    setColor(color)
    val big = dp(20).toFloat()
    val small = dp(4).toFloat()
    cornerRadii = floatArrayOf(big, big, big, big, small, small, big, big)
}

fun Context.ripple(bg: Drawable, r: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(if (C.light) Color.argb(30, 0, 0, 0) else Color.argb(40, 255, 255, 255)), bg, roundBg(Color.WHITE, r))

fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

fun Context.label(s: String, size: Float = T.BODY, color: Int = C.TEXT, medium: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    // titles sit tight, reading text gets room
    setLineSpacing(0f, if (size >= T.H2) 1f else 1.25f)
}

// kind: 0 normal, 1 primary (gold), 2 danger
fun Context.button(s: String, kind: Int = 0, onClick: () -> Unit) = TextView(this).apply {
    text = s
    textSize = T.SEC
    gravity = Gravity.CENTER
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    minHeight = dp(48)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    setTextColor(when (kind) { 1 -> C.INK; 2 -> C.BAD; else -> C.TEXT })
    val bg = when (kind) {
        1 -> roundBg(C.ACCENT, 12)
        2 -> roundBg(C.SURFACE, 12, C.BAD)
        else -> roundBg(C.SURFACE2, 12, C.STROKE)
    }
    background = ripple(bg, 12)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

// a chip is a choice, the picked one gets a gold border, gold text and a heavier weight, so it never relies on color alone
fun Context.styleChip(v: TextView, selected: Boolean) {
    v.setTextColor(if (selected) C.ACCENT else C.TEXT)
    v.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    v.background = ripple(if (selected) roundBg(C.ACCENT_SOFT, 24, C.ACCENT) else roundBg(C.SURFACE, 24, C.STROKE), 24)
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
    background = roundBg(C.SURFACE, 16, C.STROKE)
    clipToOutline = true // rows with a press ripple stay inside the rounded corners
}

fun Context.hairline() = View(this).apply { setBackgroundColor(C.STROKE) }

fun AlertDialog.showThemed(ctx: Context, danger: Boolean = false) {
    window?.setBackgroundDrawable(ctx.roundBg(C.SURFACE, 16, C.STROKE))
    show()
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(if (danger) C.BAD else C.ACCENT)
    // Cancel gets the focus, so a stray Enter never confirms
    getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(C.TEXT); requestFocus() }
}

// Small line icons I draw myself on a 24 unit grid, so every icon has the same stroke and no emoji is needed.
class IconView(ctx: Context, private val kind: IconView.Kind, tint: Int) : View(ctx) {
    enum class Kind { MIC, PRACTICE, PROGRESS, SETTINGS, SEND, CLOSE, CHEVRON, BACK, HISTORY }

    var tint: Int = tint
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
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
        when (kind) {
            Kind.MIC -> {
                canvas.drawRoundRect(RectF(9f * k, 3f * k, 15f * k, 13f * k), 3f * k, 3f * k, p)
                canvas.drawArc(RectF(6f * k, 6f * k, 18f * k, 18f * k), 0f, 180f, false, p)
                line(12f, 18f, 12f, 21f)
            }
            Kind.PRACTICE -> {
                canvas.drawCircle(12f * k, 12f * k, 9f * k, p)
                canvas.drawCircle(12f * k, 12f * k, 4.5f * k, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(12f * k, 12f * k, 1.2f * k, p)
            }
            Kind.PROGRESS -> {
                line(6f, 20f, 6f, 14f)
                line(12f, 20f, 12f, 4f)
                line(18f, 20f, 18f, 10f)
            }
            Kind.SETTINGS -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                p.style = Paint.Style.FILL
                canvas.drawCircle(9f * k, 6f * k, 2.4f * k, p)
                canvas.drawCircle(15f * k, 12f * k, 2.4f * k, p)
                canvas.drawCircle(8f * k, 18f * k, 2.4f * k, p)
            }
            Kind.SEND -> {
                line(12f, 19f, 12f, 5f)
                path.reset(); path.moveTo(6f * k, 11f * k); path.lineTo(12f * k, 5f * k); path.lineTo(18f * k, 11f * k)
                canvas.drawPath(path, p)
            }
            Kind.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Kind.CHEVRON -> {
                path.reset(); path.moveTo(9f * k, 6f * k); path.lineTo(15f * k, 12f * k); path.lineTo(9f * k, 18f * k)
                canvas.drawPath(path, p)
            }
            Kind.BACK -> {
                path.reset(); path.moveTo(15f * k, 6f * k); path.lineTo(9f * k, 12f * k); path.lineTo(15f * k, 18f * k)
                canvas.drawPath(path, p)
            }
            Kind.HISTORY -> {
                canvas.drawCircle(12f * k, 12f * k, 9f * k, p)
                line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f)
            }
        }
    }
}

// The voice orb. It breathes when idle, follows my voice while listening, shows an arc while thinking
// and pulses while Jarvis talks. With animations off in Android it stays still and only changes shape.
class OrbView(ctx: Context) : View(ctx) {
    enum class S { IDLE, LISTEN, THINK, SPEAK }

    var state = S.IDLE
        set(v) { field = v; invalidate() }
    var level = 0f // 0..1 mic loudness, set by the activity
        set(v) { field = v; if (still) invalidate() }

    private val still = ctx.reduceMotion()
    private var smooth = 0f
    private var kick = 0f // a short beat for every spoken word

    fun pulse() { kick = 1f; if (still) invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()
    private val wave = Path()

    override fun onDraw(canvas: Canvas) {
        val t = if (still) 0f else (System.nanoTime() - start) / 1_000_000_000f
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * 0.27f

        val target = when (state) {
            S.LISTEN -> 0.25f + level * 0.75f
            S.SPEAK -> if (still) 0.5f else 0.38f + 0.08f * (sin(t * 5f) * 0.5f + 0.5f) + 0.3f * kick
            S.THINK -> 0.3f
            S.IDLE -> if (still) 0.12f else 0.12f + 0.05f * sin(t * 1.6f)
        }
        kick *= 0.88f
        smooth = if (still) target else smooth + (target - smooth) * 0.18f
        val r = base * (1f + smooth * 0.3f)

        // soft glow rings, one hue
        paint.style = Paint.Style.FILL
        paint.shader = null
        for (i in 3 downTo 1) {
            paint.color = Color.argb((40 / i * (0.5f + smooth)).toInt().coerceIn(0, 255), 230, 182, 92)
            canvas.drawCircle(cx, cy, r * (1f + i * 0.3f * (0.6f + smooth)), paint)
        }

        // the core
        paint.shader = RadialGradient(
            cx - r * 0.25f, cy - r * 0.3f, r * 1.25f,
            intArrayOf(Color.parseColor("#F6DDA6"), C.ORB, Color.parseColor("#7A5A1C")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // ring that follows the voice, a plain circle when motion is off
        wave.reset()
        val n = 72
        val amp = if (still) 0f else 0.07f * smooth
        for (i in 0..n) {
            val a = i.toFloat() / n * 2f * PI.toFloat()
            val rr = r * (1.14f + amp * sin(a * 5f + t * 4f))
            val x = cx + rr * cos(a)
            val y = cy + rr * sin(a)
            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        wave.close()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(2).toFloat()
        paint.color = Color.argb(150, 246, 221, 166)
        canvas.drawPath(wave, paint)

        // thinking arc, the status text says it too
        if (state == S.THINK) {
            paint.strokeWidth = context.dp(3).toFloat()
            paint.color = Color.argb(220, 246, 221, 166)
            val o = r * 1.4f
            canvas.drawArc(RectF(cx - o, cy - o, cx + o, cy + o), if (still) 0f else (t * 260f) % 360f, 80f, false, paint)
        }
        paint.style = Paint.Style.FILL
        if (!still) postInvalidateOnAnimation()
    }
}
