package com.akkhara.keyboard

import android.content.Context

/**
 * Khmer → English mini dictionary (assets/dict/khmer_en.tsv, ~9k words, 0.6 MB).
 * Source: Wiktionary, CC BY-SA 4.0 (see assets/dict/NOTICE.txt); built by `akkhara build-dictionary`.
 *
 * Khmer has no spaces between words, so [lookup] cuts a sentence into dictionary words by
 * LONGEST MATCH from the left (ខ្ញុំចង់ទៅសាលារៀន → ខ្ញុំ · ចង់ · ទៅ · សាលារៀន / សាលា · រៀន);
 * Latin words (bro, ok) and unknown letters are skipped.
 * Loaded once, lazily, on a background thread (never while typing).
 */
class Dictionary private constructor(private val words: HashMap<String, String>, private val maxLen: Int) {

    data class Entry(val word: String, val meaning: String)

    /** Every dictionary word found in [text], in order (no duplicates). */
    fun lookup(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c !in 'ក'..'៿') { i++; continue }
            var found: String? = null
            val end = minOf(text.length, i + maxLen)
            for (j in end downTo i + 1) {                 // longest match first
                val w = text.substring(i, j)
                if (words.containsKey(w)) { found = w; break }
            }
            if (found == null) { i++; continue }
            if (seen.add(found)) out.add(Entry(found, words.getValue(found)))
            i += found.length
        }
        return out
    }

    fun meaning(word: String): String? = words[word]

    val size get() = words.size

    companion object {
        @Volatile private var instance: Dictionary? = null

        /** Load once (call from a background thread). */
        fun get(context: Context): Dictionary {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val map = HashMap<String, String>(12000)
                var maxLen = 1
                context.assets.open("dict/khmer_en.tsv").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val w = line.substring(0, tab)
                        map[w] = line.substring(tab + 1)
                        if (w.length > maxLen) maxLen = w.length
                    }
                }
                return Dictionary(map, minOf(maxLen, 24)).also { instance = it }
            }
        }
    }
}
