package com.akkhara.keyboard

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.util.Log
import java.util.concurrent.Executors

/**
 * The Akkhara keyboard.
 *
 * How typing works (Khmer mode "ខ"):
 *   - The Latin you type stays in the text box, underlined ("composing text"): `anh tunh`.
 *     Nothing becomes Khmer until YOU choose it (user decision 2026-11-06: be sure what goes in).
 *   - After every key the model runs (~10 ms on a modern phone); the Khmer top-5 appears in the
 *     candidate bar: “your Latin” (keep as typed) + ការ​ ... Tap one → it replaces the Latin.
 *   - ⏎ , . insert the #1.
 *   - EDIT ANY WORD: tap inside the underlined Latin to move the cursor there (e.g. onto "anh"
 *     in "anh tunh"), then type / backspace. Letters map 1:1 to the buffer, so the rest stays.
 *   - Spaces stay inside the buffer, so the model sees whole sentences (better guesses).
 * English mode "EN": keys type Latin directly, like a normal keyboard.
 */
class AkkharaIME : InputMethodService(), KeyboardView.Listener {

    companion object {
        private const val TAG = "AkkharaIME"   // logcat tag: timings only, never the typed text
        private const val TOP5_DELAY_MS = 200L
        private val PUNCT = setOf(",", ".", "?", "\"", "!")
    }

    private val worker = Executors.newSingleThreadExecutor()  // model runs here, never on the UI thread
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: LiveEngine? = null
    private var keyboard: KeyboardView? = null

    private val buffer = StringBuilder()
    private var cursor = 0          // cursor position INSIDE the buffer (0..buffer.length)
    private var composeStart = -1   // where the underlined Latin starts in the text box
    private var mode = Mode.SING    // Sing Khmer / ខ្មែរ (NiDA) / English — globe key cycles
    private val sing get() = mode == Mode.SING
    private var candidates: List<String> = emptyList()
    private var candidatesFor = ""          // the buffer text the candidates belong to
    private var requestId = 0                // newer keystrokes make older results useless

    override fun onCreate() {
        super.onCreate()
        mode = Mode.entries[getSharedPreferences(Haptics.PREFS, MODE_PRIVATE).getInt("mode", 0).coerceIn(0, 2)]
        worker.execute {
            val t = System.currentTimeMillis()
            val e = LiveEngine(MiniModel(this))
            e.quick("nh jg tv sala rean")  // warm-up: the first run is slower
            engine = e
            main.post { if (buffer.isEmpty() && sing) keyboard?.setStatus("Sing Khmer · ready (${System.currentTimeMillis() - t} ms to load)") }
        }
    }

    override fun onCreateInputView(): View {
        val kb = KeyboardView(this, this)
        kb.setMode(mode)
        if (engine == null && sing) kb.setStatus("loading model…")
        keyboard = kb
        return kb
    }

