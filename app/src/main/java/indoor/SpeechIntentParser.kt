package com.example.pathfinderindoor.indoor

import java.util.Locale

enum class AppNavMode {
    NONE,
    INDOOR,
    OUTDOOR
}

object SpeechIntentParser {

    /**
     * Determines whether the user asked for Indoor or Outdoor mode.
     */
    fun parseMode(speech: String): AppNavMode {
        val clean = normalize(speech)
        return when {
            Regex("\\b(indoors?|inside|indoor navigation|in doors?|take me inside|take me indoors?|i want indoor navigation|i want inside|go inside|go indoors?)\\b")
                .containsMatchIn(clean) -> AppNavMode.INDOOR

            Regex("\\b(outdoors?|outside|outdoor navigation|out doors?|take me outside|take me outdoors?|i want outdoor navigation|i want to go outside|i want outside|go outside|go outdoors?)\\b")
                .containsMatchIn(clean) -> AppNavMode.OUTDOOR

            else -> AppNavMode.NONE
        }
    }

    /**
     * Global command for stopping, cancelling, or returning home.
     */
    fun isStopOrCancel(speech: String): Boolean {
        val clean = normalize(speech)
        return Regex(
            "\\b(stop|stop navigation|cancel|exit|exit navigation|go back|back|quit|abort|return|end navigation|end|halt)\\b"
        ).containsMatchIn(clean)
    }

    /**
     * Command for starting or proceeding with navigation.
     */
    fun isStartCommand(speech: String): Boolean {
        val clean = normalize(speech)
        return Regex(
            "\\b(start|start navigation|yes start|let's go|lets go|begin|begin navigation|go|use this route|use route|proceed|use it)\\b"
        ).containsMatchIn(clean)
    }

    /**
     * Extracts a clean destination from conversational speech.
     * Examples:
     * - "Library" -> "library"
     * - "Take me to the library" -> "library"
     * - "I want to go to the library" -> "library"
     * - "Can you take me to the library?" -> "library"
     * - "I need directions to the library" -> "library"
     * - "Go to library" -> "library"
     * - "I want to go to Kochi" -> "kochi"
     * - "Kochi" -> "kochi"
     */
    fun extractDestination(speech: String): String {
        var text = normalize(speech)
        if (text.isBlank()) return ""

        // If the speech is a command, don't treat it as a destination
        if (isStopOrCancel(text) || parseMode(text) != AppNavMode.NONE) {
            return ""
        }
        if (Regex("^\\b(yes|no|yeah|nope|okay|ok|sure|cancel|stop)\\b$").matches(text)) {
            return ""
        }

        // Common conversational prefix patterns to strip
        val prefixPatterns = listOf(
            Regex("^(could|can|would) you please (take me to|guide me to|navigate to|show me directions to|show me|find)\\s+(the\\s+)?"),
            Regex("^(could|can|would) you (take me to|guide me to|navigate to|show me directions to|show me|find)\\s+(the\\s+)?"),
            Regex("^please (take me to|guide me to|navigate to|go to|head to|find)\\s+(the\\s+)?"),
            Regex("^(i want to|i'd like to|i would like to|i need to|i have to) (go to|reach|head to|navigate to|find)\\s+(the\\s+)?"),
            Regex("^(i need directions to|directions to|route to|how to reach|how to get to|show directions to|show me the way to)\\s+(the\\s+)?"),
            Regex("^(take me to|guide me to|navigate to|lead me to|direct me to)\\s+(the\\s+)?"),
            Regex("^(i am going to|i'm going to|heading to|heading for)\\s+(the\\s+)?"),
            Regex("^(go to|head to|walk to)\\s+(the\\s+)?"),
            Regex("^(take me|guide me)\\s+(the\\s+)?"),
            Regex("^to\\s+(the\\s+)?")
        )

        for (pattern in prefixPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                text = text.substring(match.range.last + 1).trim()
                break
            }
        }

        // Strip trailing polite filler words
        text = text.replace(Regex("\\b(please|now|thanks|thank you)\\b$"), "").trim()

        // Strip leading "the " if left
        if (text.startsWith("the ")) {
            text = text.removePrefix("the ").trim()
        }

        val cleaned = text.trimEnd('.', ',', '!', '?').trim()

        // If the remaining text is just a common keyword like "navigation", return empty
        if (cleaned == "navigation" || cleaned == "indoor" || cleaned == "outdoor") {
            return ""
        }

        return cleaned
    }

    fun isYes(text: String): Boolean {
        return Regex(
            "\\b(yes|yeah|yep|yup|sure|okay|ok|correct|right|start|proceed|use it|continue|let's go|lets go)\\b"
        ).containsMatchIn(normalize(text))
    }

    fun isNo(text: String): Boolean {
        return Regex(
            "\\b(no|nope|wrong|not correct|that's wrong|that is wrong|cancel|don't|do not)\\b"
        ).containsMatchIn(normalize(text))
    }

    fun wantsToSave(text: String): Boolean {
        return Regex(
            "\\b(save|save it|please save|yes|yeah|sure|okay|ok)\\b"
        ).containsMatchIn(normalize(text))
    }

    fun doesNotWantToSave(text: String): Boolean {
        return Regex(
            "\\b(don't save|do not save|no|nope|don't|do not|cancel)\\b"
        ).containsMatchIn(normalize(text))
    }

    fun isLandmarkReached(text: String): Boolean {
        return Regex(
            "\\b(reached|i reached it|i have reached|i'm here|im here|i am here|arrived|here|done|reached the landmark)\\b"
        ).containsMatchIn(normalize(text))
    }

    fun normalize(raw: String): String {
        return raw.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
