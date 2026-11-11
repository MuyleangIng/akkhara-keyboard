package com.akkhara.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Setup screen: a dark "glass" design, drawn edge to edge (behind the status and nav bars, with
 * the content padded by the real system-bar insets so nothing sits under the gesture bar).
 *
 *   header            logo + "Akkhara" + subtitle
 *   privacy pill      on-device · offline
 *   setup progress    "X of 2 done" + 2-segment bar (re-checked every time the app comes back)
 *   step cards        only the NEXT step gets the big button; done steps shrink to one row
 *   Try it            text box + live suggestion strip (the same model as the keyboard)
 *   Speed             3 tiles (while typing / top-5 / model load), measured on this phone,
 *                     and the example sentences with the model's answers
 *   Key vibration, How to type, footer with the author's name
 *
 * Plain Android views, no extra libraries. The "glass" is translucent cards over soft color
 * glows (radial gradients); no real-time blur, so it is cheap on every phone.
 * The keyboard itself (AkkharaIME) is NOT changed by this screen.
 */
class MainActivity : Activity() {
    // one background thread for the model: loading, live suggestions and the speed test run in order
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var engine: LiveEngine? = null
    @Volatile private var model: MiniModel? = null
    @Volatile private var loadMs = 0L
    @Volatile private var request = 0  // newest "Try it" request; older ones are cancelled

    private lateinit var col: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBars: List<View>
    private lateinit var steps: LinearLayout
    private lateinit var suggestBox: LinearLayout
    private lateinit var tiles: List<TextView>
    private lateinit var examples: LinearLayout

    // ---- colors (always dark: the glass look needs a dark background) ------------------------
    private val bg = 0xFF0A0E1C.toInt()
    private val glass = 0x12FFFFFF          // ~7% white
    private val glassBorder = 0x24FFFFFF    // ~14% white
    private val field = 0x4D000000          // dark inset for text boxes
    private val ink = 0xFFF4F6FB.toInt()
    private val ink2 = 0xFFA9B1C6.toInt()   // secondary text, contrast > 4.5:1 on the background
    private val accent = 0xFF2F6BFF.toInt()
    private val teal = 0xFF13B5A6.toInt()
    private val ok = 0xFF5EE0B5.toInt()

