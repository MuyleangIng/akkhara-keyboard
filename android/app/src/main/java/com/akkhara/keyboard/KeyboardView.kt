package com.akkhara.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The whole keyboard:
 *
 *   [ “anh tunh” | អញធុញ | អញទុញ | … ]     candidate chips (the #1 highlighted)
 *   ───────────────────────────────────
 *   KeysView: all keys drawn as one canvas (smooth), three keyboards:
 *     Sing Khmer  Latin letters → Khmer candidates (the model)
 *     ខ្មែរ        standard Khmer (NiDA) layout, Khmer letters typed directly
 *     English     plain English
 *   Globe key (bottom left): tap = next keyboard, long-press = other phone keyboard.
 *   ⇧ once = one capital / second layer, twice quickly = CAPS.
 * It only draws and reports presses; the typing logic is in AkkharaIME.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onText(s: String)        // a key's text (letter, digit, Khmer letter, punctuation)
        fun onSpace()
        fun onBackspace()
        fun onEnter()
        fun onGlobe()                // tap: next Akkhara keyboard (Sing → ខ្មែរ → English)
        fun onGlobeLong()            // long-press: other phone keyboard (e.g. Samsung)
        fun onCandidate(index: Int)
        fun onKeepLatin()
    }

    private val theme = KeyTheme((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES)
    private val candidateRow = LinearLayout(context)
    private val haptics = Haptics(context)
    private var lastShiftTap = 0L
    private val keys: KeysView = KeysView(context, theme) { k, long -> onKey(k, long) }

    var shift: Boolean
        get() = keys.shift
        set(v) { keys.shift = v }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.bg)
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        candidateRow.orientation = HORIZONTAL
        candidateRow.gravity = Gravity.CENTER_VERTICAL
        candidateRow.setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        scroll.addView(candidateRow)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f).toInt()))
        addView(View(context).apply { setBackgroundColor(theme.shadow) }, LayoutParams(LayoutParams.MATCH_PARENT, 1))
        addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setStatus("ខ្មែរ typing · tap the Khmer word up here")
    }

    // ---- keys → listener --------------------------------------------------------------------------
    private fun onKey(k: KeysView.Key, long: Boolean) {
        haptics.tick()
        when (k.kind) {
            KeysView.Kind.TEXT -> {
                listener.onText(if (keys.shift || keys.capsLock) k.shiftOut else k.out)
                if (keys.shift && !keys.capsLock) keys.shift = false
            }
            KeysView.Kind.SHIFT -> onShift()
            KeysView.Kind.BACKSPACE -> listener.onBackspace()
            KeysView.Kind.GLOBE -> if (long) listener.onGlobeLong() else listener.onGlobe()
            KeysView.Kind.SPACE -> listener.onSpace()
            KeysView.Kind.ENTER -> listener.onEnter()
        }
    }

    /** ⇧ once = one capital; twice quickly = CAPS LOCK (⇪); once more = off. */
    private fun onShift() {
        val now = System.currentTimeMillis()
        when {
            keys.shift && !keys.capsLock && now - lastShiftTap < 350 -> keys.capsLock = true
            keys.capsLock -> { keys.capsLock = false; keys.shift = false }
            else -> keys.shift = !keys.shift
        }
        lastShiftTap = now
    }

    fun setMode(mode: Mode) { keys.mode = mode }

    /** ⏎ shows what the app's enter key does: ➤ send, 🔍 search, → go / next, ✓ done. */
    fun setEnterLabel(label: String) { keys.enterLabel = label }

    // ---- candidate bar ------------------------------------------------------------------------
    fun setCandidates(items: List<String>, latin: String = "") {
        candidateRow.removeAllViews()
        if (latin.isNotBlank()) chip("“${latin.trim()}”", 14f, theme.ink2, null) { listener.onKeepLatin() }
        items.forEachIndexed { i, text ->
            val best = i == 0
            chip(text, if (best) 19f else 17f, if (best) theme.accent else theme.ink, if (best) theme.accentSoft else null, best) {
                listener.onCandidate(i)
            }
        }
    }

    private fun chip(text: String, sp: Float, color: Int, fill: Int?, bold: Boolean = false, onClick: () -> Unit) {
        val tv = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
            if (fill != null) background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(18f) }
            setOnClickListener { haptics.tick(); onClick() }
        }
        candidateRow.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f).toInt()).apply {
            setMargins(dp(3f).toInt(), 0, dp(3f).toInt(), 0)
        })
    }

    fun setStatus(text: String) {
        candidateRow.removeAllViews()
        candidateRow.addView(TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(theme.ink2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), 0, 0, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
}

/**
 * Key vibration, set in the Akkhara app: 0 = off, 1 = light (default), 2 = strong.
 * "Light" uses the phone's short TICK effect, much softer than the standard keyboard buzz.
 */
class Haptics(private val context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)

    private val level get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LEVEL, 1)

    fun tick() {
        val v = vibrator ?: return
        when (level) {
            0 -> return
            1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                 else v.vibrate(VibrationEffect.createOneShot(8, 40))
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    else v.vibrate(VibrationEffect.createOneShot(15, 120))
        }
    }

    companion object {
        const val PREFS = "akkhara"
        const val KEY_LEVEL = "vibration"
    }
}
