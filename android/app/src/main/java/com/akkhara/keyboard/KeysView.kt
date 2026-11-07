package com.akkhara.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/** The three keyboards inside Akkhara (the globe key cycles through them). */
enum class Mode(val short: String, val spaceLabel: String) {
    SING("Sing", "Sing Khmer → ខ្មែរ"),     // type Latin letters, get Khmer candidates (the model)
    KHMER("ខ្មែរ", "ខ្មែរ"),                 // real Khmer letters, standard NiDA layout, typed directly
    ENGLISH("ABC", "English");              // plain English

    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * ALL keys drawn as ONE view on a Canvas (like Samsung / Gboard), instead of ~40 separate views:
 * a key press only redraws that key → smooth.
 *
 * Touch: fires on finger DOWN, multi-touch, no dead gaps (nearest key in the row),
 * ⌫ repeats while held, globe: tap = next Akkhara keyboard, long-press = other phone keyboard.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeysView(context: Context, private val theme: KeyTheme, private val onKey: (Key, Boolean) -> Unit) : View(context) {

    enum class Kind { TEXT, SHIFT, BACKSPACE, GLOBE, SPACE, ENTER }

    /** One key. [out]/[shiftOut] = text it types (normal / with ⇧). */
    class Key(val kind: Kind, val out: String, val weight: Float = 1f, val shiftOut: String = out) {
        val rect = RectF()
        var pressed = false
    }

    private class Row(val keys: List<Key>, val heightDp: Float, val flat: Boolean = false) {
        var top = 0f
        var bottom = 0f
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ---- state ----------------------------------------------------------------------------------
    var mode = Mode.SING
        set(v) { field = v; shift = false; capsLock = false; build(); requestLayout(); invalidate() }
    var shift = false
        set(v) { field = v; invalidate() }
    var capsLock = false
        set(v) { field = v; invalidate() }
    var enterLabel = "⏎"
        set(v) { field = v; invalidate() }

    // ---- layouts --------------------------------------------------------------------------------
    private var rows: List<Row> = emptyList()

    private fun latin(s: String) = s.map { Key(Kind.TEXT, it.toString(), 1f, it.uppercaseChar().toString()) }

    /** Standard Khmer (NiDA) layout: pairs of (normal, shift), same positions as on a computer. */
    private fun khmer(pairs: List<Pair<String, String>>) = pairs.map { (n, s) -> Key(Kind.TEXT, n, 1f, s) }

    private fun bottomRow(comma: String, period: String) = Row(listOf(
        Key(Kind.GLOBE, "", 1.25f),
        Key(Kind.TEXT, comma, 0.9f),
        Key(Kind.SPACE, " ", 4.6f),
        Key(Kind.TEXT, period, 0.9f),
        Key(Kind.ENTER, "\n", 1.5f),
    ), 52f)

    private fun build() {
        rows = when (mode) {
            Mode.SING, Mode.ENGLISH -> listOf(
                Row("1234567890".map { Key(Kind.TEXT, it.toString()) }, 40f, flat = true),
                Row(latin("qwertyuiop"), 52f),
                Row(latin("asdfghjkl") + Key(Kind.TEXT, "'", 1f, "\""), 52f),
                Row(listOf(Key(Kind.SHIFT, "", 1.45f)) + latin("zxcvbnm") + Key(Kind.BACKSPACE, "", 1.45f), 52f),
                bottomRow(",", "."),
            )
            Mode.KHMER -> listOf(
                Row(khmer(listOf("១" to "!", "២" to "ៗ", "៣" to "\"", "៤" to "៛", "៥" to "%", "៦" to "៍",
                    "៧" to "័", "៨" to "៏", "៩" to "(", "០" to ")")), 40f, flat = true),
                Row(khmer(listOf("ឆ" to "ឈ", "ឹ" to "ឺ", "េ" to "ែ", "រ" to "ឬ", "ត" to "ទ", "យ" to "ួ",
                    "ុ" to "ូ", "ិ" to "ី", "ោ" to "ៅ", "ផ" to "ភ", "ៀ" to "ឿ", "ឪ" to "ឧ")), 52f),
                Row(khmer(listOf("ា" to "ាំ", "ស" to "ៃ", "ដ" to "ឌ", "ថ" to "ធ", "ង" to "អ", "ហ" to "ះ",
                    "្" to "ញ", "ក" to "គ", "ល" to "ឡ", "ើ" to "ោះ", "់" to "៉")), 52f),
                Row(listOf(Key(Kind.SHIFT, "", 1.3f)) + khmer(listOf("ឋ" to "ឍ", "ខ" to "ឃ", "ច" to "ជ",
                    "វ" to "េះ", "ប" to "ព", "ន" to "ណ", "ម" to "ំ", "ុំ" to "ុះ", "៊" to "៕")) +
                    Key(Kind.BACKSPACE, "", 1.3f), 52f),
                bottomRow("។", "?"),
            )
        }
        if (width > 0) place(width)
    }

    init { build() }

    private val gapX = dp(5f)
    private val gapY = dp(9f)
    private val padTop = dp(6f)
    private val padBottom = dp(10f)
    private val radius = dp(9f)
    private val shadowDy = dp(1.5f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = padTop + padBottom + rows.sumOf { dp(it.heightDp).toDouble() }.toFloat() + gapY * (rows.size - 1)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place(w)

    private fun place(w: Int) {
        var y = padTop
        val side = dp(4f)
        for (row in rows) {
            val height = dp(row.heightDp)
            row.top = y
            row.bottom = y + height
            val total = row.keys.sumOf { it.weight.toDouble() }.toFloat()
            val gap = if (row.keys.size > 11) gapX * 0.7f else gapX  // 12-key Khmer rows: slightly tighter
            val unit = (w - 2 * side - gap * (row.keys.size - 1)) / total
            var x = side
            for (k in row.keys) {
                k.rect.set(x, y, x + unit * k.weight, y + height)
                x += unit * k.weight + gap
            }
            y += height + gapY
        }
    }

    // ---- drawing --------------------------------------------------------------------------------
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.bg)
        for (row in rows) for (k in row.keys) drawKey(canvas, k, row.flat)
    }

    /** Text shown on a key: combining Khmer vowel signs get a dotted circle ◌ so they are readable. */
    private fun shown(s: String): String {
        if (s.isEmpty()) return s
        val type = Character.getType(s.codePointAt(0))
        val combining = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        return if (combining) "◌$s" else s
    }

    private fun drawKey(c: Canvas, k: Key, flat: Boolean) {
        val shifted = shift || capsLock
        val (fill, ink) = when (k.kind) {
            Kind.ENTER -> Pair(if (k.pressed) theme.accentPressed else theme.accent, 0xFFFFFFFF.toInt())
            Kind.SHIFT -> Pair(if (shifted) theme.accentSoft else if (k.pressed) theme.pressed else theme.fn,
                if (shifted) theme.accent else theme.ink)
            Kind.TEXT, Kind.SPACE -> Pair(if (k.pressed) theme.pressed else if (flat) theme.bg else theme.key, theme.ink)
            else -> Pair(if (k.pressed) theme.pressed else theme.fn, theme.ink)
        }
        if (!flat || k.pressed) {
            shade.color = theme.shadow
            c.drawRoundRect(k.rect.left, k.rect.top + shadowDy, k.rect.right, k.rect.bottom + shadowDy, radius, radius, shade)
            face.color = fill
            c.drawRoundRect(k.rect, radius, radius, face)
        }
        when (k.kind) {
            Kind.GLOBE -> { drawGlobe(c, k.rect, ink); return }
            Kind.SHIFT -> { drawShift(c, k.rect, ink, capsLock); return }
            Kind.BACKSPACE -> { drawBackspace(c, k.rect, ink); return }
            else -> {}
        }
        val label = when (k.kind) {
            Kind.SPACE -> mode.spaceLabel
            Kind.ENTER -> enterLabel
            else -> shown(if (shifted) k.shiftOut else k.out)
        }
        val khmerKey = mode == Mode.KHMER && k.kind == Kind.TEXT && !flat
        val sp = when {
            flat -> 17f
            k.kind == Kind.SPACE -> 13f
            khmerKey -> 21f
            k.kind == Kind.TEXT -> 23f
            else -> 20f
        }
        text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        text.color = if (k.kind == Kind.SPACE) theme.ink2 else ink
        text.typeface = if (k.kind == Kind.ENTER) medium else regular
        val y = k.rect.centerY() - (text.descent() + text.ascent()) / 2
        c.drawText(label, k.rect.centerX(), y, text)
    }

    /** A real globe icon (circle + meridians + latitudes) with the current keyboard name under it. */
    private fun drawGlobe(c: Canvas, r: RectF, ink: Int) {
        val rad = dp(8.5f)
        val cx = r.centerX()
        val cy = r.centerY() - dp(6f)
        icon.color = ink
        icon.strokeWidth = dp(1.6f)
        c.drawCircle(cx, cy, rad, icon)
        c.drawOval(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad, icon)
        c.drawLine(cx - rad, cy, cx + rad, cy, icon)
        c.drawLine(cx - rad * 0.86f, cy - rad * 0.5f, cx + rad * 0.86f, cy - rad * 0.5f, icon)
        c.drawLine(cx - rad * 0.86f, cy + rad * 0.5f, cx + rad * 0.86f, cy + rad * 0.5f, icon)
        text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f, resources.displayMetrics)
        text.typeface = medium
        text.color = theme.accent
        c.drawText(mode.short, cx, r.bottom - dp(6f), text)
    }

