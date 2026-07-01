package com.obsidian.quickcapture

import android.content.ContentResolver
import com.obsidian.quickcapture.content.ContentClassifier
import com.obsidian.quickcapture.content.ContentExtractor
import com.obsidian.quickcapture.content.SharedContent
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import com.obsidian.quickcapture.storage.FileWriter
import java.io.File

/** 唯一的文件操作入口——所有读写都走这里 */
object CaptureRepository {

    private fun inboxDir(): File? = InboxDir.find()

    /** 保存一条内容，返回保存的文件名 */
    fun save(content: SharedContent, resolver: ContentResolver): String? {
        val type = ContentClassifier.classify(content.mimeType, content.body)
        val source = ContentClassifier.extractSource(content.body)
        val md = MarkdownGenerator.generate(content, type, source)
        val dir = inboxDir() ?: return null
        val file = FileWriter(resolver, dir).write(content, type, md)
        return file?.name
    }

    /** 从文本直接保存 */
    fun saveText(text: String, resolver: ContentResolver): String? {
        val content = ContentExtractor.extract(android.content.Intent().apply {
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            type = "text/plain"
        })
        return save(content, resolver)
    }

    /** 获取已保存的文件列表 */
    fun listFiles(): List<File> {
        val dir = inboxDir() ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "md" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 收件箱是否可用 */
    fun isReady(): Boolean = inboxDir() != null
}
