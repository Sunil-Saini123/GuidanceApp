package com.example.floatingassistant.intent

import android.content.Context
import java.util.Locale

/**
 * Minimal WordPiece tokenizer compatible with the bert-base-uncased vocabulary
 * used by `sentence-transformers/all-MiniLM-L6-v2`.
 *
 * Loads `assets/minilm/vocab.txt` (one token per line; line index == token id)
 * and implements: lowercase → whitespace/punctuation splitting → greedy
 * longest-match-first WordPiece segmentation. This mirrors HuggingFace's
 * `BertTokenizer` closely enough to produce correct sentence embeddings,
 * without pulling in a full tokenizers library.
 */
class Tokenizer(context: Context, assetPath: String = "minilm/vocab.txt") {

    companion object {
        private const val MAX_SEQ_LEN = 128
    }

    data class Encoding(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    private val vocab: Map<String, Int>
    private val clsId: Int
    private val sepId: Int
    private val padId: Int
    private val unkId: Int

    init {
        val map = HashMap<String, Int>()
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, line -> map[line.trim()] = index }
        }
        vocab = map
        clsId = vocab["[CLS]"] ?: error("vocab.txt is missing the [CLS] token")
        sepId = vocab["[SEP]"] ?: error("vocab.txt is missing the [SEP] token")
        unkId = vocab["[UNK]"] ?: error("vocab.txt is missing the [UNK] token")
        padId = vocab["[PAD]"] ?: 0
    }

    /** Encodes [text] into fixed-length (padded/truncated to [MAX_SEQ_LEN]) BERT-style inputs. */
    fun encode(text: String): Encoding {
        val wordpieceIds = wordpieceTokenize(basicTokenize(text))

        val ids = ArrayList<Int>(minOf(wordpieceIds.size, MAX_SEQ_LEN - 2) + 2)
        ids += clsId
        ids += wordpieceIds.take(MAX_SEQ_LEN - 2)
        ids += sepId

        val inputIds = LongArray(MAX_SEQ_LEN)
        val attentionMask = LongArray(MAX_SEQ_LEN)
        val tokenTypeIds = LongArray(MAX_SEQ_LEN) // single-sentence input → all zeros

        for (i in 0 until MAX_SEQ_LEN) {
            if (i < ids.size) {
                inputIds[i] = ids[i].toLong()
                attentionMask[i] = 1L
            } else {
                inputIds[i] = padId.toLong()
                attentionMask[i] = 0L
            }
        }
        return Encoding(inputIds, attentionMask, tokenTypeIds)
    }

    /** Lowercases and splits on whitespace, isolating ASCII punctuation as its own tokens. */
    private fun basicTokenize(text: String): List<String> {
        val lower = text.lowercase(Locale.US)
        val sb = StringBuilder()
        for (ch in lower) {
            if (isPunctuation(ch)) {
                sb.append(' ').append(ch).append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        return (code in 33..47) || (code in 58..64) || (code in 91..96) || (code in 123..126)
    }

    /** Greedy longest-match-first WordPiece segmentation, falling back to [UNK] per whole word. */
    private fun wordpieceTokenize(words: List<String>): List<Int> {
        val output = ArrayList<Int>()
        for (word in words) {
            if (word.length > 200) {
                output += unkId
                continue
            }
            var start = 0
            val subTokenIds = ArrayList<Int>()
            var isUnknown = false
            while (start < word.length) {
                var end = word.length
                var matchedId: Int? = null
                while (start < end) {
                    val substr = if (start > 0) "##${word.substring(start, end)}" else word.substring(start, end)
                    val id = vocab[substr]
                    if (id != null) {
                        matchedId = id
                        break
                    }
                    end--
                }
                if (matchedId == null) {
                    isUnknown = true
                    break
                }
                subTokenIds += matchedId
                start = end
            }
            output += if (isUnknown) listOf(unkId) else subTokenIds
        }
        return output
    }
}
