package com.obsidian.quickcapture.content

enum class ContentType(val frontmatterValue: String) {
    PLAINTEXT("plain"),
    LINK("link"),
    IMAGE("image"),
    VIDEO_LINK("video"),
    FILE("file"),
    UNKNOWN("unknown");
}

object ContentClassifier {

    fun classify(mimeType: String?, contentText: String?): ContentType {
        if (mimeType == null) return ContentType.UNKNOWN

        return when {
            mimeType.startsWith("text/") -> {
                if (contentText != null && containsUrl(contentText)) {
                    ContentType.LINK
                } else {
                    ContentType.PLAINTEXT
                }
            }
            mimeType.startsWith("image/") -> ContentType.IMAGE
            mimeType.startsWith("video/") -> ContentType.VIDEO_LINK
            else -> ContentType.FILE
        }
    }

    private fun containsUrl(text: String): Boolean {
        return text.contains(Regex("https?://[^\\s]+"))
    }

    fun extractSource(text: String?): String {
        if (text == null) return "unknown"
        val urlPattern = Regex("https?://([^/\\s]+)")
        val match = urlPattern.find(text)
        return match?.groupValues?.get(1) ?: "local"
    }
}