    /** Examples for the speed test (shown with the model's answers). */
    private val exampleInputs = listOf(
        "sour sdei akkhara", "nh jg tv sala rean", "sl oun nah", "orkun bong",
        "tv kk ot bro", "b pg lvol ot torn tomne te o",
    )

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()

        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(col)
        }
        val root = FrameLayout(this).apply {
            addView(GlowBackground(this@MainActivity), FrameLayout.LayoutParams(-1, -1))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        // pad the content with the REAL system-bar insets (status bar on top, gesture/3-button bar below)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            col.setPadding(dp(18f), top + dp(16f), dp(18f), bottom + dp(24f))
            insets
        }

        buildHeader()
        buildProgress()
        steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(steps)
        buildTryIt()
        buildDictionary()
        buildSpeed()
        buildVibration()
        buildTyping()
        buildTips()
        buildFooter()

        setContentView(root)
        warmUp()
    }

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSteps()  // after the keyboard picker closes
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    /** Draw behind the status and navigation bars (transparent bars, light icons). */
    private fun goEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ---- sections -------------------------------------------------------------------------------
    private fun buildHeader() {
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo_mark) }, LinearLayout.LayoutParams(dp(60f), dp(60f)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, 0, 0) }
        titles.addView(label("Akkhara", 28f, ink, bold = true))
        titles.addView(label("Sing Khmer → ខ្មែរ keyboard", 15f, ink2))
        header.addView(titles)
        col.addView(header)

        // privacy pill
        val pill = label("🔒  On-device · works offline · nothing leaves your phone", 13f, 0xFFCFE9FF.toInt()).apply {
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            background = rounded(0x1A2F6BFF, dp(20f), 0x4D2F6BFF)
        }
        col.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
    }

    private fun buildProgress() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label("SETUP", 12f, ink2, bold = true).apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(0, -2, 1f))
        progressText = label("", 13f, ink2)
        row.addView(progressText)
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22f) })

        val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        progressBars = List(2) { i ->
            View(this).also { bars.addView(it, LinearLayout.LayoutParams(0, dp(5f), 1f).apply { if (i == 0) marginEnd = dp(6f) }) }
        }
        col.addView(bars, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
    }

    /** Rebuilt every time the app comes back: done steps are compact, only the next one is big. */
    private fun refreshSteps() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val chosen = enabled && current.startsWith(packageName)
        val done = (if (enabled) 1 else 0) + (if (chosen) 1 else 0)

        progressText.text = if (done == 2) "All set ✓" else "$done of 2 done"
        progressText.setTextColor(if (done == 2) ok else ink2)
        progressBars.forEachIndexed { i, v -> v.background = rounded(if (i < done) teal else 0x24FFFFFF, dp(3f)) }

        steps.removeAllViews()
        val openSettings = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        val pickKeyboard = { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        if (enabled) doneRow("Akkhara is turned on", "Enabled in Settings › Keyboards", "Settings", openSettings)
        else nextStep(1, "Turn on Akkhara", "Open Settings › Keyboards and switch Akkhara on. " +
            "Android will show a standard notice; Akkhara has no internet access.", "⚙  Open settings", openSettings)
        when {
            chosen -> doneRow("Akkhara is your keyboard", "You can switch back any time", "Change", pickKeyboard)
            enabled -> nextStep(2, "Make Akkhara your keyboard", "Pick Akkhara in the keyboard switcher. You can " +
                "switch back any time from the keyboard icon in the nav bar.", "⌨  Choose keyboard", pickKeyboard)
            else -> waitingRow("Then: make Akkhara your keyboard")
        }
    }

    private fun buildTryIt() {
        val c = card(col)
        c.addView(label("Try it", 19f, ink, bold = true))
        c.addView(label("Type in Latin letters, the Khmer appears below. Tap one to pick it.", 13f, ink2).apply {
            setPadding(0, dp(2f), 0, dp(12f)) })
        c.addView(label("Your text", 12f, ink2))
        val input = EditText(this).apply {
            hint = "sour sdei akkhara"
            setHintTextColor(0x80A9B1C6.toInt())
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = rounded(field, dp(14f), glassBorder)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            minHeight = dp(48f)
        }
        c.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
            background = rounded(0x33000000, dp(16f), glassBorder)
        }
        box.addView(label("SUGGESTIONS", 11f, ink2, bold = true).apply { letterSpacing = 0.12f })
        suggestBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(suggestBox)
        c.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        showSuggestions(emptyList(), "Start typing, e.g. sour sdei akkhara")

        // live suggestions: wait 120 ms after the last key, then run the model on the worker
        input.addTextChangedListener(object : TextWatcher {
            private val run = Runnable { suggest(input.text.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(run); ui.postDelayed(run, 120) }
        })
    }

    private fun suggest(text: String) {
        val id = ++request
        if (text.isBlank()) { showSuggestions(emptyList(), "Start typing, e.g. sour sdei akkhara"); return }
        worker.execute {
            if (id != request) return@execute
            val e = engineOrLoad()
            val cands = e.candidates(text) { id != request }
            ui.post { if (id == request) showSuggestions(cands, "…") }
        }
    }

    /** Best suggestion as a big highlighted chip, then 2 alternatives and "+N more". */
    private fun showSuggestions(cands: List<String>, empty: String, expanded: Boolean = false) {
        suggestBox.removeAllViews()
        if (cands.isEmpty()) {
            suggestBox.addView(label(empty, 14f, ink2).apply { setPadding(0, dp(10f), 0, 0) })
            return
        }
        val top = label(cands[0], 21f, Color.WHITE, khmer = true).apply {
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = rounded(accent, dp(14f))
            minHeight = dp(48f)
            gravity = Gravity.CENTER_VERTICAL
        }
        suggestBox.addView(top, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        val rest = cands.drop(1)
        if (rest.isEmpty()) return
        val shown = if (expanded) rest else rest.take(2)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, s) in shown.withIndex()) {
            row.addView(chip(s) {
                // the picked one moves to the top, like tapping a candidate on the keyboard
                showSuggestions(listOf(s) + cands.filter { it != s }, empty, expanded)
            }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8f) })
        }
        if (!expanded && rest.size > 2) {
            row.addView(chip("+${rest.size - 2} more", khmer = false) { showSuggestions(cands, empty, expanded = true) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) })
        }
        suggestBox.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
    }

    private fun buildSpeed() {
        val c = card(col)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Speed on this phone", 16f, ink, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label("Run again", 13f, 0xFF8FB0FF.toInt(), bold = true).apply {
            setPadding(dp(10f), dp(12f), 0, dp(12f))  // ≥ 44 dp touch height with the text
            setOnClickListener { runSpeedTest() }
        })
        c.addView(head)

        // one compact line of small pills: "typing 12 ms · top-5 38 ms · load 420 ms"
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("typing", "top-5", "load")
        tiles = names.mapIndexed { i, n ->
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                background = rounded(0x33000000, dp(12f), glassBorder)
            }
            t.addView(label(n, 11f, ink2).apply { setPadding(0, 0, dp(6f), 0) })
            val num = label("…", 13f, ink, bold = true)
            t.addView(num)
            row.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6f) })
            num
        }
        c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        c.addView(label("EXAMPLES", 10f, ink2, bold = true).apply { letterSpacing = 0.12f; setPadding(0, dp(12f), 0, 0) })
        examples = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(examples)
        for (x in exampleInputs) addExample(x, "…")
    }

    private fun addExample(latin: String, khmer: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5f), 0, dp(5f)) }
        r.addView(label(latin, 12f, ink2))
        r.addView(label(khmer, 15f, ink, khmer = true))
        examples.addView(r)
        examples.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(-1, 1))
    }

    private fun buildVibration() {
        val c = card(col)
        c.addView(label("Key vibration", 19f, ink, bold = true))
        c.addView(label("How strong each key press feels.", 13f, ink2).apply { setPadding(0, dp(2f), 0, dp(12f)) })
        val prefs = getSharedPreferences(Haptics.PREFS, MODE_PRIVATE)
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            background = rounded(0x33000000, dp(24f), glassBorder)
        }
        val pills = ArrayList<TextView>()
        fun paint() {
            val cur = prefs.getInt(Haptics.KEY_LEVEL, 1)
            pills.forEachIndexed { i, p ->
                val on = i == cur
                p.setTextColor(if (on) Color.WHITE else ink2)
                p.background = if (on) rounded(accent, dp(20f)) else null
            }
        }
        listOf("Off", "Light", "Strong").forEachIndexed { i, name ->
            val p = label(name, 15f, ink, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44f)
                setOnClickListener { prefs.edit().putInt(Haptics.KEY_LEVEL, i).apply(); paint(); Haptics(this@MainActivity).tick() }
            }
            pills.add(p)
            seg.addView(p, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint()
        c.addView(seg)
    }

    /** Typing switches. The keyboard reads them each time it opens (no restart needed). */
    private fun buildTyping() {
        val c = card(col)
        c.addView(label("Typing", 19f, ink, bold = true))
        val prefs = getSharedPreferences(Haptics.PREFS, MODE_PRIVATE)
        fun switchRow(title: String, desc: String, key: String, default: Boolean) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56f)
                setPadding(0, dp(10f), 0, 0)
            }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(title, 16f, ink, bold = true))
            t.addView(label(desc, 13f, ink2))
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this).apply {
                isChecked = prefs.getBoolean(key, default)
                contentDescription = title
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            }
            row.addView(sw)
            row.setOnClickListener { sw.toggle() }
            c.addView(row)
        }
        switchRow("Number row", "A row of 1 2 3 … (១ ២ ៣ … in ខ្មែរ) above the letters.", KeyboardView.KEY_NUMBER_ROW, true)
        switchRow("Show alternate characters", "Small symbols in the key corners; hold the key to type them.", KeyboardView.KEY_HINTS, true)
        switchRow("Key preview", "Show a big letter above the key you press.", KeyboardView.KEY_PREVIEW, true)
        switchRow("Key sound", "Play the phone's key click sound.", KeyboardView.KEY_SOUND, false)

        // keyboard height: Small / Normal / Large
        c.addView(label("Keyboard height", 16f, ink, bold = true).apply { setPadding(0, dp(16f), 0, dp(8f)) })
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            background = rounded(0x33000000, dp(24f), glassBorder)
        }
        val sizes = listOf("Small" to 0.9f, "Normal" to 1f, "Large" to 1.12f)
        val pills = ArrayList<TextView>()
        fun paint() {
            val cur = prefs.getFloat(KeyboardView.KEY_HEIGHT, 1f)
            pills.forEachIndexed { i, p ->
                val on = sizes[i].second == cur
                p.setTextColor(if (on) Color.WHITE else ink2)
                p.background = if (on) rounded(accent, dp(20f)) else null
            }
        }
        sizes.forEach { (name, v) ->
            val p = label(name, 15f, ink, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44f)
                setOnClickListener { prefs.edit().putFloat(KeyboardView.KEY_HEIGHT, v).apply(); paint() }
            }
            pills.add(p)
            seg.addView(p, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint()
        c.addView(seg)
    }

    private fun buildTips() {
        val c = card(col)
        c.addView(label("How to type", 19f, ink, bold = true))
        val tips = listOf(
            "Letters stay underlined; tap a Khmer word on top to insert it.",
            "Tap inside the underlined text to fix any word.",
            "⏎  ,  .  insert the first suggestion. ⌄ on the bar shows all 5.",
            "Hold a letter for its small corner symbol (or more); slide and lift to pick.",
            "!#1 = symbols (2 pages, with ៛). Top bar: ☺ emoji · ⧉ clipboard · ⇤ one-hand · ⚙ settings.",
            "Hold space and slide to move the cursor. Hold ⌫ to delete whole words.",
            "Globe key: tap = Sing Khmer → ខ្មែរ (NiDA layout) → English; hold = your other keyboard.",
            "ខ្មែរ keyboard: ⇧ shows the second layer.",
            "Tap “your text” on top to keep the Latin as typed.",
        )
        for (t in tips) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, 0) }
            row.addView(label("•", 14f, teal, bold = true).apply { setPadding(0, 0, dp(10f), 0) })
            row.addView(label(t, 14f, ink2, khmer = true), LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(row)
        }
    }

    private fun buildFooter() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        col.addView(label("Made by Ing Muyleang", 14f, ink, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24f), 0, 0) })
        col.addView(label("Akkhara $version · model akkhara-mini (18 MB, offline)", 12f, ink2).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4f), 0, 0) })
        col.addView(label("huggingface.co/muyleanging · github.com/MuyleangIng", 12f, ink2).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(2f), 0, 0) })
        col.addView(label("Dictionary: Wiktionary (CC BY-SA 4.0)", 11f, ink2).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(2f), 0, 0) })
    }

    /**
     * Dictionary card: type Khmer, or Sing Khmer (converted with the model first), and see the
     * English meaning of each word. Data: Wiktionary (CC BY-SA 4.0), ~9k words, offline.
     */
    private fun buildDictionary() {
        val c = card(col)
        c.addView(label("Dictionary", 16f, ink, bold = true))
        c.addView(label("Any spelling finds the word (sur sdei = surdei = សួស្តី): meaning + all Sing spellings, offline.", 12f, ink2).apply {
            setPadding(0, dp(2f), 0, dp(8f)) })
        val input = EditText(this).apply {
            hint = "surdei · sl oun · ស្រឡាញ់"
            setHintTextColor(0x80A9B1C6.toInt())
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = rounded(field, dp(14f), glassBorder)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            minHeight = dp(46f)
        }
        c.addView(input, LinearLayout.LayoutParams(-1, -2))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(results)
        var req = 0
        val run = Runnable {
            val text = input.text.toString().trim()
            val id = ++req
            results.removeAllViews()
            if (text.isEmpty()) return@Runnable
            worker.execute {
                // Latin: 1) the spelling index (any spelling, also 1 letter off: surdei → សួស្តី)
                //        2) the model's conversion of the whole text, cut into words
                val dict = Dictionary.get(this)
                val isKhmer = text.any { it in '\u1780'..'\u17FF' }
                val khmer = if (isKhmer) text else engineOrLoad().quick(text)
                val entries = LinkedHashMap<String, Dictionary.Entry>()
                if (!isKhmer) dict.byLatin(text).forEach { entries[it.word] = it }
                dict.lookup(khmer).forEach { entries.putIfAbsent(it.word, it) }
                ui.post {
                    if (id != req) return@post
                    results.removeAllViews()
                    if (khmer != text) results.addView(label(khmer, 17f, ink, khmer = true).apply { setPadding(0, dp(10f), 0, 0) })
                    if (entries.isEmpty()) results.addView(label("No dictionary words found.", 13f, ink2).apply { setPadding(0, dp(8f), 0, 0) })
                    for (e in entries.values.take(12)) {
                        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8f), 0, dp(4f)) }
                        r.addView(label(e.word, 16f, 0xFF8FB0FF.toInt(), khmer = true))
                        e.meaning?.let { r.addView(label(it.replace(" | ", "\n"), 13f, ink2)) }
                        if (e.spellings.isNotEmpty()) r.addView(label("Sing: " + e.spellings.take(8).joinToString(" · "), 12f, ink2))
                        results.addView(r)
                    }
                }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(run); ui.postDelayed(run, 250) }
        })
    }

    // ---- model work (always on the worker thread) ---------------------------------------------
    private fun engineOrLoad(): LiveEngine {
        engine?.let { return it }
        val t0 = System.currentTimeMillis()
        val m = MiniModel(this)
        loadMs = System.currentTimeMillis() - t0
        model = m
        return LiveEngine(m).also { engine = it }
    }

    /** Load the model in the background when the screen opens, then measure the speed once. */
    private fun warmUp() = runSpeedTest()

    private fun runSpeedTest() {
        tiles.forEach { it.text = "…" }
        worker.execute {
            engineOrLoad()
            val m = model ?: return@execute
            m.greedy(exampleInputs[0])  // warm-up run, not timed
            var g = 0L
            var b = 0L
            val answers = ArrayList<String>()
            for (x in exampleInputs) {
                var t = System.nanoTime(); answers.add(m.greedy(x)); g += System.nanoTime() - t
                t = System.nanoTime(); m.topK(x); b += System.nanoTime() - t
            }
            val n = exampleInputs.size
            ui.post {
                tiles[0].text = "${g / n / 1_000_000} ms"
                tiles[1].text = "${b / n / 1_000_000} ms"
                tiles[2].text = "$loadMs ms"
                examples.removeAllViews()
                exampleInputs.zip(answers).forEach { (x, k) -> addExample(x, k) }
            }
        }
    }

    // ---- small UI helpers ---------------------------------------------------------------------
    /** Khmer text gets extra line height so stacked subscripts are never clipped. */
    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false, khmer: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, if (khmer) 1.4f else 1.2f)
        includeFontPadding = true
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1f), stroke)
    }

    /** Translucent "glass" card with a thin light border. */
    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        background = rounded(glass, dp(26f), glassBorder)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = label(text, 16f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(52f)
        background = rounded(accent, dp(18f))
        setOnClickListener { onClick() }
    }

    private fun outlineButton(text: String, onClick: () -> Unit) = label(text, 14f, ink, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44f)
        setPadding(dp(16f), 0, dp(16f), 0)
        background = rounded(0, dp(22f), 0x40FFFFFF)
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, khmer: Boolean = true, onClick: () -> Unit) = label(text, if (khmer) 17f else 14f, ink, khmer = khmer).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44f)
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        background = rounded(0x14FFFFFF, dp(14f), glassBorder)
        setOnClickListener { onClick() }
    }

    /** The next step to do: big card with a number badge and one primary button. */
    private fun nextStep(n: Int, title: String, body: String, action: String, onClick: () -> Unit) {
        val c = card(steps)
        c.background = rounded(0x1A2F6BFF, dp(26f), 0x662F6BFF)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("$n", 16f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        }, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, 0, 0) }
        t.addView(label("NEXT STEP", 11f, 0xFF8FB0FF.toInt(), bold = true).apply { letterSpacing = 0.12f })
        t.addView(label(title, 19f, ink, bold = true))
        head.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(head)
        c.addView(label(body, 14f, ink2).apply { setPadding(0, dp(10f), 0, dp(14f)) })
        c.addView(primaryButton(action, onClick), LinearLayout.LayoutParams(-1, -2))
    }

    /** A finished step: one compact row with a check mark and a small outline button. */
    private fun doneRow(title: String, sub: String, action: String, onClick: () -> Unit) {
        val c = card(steps)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(16f), dp(14f), dp(14f), dp(14f))
        c.addView(label("✓", 16f, ok, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x2613B5A6) }
        }, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(8f), 0) }
        t.addView(label(title, 16f, ink, bold = true))
        t.addView(label(sub, 13f, ink2))
        c.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(outlineButton(action, onClick))
    }

    /** A step that has to wait for the one before it. */
    private fun waitingRow(title: String) {
        val c = card(steps)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(16f), dp(14f), dp(14f), dp(14f))
        c.addView(label("2", 15f, ink2, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1f), 0x40FFFFFF) }
        }, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        c.addView(label(title, 15f, ink2).apply { setPadding(dp(12f), 0, 0, 0) })
    }

    /** Near-black background with three soft color glows (accent blue, teal, violet). */
    private class GlowBackground(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFF0A0E1C.toInt())
            val w = width.toFloat()
            val h = height.toFloat()
            glow(canvas, w * 0.10f, h * 0.06f, w * 0.95f, 0x552F6BFF)
            glow(canvas, w * 1.05f, h * 0.42f, w * 0.85f, 0x3813B5A6)
            glow(canvas, w * 0.00f, h * 0.88f, w * 0.95f, 0x406A4CFF)
        }

        private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
            paint.shader = RadialGradient(x, y, r, color, 0x00000000, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
        }
    }
}
