package com.obsidian.quickcapture.content

/**
 * 内容类型枚举
 *
 * 根据 Android Share Intent 的 MIME type 和实际数据判断分享内容的类型。
 * 这个类型会写入 Markdown 文件的 frontmatter 中，供 Obsidian 插件后续处理。
 */
enum class ContentType(val frontmatterValue: String) {
    /** 纯文本 - 用户直接分享的文字 */
    PLAINTEXT("plain"),

    /** 网页/文章链接 - 包含URL的分享 */
    LINK("link"),

    /** 图片 - image/* MIME type */
    IMAGE("image"),

    /** 视频链接 - 视频平台分享的URL */
    VIDEO_LINK("video"),

    /** 文件 - PDF/PPT/文档等 */
    FILE("file"),

    /** 无法识别的类型，原始保存 */
    UNKNOWN("unknown")
}

/**
 * 根据 Intent 的 MIME type 和内容判断分享类型
 *
 * 判断逻辑：
 * 1. 如果 MIME type 是 text/plain，进一步检查内容是否包含URL → LINK，否则 PLAINTEXT
 * 2. 如果 MIME type 以 image/ 开头 → IMAGE
 * 3. 如果 MIME type 以 video/ 开头 → VIDEO_LINK
 * 4. 其他 → FILE
 */
object ContentClassifier {

    /**
     * 判断内容的类型
     *
     * @param mimeType Intent 的 MIME type (如 "text/plain", "image/png")
     * @param contentText 提取到的文字内容(可能为null)
     * @return 识别出的内容类型
     */
    fun classify(mimeType: String?, contentText: String?): ContentType {
        if (mimeType == null) return ContentType.UNKNOWN

        return when {
            // text/plain: 进一步判断是纯文字还是链接
            mimeType.startsWith("text/") -> {
                if (contentText != null && containsUrl(contentText)) {
                    ContentType.LINK
                } else {
                    ContentType.PLAINTEXT
                }
            }

            // 图片
            mimeType.startsWith("image/") -> ContentType.IMAGE

            // 视频(通常是分享的视频链接)
            mimeType.startsWith("video/") -> ContentType.VIDEO_LINK

            // 其他文件类型
            else -> ContentType.FILE
        }
    }

    /**
     * 检测文本是否包含URL
     *
     * Android 分享文章时，通常会把链接附带在文字中。
     * 从微信分享公众号文章时，格式通常为 "标题\nURL"
     */
    private fun containsUrl(text: String): Boolean {
        return text.contains(Regex("https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+"))
    }

    /**
     * 从文字中提取域名作为来源标识
     *
     * 例如:
     *   "https://mp.weixin.qq.com/s/xxx" → "weixin.qq.com"
     *   "https://www.bilibili.com/video/BVxxx" → "bilibili.com"
     *   "这是一段纯文字" → "local"
     */
    fun extractSource(text: String?): String {
        if (text == null) return "unknown"

        val urlPattern = Regex("https?://([\\w\\-]+(\\.[\\w\\-]+)+)/?")
        val match = urlPattern.find(text)
        return match?.groupValues?.get(1) ?: "local"
    }
}
