package com.bytasq.aiclipper

class LocalClipMetadataEngine {
    fun generate(
        transcriptText: String,
        suggestedTitle: String,
        hook: String,
        reason: String
    ): ClipMetadata {
        val cleanTitle = suggestedTitle.replace(Regex("\\s+"), " ").trim()
            .ifBlank { "Videodan önemli bir bilgi" }.take(90)
        val firstSentence = transcriptText.replace(Regex("\\s+"), " ").trim()
            .split(Regex("(?<=[.!?])\\s+")).firstOrNull()?.take(180).orEmpty()
        val description = (firstSentence.ifBlank { hook.take(180) } +
            "\n\nBu Short, kaynak videodaki önemli noktaların özgün bir özetidir.").trim()

        val tags = linkedSetOf<String>()
        Regex("[\\p{L}\\p{N}]{4,}").findAll((cleanTitle + " " + transcriptText).lowercase())
            .map { it.value }
            .filter { it !in STOP_WORDS }
            .take(6)
            .forEach { tags += "#$it" }

        return ClipMetadata(cleanTitle, description, tags.toList())
    }

    private companion object {
        val STOP_WORDS = setOf(
            "video", "bunun", "olarak", "için", "olan", "gibi", "daha",
            "bir", "veya", "ama", "çok", "şöyle", "şimdi", "sonra", "nasıl"
        )
    }
}
