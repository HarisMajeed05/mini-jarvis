package com.jarvis.personal

import android.animation.ValueAnimator
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

// My colors. The whole app is built around who has the floor:
// saffron is me (my voice and anything I tap to act), the inverse field is Jarvis talking,
// and everything else stays night blue and quiet so those two moments stand out.
object C {
    var light = false
    var BG = 0          // night, most of every screen
    var RAISED = 0      // the resting floor, inputs, the feedback card
    var LINE = 0        // hairlines and outlines
    var TEXT = 0
    var MUTED = 0
    var SAFFRON = 0     // me, and the main action
    var ON_SAFFRON = 0
    var YOU = 0         // saffron as text and marks, deepened on the light theme so it stays readable
    var INVERSE = 0     // the floor while Jarvis speaks, always the opposite of the base
    var ON_INVERSE = 0
    var GOOD = 0
    var BAD = 0

    init { palette(false) }

    private fun c(s: String) = Color.parseColor(s)

    private fun palette(l: Boolean) {
        light = l
        SAFFRON = c("#F4B73B"); ON_SAFFRON = c("#241800")
        if (!l) {
            BG = c("#142033"); RAISED = c("#1D2B42"); LINE = c("#2E3F5C")
            TEXT = c("#F1ECE2"); MUTED = c("#93A0B6"); YOU = SAFFRON
            INVERSE = c("#F1ECE2"); ON_INVERSE = c("#142033")
            GOOD = c("#86D7A6"); BAD = c("#FF8F85")
        } else {
            BG = c("#E8EBEF"); RAISED = c("#F7F8FA"); LINE = c("#C5CCD6")
            TEXT = c("#142033"); MUTED = c("#4D5B70"); YOU = c("#8A5A00")
            INVERSE = c("#142033"); ON_INVERSE = c("#F1ECE2")
            GOOD = c("#17683F"); BAD = c("#B3261E")
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

// My type. One variable family, Bricolage Grotesque, cut into faces with its width and optical size axes:
// a narrow heavy display for titles, a slightly narrow voice for Jarvis, and plain text faces for the rest.
// The scale steps by about 1.33, nothing below 12.
object T {
    const val META = 12f
    const val SEC = 14f
    const val BODY = 16f
    const val H3 = 21f
    const val VOICE = 26f
    const val H2 = 34f
    const val H1 = 46f

    lateinit var display: Typeface
    lateinit var voice: Typeface
    lateinit var text: Typeface
    lateinit var strong: Typeface

    fun init(ctx: Context) {
        if (::display.isInitialized) return
        // build() is a Java call that can hand back null, so a missing or broken font falls back instead of crashing
        fun cut(axes: String, fallback: String): Typeface = try {
            Typeface.Builder(ctx.assets, "fonts/bricolage.ttf").setFontVariationSettings(axes).build()
        } catch (e: Exception) { null } ?: Typeface.create(fallback, Typeface.NORMAL)
        display = cut("'wght' 700, 'wdth' 78, 'opsz' 96", "sans-serif-condensed")
        voice = cut("'wght' 480, 'wdth' 88, 'opsz' 36", "sans-serif")
        text = cut("'wght' 400, 'wdth' 100, 'opsz' 14", "sans-serif")
        strong = cut("'wght' 620, 'wdth' 100, 'opsz' 14", "sans-serif-medium")
    }
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

fun Context.ripple(bg: Drawable, r: Int, dark: Boolean = C.light): Drawable =
    RippleDrawable(ColorStateList.valueOf(if (dark) Color.argb(30, 0, 0, 0) else Color.argb(40, 255, 255, 255)), bg, roundBg(Color.WHITE, r))

fun clearBg(): Drawable = ColorDrawable(Color.TRANSPARENT)

fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

fun Context.label(s: String, size: Float = T.BODY, color: Int = C.TEXT, strong: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = if (strong) T.strong else T.text
    setLineSpacing(0f, 1.35f)
}

// Jarvis's words. Narrow and large, so a reply reads from arm's length.
fun Context.voice(s: String, size: Float = T.VOICE, color: Int = C.TEXT) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = T.voice
    setLineSpacing(0f, 1.18f)
}

// page and section titles, tight and heavy
fun Context.heading(s: String, size: Float = T.H1, color: Int = C.TEXT) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = T.display
    setLineSpacing(0f, 1.02f)
    letterSpacing = -0.01f
}

// kind: 0 quiet outline, 1 saffron main action, 2 danger, 3 dark button sitting on a saffron block
fun Context.button(s: String, kind: Int = 0, onClick: () -> Unit) = TextView(this).apply {
    text = s
    textSize = T.BODY
    gravity = Gravity.CENTER
    typeface = T.strong
    minHeight = dp(52)
    setPadding(dp(22), dp(10), dp(22), dp(10))
    setTextColor(when (kind) { 1 -> C.ON_SAFFRON; 2 -> C.BAD; 3 -> C.SAFFRON; else -> C.TEXT })
    val bg = when (kind) {
        1 -> roundBg(C.SAFFRON, 16)
        2 -> roundBg(Color.TRANSPARENT, 16, C.BAD)
        3 -> roundBg(C.ON_SAFFRON, 16)
        else -> roundBg(Color.TRANSPARENT, 16, C.LINE)
    }
    background = ripple(bg, 16, kind == 1)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

fun Context.hairline() = View(this).apply { setBackgroundColor(C.LINE) }

// A vertical list where every item sits on a hairline, my replacement for boxed panels.
fun Context.rows() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun LinearLayout.item(v: View) {
    if (childCount > 0) addView(context.hairline(), lp(-1, 1))
    addView(v, lp(-1, -2))
}

// One of a few choices. The picked one is bold with a saffron bar under it, so it never relies on color alone.
fun Context.segments(options: List<Pair<String, String>>, current: String, pick: (String) -> Unit) = LinearLayout(this).apply {
    for ((id, name) in options) {
        val on = id == current
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(52)
            background = ripple(clearBg(), 12)
            isClickable = true
            isFocusable = true
            contentDescription = if (on) "$name, selected" else name
            setOnClickListener { if (!on) pick(id) }
        }
        cell.addView(label(name, T.SEC, if (on) C.TEXT else C.MUTED, on).apply { gravity = Gravity.CENTER })
        cell.addView(View(context).apply { background = roundBg(if (on) C.YOU else C.LINE, 2) },
            lp(-1, dp(if (on) 3 else 1)).apply { topMargin = dp(if (on) 8 else 9) })
        addView(cell, lp(0, -2, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
    }
}

fun AlertDialog.showThemed(ctx: Context, danger: Boolean = false) {
    window?.setBackgroundDrawable(ctx.roundBg(C.RAISED, 24, C.LINE))
    show()
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(if (danger) C.BAD else C.YOU)
    // Cancel gets the focus, so a stray Enter never confirms
    getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(C.TEXT); requestFocus() }
}

// My own switch: a saffron track with a dark knob when on, an outline when off. The row around it does the tapping.
class Toggle(ctx: Context, on: Boolean) : View(ctx) {
    var on = on
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(48), context.dp(28))

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val s = context.dp(1).toFloat()
        r.set(s, s, width - s, h - s)
        p.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeWidth = s * 1.5f
        p.color = if (on) C.SAFFRON else C.MUTED
        canvas.drawRoundRect(r, h / 2, h / 2, p)
        p.style = Paint.Style.FILL
        p.color = if (on) C.ON_SAFFRON else C.MUTED
        val knob = h / 2 - s * 5
        canvas.drawCircle(if (on) width - h / 2 else h / 2, h / 2, knob, p)
    }
}

// How hard a practice scene feels, as one to three rising bars. Filled bars are the level.
class LevelMark(ctx: Context, private val level: Int, private val ink: Int, private val empty: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(20), context.dp(16))