    /** ⇧ arrow outline (filled + underline when CAPS LOCK). */
    private fun drawShift(c: Canvas, r: RectF, ink: Int, caps: Boolean) {
        val s = dp(9f)
        val cx = r.centerX()
        val cy = r.centerY() - dp(1f)
        icon.color = ink
        icon.strokeWidth = dp(1.7f)
        icon.strokeJoin = Paint.Join.ROUND
        val p = android.graphics.Path().apply {
            moveTo(cx, cy - s)
            lineTo(cx + s, cy)
            lineTo(cx + s * 0.45f, cy)
            lineTo(cx + s * 0.45f, cy + s * 0.75f)
            lineTo(cx - s * 0.45f, cy + s * 0.75f)
            lineTo(cx - s * 0.45f, cy)
            lineTo(cx - s, cy)
            close()
        }
        if (caps) { icon.style = Paint.Style.FILL_AND_STROKE; c.drawPath(p, icon); icon.style = Paint.Style.STROKE
            c.drawLine(cx - s * 0.5f, cy + s * 1.15f, cx + s * 0.5f, cy + s * 1.15f, icon)
        } else c.drawPath(p, icon)
    }

    /** ⌫ outline: a tag shape with an ×. */
    private fun drawBackspace(c: Canvas, r: RectF, ink: Int) {
        val w = dp(11f)
        val h = dp(8f)
        val cx = r.centerX() + dp(2f)
        val cy = r.centerY()
        icon.color = ink
        icon.strokeWidth = dp(1.7f)
        icon.strokeJoin = Paint.Join.ROUND
        val p = android.graphics.Path().apply {
            moveTo(cx - w - h * 0.8f, cy)
            lineTo(cx - w, cy - h)
            lineTo(cx + w, cy - h)
            lineTo(cx + w, cy + h)
            lineTo(cx - w, cy + h)
            close()
        }
        c.drawPath(p, icon)
        val x = h * 0.45f
        c.drawLine(cx - x, cy - x, cx + x, cy + x, icon)
        c.drawLine(cx - x, cy + x, cx + x, cy - x, icon)
    }

