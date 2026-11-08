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
        val cs = chunks(text)
        if (cs.isEmpty()) return ""
        val last = cs.last()
        val known = synchronized(this) { cache[last] }
        val lastKhmer = known?.firstOrNull()?.first ?: model.greedy(last, cancelled)
        return join(prefixOf(cs), lastKhmer)
    }

    /** Top-5 whole-text candidates: best finished chunks + each candidate of the last chunk. */
    fun candidates(text: String, cancelled: () -> Boolean = { false }): List<String> {
        val cs = chunks(text)
        if (cs.isEmpty()) return emptyList()
        val prefix = prefixOf(cs)
        return expand(top(cs.last(), cancelled).map { join(prefix, it.first) }, model.abbreviations)
    }

    companion object {
        private fun isLatin(c: Char) = c.code < 128 && c.isLetter()
        private const val TRAIL = ".,!?។៕"

        /** The sentence with each known abbreviation replaced by its full Khmer name, or null. */
        fun expandText(text: String, table: Map<String, String>): String? {
            var out = ""
            var hit = false
            for (tok in text.split(' ')) {
                val core = tok.trimEnd { it in TRAIL }
                val full = if (core.isNotEmpty()) table[core.lowercase()] else null
                if (full != null) { hit = true; out = join(out, full) + tok.substring(core.length) }
                else if (tok.isNotEmpty()) out = join(out, tok)
            }
            return if (hit) out else null
        }

        /** Abbreviation kept as typed at #1 (mptc) → the same sentence with the full Khmer name at #2. */
        fun expand(cands: List<String>, table: Map<String, String>): List<String> {
            if (cands.isEmpty() || table.isEmpty()) return cands
            val full = expandText(cands[0], table) ?: return cands
            if (full in cands) return cands
            return (listOf(cands[0], full) + cands.drop(1).filter { it != full }).take(maxOf(cands.size, 2))
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
