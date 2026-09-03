package com.example.floatingassistant

/**
 * CommandValidator
 *
 * Lightweight client-side pre-validation for natural-language commands entered
 * in the floating overlay panel.  Runs synchronously on the Main thread BEFORE
 * any network call is made so the user gets instant feedback for obvious mistakes.
 *
 * Rules (in order):
 *  1. Blank / whitespace-only  → "Please enter a command."
 *  2. Fewer than 3 characters  → "Command is too short. Try something like \"Call Mom\"."
 *  3. Purely numeric           → "That doesn't look like a command. Describe an action, e.g. \"Open Camera\"."
 *  4. No recognisable verb OR app-related keyword → heuristic rejection with hint.
 *
 * The heuristic in rule 4 is intentionally permissive: we only reject when the
 * input contains NO word from a broad set of common action verbs / app names.
 * False negatives (valid commands that pass through) are handled gracefully by
 * the AI model.  False positives (valid commands incorrectly rejected) are the
 * bigger UX problem, so we keep the list broad.
 */
sealed class ValidationResult {
    /** Input looks like a valid natural-language command — proceed to AI. */
    object Valid : ValidationResult()

    /** Input is clearly invalid. [reason] is shown directly to the user. */
    data class Invalid(val reason: String) : ValidationResult()
}

object CommandValidator {

    // ── Broad vocabulary of action verbs ──────────────────────────────────────
    private val ACTION_VERBS = setOf(
        "open", "close", "start", "stop", "launch", "turn", "enable", "disable",
        "toggle", "set", "call", "dial", "send", "message", "text", "email", "mail",
        "play", "pause", "resume", "skip", "next", "previous", "mute", "unmute",
        "volume", "navigate", "go", "search", "find", "check", "show", "view",
        "take", "capture", "record", "share", "post", "upload", "download",
        "delete", "remove", "add", "create", "make", "schedule",
        "read", "listen", "watch", "browse", "book", "order", "buy", "pay",
        "lock", "unlock", "connect", "disconnect", "sync", "update", "install",
        "alarm", "reminder", "note", "timer", "clock", "camera", "selfie", "photo",
        "want", "need", "like", "get", "put", "scan", "change", "switch", "dark",
        "mode", "brightness", "wifi", "bluetooth", "airplane", "hotspot"
    )

    // ── Well-known app names / domains ────────────────────────────────────────
    private val APP_KEYWORDS = setOf(
        "whatsapp", "instagram", "facebook", "twitter", "youtube", "spotify",
        "gmail", "google", "maps", "chrome", "safari", "telegram", "snapchat",
        "tiktok", "netflix", "amazon", "prime", "uber", "ola", "swiggy",
        "zomato", "paytm", "phonepe", "gpay", "phone", "dialer", "contacts",
        "camera", "gallery", "photos", "settings", "clock", "calendar",
        "calculator", "notes", "files", "music", "radio", "podcast", "zoom",
        "meet", "teams", "slack", "discord", "linkedin", "reddit", "pinterest",
        "flipkart", "myntra", "hotstar", "jiocinema", "cricbuzz", "truecaller",
        "maps", "weather", "news", "browser", "app"
    )

    /**
     * Validate [input] and return a [ValidationResult].
     */
    fun validate(input: String): ValidationResult {
        val trimmed = input.trim()

        // Rule 1: blank
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("Please enter a command.")
        }

        // Rule 2: too short
        if (trimmed.length < 3) {
            return ValidationResult.Invalid(
                "Command is too short. Try something like \"Call Mom\" or \"Open Camera\"."
            )
        }

        // Rule 3: purely numeric
        if (trimmed.all { it.isDigit() || it.isWhitespace() }) {
            return ValidationResult.Invalid(
                "That doesn't look like a command. Describe an action, e.g. \"Open Camera\"."
            )
        }

        // Rule 4: heuristic — must contain at least one known verb or app keyword
        val tokens = trimmed.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()

        val hasVerb = tokens.any { it in ACTION_VERBS }
        val hasApp  = tokens.any { it in APP_KEYWORDS }

        if (!hasVerb && !hasApp) {
            return ValidationResult.Invalid(
                "Please describe what you want to do. For example:\n" +
                "• \"Call Mom\"\n" +
                "• \"Send a WhatsApp message to John\"\n" +
                "• \"Play music on Spotify\""
            )
        }

        return ValidationResult.Valid
    }
}
