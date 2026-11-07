package com.akkhara.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.abs

/** The three keyboards inside Akkhara (the globe key cycles through them). */
enum class Mode(val short: String, val spaceLabel: String) {
    SING("Sing", "Sing Khmer"),     // type Latin letters, get Khmer candidates (the model)
    KHMER("ខ្មែរ", "ខ្មែរ"),           // real Khmer letters, standard NiDA layout, typed directly
    ENGLISH("ABC", "English");      // plain English

    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * ALL keys drawn as ONE view on a Canvas (like Samsung / Gboard): key rects are computed once per
 * layout and Paint / Path / RectF objects are reused, so onDraw and onTouchEvent allocate nothing.
 *
 * Layout follows the familiar Samsung pattern: number row (real keys, optional), QWERTY with the
 * middle row set in by half a key, and the bottom row  !#1 · 🌐 · , · space · . · ⏎.
 * Symbols: two pages (1/2, 2/2); the bottom-left key goes back to letters ("abc" / "កខគ").
 *
 * Touch: a key types on finger DOWN (instant, like before); preview balloon while pressed;
 * hold a key for its extras (slide + lift to pick; the first letter is replaced);
 * ⌫ hold: letters, then whole words after ~1 s; space hold + slide: cursor (trackpad);
 * globe hold: other phone keyboard. TalkBack: every key is a virtual view (lift to type).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeysView(context: Context, private val theme: KeyTheme, private val listener: Listener) : View(context) {

    interface Listener {
        fun onPress(k: Key)                         // finger down: haptics + sound
        fun onKey(k: Key, long: Boolean)            // the key fires (long = ⌫ word mode / globe hold)
        fun onAlt(text: String)                     // an extra was picked: replace the letter typed on DOWN
        fun onCursor(steps: Int)                    // space trackpad
        fun onTick()                                // light haptic tick
        fun onLongPress()                           // stronger haptic for long-press actions
    }

    enum class Kind { TEXT, SHIFT, BACKSPACE, GLOBE, SPACE, ENTER, SYMBOLS, PAGE, SPACER }

    /** One key. [out]/[shiftOut] = text it types (normal / with ⇧); [alts] = long-press extras. */
    class Key(val kind: Kind, val out: String, val weight: Float = 1f, val shiftOut: String = out,
              val alts: List<String> = emptyList()) {
        val rect = RectF()
        var pressed = false
        var bottomRow = false
        var numberRow = false
    }

    private class Row(val keys: List<Key>, val heightDp: Float) {
        var top = 0f
        var bottom = 0f
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ---- state ----------------------------------------------------------------------------------
    var mode = Mode.SING
        set(v) { field = v; shift = false; capsLock = false; symbols = 0; rebuild() }
    /** 0 = letters, 1 = symbols page 1, 2 = symbols page 2 */
    var symbols = 0
        set(v) { field = v; rebuild() }
    var shift = false
        set(v) { field = v; invalidate() }
    var capsLock = false
        set(v) { field = v; invalidate() }
    var enterLabel = "⏎"
        set(v) { field = v; invalidate() }
    var previewOn = true
    var hintsOn = true
        set(v) { field = v; invalidate() }
    var numberRow = true
        set(v) { if (field != v) { field = v; rebuild() } }
    var heightScale = 1f
        set(v) { if (field != v) { field = v; rebuild() } }

    private fun rebuild() { build(); requestLayout(); invalidate() }

    // ---- layouts --------------------------------------------------------------------------------
    private var rows: List<Row> = emptyList()
    private var all: List<Key> = emptyList()   // every real key, in reading order (TalkBack ids)

    private val khmerDigits = "១២៣៤៥៦៧៨៩០"
    private val arabicDigits = "1234567890"

    /** Small extras (shown in the key corner, long-press to pick), like Samsung's hints. */
    private val latinAlts = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5", 'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&", 'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
        'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";", 'n' to "!", 'm' to "?",
    )

    private fun latin(s: String) = s.map { c ->
        Key(Kind.TEXT, c.toString(), 1f, c.uppercaseChar().toString(), latinAlts[c]?.map { it.toString() } ?: emptyList())
    }

    /** Khmer (NiDA): (normal, shift[, extras]); the shift letter is always the first extra. */
    private fun khmer(pairs: List<Pair<String, String>>, extra: Map<String, List<String>> = emptyMap()) =
        pairs.map { (n, s) -> Key(Kind.TEXT, n, 1f, s, listOf(s) + (extra[n] ?: emptyList())) }

    /** Independent vowels and variants on long-press (Khmer). */
    private val khmerExtras = mapOf(
        "ង" to listOf("ឥ", "ឦ", "ឧ", "ឪ", "ឯ", "ឰ", "ឱ", "ឳ"),
        "រ" to listOf("ឫ", "ឬ"),
        "ល" to listOf("ឭ", "ឮ"),
        "ក" to listOf("ក្ក"),
        "ា" to listOf("ះ", "ៈ"),
        "្" to listOf("៑"),
    )

    private fun spacer() = Key(Kind.SPACER, "", 0.5f)

    private fun numberKeys(): Row {
        val khmerMode = mode == Mode.KHMER
        val keys = (0 until 10).map { i ->
            val main = if (khmerMode) khmerDigits[i].toString() else arabicDigits[i].toString()
            val other = if (khmerMode) arabicDigits[i].toString() else khmerDigits[i].toString()
            Key(Kind.TEXT, main, 1f, main, listOf(other)).also { it.numberRow = true }
        }
        return Row(keys, 40f)
    }

    private fun bottomRow(): Row {
        val khmerMode = mode == Mode.KHMER
        val period = if (khmerMode) "។" else "."
        val periodAlts = if (khmerMode) listOf(".", "?", "!", "៕", "៖", "\"", "-") else listOf("?", "!", "'", "\"", "-", "។")
        val commaAlts = if (khmerMode) listOf("ៗ", ";", ":") else listOf(";", ":", "ៗ")
        return Row(listOf(
            Key(Kind.SYMBOLS, "", 1.3f),
            Key(Kind.GLOBE, "", 1.1f),
            Key(Kind.TEXT, ",", 1f, ",", commaAlts),
            Key(Kind.SPACE, " ", 4.3f),
            Key(Kind.TEXT, period, 1f, period, periodAlts),
            Key(Kind.ENTER, "\n", 1.5f),
        ), 48f).also { r -> r.keys.forEach { it.bottomRow = true } }
    }

    private fun sym(s: String) = s.split(" ").filter { it.isNotEmpty() }.map { Key(Kind.TEXT, it) }

    private fun build() {
        val letters: List<Row> = when {
            symbols == 1 -> listOf(
                Row(sym("+ × ÷ = / _ < > [ ]"), 48f),
                Row(sym("! @ # ៛ % ^ & * ( )"), 48f),
                Row(listOf(Key(Kind.PAGE, "", 1.5f)) + sym("- ' \" : ; , ?") + Key(Kind.BACKSPACE, "", 1.5f), 48f),
            )
            symbols == 2 -> listOf(
                Row(sym("` ~ \\ | { } € £ ¥ $"), 48f),
                Row(sym("° • ○ ● □ ■ ☆ « » ¡"), 48f),
                Row(listOf(Key(Kind.PAGE, "", 1.5f)) + sym("¿ ♥ ★ ✓ ※ ℃ …") + Key(Kind.BACKSPACE, "", 1.5f), 48f),
            )
            mode == Mode.KHMER -> listOf(
                Row(khmer(listOf("ឆ" to "ឈ", "ឹ" to "ឺ", "េ" to "ែ", "រ" to "ឬ", "ត" to "ទ", "យ" to "ួ",
                    "ុ" to "ូ", "ិ" to "ី", "ោ" to "ៅ", "ផ" to "ភ", "ៀ" to "ឿ", "ឪ" to "ឧ"), khmerExtras), 48f),
                Row(khmer(listOf("ា" to "ាំ", "ស" to "ៃ", "ដ" to "ឌ", "ថ" to "ធ", "ង" to "អ", "ហ" to "ះ",
                    "្" to "ញ", "ក" to "គ", "ល" to "ឡ", "ើ" to "ោះ", "់" to "៉"), khmerExtras), 48f),
                Row(listOf(Key(Kind.SHIFT, "", 1.3f)) + khmer(listOf("ឋ" to "ឍ", "ខ" to "ឃ", "ច" to "ជ",
                    "វ" to "េះ", "ប" to "ព", "ន" to "ណ", "ម" to "ំ", "ុំ" to "ុះ", "៊" to "៕"), khmerExtras) +
                    Key(Kind.BACKSPACE, "", 1.3f), 48f),
            )
            else -> listOf(
                Row(latin("qwertyuiop"), 48f),
                Row(listOf(spacer()) + latin("asdfghjkl") + spacer(), 48f),   // set in by half a key
                Row(listOf(Key(Kind.SHIFT, "", 1.5f)) + latin("zxcvbnm") + Key(Kind.BACKSPACE, "", 1.5f), 48f),
            )
        }
        rows = (if (numberRow) listOf(numberKeys()) else emptyList()) + letters + bottomRow()
        all = rows.flatMap { it.keys }.filter { it.kind != Kind.SPACER }
        if (width > 0) place(width)
    }

    init {
        build()
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val gapX get() = dp(5f)
    private val gapY get() = dp(7f) * scaleY()
    private val padTop = dp(6f)
    private val padBottom = dp(8f)
    private val radius = dp(8f)
    private val shadowDy = dp(1f)
    private fun scaleY() = heightScale * (if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 0.72f else 1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = padTop + padBottom + rows.sumOf { dp(it.heightDp * scaleY()).toDouble() }.toFloat() + gapY * (rows.size - 1)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place(w)

    private fun place(w: Int) {
        var y = padTop
        val maxW = dp(720f)                                     // tablets / foldables: centred
        val side = if (w > maxW) (w - maxW) / 2f else dp(3f)
        for (row in rows) {
            val height = dp(row.heightDp * scaleY())
            row.top = y
            row.bottom = y + height
            val total = row.keys.sumOf { it.weight.toDouble() }.toFloat()
            val gap = if (row.keys.size > 11) gapX * 0.6f else gapX   // 12-key Khmer rows: tighter
            val unit = (w - 2 * side - gap * (row.keys.size - 1)) / total
            var x = side
            for (k in row.keys) {
                k.rect.set(x, y, x + unit * k.weight, y + height)
                x += unit * k.weight + gap
            }
            y += height + gapY
        }
    }

    // ---- drawing (no allocations) ---------------------------------------------------------------
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val path = Path()
    private val tmp = RectF()
    private val sp = resources.displayMetrics.scaledDensity

    override fun onDraw(canvas: Canvas) {
        // NOT drawColor(): the preview balloon may draw outside this view (over the suggestion
        // bar), so drawColor would paint over the bar too. Fill only our own area.
        face.color = theme.bg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)
        for (row in rows) for (k in row.keys) if (k.kind != Kind.SPACER) drawKey(canvas, k)
        drawPreviews(canvas)
        drawAlts(canvas)
    }

    /** Text shown on a key: combining Khmer vowel signs get a dotted circle ◌ so they are readable. */
    private fun shown(s: String): String {
        if (s.isEmpty()) return s
        val type = Character.getType(s.codePointAt(0))
        val combining = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        return if (combining) "◌$s" else s
    }

    private fun drawKey(c: Canvas, k: Key) {
        val shifted = shift || capsLock
        val fill: Int
        val ink: Int
        when (k.kind) {
            Kind.ENTER -> { fill = if (k.pressed) theme.accentPressed else theme.accent; ink = 0xFFFFFFFF.toInt() }
            Kind.SHIFT -> { fill = if (shifted) theme.accentSoft else if (k.pressed) theme.pressed else theme.fn
                            ink = if (shifted) theme.accent else theme.ink }
            Kind.TEXT, Kind.SPACE -> { fill = if (k.pressed) theme.pressed else theme.key; ink = theme.ink }
            else -> { fill = if (k.pressed) theme.pressed else theme.fn; ink = theme.ink }
        }
        shade.color = theme.shadow
        c.drawRoundRect(k.rect.left, k.rect.top + shadowDy, k.rect.right, k.rect.bottom + shadowDy, radius, radius, shade)
        face.color = fill
        c.drawRoundRect(k.rect, radius, radius, face)
        when (k.kind) {
            Kind.GLOBE -> { drawGlobe(c, k.rect, ink); return }
            Kind.SHIFT -> { drawShift(c, k.rect, ink, capsLock); return }
            Kind.BACKSPACE -> { drawBackspace(c, k.rect, ink); return }
            else -> {}
        }
        val label = when (k.kind) {
            Kind.SPACE -> mode.spaceLabel
            Kind.ENTER -> enterLabel
            Kind.SYMBOLS -> if (symbols == 0) "!#1" else if (mode == Mode.KHMER) "កខគ" else "abc"
            Kind.PAGE -> if (symbols == 1) "1/2" else "2/2"
            else -> shown(if (shifted && symbols == 0) k.shiftOut else k.out)
        }
        val khmerKey = mode == Mode.KHMER && k.kind == Kind.TEXT && symbols == 0 && !k.numberRow && !k.bottomRow
        val size = when {
            k.kind == Kind.SPACE -> 14f
            k.kind == Kind.SYMBOLS || k.kind == Kind.PAGE -> 15f
            k.numberRow -> 17f
            khmerKey -> 21f
            k.kind == Kind.TEXT -> 22f
            else -> 20f
        }
        text.textSize = size * sp
        text.color = if (k.kind == Kind.SPACE) theme.ink2 else ink
        text.typeface = if (k.kind == Kind.ENTER || k.kind == Kind.SYMBOLS || k.kind == Kind.PAGE) medium else regular
        c.drawText(label, k.rect.centerX(), k.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        // small extra character in the corner (letters only), like Samsung's hints
        if (hintsOn && k.kind == Kind.TEXT && symbols == 0 && !k.numberRow && !k.bottomRow && k.alts.isNotEmpty()) {
            hint.textSize = 10f * sp
            hint.color = theme.ink2
            c.drawText(shown(k.alts[0]), k.rect.right - dp(5f), k.rect.top + dp(13f), hint)
        }
    }

    /** Balloon above each pressed character key (not for the bottom row). */
    private fun drawPreviews(c: Canvas) {
        if (!previewOn || altKey != null) return
        for (i in 0 until pointerCount) {
            val k = pointerKeys[i] ?: continue
            if (k.kind != Kind.TEXT || k.bottomRow || !k.pressed) continue
            val w = maxOf(k.rect.width() * 1.45f, dp(48f))
            val h = dp(56f)
            var left = k.rect.centerX() - w / 2
            left = left.coerceIn(dp(2f), width - w - dp(2f))
            tmp.set(left, k.rect.top - h - dp(4f), left + w, k.rect.top - dp(4f))
            shade.color = theme.shadow
            c.drawRoundRect(tmp.left, tmp.top + dp(1.5f), tmp.right, tmp.bottom + dp(1.5f), dp(12f), dp(12f), shade)
            face.color = theme.preview
            c.drawRoundRect(tmp, dp(12f), dp(12f), face)
            val label = shown(if ((shift || capsLock) && symbols == 0) k.shiftOut else k.out)
            text.textSize = (if (mode == Mode.KHMER) 28f else 32f) * sp
            text.color = theme.ink
            text.typeface = regular
            c.drawText(label, tmp.centerX(), tmp.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    /** Long-press extras: a row of options above the key; the finger's x picks one. */
    private fun drawAlts(c: Canvas) {
        val k = altKey ?: return
        val n = k.alts.size + 1
        val cell = altCell
        val h = dp(52f)
        tmp.set(altLeft, k.rect.top - h - dp(6f), altLeft + cell * n, k.rect.top - dp(6f))
        shade.color = theme.shadow
        c.drawRoundRect(tmp.left, tmp.top + dp(1.5f), tmp.right, tmp.bottom + dp(1.5f), dp(12f), dp(12f), shade)
        face.color = theme.preview
        c.drawRoundRect(tmp, dp(12f), dp(12f), face)
        text.textSize = 22f * sp
        text.typeface = regular
        for (i in 0 until n) {
            val l = tmp.left + cell * i
            if (i == altIndex) {
                face.color = theme.accent
                c.drawRoundRect(l + dp(3f), tmp.top + dp(3f), l + cell - dp(3f), tmp.bottom - dp(3f), dp(9f), dp(9f), face)
            }
            text.color = if (i == altIndex) 0xFFFFFFFF.toInt() else theme.ink
            c.drawText(shown(altText(k, i)), l + cell / 2, tmp.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun altText(k: Key, i: Int) =
        if (i == 0) (if ((shift || capsLock) && symbols == 0) k.shiftOut else k.out) else k.alts[i - 1]

    /** Globe icon with the current keyboard name under it. */
    private fun drawGlobe(c: Canvas, r: RectF, ink: Int) {
        val rad = dp(8.5f)
        val cx = r.centerX()
        val cy = r.centerY() - dp(6f)
        icon.color = ink
        icon.strokeWidth = dp(1.6f)
        icon.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, rad, icon)
        c.drawOval(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad, icon)
        c.drawLine(cx - rad, cy, cx + rad, cy, icon)
        c.drawLine(cx - rad * 0.86f, cy - rad * 0.5f, cx + rad * 0.86f, cy - rad * 0.5f, icon)
        c.drawLine(cx - rad * 0.86f, cy + rad * 0.5f, cx + rad * 0.86f, cy + rad * 0.5f, icon)
        text.textSize = 10.5f * sp
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
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s, cy)
        path.lineTo(cx + s * 0.45f, cy)
        path.lineTo(cx + s * 0.45f, cy + s * 0.75f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.75f)
        path.lineTo(cx - s * 0.45f, cy)
        path.lineTo(cx - s, cy)
        path.close()
        if (caps) {
            icon.style = Paint.Style.FILL_AND_STROKE; c.drawPath(path, icon); icon.style = Paint.Style.STROKE
            c.drawLine(cx - s * 0.5f, cy + s * 1.15f, cx + s * 0.5f, cy + s * 1.15f, icon)
        } else {
            icon.style = Paint.Style.STROKE; c.drawPath(path, icon)
        }
    }

    /** ⌫ outline: a tag shape with an ×. */
    private fun drawBackspace(c: Canvas, r: RectF, ink: Int) {
        val w = dp(11f)
        val h = dp(8f)
        val cx = r.centerX() + dp(2f)
        val cy = r.centerY()
        icon.color = ink
        icon.strokeWidth = dp(1.7f)
        icon.style = Paint.Style.STROKE
        path.reset()
        path.moveTo(cx - w - h * 0.8f, cy)
        path.lineTo(cx - w, cy - h)
        path.lineTo(cx + w, cy - h)
        path.lineTo(cx + w, cy + h)
        path.lineTo(cx - w, cy + h)
        path.close()
        c.drawPath(path, icon)
        val x = h * 0.45f
        c.drawLine(cx - x, cy - x, cx + x, cy + x, icon)
        c.drawLine(cx - x, cy + x, cx + x, cy - x, icon)
    }

    // ---- touch ----------------------------------------------------------------------------------
    private val maxPointers = 10
    private val pointerIds = IntArray(maxPointers) { -1 }
    private val pointerKeys = arrayOfNulls<Key>(maxPointers)
    private var pointerCount = 0

    private val handler = Handler(Looper.getMainLooper())

    // ⌫ repeat: letters first, whole words after ~1 s
    private var repeatKey: Key? = null
    private var repeatStart = 0L
    private val repeat = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            val words = SystemClock.uptimeMillis() - repeatStart > 1000
            listener.onKey(k, words)
            handler.postDelayed(this, if (words) 160 else 55)
        }
    }

    // globe: tap = next Akkhara keyboard, hold = other phone keyboard
    private var globeLongFired = false
    private var globeKey: Key? = null
    private val globeLong = Runnable { globeKey?.let { globeLongFired = true; listener.onLongPress(); listener.onKey(it, true) } }

    // long-press extras
    private var altKey: Key? = null
    private var altPointer = -1
    private var altIndex = 0
    private var altLeft = 0f
    private var altCell = 0f
    private var longKey: Key? = null
    private var longPointer = -1
    private val longPress = Runnable { openAlts() }

    // space trackpad
    private var spacePointer = -1
    private var spaceStartX = 0f
    private var trackpad = false
    private var trackSteps = 0
    private val spaceLong = Runnable { trackpad = true; trackSteps = 0; listener.onLongPress() }

    /** Nearest real key: pick the row by y, then the key closest in x (no dead gaps). */
    private fun keyAt(x: Float, y: Float): Key? {
        var best: Row? = null
        var bestD = Float.MAX_VALUE
        for (r in rows) {
            val d = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            if (d < bestD) { bestD = d; best = r }
        }
        val row = best ?: return null
        var bk: Key? = null
        var bkD = Float.MAX_VALUE
        for (k in row.keys) {
            if (k.kind == Kind.SPACER) continue
            val d = if (x < k.rect.left) k.rect.left - x else if (x > k.rect.right) x - k.rect.right else 0f
            if (d < bkD) { bkD = d; bk = k }
        }
        return bk
    }

    private fun slot(id: Int): Int {
        for (i in 0 until pointerCount) if (pointerIds[i] == id) return i
        return -1
    }

    private fun removeSlot(i: Int) {
        for (j in i until pointerCount - 1) { pointerIds[j] = pointerIds[j + 1]; pointerKeys[j] = pointerKeys[j + 1] }
        pointerCount--
        pointerIds[pointerCount] = -1; pointerKeys[pointerCount] = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val k = keyAt(e.getX(i), e.getY(i)) ?: return true
                if (pointerCount < maxPointers) {
                    pointerIds[pointerCount] = e.getPointerId(i); pointerKeys[pointerCount] = k; pointerCount++
                }
                k.pressed = true
                listener.onPress(k)
                when (k.kind) {
                    Kind.TEXT -> {
                        listener.onKey(k, false)                        // types at once (no waiting)
                        if (k.alts.isNotEmpty()) { longKey = k; longPointer = e.getPointerId(i); handler.postDelayed(longPress, 400) }
                    }
                    Kind.GLOBE -> { globeKey = k; globeLongFired = false; handler.postDelayed(globeLong, 450) }
                    Kind.BACKSPACE -> { listener.onKey(k, false); repeatKey = k; repeatStart = SystemClock.uptimeMillis(); handler.postDelayed(repeat, 380) }
                    Kind.SPACE -> { spacePointer = e.getPointerId(i); spaceStartX = e.getX(i); trackpad = false; handler.postDelayed(spaceLong, 400) }
                    Kind.SHIFT, Kind.SYMBOLS, Kind.PAGE -> listener.onKey(k, false)
                    Kind.ENTER, Kind.SPACER -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val id = e.getPointerId(p)
                    val x = e.getX(p)
                    if (id == altPointer && altKey != null) moveAlts(x)
                    else if (id == spacePointer) {
                        if (trackpad) moveTrackpad(x)
                        else if (abs(x - spaceStartX) > dp(24f)) { handler.removeCallbacks(spaceLong); spaceLong.run() }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val s = slot(id)
                if (s >= 0) {
                    val k = pointerKeys[s]
                    removeSlot(s)
                    if (k != null) release(k, id, cancelled = false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (j in 0 until pointerCount) pointerKeys[j]?.let { release(it, pointerIds[j], cancelled = true) }
                while (pointerCount > 0) removeSlot(0)
            }
        }
        return true
    }

    private fun release(k: Key, id: Int, cancelled: Boolean) {
        k.pressed = false
        when (k.kind) {
            Kind.BACKSPACE -> { handler.removeCallbacks(repeat); repeatKey = null }
            Kind.GLOBE -> { handler.removeCallbacks(globeLong); if (!globeLongFired && !cancelled) listener.onKey(k, false) }
            Kind.SPACE -> {
                handler.removeCallbacks(spaceLong)
                if (!trackpad && !cancelled) listener.onKey(k, false)
                trackpad = false; spacePointer = -1
            }
            Kind.ENTER -> if (!cancelled) listener.onKey(k, false)
            Kind.TEXT -> {
                if (id == longPointer) { handler.removeCallbacks(longPress); longKey = null; longPointer = -1 }
                if (altKey != null && id == altPointer) {
                    if (!cancelled && altIndex > 0) listener.onAlt(altText(k, altIndex))   // replaces the letter
                    altKey = null; altPointer = -1
                }
            }
            else -> {}
        }
        invalidate()
    }

    private fun openAlts() {
        val k = longKey ?: return
        if (!k.pressed) return
        altKey = k
        altPointer = longPointer
        altIndex = 1.coerceAtMost(k.alts.size)        // the first extra is pre-selected (like Samsung)
        altCell = maxOf(k.rect.width(), dp(40f))
        val total = altCell * (k.alts.size + 1)
        altLeft = (k.rect.centerX() - altCell * 1.5f).coerceIn(dp(2f), maxOf(dp(2f), width - total - dp(2f)))
        listener.onLongPress()
        invalidate()
    }

    private fun moveAlts(x: Float) {
        val k = altKey ?: return
        val i = ((x - altLeft) / altCell).toInt().coerceIn(0, k.alts.size)
        if (i != altIndex) { altIndex = i; listener.onTick(); invalidate() }
    }

    private fun moveTrackpad(x: Float) {
        val steps = ((x - spaceStartX) / dp(9f)).toInt()
        if (steps != trackSteps) {
            listener.onCursor(steps - trackSteps)
            listener.onTick()
            trackSteps = steps
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(repeat)
        handler.removeCallbacks(globeLong)
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(spaceLong)
        super.onDetachedFromWindow()
    }

    // ---- TalkBack: every key is a virtual view (explore by touch, lift to type) -----------------
    private val a11y = context.getSystemService(AccessibilityManager::class.java)
    private var hovered = -1
    private var a11yFocused = -1
    private val boundsTmp = Rect()
    private val location = IntArray(2)

    private fun describe(k: Key): String = when (k.kind) {
        Kind.SHIFT -> if (capsLock) "Caps lock on" else if (shift) "Shift on" else "Shift"
        Kind.BACKSPACE -> "Delete"
        Kind.GLOBE -> "Next keyboard, now ${mode.name.lowercase()}; hold for other keyboards"
        Kind.SPACE -> "Space"
        Kind.SYMBOLS -> if (symbols == 0) "Symbols" else "Letters"
        Kind.PAGE -> "More symbols"
        Kind.ENTER -> when (enterLabel) { "➤" -> "Send"; "🔍" -> "Search"; "→" -> "Go"; "✓" -> "Done"; else -> "Enter" }
        else -> {
            val t = if ((shift || capsLock) && symbols == 0) k.shiftOut else k.out
            when (t) { "," -> "comma"; "." -> "period"; "'" -> "apostrophe"; "?" -> "question mark"; else -> t }
        }
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (a11y?.isTouchExplorationEnabled != true) return super.onHoverEvent(e)
        val k = keyAt(e.x, e.y)
        val id = if (k == null) -1 else all.indexOf(k)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> if (id != hovered) {
                if (hovered >= 0) sendA11y(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                hovered = id
                if (id >= 0) sendA11y(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hovered >= 0 && e.y >= 0 && e.y <= height) all.getOrNull(hovered)?.let { listener.onPress(it); listener.onKey(it, false) }
                if (hovered >= 0) sendA11y(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                hovered = -1
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun sendA11y(id: Int, type: Int) {
        val k = all.getOrNull(id) ?: return
        val ev = AccessibilityEvent.obtain(type)
        ev.packageName = context.packageName
        ev.className = "android.widget.Button"
        ev.contentDescription = describe(k)
        ev.setSource(this, id)
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider

    @Suppress("DEPRECATION")
    private val provider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == View.NO_ID) {
                val info = AccessibilityNodeInfo.obtain(this@KeysView)
                onInitializeAccessibilityNodeInfo(info)
                info.contentDescription = "Keyboard"
                for (i in all.indices) info.addChild(this@KeysView, i)
                return info
            }
            val k = all.getOrNull(virtualViewId) ?: return null
            val info = AccessibilityNodeInfo.obtain(this@KeysView, virtualViewId)
            info.packageName = context.packageName
            info.className = "android.widget.Button"
            info.contentDescription = describe(k)
            info.setParent(this@KeysView)
            k.rect.roundOut(boundsTmp)
            info.setBoundsInParent(boundsTmp)
            getLocationOnScreen(location)
            boundsTmp.offset(location[0], location[1])
            info.setBoundsInScreen(boundsTmp)
            info.isEnabled = true
            info.isVisibleToUser = true
            info.isClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            if (a11yFocused == virtualViewId) {
                info.isAccessibilityFocused = true
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            } else info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == View.NO_ID) return performAccessibilityAction(action, arguments)
            val k = all.getOrNull(virtualViewId) ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> { listener.onPress(k); listener.onKey(k, false); true }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    a11yFocused = virtualViewId; sendA11y(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED); true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocused == virtualViewId) a11yFocused = -1
                    sendA11y(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED); true
                }
                else -> false
            }
        }
    }
}

/** Colours for light / dark mode: lighter letter keys, darker function keys, only ⏎ in the accent. */
class KeyTheme(dark: Boolean) {
    val bg = if (dark) 0xFF0F1012.toInt() else 0xFFE3E5E9.toInt()
    val key = if (dark) 0xFF34363B.toInt() else 0xFFFFFFFF.toInt()
    val fn = if (dark) 0xFF24262A.toInt() else 0xFFC4C8D0.toInt()
    val shadow = if (dark) 0xFF000000.toInt() else 0xFFA4A8B0.toInt()
    val pressed = if (dark) 0xFF55585F.toInt() else 0xFFB5BAC4.toInt()
    val preview = if (dark) 0xFF4A4C51.toInt() else 0xFFFFFFFF.toInt()
    val ink = if (dark) 0xFFF4F5F7.toInt() else 0xFF111317.toInt()
    val ink2 = if (dark) 0xFF9CA1A9.toInt() else 0xFF5B6070.toInt()
    val accent = 0xFF2F6BFF.toInt()
    val accentPressed = 0xFF2355CC.toInt()
    val accentSoft = if (dark) 0xFF1E2E55.toInt() else 0xFFDCE6FF.toInt()
    val bar = if (dark) 0xFF0F1012.toInt() else 0xFFE3E5E9.toInt()
    val card = if (dark) 0xFF24262A.toInt() else 0xFFFFFFFF.toInt()
}