    // ---- touch ----------------------------------------------------------------------------------
    private val active = HashMap<Int, Key>()      // finger id → key it is holding
    private val handler = Handler(Looper.getMainLooper())
    private var repeatKey: Key? = null
    private val repeat = object : Runnable {
        override fun run() { repeatKey?.let { onKey(it, false) }; handler.postDelayed(this, 55) }
    }
    private var globeLongFired = false
    private var globeKey: Key? = null
    private val globeLong = Runnable { globeKey?.let { globeLongFired = true; onKey(it, true) } }

    /** Nearest key: pick the row by y, then the key closest in x (no dead gaps between keys). */
    private fun keyAt(x: Float, y: Float): Key? {
        val row = rows.minByOrNull { r -> if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f } ?: return null
        return row.keys.minByOrNull { k -> if (x < k.rect.left) k.rect.left - x else if (x > k.rect.right) x - k.rect.right else 0f }
    }

    private fun redraw(k: Key) =
        invalidate(k.rect.left.toInt() - 1, k.rect.top.toInt() - 1, k.rect.right.toInt() + 1, (k.rect.bottom + shadowDy).toInt() + 1)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val k = keyAt(e.getX(i), e.getY(i)) ?: return true
                active[e.getPointerId(i)] = k
                k.pressed = true
                redraw(k)
                when (k.kind) {
                    Kind.GLOBE -> { globeKey = k; globeLongFired = false; handler.postDelayed(globeLong, 450) }
                    Kind.BACKSPACE -> { onKey(k, false); repeatKey = k; handler.postDelayed(repeat, 380) }
                    else -> onKey(k, false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(active.remove(e.getPointerId(e.actionIndex)))
            MotionEvent.ACTION_CANCEL -> { active.values.toList().forEach { release(it, cancelled = true) }; active.clear() }
        }
        return true
    }

    private fun release(k: Key?, cancelled: Boolean = false) {
        k ?: return
        k.pressed = false
        when (k.kind) {
            Kind.BACKSPACE -> { handler.removeCallbacks(repeat); repeatKey = null }
            Kind.GLOBE -> { handler.removeCallbacks(globeLong); if (!globeLongFired && !cancelled) onKey(k, false) }
            else -> {}
        }
        redraw(k)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(repeat)
        handler.removeCallbacks(globeLong)
        super.onDetachedFromWindow()
    }
}

/** Colours for light / dark mode. */
class KeyTheme(dark: Boolean) {
    val bg = if (dark) 0xFF121316.toInt() else 0xFFEEF0F3.toInt()
    val key = if (dark) 0xFF2C2D31.toInt() else 0xFFFFFFFF.toInt()
    val fn = if (dark) 0xFF1F2024.toInt() else 0xFFD9DDE3.toInt()
    val shadow = if (dark) 0xFF060607.toInt() else 0xFFC2C7CF.toInt()
    val pressed = if (dark) 0xFF47494F.toInt() else 0xFFC6CCD5.toInt()
    val ink = if (dark) 0xFFF1F3F4.toInt() else 0xFF1F2328.toInt()
    val ink2 = if (dark) 0xFF9AA0A6.toInt() else 0xFF6B7280.toInt()
    val accent = 0xFF2A78D6.toInt()
    val accentPressed = 0xFF1F5FB8.toInt()
    val accentSoft = if (dark) 0xFF1C3554.toInt() else 0xFFDCE9FB.toInt()
}
