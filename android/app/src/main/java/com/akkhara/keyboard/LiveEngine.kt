package com.akkhara.keyboard

/**
 * Live typing on top of MiniModel, same idea as akkhara/ime.py:
 *  - long text is cut into chunks of 6 words (the model was trained on 2–12-word pieces)
 *  - finished chunks are cached, so each keystroke only recomputes the LAST chunk
 *  - quick(): greedy #1 (fastest, while typing); candidates(): top-5 for the last chunk
 */
class LiveEngine(private val model: MiniModel, private val chunkWords: Int = 6) {
    private val cache = object : LinkedHashMap<String, List<Pair<String, Float>>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, Float>>>) = size > 500
    }

    private fun chunks(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.chunked(chunkWords).map { it.joinToString(" ") }

    /** Top-5 of one chunk (cached). A cancelled (empty) result is NOT cached. */
    private fun top(chunk: String, cancelled: () -> Boolean = { false }): List<Pair<String, Float>> {
        synchronized(this) { cache[chunk]?.let { return it } }
        val r = model.topK(chunk, cancelled = cancelled)
        if (r.isNotEmpty()) synchronized(this) { cache[chunk] = r }
        return r
    }

    private fun prefixOf(cs: List<String>): String =
        cs.dropLast(1).fold("") { acc, c -> join(acc, top(c).firstOrNull()?.first ?: "") }

    /** #1 only, greedy for the last chunk (uses the cached top-5 if it is already known). */
    fun quick(text: String, cancelled: () -> Boolean = { false }): String {
        withAbbreviations(text, model.abbreviations, 1) { listOf(quickPlain(it, cancelled)) }?.let { return it.firstOrNull() ?: "" }
        return quickPlain(text, cancelled)
    }

    private fun quickPlain(text: String, cancelled: () -> Boolean): String {
        val cs = chunks(text)
        if (cs.isEmpty()) return ""
        val last = cs.last()
        val known = synchronized(this) { cache[last] }
        val lastKhmer = known?.firstOrNull()?.first ?: model.greedy(last, cancelled)
        return join(prefixOf(cs), lastKhmer)
    }

    /** Top-5 whole-text candidates: best finished chunks + each candidate of the last chunk. */
    fun candidates(text: String, cancelled: () -> Boolean = { false }): List<String> {
        // known abbreviations (mptc, rupp …) never reach the model: as typed #1, full Khmer name #2
        withAbbreviations(text, model.abbreviations, 5) { candidatesPlain(it, cancelled) }?.let { return it }
        return candidatesPlain(text, cancelled)
    }

    private fun candidatesPlain(text: String, cancelled: () -> Boolean): List<String> {
        val cs = chunks(text)
        if (cs.isEmpty()) return emptyList()
        val prefix = prefixOf(cs)
        return top(cs.last(), cancelled).map { join(prefix, it.first) }
    }

    companion object {
        private fun isLatin(c: Char) = c.code < 128 && c.isLetter()
        private const val TRAIL = ".,!?។៕"

        /**
         * Top-k for text with known abbreviations, which are NOT sent to the model (alone, the model
         * turned "mptc" into a wrong ministry): #1 = other words from the model + abbreviation as typed,
         * #2 = the same with the full Khmer name, then other candidates of the last plain piece.
         * Null when the text has no known abbreviation. Same logic as akkhara/abbrev.py.
         */
        fun withAbbreviations(text: String, table: Map<String, String>, k: Int,
                              topFn: (String) -> List<String>): List<String>? {
            if (table.isEmpty()) return null
            val segs = ArrayList<Pair<Boolean, String>>()  // (isAbbreviation, text)
            val cur = ArrayList<String>()
            for (w in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val core = w.trimEnd { it in TRAIL }
                if (core.isNotEmpty() && table.containsKey(core.lowercase())) {
                    if (cur.isNotEmpty()) { segs.add(false to cur.joinToString(" ")); cur.clear() }
                    segs.add(true to w)
                } else cur.add(w)
            }
            if (cur.isNotEmpty()) segs.add(false to cur.joinToString(" "))
            if (segs.none { it.first }) return null
            val results = segs.map { (abbr, v) -> if (abbr) emptyList() else topFn(v) }
            val last = segs.indices.lastOrNull { !segs[it].first }
            fun build(alt: Int, full: Boolean): String {
                var out = ""
                segs.forEachIndexed { i, (abbr, v) ->
                    val piece = if (abbr) {
                        val core = v.trimEnd { it in TRAIL }
                        if (full) dropOverlap(out, table.getValue(core.lowercase()) + v.substring(core.length)) else v
                    } else {
                        val r = results[i]
                        val j = if (i == last) alt else 0
                        r.getOrNull(j) ?: r.firstOrNull() ?: v
                    }
                    out = join(out, piece)
                }
                return out
            }
            val cands = arrayListOf(build(0, false), build(0, true))
            if (last != null) for (j in 1 until results[last].size) {
                val c = build(j, false)
                if (c !in cands) cands.add(c)
                if (cands.size >= k) break
            }
            return cands.take(k)
        }

        /** 'theanakear aba': do not repeat ធនាគារ already written (≥ 3 letters). */
        private fun dropOverlap(out: String, full: String): String {
            for (n in full.length - 1 downTo 3) if (out.endsWith(full.substring(0, n))) return full.substring(n)
            return full
        }

        /** No space between Khmer pieces; one space next to a Latin word (training target rule). */
        fun join(left: String, right: String): String = when {
            left.isEmpty() -> right
            right.isEmpty() -> left
            isLatin(left.last()) || isLatin(right.first()) -> "$left $right"
            else -> left + right
        }
    }
}
