package com.example.floatingassistant

/**
 * FNV-1a (Fowler-Noll-Vo alternate) — 64-bit variant.
 *
 * Why FNV-1a:
 *  - Extremely fast: one XOR + one multiply per byte, no table lookups.
 *  - Good avalanche on short strings (resource IDs, class names, UI labels).
 *  - Pure Kotlin — no JNI, no external library.
 *  - 64-bit output keeps collision probability negligible for typical UI trees
 *    (thousands of nodes per session).
 *
 * Algorithm (per byte):
 *   hash = hash XOR byte          ← XOR before multiply → "alternate" variant
 *   hash = hash * FNV_PRIME_64
 *
 * Constants (from the FNV spec, https://www.isthe.com/chongo/tech/comp/fnv/):
 *   Offset basis : 14695981039346656037  (0xcbf29ce484222325)
 *   Prime        : 1099511628211         (0x00000100000001B3)
 *
 * Note on sign: Kotlin Long is signed, so the offset basis is represented as its
 * two's-complement equivalent (-3750763034362895579L).  The arithmetic is identical
 * because Kotlin Long wraps on overflow, which is exactly what FNV intends.
 */
object FnvHash {

    // 0xcbf29ce484222325 as signed Long
    private const val OFFSET_BASIS = -3750763034362895579L

    // 0x00000100000001B3 — fits in a positive Long
    private const val PRIME = 1099511628211L

    /**
     * Hash a [String] using FNV-1a 64-bit.
     *
     * The string is encoded as UTF-8 before hashing so multi-byte characters
     * (e.g. emoji in UI labels) are handled correctly.
     *
     * @return A 64-bit hash value. The same input always produces the same output
     *         within a session (not persisted — no stability guarantee across JVM restarts).
     */
    fun hash64(input: String): Long {
        var hash = OFFSET_BASIS
        for (byte in input.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)  // mask to unsigned byte
            hash *= PRIME
        }
        return hash
    }

    /**
     * Hash the concatenation of multiple strings separated by a pipe delimiter.
     *
     * This is a convenience for building composite keys without creating an
     * intermediate String allocation on the hot path.
     *
     * Example:
     *   FnvHash.hash64of("com.android.settings:id/title", "Wi-Fi", "TextView")
     */
    fun hash64of(vararg parts: String): Long {
        var hash = OFFSET_BASIS
        parts.forEachIndexed { index, part ->
            // Hash each part's bytes
            for (byte in part.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= PRIME
            }
            // Hash a separator byte between parts (except after the last)
            if (index < parts.size - 1) {
                hash = hash xor 0x7CL  // '|' = 0x7C
                hash *= PRIME
            }
        }
        return hash
    }
}
