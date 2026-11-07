package com.akkhara.keyboard

import android.content.Context

/**
 * Akkhara's offline dictionary, two files in assets/dict/ (built by `akkhara build-dictionary`):
 *
 *   khmer_en.tsv   Khmer word → English meaning (~9k words, Wiktionary, CC BY-SA 4.0, see NOTICE.txt)
 *   spellings.tsv  Khmer word → its Sing Khmer spellings (~22k words; the user's own spellings first)
 *
 * So every way of writing a word finds it: `sur sdei`, `surdei`, `sous dey` → សួស្តី.
 *
 * Khmer has no spaces between words, so [lookup] cuts a sentence into dictionary words by
 * LONGEST MATCH from the left (ខ្ញុំចង់ទៅសាលារៀន → ខ្ញុំ · ចង់ · ទៅ · សាលារៀន).
 * Loaded once, lazily, on a background thread (never while typing).
 */
class Dictionary private constructor(
    private val meanings: HashMap<String, String>,
    private val spellings: HashMap<String, List<String>>,      // Khmer → Latin spellings
    private val byKey: HashMap<String, MutableList<String>>,    // Latin key (no spaces) → Khmer words
    private val maxLen: Int,
) {

    data class Entry(val word: String, val meaning: String?, val spellings: List<String>)

    private fun entry(w: String) = Entry(w, meanings[w], spellings[w] ?: emptyList())

    /** Every known Khmer word in [text], in order (no duplicates). */
    fun lookup(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] !in 'ក'..'៿') { i++; continue }
            var found: String? = null
            val end = minOf(text.length, i + maxLen)
            for (j in end downTo i + 1) {                 // longest match first
                val w = text.substring(i, j)
                if (meanings.containsKey(w) || spellings.containsKey(w)) { found = w; break }
            }
            if (found == null) { i++; continue }
            if (seen.add(found)) out.add(entry(found))
            i += found.length
        }
        return out
    }

    /**
     * Khmer words written as [latin] in Sing Khmer. Spaces do not matter (`sur sdei` = `sursdei`).
     * No exact match → spellings one letter away (`surdei` ≈ `sursdei`). Best match first.
     */
    fun byLatin(latin: String): List<Entry> {
        val q = key(latin)
        if (q.isEmpty()) return emptyList()
        byKey[q]?.let { return it.map(::entry) }
        if (q.length < 4) return emptyList()
        val out = LinkedHashSet<String>()
        for ((k, words) in byKey) {
            if (k[0] != q[0] || kotlin.math.abs(k.length - q.length) > 1) continue
            if (oneEdit(k, q)) out.addAll(words)
            if (out.size >= 6) break
        }
        return out.map(::entry)
    }

    fun meaning(word: String): String? = meanings[word]

    companion object {
        @Volatile private var instance: Dictionary? = null

        private fun key(s: String) = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '\'' }

        /** True when a and b differ by exactly one inserted, deleted or changed letter. */
        private fun oneEdit(a: String, b: String): Boolean {
            if (a == b) return false
            if (a.length == b.length) return a.indices.count { a[it] != b[it] } == 1
            val (s, l) = if (a.length < b.length) a to b else b to a
            var i = 0
            var j = 0
            var skipped = false
            while (i < s.length && j < l.length) {
                if (s[i] == l[j]) { i++; j++ } else { if (skipped) return false; skipped = true; j++ }
            }
            return true
        }

        /** Load once (call from a background thread). */
        fun get(context: Context): Dictionary {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val meanings = HashMap<String, String>(12000)
                val spellings = HashMap<String, List<String>>(30000)
                val byKey = HashMap<String, MutableList<String>>(160000)
                var maxLen = 1
                context.assets.open("dict/khmer_en.tsv").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val w = line.substring(0, tab)
                        meanings[w] = line.substring(tab + 1)
                        if (w.length > maxLen) maxLen = w.length
                    }
                }
                context.assets.open("dict/spellings.tsv").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val w = line.substring(0, tab)
                        val list = line.substring(tab + 1).split(',')
                        spellings[w] = list
                        if (w.length > maxLen) maxLen = w.length
                        for (sp in list) byKey.getOrPut(key(sp)) { ArrayList(2) }.let { if (w !in it) it.add(w) }
                    }
                }
                return Dictionary(meanings, spellings, byKey, minOf(maxLen, 24)).also { instance = it }
            }
        }
    }
}
