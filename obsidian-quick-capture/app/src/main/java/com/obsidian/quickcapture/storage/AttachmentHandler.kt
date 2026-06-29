package com.obsidian.quickcapture.storage

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 处理分享中的附件(图片/文件)
 *
 * 将附件从 Intent URI 复制到 Syncthing 文件夹的 attachments/ 子目录中。
 * 文件命名格式: YYYY-MM-DD-HHmmss-原始文件名
 */
object AttachmentHandler {

    private const val ATTACHMENTS_DIR = "attachments"

    /**
     * 将附件从URI复制到目标目录
     *
     * @param contentResolver Android ContentResolver, 用于读取URI内容
     * @param sourceUri 分享Intent中的附件URI
     * @param targetDir 目标根目录(Syncthing文件夹路径)
     * @param timestamp 时间戳, 用于文件命名
     * @param originalFileName 原始文件名
     * @return 复制后的相对文件路径, 如果失败返回 null
     */
    fun copyAttachment(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        targetDir: File,
        timestamp: String,
        originalFileName: String?
    ): String? {
        return try {
            // 确保 attachments 子目录存在
            val attachmentsDir = File(targetDir, ATTACHMENTS_DIR)
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }

            // 生成安全的文件名
            val safeName = buildSafeFileName(timestamp, originalFileName)
            val targetFile = File(attachmentsDir, safeName)

            // 复制文件
            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 返回相对于收件箱根目录的路径
            "$ATTACHMENTS_DIR/$safeName"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 生成安全的文件名
     *
     * 格式: 时间戳-清理后的原始文件名
     */
    private fun buildSafeFileName(timestamp: String, originalName: String?): String {
        val safeName = originalName
            ?.replace(Regex("[/\\\\:*?\"<>|]"), "_")  // 替换非法字符
            ?.take(100)                                // 限制长度
            ?: "attachment"

        return "${timestamp}-${safeName}"
    }

    /**
     * 根据MIME type获取文件扩展名
     */
    fun getExtensionFromMimeType(mimeType: String?): String {
        return when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "application/pdf" -> ".pdf"
            "application/msword" -> ".doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
            "application/vnd.ms-powerpoint" -> ".ppt"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"
            else -> ""
        }
    }
}
