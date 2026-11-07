package com.akkhara.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The whole keyboard (Samsung-style layout, our own look):
 *
 *   top strip    IDLE:   ☺ emoji · ⧉ clipboard · [paste chip / note] · Sing · ⇤ one-hand · ⚙
 *                TYPING: “raw text” · best Khmer · alt · alt · ⌄ (full top-5)
 *                (the two cross-fade in 150 ms; the height never changes)
 *   body         keys (KeysView) — or, in the SAME height: top-5 panel, emoji panel, clipboard panel
 *   one-hand     keys shrink to one side; side buttons swap the side or go back to full width
 *
 * It only draws and reports presses; the typing logic is in AkkharaIME.
 * No blur and no heavy effects in here: typing speed comes first.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetTextI18n")
class KeyboardView(context: Context, private val listener: Listener) : LinearLayout(context), KeysView.Listener {

    interface Listener {
        fun onText(s: String)        // a key's text (letter, digit, Khmer letter, punctuation)
        fun onSpace()
        fun onBackspace()
        fun onBackspaceWord()        // ⌫ held ~1 s: whole words
        fun onEnter()
        fun onGlobe()                // tap: next Akkhara keyboard (Sing → ខ្មែរ → English)
        fun onGlobeLong()            // long-press: other phone keyboard
        fun onCandidate(index: Int)
        fun onKeepLatin()
        fun onCursorMove(steps: Int) // space-bar trackpad
        fun onInsert(text: String)   // emoji / clipboard: insert as is (the Latin being typed stays as typed)
        fun onOpenSettings()
    }

    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val theme = KeyTheme(dark)
    private val prefs = context.getSharedPreferences(Haptics.PREFS, Context.MODE_PRIVATE)
    private val haptics = Haptics(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val clips = ClipStore(context)
    private val ui = Handler(Looper.getMainLooper())
    private var lastShiftTap = 0L

    // ---- views ----
    private val strip = FrameLayout(context)
    private val toolbar = LinearLayout(context)
    private val toolNote = FrameLayout(context)          // centre of the toolbar: paste chip or a note
    private val modeTool = TextView(context)
    private val oneHandTool = ImageView(context)
    private val bar = LinearLayout(context)              // suggestions
    private val candidateRow = LinearLayout(context)
    private val barScroll = HorizontalScrollView(context)
    private val moreButton = ImageView(context)
    private val searchBar = LinearLayout(context)        // emoji search: query + results
    private val searchText = TextView(context)
    private val searchResults = LinearLayout(context)

    private val body = FrameLayout(context)
    private val keysRow = LinearLayout(context)
    private val sideLeft = LinearLayout(context)
    private val sideRight = LinearLayout(context)
    private val keys: KeysView = KeysView(context, theme, this)
    private val top5 = ScrollView(context)
    private val top5List = LinearLayout(context)
    private val emojiPanel = LinearLayout(context)
    private val clipPanel = LinearLayout(context)
    private val dictPanel = LinearLayout(context)         // meanings (Wiktionary) of a Khmer suggestion

    private var shownItems: List<String> = emptyList()
    private var shownLatin = ""
    private var typing = false
    private var searching = false
    private val searchQuery = StringBuilder()
    private var pasteText: String? = null

    var shift: Boolean
        get() = keys.shift
        set(v) { keys.shift = v }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.bg)
        clipChildren = false      // the key preview balloon may draw over the top strip
        clipToPadding = false

        buildToolbar()
        buildBar()
        buildSearchBar()
        strip.addView(toolbar, FrameLayout.LayoutParams(-1, -1))
        strip.addView(bar, FrameLayout.LayoutParams(-1, -1))
        strip.addView(searchBar, FrameLayout.LayoutParams(-1, -1))
        bar.visibility = GONE
        searchBar.visibility = GONE
        addView(strip, LayoutParams(-1, dpi(46f)))

