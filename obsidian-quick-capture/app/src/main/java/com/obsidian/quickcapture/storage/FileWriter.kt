package com.obsidian.quickcapture.storage

import android.content.ContentResolver
import android.net.Uri
import com.obsidian.quickcapture.content.ContentType
import com.obsidian.quickcapture.content.SharedContent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 写入 Markdown 文件到 Syncthing 同步文件夹
 *
 * 目标路径: /storage/emulated/0/Syncthing/Obsidian-Inbox/
 * 文件命名: YYYY-MM-DD-HHmmss-标题.md
 *
 * 核心职责:
 * 1. 确保目标目录存在
 * 2. 生成安全的文件名
 * 3. 写入 Markdown 内容
 * 4. 如果有附件(图片/文件), 调用 AttachmentHandler 复制
 */
class FileWriter(
    private val contentResolver: ContentResolver,
    private val baseDir: File
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    /**
     * 写入分享内容到收件箱
     *
     * @param content 提取的分享内容
     * @param contentType 内容类型
     * @param markdownContent 生成好的 Markdown 文本
     * @return 写入的文件对象, 失败返回 null
     */
    fun write(
        content: SharedContent,
        contentType: ContentType,
        markdownContent: String
    ): File? {
        return try {
            // 确保目录存在
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            // 处理附件(如果有)
            var attachmentPath: String? = null
            if (content.attachmentUri != null &&
                (contentType == ContentType.IMAGE || contentType == ContentType.FILE)
            ) {
                val timestamp = LocalDateTime.now().format(dateFormatter)
                attachmentPath = AttachmentHandler.copyAttachment(
                    contentResolver = contentResolver,
                    sourceUri = content.attachmentUri,
                    targetDir = baseDir,
                    timestamp = timestamp,
                    originalFileName = content.attachmentFileName
                )
            }

            // 生成文件名
            val timestamp = LocalDateTime.now().format(dateFormatter)
            val safeTitle = sanitizeFileName(content.title)
            val fileName = "$timestamp-$safeTitle.md"
            val file = File(baseDir, fileName)

            // 写入文件
            file.writeText(markdownContent, Charsets.UTF_8)

            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 清理文件名中的非法字符
     */
    private fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[/\\\\:*?\"<>|]"), "-")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(50)
    }

    companion object {
        /**
         * Syncthing 默认的收件箱路径
         */
        val DEFAULT_INBOX_PATH = File("/storage/emulated/0/Syncthing/Obsidian-Inbox")

        /**
         * 备用路径 (某些设备的内部存储挂载点不同)
         */
        val FALLBACK_INBOX_PATH = File("/sdcard/Syncthing/Obsidian-Inbox")
    }
}
