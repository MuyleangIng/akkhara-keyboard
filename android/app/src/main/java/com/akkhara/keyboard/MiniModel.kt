package com.akkhara.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * AKKHARA-MINI on the phone: Sing Khmer (Latin letters) → Khmer script.
 *
 * Same steps as akkhara/onnx_model.py on the computer:
 *   1. tokenizer: every character → an id (tokenizer.json), then </s>
 *   2. encoder.int8.onnx: ids → hidden states            (once per chunk)
 *   3. decoder.int8.onnx: hidden states + Khmer letters so far → scores for the next letter
 *      greedy = take the best letter each step (fast, #1 only)
 *      beam   = keep the 5 best partial sentences each step (top-5)
 */
class MiniModel(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val vocab: Map<String, Long>
    private val idToChar: Map<Long, String>
    val abbreviations: Map<String, String>

    private val pad = 0L  // T5: the decoder starts from <pad>
    private val eos = 1L  // </s>
    private val unk = 2L  // unknown character
    private val maxIn = 160
    private val maxOut = 160

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        fun asset(name: String) = context.assets.open("akkhara-mini/$name").use { it.readBytes() }
        encoder = env.createSession(asset("encoder.int8.onnx"), opts)
        decoder = env.createSession(asset("decoder.int8.onnx"), opts)

        // abbreviation → full Khmer name (#2 suggestion: mptc → ក្រសួងប្រៃសណីយ៍…), optional file
        abbreviations = try {
            String(asset("abbreviations.tsv"), Charsets.UTF_8).lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("abbreviation\t") }
                .mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }
                .toMap()
        } catch (e: Exception) { emptyMap() }

        val json = JSONObject(String(asset("tokenizer.json"), Charsets.UTF_8))
        val v = json.getJSONObject("model").getJSONObject("vocab")
        val map = HashMap<String, Long>()
        v.keys().forEach { k -> map[k] = v.getLong(k) }
        vocab = map
        idToChar = map.entries.associate { (k, id) -> id to k }
    }

    // ---- tokenizer -----------------------------------------------------------------------
    /** One id per Unicode code point (= the Python Split(".") pre-tokenizer), then </s>. */
    private fun tokenize(text: String): LongArray {
        val ids = ArrayList<Long>()
        var i = 0
        while (i < text.length && ids.size < maxIn - 1) {
            val cp = text.codePointAt(i)
            ids.add(vocab[String(Character.toChars(cp))] ?: unk)
            i += Character.charCount(cp)
        }
        ids.add(eos)
        return ids.toLongArray()
    }

    private fun detokenize(ids: List<Long>): String =
        ids.filter { it > unk }.joinToString("") { idToChar[it] ?: "" }

    // ---- model calls -----------------------------------------------------------------------
    private class Encoded(val hidden: FloatArray, val srcLen: Int, val dim: Int, val mask: LongArray)

    private fun encode(text: String): Encoded {
        val ids = tokenize(text)
        val mask = LongArray(ids.size) { 1L }
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskT ->
                encoder.run(mapOf("input_ids" to idT, "attention_mask" to maskT)).use { out ->
                    val t = out[0] as OnnxTensor
                    val dims = t.info.shape  // [1, srcLen, dim]
                    val buf = t.floatBuffer
                    val arr = FloatArray(buf.remaining()).also { buf.get(it) }
                    return Encoded(arr, dims[1].toInt(), dims[2].toInt(), mask)
                }
            }
        }
    }

    /** Log-probabilities of the next letter for each of the given prefixes (all for the same input). */
    private fun nextLogProbs(enc: Encoded, prefixes: List<List<Long>>): Array<FloatArray> {
        val b = prefixes.size
        val t = prefixes[0].size
        val dec = LongArray(b * t) { prefixes[it / t][it % t] }
        val hidden = FloatArray(b * enc.hidden.size).also { for (i in 0 until b) enc.hidden.copyInto(it, i * enc.hidden.size) }
        val mask = LongArray(b * enc.srcLen).also { for (i in 0 until b) enc.mask.copyInto(it, i * enc.srcLen) }
        OnnxTensor.createTensor(env, LongBuffer.wrap(dec), longArrayOf(b.toLong(), t.toLong())).use { decT ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden),
                longArrayOf(b.toLong(), enc.srcLen.toLong(), enc.dim.toLong())).use { hT ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(b.toLong(), enc.srcLen.toLong())).use { mT ->
                    decoder.run(mapOf("decoder_input_ids" to decT, "encoder_hidden_states" to hT,
                        "attention_mask" to mT)).use { out ->
                        val logits = (out[0] as OnnxTensor).floatBuffer  // [b, t, V]
                        val v = logits.capacity() / (b * t)
                        return Array(b) { bi ->
                            val row = FloatArray(v)
                            logits.position((bi * t + t - 1) * v)  // only the LAST position matters
                            logits.get(row)
                            logSoftmax(row)
                        }
                    }
                }
            }
        }
    }

    private fun logSoftmax(x: FloatArray): FloatArray {
        val m = x.max()
        var s = 0.0
        for (v in x) s += Math.exp((v - m).toDouble())
        val log = m + Math.log(s).toFloat()
        return FloatArray(x.size) { x[it] - log }
    }

    // ---- greedy: #1 only (typing) ---------------------------------------------------------
    fun greedy(text: String, cancelled: () -> Boolean = { false }): String {
        if (text.isBlank()) return ""
        val enc = encode(text)
        val seq = mutableListOf(pad)
        repeat(maxOut) {
            if (cancelled()) return detokenize(seq)  // a newer keystroke arrived: stop early
            val lp = nextLogProbs(enc, listOf(seq))[0]
            var best = 0
            for (i in lp.indices) if (lp[i] > lp[best]) best = i
            if (best.toLong() == eos) return detokenize(seq)
            seq.add(best.toLong())
        }
        return detokenize(seq)
    }

    // ---- beam search: top-k ----------------------------------------------------------------
    /** Top-k (khmer, score). Score = sum of log-probs / length, like transformers' generate. */
    fun topK(text: String, k: Int = 5, beams: Int = 5, cancelled: () -> Boolean = { false }): List<Pair<String, Float>> {
        if (text.isBlank()) return emptyList()
        val enc = encode(text)
        var seqs = listOf(listOf(pad))
        var scores = floatArrayOf(0f)
        val finished = ArrayList<Pair<Float, List<Long>>>()
        for (step in 0 until maxOut) {
            if (cancelled()) return emptyList()  // a newer keystroke arrived: stop early
            val lp = nextLogProbs(enc, seqs)
            // all (beam, letter) pairs, best first; keep the top 2×beams
            val cand = ArrayList<Triple<Float, Int, Int>>()
            for (bi in seqs.indices) for (tok in lp[bi].indices) cand.add(Triple(scores[bi] + lp[bi][tok], bi, tok))
            cand.sortByDescending { it.first }
            val newSeqs = ArrayList<List<Long>>()
            val newScores = ArrayList<Float>()
            for ((s, bi, tok) in cand.take(2 * beams)) {
                val seq = seqs[bi] + tok.toLong()
                if (tok.toLong() == eos) finished.add(Pair(s / (seq.size - 1), seq))
                else { newSeqs.add(seq); newScores.add(s) }
                if (newSeqs.size == beams) break
            }
            if (finished.size >= k && newScores.isNotEmpty()) {
                val worstKept = finished.map { it.first }.sortedDescending()[k - 1]
                if (newScores.max() / (step + 1) < worstKept) break
            }
            if (newSeqs.isEmpty()) break
            seqs = newSeqs
            scores = newScores.toFloatArray()
        }
        if (finished.size < k) seqs.forEachIndexed { i, q -> finished.add(Pair(scores[i] / maxOf(1, q.size - 1), q)) }
        val out = ArrayList<Pair<String, Float>>()
        val seen = HashSet<String>()
        for ((s, q) in finished.sortedByDescending { it.first }) {
            val text = detokenize(q)
            if (seen.add(text)) out.add(Pair(text, s))
            if (out.size == k) break
        }
        return out
    }
}