    /** The keyboard is shown for a text box: label ⏎ like the app's action (➤ in chats). */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val label = if (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) "⏎" else when (action) {
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "⏎"
        }
        keyboard?.setEnterLabel(label)
        keyboard?.setMode(mode)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        reset()
    }

    override fun onFinishInput() {
        currentInputConnection?.finishComposingText()
        reset()
        super.onFinishInput()
    }

    /** Cursor moved. Inside the underlined Latin → edit there. Outside → leave the Latin, start fresh. */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (buffer.isEmpty()) return
        if (candidatesStart >= 0) composeStart = candidatesStart
        val inside = candidatesStart >= 0 && newSelStart == newSelEnd &&
            newSelStart in candidatesStart..candidatesEnd
        if (inside) {
            cursor = (newSelStart - candidatesStart).coerceIn(0, buffer.length)  // tapped onto a word
        } else {
            currentInputConnection?.finishComposingText()  // the Latin stays as typed
            reset()
        }
    }

    private fun reset() {
        buffer.setLength(0)
        cursor = 0
        composeStart = -1
        candidates = emptyList()
        candidatesFor = ""
        requestId++
        keyboard?.setStatus(statusText())
    }

    private fun statusText() = when (mode) {
        Mode.SING -> if (engine == null) "loading model…" else "Sing Khmer · type Latin, tap the Khmer word up here"
        Mode.KHMER -> "ខ្មែរ keyboard (NiDA) · ⇧ for more letters"
        Mode.ENGLISH -> "English · letters go in as typed"
    }

    // ---- model in the background ------------------------------------------------------------
    // Smooth typing:
    //  - every key: the fast #1 (greedy) right away
    //  - the slower top-5 only after a short PAUSE in typing (TOP5_DELAY_MS)
    //  - any work for an old keystroke stops as soon as a new key arrives (cancelled())
    private val top5Delay = Runnable { requestTop5() }

    private fun predict() {
        val text = buffer.toString()
        val id = ++requestId
        main.removeCallbacks(top5Delay)
        if (text.isBlank()) { keyboard?.setStatus(statusText()); return }
        val e = engine ?: run { keyboard?.setStatus("loading model…"); return }
        worker.execute {
            if (id != requestId) return@execute          // already outdated: skip
            val t = System.nanoTime()
            val first = e.quick(text) { id != requestId } // fast #1, stops if outdated
            if (id != requestId) return@execute
            Log.d(TAG, "quick ${(System.nanoTime() - t) / 1_000_000} ms, ${text.length} chars")
            main.post {
                if (id == requestId) {
                    show(listOf(first), text)
                    main.postDelayed(top5Delay, TOP5_DELAY_MS)
                }
            }
        }
    }

    private fun requestTop5() {
        val text = buffer.toString()
        val id = requestId
        val e = engine ?: return
        if (text.isBlank()) return
        worker.execute {
            if (id != requestId) return@execute
            val t = System.nanoTime()
            val top = e.candidates(text) { id != requestId }
            if (id != requestId || top.isEmpty()) return@execute
            Log.d(TAG, "top5 ${(System.nanoTime() - t) / 1_000_000} ms, ${text.length} chars")
            main.post { if (id == requestId) show(top, text) }
        }
    }

    private fun show(items: List<String>, forText: String) {
        if (forText != buffer.toString()) return  // typed on meanwhile: a newer result comes
        candidates = items
        candidatesFor = forText
        keyboard?.setCandidates(items, forText)
    }

    /** Put Khmer into the text box (replacing the underlined Latin) and clear the buffer. */
    private fun commit(khmer: String) {
        currentInputConnection?.commitText(khmer, 1)
        buffer.setLength(0)
        cursor = 0
        composeStart = -1
        candidates = emptyList()
        candidatesFor = ""
        requestId++
        keyboard?.setStatus(statusText())
    }

    /** Commit the #1 for the current buffer (compute it now if the background result is not ready). */
    private fun commitBest() {
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        main.removeCallbacks(top5Delay)
        requestId++  // stop background work for this text: we answer it right here
        val best = if (candidatesFor == text && candidates.isNotEmpty()) candidates[0]
                   else engine?.quick(text) ?: text  // only when the #1 was not ready yet
        commit(best)
    }

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        // one batch = the app reports ONE cursor position afterwards (no jump to the end in between)
        ic.beginBatchEdit()
        ic.setComposingText(buffer, 1)
        // keep the cursor where the user is editing (not always at the end)
        if (cursor < buffer.length && composeStart >= 0) ic.setSelection(composeStart + cursor, composeStart + cursor)
        ic.endBatchEdit()
        predict()
    }

    // ---- keys -----------------------------------------------------------------------------------
    /** Any text key. Sing Khmer: Latin goes into the buffer (punctuation inserts the #1 first).
     *  ខ្មែរ / English: typed straight into the text box. */
    override fun onText(s: String) {
        val ic = currentInputConnection ?: return
        if (!sing) { ic.commitText(s, 1); return }
        if (s in PUNCT) { commitBest(); ic.commitText(s, 1); return }
        if (buffer.isEmpty()) composeStart = -1
        buffer.insert(cursor, s)
        cursor += s.length
        updateComposing()
    }

    override fun onSpace() {
        if (!sing || buffer.isEmpty()) { currentInputConnection?.commitText(" ", 1); return }
        buffer.insert(cursor, ' ')
        cursor++
        updateComposing()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (sing && buffer.isNotEmpty()) {
            if (cursor == 0) return                    // at the start of the Latin: nothing before it
            buffer.deleteCharAt(cursor - 1)
            cursor--
            if (buffer.isEmpty()) { ic.commitText("", 1); reset() } else updateComposing()
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    override fun onEnter() {
        if (sing && buffer.isNotEmpty()) { commitBest(); return }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)  // e.g. "Send" / "Search"
        } else {
            ic.commitText("\n", 1)
        }
    }

    /** Globe tap: Sing Khmer → ខ្មែរ → English → … (remembered for next time). */
    override fun onGlobe() {
        if (sing) commitBest()
        mode = mode.next()
        getSharedPreferences(Haptics.PREFS, MODE_PRIVATE).edit().putInt("mode", mode.ordinal).apply()
        keyboard?.setMode(mode)
        reset()
    }

    /** Globe long-press: go to another phone keyboard (e.g. Samsung). */
    override fun onGlobeLong() {
        if (sing) commitBest()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToNextInputMethod(false)) showPicker()
        } else showPicker()
    }

    private fun showPicker() =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()

    override fun onCandidate(index: Int) {
        candidates.getOrNull(index)?.let { commit(it) }
    }

    override fun onKeepLatin() {
        if (buffer.isNotEmpty()) commit(buffer.toString())
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
