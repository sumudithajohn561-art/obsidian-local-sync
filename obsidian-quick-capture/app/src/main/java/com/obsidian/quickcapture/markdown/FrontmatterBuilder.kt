package com.obsidian.quickcapture.markdown

import com.obsidian.quickcapture.content.ContentType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 构建 Markdown 文件的 YAML frontmatter
 *
 * 这是手机↔电脑之间的"协议"——格式固定，电脑端 Obsidian 插件
 * 根据这些元数据判断如何处理素材。
 *
 * 生成的 frontmatter 示例:
 * ```yaml
 * ---
 * title: "用AI做跨境电商选品"
 * source: "weixin.qq.com"
 * source_type: "link"
 * url: "https://mp.weixin.qq.com/s/xxxxx"
 * created: "2026-06-29-143052"
 * status: "pending"
 * ---
 * ```
 */
object FrontmatterBuilder {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    /**
     * 构建完整的 frontmatter YAML 块
     */
    fun build(
        title: String,
        sourceType: ContentType,
        source: String,
        url: String?,
        attachmentName: String?
    ): String {
        val now = LocalDateTime.now().format(dateFormatter)
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("title: \"${escapeYaml(title)}\"")
        sb.appendLine("source_type: \"${sourceType.frontmatterValue}\"")
        sb.appendLine("source: \"${escapeYaml(source)}\"")
        sb.appendLine("created: \"$now\"")
        sb.appendLine("status: \"pending\"")

        if (!url.isNullOrBlank()) {
            sb.appendLine("url: \"${escapeYaml(url)}\"")
        }

        if (!attachmentName.isNullOrBlank()) {
            sb.appendLine("attachment: \"${escapeYaml(attachmentName)}\"")
        }

        sb.appendLine("---")
        return sb.toString()
    }

    /**
     * 转义 YAML 字符串中的特殊字符
     */
    private fun escapeYaml(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
    }
}
