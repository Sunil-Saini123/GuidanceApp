package com.example.floatingassistant.intent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Wraps the ONNX Runtime session for `sentence-transformers/all-MiniLM-L6-v2`
 * (quantized, ARM64: `model_qint8_arm64.onnx`). Produces one 384-dim,
 * L2-normalized sentence embedding per input string using mean pooling over
 * token embeddings, masked by attention — the pooling operation
 * sentence-transformers uses for this model.
 *
 * The session is created once in the constructor and reused for every
 * [embed] call — construct exactly one instance per process
 * ([IntentClassifier] owns the singleton) and [close] it only on shutdown.
 */
class MiniLMEmbedder(
    context: Context,
    modelAssetPath: String = "minilm/model_qint8_arm64.onnx"
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer = Tokenizer(context)

    init {
        val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    /** Returns a 384-dim, L2-normalized sentence embedding for [text]. */
    fun embed(text: String): FloatArray {
        val encoding = tokenizer.encode(text)
        val shape = longArrayOf(1, encoding.inputIds.size.toLong())

        OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.inputIds), shape).use { inputIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.attentionMask), shape).use { attentionMask ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.tokenTypeIds), shape).use { tokenTypeIds ->

                    val inputs = linkedMapOf(
                        "input_ids" to inputIds,
                        "attention_mask" to attentionMask,
                        "token_type_ids" to tokenTypeIds
                    )
                    // Some ONNX exports omit token_type_ids from the input
                    // signature — drop it if the model doesn't declare it.
                    if (!session.inputNames.contains("token_type_ids")) {
                        inputs.remove("token_type_ids")
                    }

                    session.run(inputs).use { results ->
                        val tokenEmbeddings = firstOutputAsTokenEmbeddings(results)
                        val pooled = meanPool(tokenEmbeddings, encoding.attentionMask)
                        return l2Normalize(pooled)
                    }
                }
            }
        }
    }

    /**
     * Reads the [seq_len, hidden] token-embedding matrix out of the model
     * output. Prefers an output literally named "last_hidden_state" (the
     * standard sentence-transformers ONNX export name); falls back to
     * whatever the first declared output is, since export tooling names
     * this differently ("token_embeddings", etc.) depending on version.
     */
    private fun firstOutputAsTokenEmbeddings(results: OrtSession.Result): Array<FloatArray> {
        val named = runCatching { results.get("last_hidden_state") }.getOrNull()
        val onnxValue = if (named != null && named.isPresent) named.get() else results.iterator().next().value

        @Suppress("UNCHECKED_CAST")
        val batch = onnxValue.value as Array<Array<FloatArray>> // [batch=1, seqLen, hidden]
        return batch[0]
    }

    private fun meanPool(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val hidden = tokenEmbeddings[0].size
        val summed = FloatArray(hidden)
        var maskSum = 0f
        for (i in tokenEmbeddings.indices) {
            val m = attentionMask[i].toFloat()
            if (m == 0f) continue
            maskSum += m
            val vec = tokenEmbeddings[i]
            for (j in 0 until hidden) {
                summed[j] += vec[j] * m
            }
        }
        val denom = if (maskSum > 0f) maskSum else 1f
        for (j in 0 until hidden) summed[j] /= denom
        return summed
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var normSq = 0f
        for (v in vec) normSq += v * v
        val norm = sqrt(normSq).let { if (it > 1e-9f) it else 1e-9f }
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }

    override fun close() {
        session.close()
    }
}
