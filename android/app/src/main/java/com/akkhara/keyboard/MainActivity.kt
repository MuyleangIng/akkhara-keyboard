package com.akkhara.keyboard

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Setup screen, as simple cards:
 *   header (logo + name)
 *   ① Turn on Akkhara       ✓ when done
 *   ② Choose Akkhara         ✓ when it is the current keyboard
 *   ③ Try it                 a text box
 *   ④ Speed on this phone    runs the model and shows ms
 */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var step1: TextView
    private lateinit var step2: TextView
    private lateinit var speed: TextView

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val bg get() = if (dark) 0xFF121316.toInt() else 0xFFF3F5F8.toInt()
    private val card get() = if (dark) 0xFF1E1F23.toInt() else 0xFFFFFFFF.toInt()
    private val ink get() = if (dark) 0xFFF1F3F4.toInt() else 0xFF1F2328.toInt()
    private val ink2 get() = if (dark) 0xFF9AA0A6.toInt() else 0xFF6B7280.toInt()
    private val accent = 0xFF2A78D6.toInt()
    private val ok = 0xFF1BAF7A.toInt()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(28f), dp(18f), dp(28f))
            setBackgroundColor(bg)
        }

        // ---- header ----
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo_mark) }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, 0, 0) }
        titles.addView(label("Akkhara", 26f, ink, bold = true))
        titles.addView(label("Sing Khmer → ខ្មែរ keyboard", 15f, ink2))
        header.addView(titles)
        col.addView(header)
        col.addView(label("Type Khmer the way you chat, with Latin letters. The model runs on this phone, " +
            "offline: nothing you type leaves your phone.", 14f, ink2).apply { setPadding(0, dp(16f), 0, dp(6f)) })

        // ---- steps ----
        step1 = stepCard(col, "1", "Turn on Akkhara", "Settings → Keyboards → switch Akkhara on", "Open settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        step2 = stepCard(col, "2", "Choose Akkhara", "Make it the keyboard you type with", "Choose keyboard") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        val tryCard = card(col)
        tryCard.addView(label("3  Try it", 17f, ink, bold = true))
        tryCard.addView(label("Type, then tap the Khmer word on top of the keyboard.", 13f, ink2).apply {
            setPadding(0, dp(2f), 0, dp(10f)) })
        tryCard.addView(EditText(this).apply {
            hint = "nh jg tv sala rean"
            setHintTextColor(ink2)
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            minLines = 2
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(12f).toFloat() }
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        })

        val speedCard = card(col)
        speedCard.addView(label("4  Speed on this phone", 17f, ink, bold = true))
        speed = label("Runs the model on 6 chat sentences.", 13f, ink2).apply { setPadding(0, dp(2f), 0, dp(10f)) }
        speedCard.addView(speed)
        speedCard.addView(button("Run speed test", filled = false) { runSpeedTest() })

        // ---- vibration setting ----
        val vib = card(col)
        vib.addView(label("Key vibration", 17f, ink, bold = true))
        vib.addView(label("How strong each key press feels.", 13f, ink2).apply { setPadding(0, dp(2f), 0, dp(10f)) })
        val prefs = getSharedPreferences(Haptics.PREFS, MODE_PRIVATE)
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val opts = listOf("Off", "Light", "Strong")
        val pills = ArrayList<TextView>()
        fun paint() {
            val cur = prefs.getInt(Haptics.KEY_LEVEL, 1)
            pills.forEachIndexed { i, p ->
                val on = i == cur
                p.setTextColor(if (on) 0xFFFFFFFF.toInt() else ink)
                p.background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(if (on) accent else bg) }
            }
        }
        opts.forEachIndexed { i, name ->
            val p = label(name, 15f, ink).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10f), 0, dp(10f))
                setOnClickListener { prefs.edit().putInt(Haptics.KEY_LEVEL, i).apply(); paint(); Haptics(this@MainActivity).tick() }
            }
            pills.add(p)
            seg.addView(p, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (i < 2) dp(8f) else 0 })
        }
        paint()
        vib.addView(seg)

        // ---- tips + footer ----
        val tips = card(col)
        tips.addView(label("How to type", 17f, ink, bold = true))
        tips.addView(label(
            "• Letters stay underlined; tap a Khmer word on top to insert it\n" +
            "• Tap inside the underlined text to fix any word\n" +
            "• ⏎  ,  .  insert the first word\n" +
            "• Globe key: tap = Sing Khmer → ខ្មែរ (real Khmer layout) → English; hold = Samsung keyboard\n" +
            "• ខ្មែរ keyboard: standard NiDA layout, ⇧ shows the second layer\n" +
            "• Tap “your text” on top to keep the Latin as typed", 14f, ink2).apply { setPadding(0, dp(6f), 0, 0) })

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        col.addView(label("Akkhara $version · model AKKHARA-MINI (18 MB, offline)", 12f, ink2).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(18f), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSteps()  // after the keyboard picker closes
    }

    private fun refreshSteps() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        setStatus(step1, enabled, "Turned on", "Not turned on yet")
        setStatus(step2, current.startsWith(packageName), "Akkhara is your keyboard", "Not chosen yet")
    }

    private fun setStatus(tv: TextView, done: Boolean, yes: String, no: String) {
        tv.text = if (done) "✓  $yes" else "○  $no"
        tv.setTextColor(if (done) ok else ink2)
    }

    // ---- small UI helpers -------------------------------------------------------------------
    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(18f).toFloat() }
        elevation = dp(1f).toFloat()
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
    }

    private fun button(text: String, filled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(24f).toFloat()
            if (filled) setColor(accent) else { setColor(0); setStroke(dp(1.5f), accent) }
        }
        setPadding(dp(18f), dp(11f), dp(18f), dp(11f))
        setOnClickListener { onClick() }
    }

    private fun stepCard(parent: LinearLayout, n: String, title: String, sub: String, action: String,
                         onClick: () -> Unit): TextView {
        val c = card(parent)
        c.addView(label("$n  $title", 17f, ink, bold = true))
        c.addView(label(sub, 13f, ink2).apply { setPadding(0, dp(2f), 0, dp(10f)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val status = label("", 14f, ink2)
        row.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button(action, onClick = onClick))
        c.addView(row)
        return status
    }

    private fun runSpeedTest() {
        speed.text = "Loading model…"
        worker.execute {
            val t0 = System.currentTimeMillis()
            val model = MiniModel(this)
            val load = System.currentTimeMillis() - t0
            val tests = listOf("nh jg tv sala rean", "sl oun nah", "tos jol CG", "b pg lvol ot torn tomne te o",
                "sok sbay ot bro", "loy heh bro mx dg te")
            model.greedy(tests[0])  // warm-up
            val sb = StringBuilder()
            var g = 0L; var b = 0L
            for (x in tests) {
                var t = System.nanoTime(); val one = model.greedy(x); g += System.nanoTime() - t
                t = System.nanoTime(); model.topK(x); b += System.nanoTime() - t
                sb.append("$x → $one\n")
            }
            sb.append("\nWhile typing (#1): ${g / tests.size / 1_000_000} ms · top-5: ${b / tests.size / 1_000_000} ms" +
                " · model load: $load ms")
            runOnUiThread { speed.text = sb.toString(); speed.setTextColor(ink) }
        }
    }
}
