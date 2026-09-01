package com.example.floatingassistant.intent

/** A single supported intent plus the example phrasings used to recognize it. */
data class Intent(val name: String, val examples: List<String>)

/** Result of classifying one user query. */
data class ClassificationResult(
    val intent: String,
    val confidence: Float
)
