package com.example.floatingassistant.intent

import android.content.Context
import android.util.Log

/**
 * On-device semantic intent classifier built on `sentence-transformers/all-MiniLM-L6-v2`
 * (ONNX, quantized ARM64). Mirrors [com.example.floatingassistant.PathDatabase]'s
 * singleton/lazy-load style: the ONNX session is created once, and every
 * [IntentExamples] example sentence is embedded once at that time and cached.
 *
 * Matching strategy — **compare against every individual example embedding
 * and take the single best match**, rather than averaging each intent's
 * examples into one centroid. Centroids wash out intents with semantically
 * diverse phrasings (e.g. "make it quieter" vs. "lower the volume" pull a
 * centroid toward their midpoint, away from both). Per-example comparison
 * costs a few dozen extra dot products per query — negligible, since
 * everything is precomputed and dot products over 384-dim vectors are cheap.
 *
 * All public entry points are safe to call from any thread but do real
 * file I/O and inference, so callers should dispatch off the main thread
 * (see `FloatingOverlayService.handleSubmittedQuery`).
 */
object IntentClassifier {

    private const val TAG = "IntentClassifier"

    /** Returned when no example clears [CONFIDENCE_THRESHOLD], or the model isn't available. */
    const val UNKNOWN_INTENT = "UNKNOWN_INTENT"

    /**
     * Minimum cosine similarity required to accept a match. all-MiniLM-L6-v2
     * cosine scores for genuinely matching short phrases typically land
     * ~0.55–0.9; unrelated sentences are usually well under 0.4. Tune this
     * against your own example set if you see false positives/negatives.
     */
    private const val CONFIDENCE_THRESHOLD = 0.55f

    private data class ExampleEmbedding(val intent: String, val vector: FloatArray)

    private var embedder: MiniLMEmbedder? = null
    private val exampleEmbeddings = mutableListOf<ExampleEmbedding>()
    private var initialized = false
    private var initFailed = false

    /**
     * Loads the ONNX model + tokenizer and precomputes every intent example's
     * embedding. Idempotent — later calls are no-ops once it has succeeded
     * (or permanently failed once, to avoid retry-looping a broken asset on
     * every keystroke). Call this once, early (e.g. `Service.onCreate`), off
     * the main thread, so the first real query doesn't pay the load cost.
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized || initFailed) return
        try {
            val model = MiniLMEmbedder(context.applicationContext)
            val embeddings = mutableListOf<ExampleEmbedding>()
            for (intent in IntentExamples.ALL) {
                for (example in intent.examples) {
                    embeddings += ExampleEmbedding(intent.name, model.embed(example))
                }
            }
            embedder = model
            exampleEmbeddings.clear()
            exampleEmbeddings += embeddings
            initialized = true
            Log.i(
                TAG,
                "Initialized: ${IntentExamples.ALL.size} intents, ${exampleEmbeddings.size} example embeddings"
            )
        } catch (e: Exception) {
            // Missing/corrupt assets, bad ONNX file, unsupported ops, OOM, etc.
            // Fail closed: classify() will just report UNKNOWN_INTENT forever,
            // and callers fall back to their existing (non-ML) behavior.
            initFailed = true
            Log.e(TAG, "Model/tokenizer load failed — intent classification disabled: ${e.message}", e)
        }
    }

    /**
     * Classifies [text] against the precomputed intent examples. Never
     * throws: empty input, an uninitialized/failed model, or a mid-inference
     * error all just return [UNKNOWN_INTENT] with confidence 0.
     */
    fun classify(context: Context, text: String): ClassificationResult {
        val query = text.trim()
        if (query.isEmpty()) return ClassificationResult(UNKNOWN_INTENT, 0f)

        ensureInitialized(context)
        val model = embedder ?: return ClassificationResult(UNKNOWN_INTENT, 0f)

        val queryVector = try {
            model.embed(query)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed for query=\"$query\": ${e.message}", e)
            return ClassificationResult(UNKNOWN_INTENT, 0f)
        }

        var bestIntent = UNKNOWN_INTENT
        var bestScore = -1f
        for (example in exampleEmbeddings) {
            val score = cosineSimilarity(queryVector, example.vector)
            if (score > bestScore) {
                bestScore = score
                bestIntent = example.intent
            }
        }

        return if (bestScore >= CONFIDENCE_THRESHOLD) {
            ClassificationResult(bestIntent, bestScore)
        } else {
            ClassificationResult(UNKNOWN_INTENT, bestScore.coerceAtLeast(0f))
        }
    }

    /** Both vectors are already L2-normalized, so cosine similarity is just a dot product. */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