        // body: keys (+ one-hand side buttons), panels on top in the same height
        body.clipChildren = false
        keysRow.orientation = HORIZONTAL
        keysRow.clipChildren = false
        buildSide(sideLeft)
        buildSide(sideRight)
        keysRow.addView(sideLeft, LayoutParams(dpi(52f), -1))
        keysRow.addView(keys, LayoutParams(0, -2, 1f))
        keysRow.addView(sideRight, LayoutParams(dpi(52f), -1))
        body.addView(keysRow, FrameLayout.LayoutParams(-1, -2))
        top5.addView(top5List.apply { orientation = VERTICAL; setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(10f)) })
        top5.setBackgroundColor(theme.bg)
        top5.setOnTouchListener(SwipeDownToClose())
        for (p in listOf<View>(top5, emojiPanel, clipPanel, dictPanel)) {
            p.visibility = GONE
            body.addView(p, FrameLayout.LayoutParams(-1, -1))
        }
        addView(body, LayoutParams(-1, -2))

        reloadSettings()
        showIdle(null)

        // edge to edge: keep the bottom row above the gesture bar / 3-button bar
        setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                         else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
    }

    /** Settings may change in the Akkhara app while the keyboard is open: read them each time it shows. */
    fun reloadSettings() {
        keys.previewOn = prefs.getBoolean(KEY_PREVIEW, true)
        keys.hintsOn = prefs.getBoolean(KEY_HINTS, true)
        keys.numberRow = prefs.getBoolean(KEY_NUMBER_ROW, true)
        keys.heightScale = prefs.getFloat(KEY_HEIGHT, 1f)
        applyOneHand()
    }

    // ---- top strip: toolbar ------------------------------------------------------------------------
    /** A real (vector) icon, tinted to the theme. */
    private fun ImageView.icon(res: Int, color: Int = theme.ink) {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    private fun tool(res: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
        icon(res, theme.ink2)
        contentDescription = desc
        setPadding(dpi(9f), dpi(9f), dpi(9f), dpi(9f))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.card) }
        setOnClickListener { haptics.key(this); onClick() }
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
        icon(res)
        contentDescription = desc
        setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f))
        minimumWidth = dpi(48f)
        background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(theme.fn) }
        setOnClickListener { haptics.key(this); onClick() }
    }

    private fun buildToolbar() {
        toolbar.orientation = HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setPadding(dpi(8f), 0, dpi(8f), 0)
        fun add(v: View) = toolbar.addView(v, LayoutParams(dpi(40f), dpi(40f)).apply { marginEnd = dpi(8f) })
        add(tool(R.drawable.ic_emoji, "Emoji") { openEmoji() })
        add(tool(R.drawable.ic_clipboard, "Clipboard") { openClipboard() })
        toolbar.addView(toolNote, LayoutParams(0, -1, 1f))
        modeTool.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(theme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dpi(12f), 0, dpi(12f), 0)
            background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(theme.card) }
            contentDescription = "Change keyboard"
            setOnClickListener { haptics.key(this); listener.onGlobe() }
        }
        toolbar.addView(modeTool, LayoutParams(-2, dpi(40f)).apply { marginStart = dpi(8f); marginEnd = dpi(8f) })
        oneHandTool.apply {
            icon(R.drawable.ic_one_hand, theme.ink2); contentDescription = "One-hand mode"
            setPadding(dpi(9f), dpi(9f), dpi(9f), dpi(9f))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.card) }
            setOnClickListener { haptics.key(this); setOneHand(if (oneHand() == 0) 2 else 0) }
        }
        add(oneHandTool)
        toolbar.addView(tool(R.drawable.ic_settings, "Akkhara settings") { listener.onOpenSettings() }, LayoutParams(dpi(40f), dpi(40f)))
    }

    /** IDLE strip. [note]: a short status (loading, password, basic mode), or null. */
    fun showIdle(note: String?) {
        typing = false
        shownItems = emptyList()
        if (top5.visibility == VISIBLE) closePanel(top5)
        toolNote.removeAllViews()
        val paste = pasteText
        when {
            paste != null -> toolNote.addView(pasteChip(paste), FrameLayout.LayoutParams(-2, dpi(36f), Gravity.CENTER))
            note != null -> toolNote.addView(TextView(context).apply {
                text = note; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(theme.ink2)
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, FrameLayout.LayoutParams(-1, -1))
        }
        swapStrip(toolbar)
    }

    /** Old name kept for AkkharaIME. */
    fun setStatus(text: String) = showIdle(text.ifBlank { null })
    fun setBadge(text: String) = showIdle(text)

    private fun swapStrip(target: View) {
        for (v in listOf<View>(toolbar, bar, searchBar)) {
            if (v === target) {
                if (v.visibility != VISIBLE) { v.alpha = 0f; v.visibility = VISIBLE; v.animate().alpha(1f).setDuration(150).start() }
            } else if (v.visibility == VISIBLE) {
                v.animate().cancel(); v.visibility = GONE
            }
        }
    }

    // ---- paste chip -----------------------------------------------------------------------------
    private val hidePaste = Runnable { pasteText = null; if (!typing && !searching) showIdle(null) }

    /** A new clipboard item: offer a "Paste" chip for 60 s. */
    fun offerPaste(text: String) {
        pasteText = text
        ui.removeCallbacks(hidePaste)
        ui.postDelayed(hidePaste, 60_000)
        if (!typing && !searching) {
            showIdle(null)
            toolNote.getChildAt(0)?.let { chip ->
                chip.scaleX = 0.9f; chip.scaleY = 0.9f
                chip.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(1.2f)).start()
            }
        }
    }

    private fun pasteChip(text: String) = TextView(context).apply {
        val preview = if (ClipStore.looksPrivate(text)) "••••" else text.replace('\n', ' ').take(24)
        this.text = "Paste  ·  $preview"
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_clipboard, 0, 0, 0)
        compoundDrawablePadding = dpi(6f)
        compoundDrawableTintList = ColorStateList.valueOf(theme.accent)
        contentDescription = "Paste from clipboard"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(theme.ink)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dpi(14f), 0, dpi(14f), 0)
        background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(theme.accentSoft) }
        setOnClickListener {
            haptics.key(this)
            listener.onInsert(text)
            pasteText = null
            ui.removeCallbacks(hidePaste)
            showIdle(null)
        }
    }

    // ---- top strip: suggestions --------------------------------------------------------------------
    private fun buildBar() {
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        barScroll.isHorizontalScrollBarEnabled = false
        candidateRow.orientation = HORIZONTAL
        candidateRow.gravity = Gravity.CENTER_VERTICAL
        candidateRow.setPadding(dpi(4f), 0, dpi(4f), 0)
        barScroll.addView(candidateRow)
        bar.addView(barScroll, LayoutParams(0, -1, 1f))
        moreButton.apply {
            icon(R.drawable.ic_expand_more, theme.ink2)
            setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
            contentDescription = "Show all suggestions"
            setOnClickListener { haptics.key(this); if (top5.visibility == VISIBLE) closePanel(top5) else openTop5() }
        }
        bar.addView(moreButton, LayoutParams(dpi(48f), -1))
    }

    /**
     * Show candidates while typing. [announce]: TalkBack reads the #1 (only for the settled top-5,
     * not on every keystroke). No animation per keystroke: the text just updates (fast feel).
     */
    fun setCandidates(items: List<String>, latin: String = "", announce: Boolean = false) {
        if (searching) return
        typing = true
        shownItems = items
        shownLatin = latin
        candidateRow.removeAllViews()
        if (latin.isNotBlank()) chip("“${latin.trim()}”", 14f, theme.ink2, null) { listener.onKeepLatin() }
        items.forEachIndexed { i, text ->
            val best = i == 0
            chip(text, if (best) 19f else 17f, if (best) theme.accent else theme.ink, if (best) theme.accentSoft else null, best) {
                listener.onCandidate(i)
            }
        }
        moreButton.visibility = if (items.size > 1) VISIBLE else INVISIBLE
        barScroll.scrollTo(0, 0)
        swapStrip(bar)
        if (top5.visibility == VISIBLE) fillTop5()
        if (announce && items.isNotEmpty()) announceForAccessibility(items[0])
    }

    private fun chip(text: String, sp: Float, color: Int, fill: Int?, bold: Boolean = false, onClick: () -> Unit) {
        val tv = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = true                    // room for Khmer subscripts
            setPadding(dpi(14f), 0, dpi(14f), 0)
            if (fill != null) background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(20f) }
            setOnClickListener { haptics.key(this); onClick() }
            // hold a Khmer suggestion: show what its words mean (dictionary panel)
            if (text.any { it in '\u1780'..'\u17FF' }) setOnLongClickListener { haptics.long(this); openDictionary(text); true }
        }
        candidateRow.addView(tv, LayoutParams(-2, dpi(40f)).apply { setMargins(dpi(3f), 0, dpi(3f), 0) })
    }

    // ---- top-5 panel (⌄) ----------------------------------------------------------------------------
    private fun openTop5() {
        fillTop5()
        openPanel(top5)
        moreButton.icon(R.drawable.ic_expand_less, theme.ink2)
        moreButton.contentDescription = "Close suggestions"
    }

    private fun fillTop5() {
        top5List.removeAllViews()
        fun row(text: String, hint: String, best: Boolean, onClick: () -> Unit) {
            val r = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f))
                background = GradientDrawable().apply { setColor(if (best) theme.accentSoft else theme.key); cornerRadius = dp(12f) }
                minimumHeight = dpi(48f)
                setOnClickListener { haptics.key(this); closePanel(top5); onClick() }
                if (text.any { it in '\u1780'..'\u17FF' }) setOnLongClickListener { haptics.long(this); openDictionary(text); true }
            }
            r.addView(TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (best) 20f else 18f)
                setTextColor(if (best) theme.accent else theme.ink)
                setLineSpacing(0f, 1.3f)
            })
            if (hint.isNotEmpty()) r.addView(TextView(context).apply {
                this.text = hint; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(theme.ink2)
            })
            top5List.addView(r, LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        }
        shownItems.forEachIndexed { i, s -> row(s, if (i == 0) "best" else "", i == 0) { listener.onCandidate(i) } }
        if (shownLatin.isNotBlank()) row(shownLatin.trim(), "keep as typed", false) { listener.onKeepLatin() }
    }

    // ---- panels share the keys' height (the app above never jumps) ---------------------------------
    private fun openPanel(p: View) {
        for (o in listOf<View>(top5, emojiPanel, clipPanel, dictPanel)) if (o !== p) o.visibility = GONE
        // exactly the keys' height: the keyboard never grows (no full-screen panels, no jumping app)
        p.layoutParams = p.layoutParams.apply { height = keysRow.height.takeIf { it > 0 } ?: dpi(260f) }
        p.visibility = VISIBLE
        p.alpha = 0f
        p.translationY = dp(14f)
        p.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(OvershootInterpolator(0.8f)).start()
    }

    private fun closePanel(p: View) {
        p.animate().alpha(0f).translationY(dp(10f)).setDuration(130).withEndAction { p.visibility = GONE; p.translationY = 0f }.start()
        if (p === top5) { moreButton.icon(R.drawable.ic_expand_more, theme.ink2); moreButton.contentDescription = "Show all suggestions" }
    }

    /** Swipe down on the top-5 panel closes it. */
    private inner class SwipeDownToClose : OnTouchListener {
        private var y0 = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> y0 = e.rawY
                MotionEvent.ACTION_UP -> if (e.rawY - y0 > dp(60f) && top5.scrollY == 0) { closePanel(top5); return true }
            }
            return false
        }
    }

    private fun panelButton(label: String, desc: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = desc
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(theme.ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minWidth = dpi(52f)
        setPadding(dpi(12f), 0, dpi(12f), 0)
        background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(theme.fn) }
        setOnClickListener { haptics.key(this); onClick() }
    }

    // ---- emoji panel ---------------------------------------------------------------------------------
    private var emojiCategory = 0

    private fun openEmoji() {
        emojiPanel.removeAllViews()
        emojiPanel.orientation = VERTICAL
        emojiPanel.setBackgroundColor(theme.bg)
        val grid = ScrollView(context)
        val gridBox = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dpi(6f), dpi(4f), dpi(6f), dpi(4f)) }
        grid.addView(gridBox)
        emojiPanel.addView(grid, LayoutParams(-1, 0, 1f))

        fun fill(list: List<String>) {
            gridBox.removeAllViews()
            if (list.isEmpty()) {
                gridBox.addView(TextView(context).apply {
                    text = "No recent emoji yet"; setTextColor(theme.ink2); gravity = Gravity.CENTER; setPadding(0, dpi(24f), 0, 0)
                })
                return
            }
            list.chunked(8).forEach { chunk ->
                val r = LinearLayout(context).apply { orientation = HORIZONTAL }
                chunk.forEach { e -> r.addView(emojiCell(e), LayoutParams(0, dpi(40f), 1f)) }
                repeat(8 - chunk.size) { r.addView(View(context), LayoutParams(0, dpi(40f), 1f)) }
                gridBox.addView(r)
            }
            grid.scrollTo(0, 0)
        }

        // bottom: abc · 🔍 · categories (recent first) · ⌫
        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(6f), dpi(4f), dpi(6f), dpi(4f)) }
        bottom.addView(panelButton(if (keys.mode == Mode.KHMER) "កខគ" else "abc", "Back to keys") { closePanel(emojiPanel) }, LayoutParams(-2, dpi(40f)))
        bottom.addView(iconButton(R.drawable.ic_search, "Search emoji") { startSearch() }, LayoutParams(-2, dpi(40f)).apply { marginStart = dpi(6f) })
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tabScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
        val names = listOf("🕘") + Emoji.CATEGORIES.map { it.first }
        val tabViews = ArrayList<TextView>()
        fun select(i: Int) {
            emojiCategory = i
            tabViews.forEachIndexed { j, t -> t.background = if (i == j) GradientDrawable().apply { cornerRadius = dp(10f); setColor(theme.accentSoft) } else null }
            fill(if (i == 0) recentEmoji() else Emoji.CATEGORIES[i - 1].second)
        }
        names.forEachIndexed { i, n ->
            val t = TextView(context).apply {
                text = n; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER
                contentDescription = if (i == 0) "Recent" else "Category ${i}"
                setOnClickListener { haptics.key(this); select(i) }
            }
            tabViews.add(t)
            tabs.addView(t, LayoutParams(dpi(42f), dpi(40f)))
        }
        bottom.addView(tabScroll, LayoutParams(0, dpi(40f), 1f).apply { marginStart = dpi(6f); marginEnd = dpi(6f) })
        bottom.addView(iconButton(R.drawable.ic_backspace, "Delete") { listener.onBackspace() }, LayoutParams(-2, dpi(40f)))
        emojiPanel.addView(bottom, LayoutParams(-1, -2))
        select(if (recentEmoji().isEmpty()) 1 else 0)
        openPanel(emojiPanel)
    }

    private fun emojiCell(e: String) = TextView(context).apply {
        text = e
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
        gravity = Gravity.CENTER
        setOnClickListener { haptics.key(this); listener.onInsert(e); rememberEmoji(e) }
    }

    private fun recentEmoji(): List<String> = (prefs.getString(KEY_EMOJI_RECENT, "") ?: "").split(" ").filter { it.isNotEmpty() }

    private fun rememberEmoji(e: String) {
        val list = (listOf(e) + recentEmoji().filter { it != e }).take(32)
        prefs.edit().putString(KEY_EMOJI_RECENT, list.joinToString(" ")).apply()
    }

    // emoji search: the keys type into the search box (Sing Khmer / English keywords)
    private fun buildSearchBar() {
        searchBar.orientation = HORIZONTAL
        searchBar.gravity = Gravity.CENTER_VERTICAL
        searchBar.setPadding(dpi(8f), 0, dpi(8f), 0)
        searchText.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(theme.ink); gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12f), 0, dpi(12f), 0)
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(theme.card) }
            minWidth = dpi(110f)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dpi(6f)
            compoundDrawableTintList = ColorStateList.valueOf(theme.ink2)
        }
        searchBar.addView(searchText, LayoutParams(-2, dpi(36f)))
        searchResults.orientation = HORIZONTAL
        searchBar.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(searchResults) },
            LayoutParams(0, -1, 1f).apply { marginStart = dpi(6f) })
        searchBar.addView(ImageView(context).apply {
            icon(R.drawable.ic_close, theme.ink2); setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
            contentDescription = "Close search"
            setOnClickListener { endSearch(); openEmoji() }
        }, LayoutParams(dpi(40f), -1))
    }

    private fun startSearch() {
        searching = true
        searchQuery.setLength(0)
        closePanel(emojiPanel)
        updateSearch()
        swapStrip(searchBar)
    }

    private fun endSearch() {
        searching = false
        searchQuery.setLength(0)
        showIdle(null)
    }

    private fun updateSearch() {
        val q = searchQuery.toString().trim().lowercase()
        searchText.text = if (q.isEmpty()) "type: sok, srolanh…" else q
        searchResults.removeAllViews()
        if (q.isEmpty()) return
        val found = LinkedHashSet<String>()
        for ((k, v) in Emoji.KEYWORDS) if (k.startsWith(q) || q.startsWith(k)) found.addAll(v)
        for (e in found) searchResults.addView(TextView(context).apply {
            text = e; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER
            setOnClickListener { haptics.key(this); listener.onInsert(e); rememberEmoji(e) }
        }, LayoutParams(dpi(44f), -1))
    }

    // ---- dictionary panel (Wiktionary, CC BY-SA) ----------------------------------------------------
    /** Meanings of every dictionary word in [text]; loads the 0.6 MB dictionary once, off the UI thread. */
    private fun openDictionary(text: String) {
        dictPanel.removeAllViews()
        dictPanel.orientation = VERTICAL
        dictPanel.setBackgroundColor(theme.bg)
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(6f), dpi(4f), dpi(6f), dpi(4f)) }
        header.addView(iconButton(R.drawable.ic_keyboard, "Back to keys") { closePanel(dictPanel) }, LayoutParams(-2, dpi(38f)))
        header.addView(TextView(context).apply {
            this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(theme.ink)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dpi(12f), 0, dpi(8f), 0)
        }, LayoutParams(0, -2, 1f))
        dictPanel.addView(header, LayoutParams(-1, -2))
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dpi(12f), 0, dpi(12f), dpi(8f)) }
        dictPanel.addView(ScrollView(context).apply { addView(box) }, LayoutParams(-1, 0, 1f))
        dictPanel.addView(TextView(context).apply {
            this.text = "Dictionary: Wiktionary (CC BY-SA 4.0)"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(theme.ink2); gravity = Gravity.CENTER; setPadding(0, 0, 0, dpi(4f))
        })
        box.addView(TextView(context).apply { this.text = "…"; setTextColor(theme.ink2) })
        openPanel(dictPanel)
        Thread {
            val entries = try { Dictionary.get(context).lookup(text) } catch (e: Exception) { emptyList() }
            ui.post {
                box.removeAllViews()
                if (entries.isEmpty()) box.addView(TextView(context).apply {
                    this.text = "No dictionary words found."; setTextColor(theme.ink2); setPadding(0, dpi(16f), 0, 0)
                })
                for (e in entries) {
                    val r = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dpi(8f), 0, dpi(8f)) }
                    r.addView(TextView(context).apply { this.text = e.word; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTextColor(theme.accent) })
                    r.addView(TextView(context).apply { this.text = e.meaning.replace(" | ", "\n"); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(theme.ink); setLineSpacing(0f, 1.2f) })
                    box.addView(r)
                    box.addView(View(context).apply { setBackgroundColor(theme.fn) }, LayoutParams(-1, 1))
                }
                if (entries.isNotEmpty()) announceForAccessibility(entries.joinToString(". ") { "${it.word}: ${it.meaning}" })
            }
        }.start()
    }

    // ---- clipboard panel ---------------------------------------------------------------------------
    private var revealed = HashSet<String>()

    private fun openClipboard() {
        clipPanel.removeAllViews()
        clipPanel.orientation = VERTICAL
        clipPanel.setBackgroundColor(theme.bg)
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(6f), dpi(4f), dpi(6f), dpi(4f)) }
        header.addView(iconButton(R.drawable.ic_keyboard, "Back to keys") { closePanel(clipPanel) }, LayoutParams(-2, dpi(38f)))
        header.addView(TextView(context).apply {
            text = "Clipboard"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(theme.ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dpi(12f), 0, 0, 0)
        }, LayoutParams(0, -2, 1f))
        header.addView(TextView(context).apply {
            text = "On this phone only · 1 h"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(theme.ink2); setPadding(0, 0, dpi(8f), 0)
        })
        header.addView(iconButton(R.drawable.ic_delete, "Delete unpinned") { clips.clearUnpinned(); openClipboard() }, LayoutParams(-2, dpi(38f)))
        clipPanel.addView(header, LayoutParams(-1, -2))

        val scroll = ScrollView(context)
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dpi(8f), 0, dpi(8f), dpi(8f)) }
        scroll.addView(box)
        clipPanel.addView(scroll, LayoutParams(-1, 0, 1f))

        val items = clips.all()
        if (items.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Copy text in any app and it shows up here.\nPasswords and sensitive items are never saved."
                setTextColor(theme.ink2); gravity = Gravity.CENTER; setPadding(0, dpi(28f), 0, 0)
            })
        }
        fun section(title: String, list: List<ClipStore.Clip>) {
            if (list.isEmpty()) return
            box.addView(TextView(context).apply {
                text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(theme.ink2); setPadding(dpi(4f), dpi(10f), 0, dpi(4f))
            })
            list.chunked(2).forEach { pair ->
                val r = LinearLayout(context).apply { orientation = HORIZONTAL }
                pair.forEach { c -> r.addView(clipCard(c), LayoutParams(0, dpi(84f), 1f).apply { setMargins(dpi(4f), dpi(4f), dpi(4f), dpi(4f)) }) }
                if (pair.size == 1) r.addView(View(context), LayoutParams(0, dpi(84f), 1f))
                box.addView(r)
            }
        }
        section("Pinned", items.filter { it.pinned })
        section("Recent", items.filter { !it.pinned })
        openPanel(clipPanel)
    }

    /** Tap = paste · hold = pin / unpin · ✕ = delete. Private-looking items stay hidden until tapped once. */
    private fun clipCard(c: ClipStore.Clip) = FrameLayout(context).apply {
        background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(theme.card) }
        val hidden = ClipStore.looksPrivate(c.text) && c.text !in revealed
        addView(TextView(context).apply {
            text = if (hidden) "•••• hidden · tap to show" else c.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(if (hidden) theme.ink2 else theme.ink)
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            setPadding(dpi(12f), dpi(10f), dpi(26f), dpi(10f))
        }, FrameLayout.LayoutParams(-1, -1))
        addView(ImageView(context).apply {
            icon(if (c.pinned) R.drawable.ic_pin else R.drawable.ic_close, theme.ink2)
            setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f))
            contentDescription = if (c.pinned) "Pinned" else "Delete"
            setOnClickListener { if (!c.pinned) { clips.remove(c.text); openClipboard() } }
        }, FrameLayout.LayoutParams(dpi(28f), dpi(28f), Gravity.TOP or Gravity.END))
        setOnClickListener {
            haptics.key(this)
            if (hidden) { revealed.add(c.text); openClipboard() }
            else { listener.onInsert(c.text); closePanel(clipPanel) }
        }
        setOnLongClickListener { haptics.long(this); clips.togglePin(c.text); openClipboard(); true }
    }

    // ---- one-hand mode -----------------------------------------------------------------------------
    private fun oneHand() = prefs.getInt(KEY_ONE_HAND, 0)   // 0 off, 1 left, 2 right

    private fun setOneHand(v: Int) {
        prefs.edit().putInt(KEY_ONE_HAND, v).apply()
        applyOneHand()
    }

    private fun applyOneHand() {
        val v = oneHand()
        // keys on the left → buttons on the right, and the other way round
        sideLeft.visibility = if (v == 2) VISIBLE else GONE
        sideRight.visibility = if (v == 1) VISIBLE else GONE
        oneHandTool.icon(if (v == 0) R.drawable.ic_one_hand else R.drawable.ic_full_width, theme.ink2)
        oneHandTool.contentDescription = if (v == 0) "One-hand mode" else "Full width"
    }

    private fun buildSide(side: LinearLayout) {
        side.orientation = VERTICAL
        side.gravity = Gravity.CENTER
        side.addView(iconButton(R.drawable.ic_swap, "Move keyboard to the other side") { setOneHand(if (oneHand() == 1) 2 else 1) },
            LayoutParams(dpi(44f), dpi(48f)))
        side.addView(iconButton(R.drawable.ic_full_width, "Full width") { setOneHand(0) }, LayoutParams(dpi(44f), dpi(48f)).apply { topMargin = dpi(12f) })
    }

    // ---- KeysView.Listener -------------------------------------------------------------------------
    override fun onPress(k: KeysView.Key) {
        haptics.key(this)
        if (prefs.getBoolean(KEY_SOUND, false)) {
            audio?.playSoundEffect(when (k.kind) {
                KeysView.Kind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeysView.Kind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeysView.Kind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }, -1f)
        }
    }

    override fun onKey(k: KeysView.Key, long: Boolean) {
        if (searching) { searchKey(k); return }
        when (k.kind) {
            KeysView.Kind.TEXT -> {
                listener.onText(if ((keys.shift || keys.capsLock) && keys.symbols == 0) k.shiftOut else k.out)
                if (keys.shift && !keys.capsLock) keys.shift = false
            }
            KeysView.Kind.SHIFT -> onShift()
            KeysView.Kind.BACKSPACE -> if (long) listener.onBackspaceWord() else listener.onBackspace()
            KeysView.Kind.GLOBE -> if (long) listener.onGlobeLong() else listener.onGlobe()
            KeysView.Kind.SPACE -> listener.onSpace()
            KeysView.Kind.ENTER -> listener.onEnter()
            KeysView.Kind.SYMBOLS -> keys.symbols = if (keys.symbols == 0) 1 else 0
            KeysView.Kind.PAGE -> keys.symbols = if (keys.symbols == 1) 2 else 1
            KeysView.Kind.SPACER -> {}
        }
    }

    private fun searchKey(k: KeysView.Key) {
        when (k.kind) {
            KeysView.Kind.TEXT -> { searchQuery.append(k.out); updateSearch() }
            KeysView.Kind.SPACE -> { searchQuery.append(' '); updateSearch() }
            KeysView.Kind.BACKSPACE -> if (searchQuery.isNotEmpty()) { searchQuery.setLength(searchQuery.length - 1); updateSearch() } else endSearch()
            KeysView.Kind.ENTER -> { endSearch(); openEmoji() }
            KeysView.Kind.SHIFT -> onShift()
            KeysView.Kind.SYMBOLS -> keys.symbols = if (keys.symbols == 0) 1 else 0
            KeysView.Kind.PAGE -> keys.symbols = if (keys.symbols == 1) 2 else 1
            else -> {}
        }
    }

    /** A long-press extra replaces the letter that was typed on finger down. */
    override fun onAlt(text: String) {
        if (searching) {
            if (searchQuery.isNotEmpty()) searchQuery.setLength(searchQuery.length - 1)
            searchQuery.append(text); updateSearch(); return
        }
        listener.onBackspace()
        listener.onText(text)
    }

    override fun onCursor(steps: Int) = listener.onCursorMove(steps)
    override fun onTick() = haptics.light(this)
    override fun onLongPress() = haptics.long(this)

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

    /** Switch keyboard (Sing / ខ្មែរ / English): the keys cross-fade in place. */
    fun setMode(mode: Mode) {
        modeTool.text = when (mode) { Mode.SING -> "Sing → ខ្មែរ"; Mode.KHMER -> "ខ្មែរ"; Mode.ENGLISH -> "English" }
        if (keys.mode == mode) return
        keys.mode = mode
        keys.alpha = 0.35f
        keys.animate().alpha(1f).setDuration(140).start()
    }

    /** ⏎ shows what the app's enter key does: ➤ send, 🔍 search, → go / next, ✓ done. */
    fun setEnterLabel(label: String) { keys.enterLabel = label }

    /** Back to letters when a new text box opens (panels closed, no search). */
    fun resetPanels() {
        if (searching) { searching = false; searchQuery.setLength(0) }
        for (p in listOf<View>(top5, emojiPanel, clipPanel, dictPanel)) p.visibility = GONE
        if (keys.symbols != 0) keys.symbols = 0
    }

    companion object {
        const val KEY_PREVIEW = "key_preview"       // Setup app switches
        const val KEY_SOUND = "key_sound"
        const val KEY_HINTS = "key_hints"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_HEIGHT = "kb_height"          // 0.9 / 1.0 / 1.12
        const val KEY_ONE_HAND = "one_hand"
        const val KEY_EMOJI_RECENT = "emoji_recent"
    }
}

/**
 * Key vibration, set in the Akkhara app: 0 = off, 1 = light (default), 2 = strong.
 * Light = the system keyboard-tap effect (respects the phone's touch-feedback setting);
 * Strong = a firmer click. Long-press actions get a slightly stronger effect.
 */
class Haptics(private val context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)

    private val level get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LEVEL, 1)

    fun key(view: View) {
        when (level) {
            0 -> return
            1 -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            else -> click(VibrationEffect.EFFECT_CLICK, 15, 120)
        }
    }

    fun light(view: View) {
        if (level == 0) return
        if (Build.VERSION.SDK_INT >= 27) view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun long(view: View) {
        if (level == 0) return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Used by the setup screen. */
    fun tick() {
        when (level) {
            0 -> return
            1 -> click(VibrationEffect.EFFECT_TICK, 8, 40)
            else -> click(VibrationEffect.EFFECT_CLICK, 15, 120)
        }
    }

    private fun click(effect: Int, ms: Long, amp: Int) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.vibrate(VibrationEffect.createPredefined(effect))
        else v.vibrate(VibrationEffect.createOneShot(ms, amp))
    }

    companion object {
        const val PREFS = "akkhara"
        const val KEY_LEVEL = "vibration"
    }
}
