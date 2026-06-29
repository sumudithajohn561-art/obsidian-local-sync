package com.obsidian.quickcapture.content

import android.content.Intent
import android.net.Uri

/**
 * 分享内容的数据载体
 *
 * @param title 提取的标题
 * @param body 主要内容(文字或URL)
 * @param url 如果有URL则为链接地址
 * @param mimeType 原始MIME类型
 * @param attachmentUri 如果有附件(图片/文件)，其URI
 * @param attachmentFileName 附件的文件名
 */
data class SharedContent(
    val title: String,
    val body: String,
    val url: String?,
    val mimeType: String?,
    val attachmentUri: Uri?,
    val attachmentFileName: String?
)

/**
 * 从 Intent 中提取分享内容
 *
 * 处理各种 App 的分享格式：
 * - 微信: EXTRA_TEXT 包含 "标题\nURL"
 * - 浏览器: EXTRA_TEXT 包含 URL, EXTRA_SUBJECT 包含标题
 * - 相册: EXTRA_STREAM 包含图片URI
 * - 文件管理器: EXTRA_STREAM 包含文件URI
 */
object ContentExtractor {

    fun extract(intent: Intent): SharedContent {
        val mimeType = intent.type

        // 获取文字内容
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val extraSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        val extraTitle = intent.getStringExtra(Intent.EXTRA_TITLE)?.trim()
        // 图片/文件附件
        val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        // 提取标题
        val title = extraSubject
            ?: extraTitle
            ?: extractTitleFromText(extraText)
            ?: generateDefaultTitle()

        // 提取URL
        val url = extractUrlFromText(extraText)

        // 提取正文(去重URL后的纯文字部分)
        val body = when {
            extraText != null -> extraText
            streamUri != null -> streamUri.toString()
            else -> ""
        }

        // 附件文件名
        val attachmentFileName = when {
            streamUri != null -> extractFileName(streamUri)
            else -> null
        }

        return SharedContent(
            title = sanitizeTitle(title),
            body = body,
            url = url,
            mimeType = mimeType,
            attachmentUri = streamUri,
            attachmentFileName = attachmentFileName
        )
    }

    /**
     * 从文字中提取标题
     *
     * 微信分享格式: "文章标题\nhttps://mp.weixin.qq.com/..."
     * 浏览器分享格式: "页面标题\nhttps://..."
     */
    private fun extractTitleFromText(text: String?): String? {
        if (text == null) return null

        // 取第一行非URL的内容作为标题
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val firstLine = lines.firstOrNull() ?: return null

        // 如果第一行是URL，则没有单独标题
        return if (firstLine.startsWith("http://") || firstLine.startsWith("https://")) {
            null
        } else {
            firstLine.take(100) // 标题最多100字符
        }
    }

    /**
     * 从文字中提取URL
     */
    private fun extractUrlFromText(text: String?): String? {
        if (text == null) return null
        val urlPattern = Regex(
            "https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])?"
        )
        return urlPattern.find(text)?.value
    }

    /**
     * 从URI推断文件名
     */
    private fun extractFileName(uri: Uri): String {
        return uri.lastPathSegment ?: "attachment"
    }

    /**
     * 清理标题中的非法文件名
     */
    private fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("[/\\\\:*?\"<>|]"), "-") // 替换非法文件名字符
            .replace(Regex("\\s+"), " ")              // 合并多余空白
            .trim()
            .take(80)                                 // 限制长度
    }

    /**
     * 生成默认标题
     */
    private fun generateDefaultTitle(): String {
        return "未命名素材"
    }
}