    override fun onDraw(canvas: Canvas) {
        val bw = width / 4.2f
        for (i in 0 until 3) {
            val top = height * (1f - (i + 1) / 3f)
            r.set(i * bw * 1.6f, top, i * bw * 1.6f + bw, height.toFloat())
            p.color = if (i < level) ink else empty
            canvas.drawRoundRect(r, bw / 3, bw / 3, p)
        }
    }
}

// Small line icons I draw myself on a 24 unit grid, so every icon has the same stroke and no emoji is needed.
class IconView(ctx: Context, kind: IconView.Kind, tint: Int) : View(ctx) {
    enum class Kind { MIC, STOP, SEND, CLOSE, CHEVRON, BACK, HISTORY }

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

// The voice line. It lives on the floor and is drawn in the floor's ink, so it always contrasts with the field:
// almost flat while resting, loud with my voice, a quick ripple while Jarvis thinks,
// beating with every word Jarvis says. With animations off it holds a still shape.
class VoiceLine(ctx: Context) : View(ctx) {
    enum class S { IDLE, STANDBY, LISTEN, THINK, SPEAK }

    var state = S.IDLE
        set(v) { field = v; invalidate() }
    var ink = C.MUTED
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
            S.STANDBY -> if (still) 0.06f else 0.06f + 0.04f * (sin(t * 1.4f) * 0.5f + 0.5f)
            S.IDLE -> 0.02f
        }
        kick *= 0.86f
        smooth = if (still) target else smooth + (target - smooth) * 0.2f
        val amp = mid * 0.92f * smooth
        val speed = when (state) { S.THINK -> 5f; S.LISTEN -> 7f; S.SPEAK -> 6f; else -> 1.2f }

        // three strands, the back ones are thinner and fainter
        for (strand in 2 downTo 0) {
            path.reset()
            val n = 72
            for (i in 0..n) {
                val x = i / n.toFloat()
                // the line is pinned at both edges and loudest in the middle
                val window = sin(x * PI.toFloat())
                val y = mid + amp * window * (1f - strand * 0.28f) *
                    sin(x * (8f + strand * 3f) + t * speed * (1f + strand * 0.35f) + strand * 1.7f)
                if (i == 0) path.moveTo(0f, y) else path.lineTo(x * w, y)
            }
            paint.color = ink
            paint.alpha = when (strand) { 0 -> 255; 1 -> 110; else -> 50 }
            paint.strokeWidth = context.dp(if (strand == 0) 3 else 1).toFloat()
            canvas.drawPath(path, paint)
        }
        // resting draws once, every other state keeps moving
        if (!still && (state != S.IDLE || smooth > 0.03f)) postInvalidateOnAnimation()
    }
}

// The floor's background eases from one field color to the next, so a change of speaker reads as one motion.
fun Context.fade(bg: GradientDrawable, from: Int, to: Int) {
    if (from == to || reduceMotion()) { bg.setColor(to); return }
    ValueAnimator.ofArgb(from, to).apply {
        duration = 260
        addUpdateListener { bg.setColor(it.animatedValue as Int) }
        start()
    }
}
