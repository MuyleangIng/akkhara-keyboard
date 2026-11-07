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
        return top(cs.last(), cancelled).map { join(prefix, it.first) }
    }

    companion object {
        private fun isLatin(c: Char) = c.code < 128 && c.isLetter()

        /** No space between Khmer pieces; one space next to a Latin word (training target rule). */
        fun join(left: String, right: String): String = when {
            left.isEmpty() -> right
            right.isEmpty() -> left
            isLatin(left.last()) || isLatin(right.first()) -> "$left $right"
            else -> left + right
        }
    }
}
