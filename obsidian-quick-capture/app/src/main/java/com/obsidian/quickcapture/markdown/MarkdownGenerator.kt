package com.obsidian.quickcapture.markdown

import com.obsidian.quickcapture.content.ContentType
import com.obsidian.quickcapture.content.SharedContent

/**
 * 生成完整的 Markdown 文件内容
 *
 * 格式: frontmatter + 空行 + 正文
 * 不做任何内容处理——只保存原始素材。
 */
object MarkdownGenerator {

    /**
     * 根据分享内容和分类生成完整的 .md 文件内容
     */
    fun generate(content: SharedContent, contentType: ContentType, source: String): String {
        val frontmatter = FrontmatterBuilder.build(
            title = content.title,
            sourceType = contentType,
            source = source,
            url = content.url,
            attachmentName = content.attachmentFileName
        )

        val body = buildBody(content, contentType)

        return frontmatter + "\n" + body
    }

    /**
     * 根据内容类型构建正文
     */
    private fun buildBody(content: SharedContent, contentType: ContentType): String {
        return when (contentType) {
            ContentType.LINK -> buildLinkBody(content)
            ContentType.IMAGE -> buildImageBody(content)
            ContentType.VIDEO_LINK -> buildVideoBody(content)
            ContentType.FILE -> buildFileBody(content)
            ContentType.PLAINTEXT -> buildPlaintextBody(content)
            ContentType.UNKNOWN -> buildUnknownBody(content)
        }
    }

    /**
     * 链接类: 保留标题+URL
     */
    private fun buildLinkBody(content: SharedContent): String {
        val sb = StringBuilder()
        sb.appendLine("> ${content.title}")
        sb.appendLine()
        if (content.url != null) {
            sb.appendLine(content.url)
        } else {
            sb.appendLine(content.body)
        }
        return sb.toString()
    }

    /**
     * 图片类: Obsidian 图片嵌入语法
     */
    private fun buildImageBody(content: SharedContent): String {
        val sb = StringBuilder()
        sb.appendLine("> ${content.title}")
        sb.appendLine()
        // 图片附件会被复制到 attachments/ 子目录
        val imageName = content.attachmentFileName ?: "image"
        sb.appendLine("![[attachments/$imageName]]")
        return sb.toString()
    }

    /**
     * 视频链接: 保留链接+标题
     */
    private fun buildVideoBody(content: SharedContent): String {
        val sb = StringBuilder()
        sb.appendLine("> ${content.title}")
        sb.appendLine()
        if (content.url != null) {
            sb.appendLine("**视频链接:** ${content.url}")
        } else {
            sb.appendLine(content.body)
        }
        return sb.toString()
    }

    /**
     * 文件类: 保留文件引用
     */
    private fun buildFileBody(content: SharedContent): String {
        val sb = StringBuilder()
        sb.appendLine("> ${content.title}")
        sb.appendLine()
        val fileName = content.attachmentFileName ?: "file"
        sb.appendLine("**附件:** [[attachments/$fileName]]")
        return sb.toString()
    }

    /**
     * 纯文字: 直接保存原文
     */
    private fun buildPlaintextBody(content: SharedContent): String {
        return content.body
    }

    /**
     * 未知类型: 原始保存
     */
    private fun buildUnknownBody(content: SharedContent): String {
        return content.body
    }
}
