package com.obsidian.quickcapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.obsidian.quickcapture.content.ContentClassifier
import com.obsidian.quickcapture.content.ContentExtractor
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import com.obsidian.quickcapture.storage.FileWriter
import kotlinx.coroutines.*
import java.io.File

/**
 * 主 Activity — 接收 Android 分享 Intent
 *
 * 设计理念:
 * - 透明主题、秒开、无主界面
 * - 接收分享 → 处理 → 存文件 → Toast → 退出
 * - 全程在后台线程执行，不阻塞 UI
 * - 只保存原始素材，不做内容修改
 *
 * 从微信/浏览器等App点「分享」→ 选「Quick Capture」→ 进入这里
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首先检查存储权限
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        // 处理分享Intent
        when (intent?.action) {
            Intent.ACTION_SEND -> handleSingleShare(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleMultipleShare(intent)
            else -> {
                // 不是分享进来的 (比如从启动器打开)，显示设置页
                showSettings()
            }
        }
    }

    /**
     * 处理单条分享
     */
    private fun handleSingleShare(intent: Intent?) {
        if (intent == null) {
            finishWithToast("无法读取分享内容")
            return
        }

        scope.launch {
            try {
                // 1. 提取内容
                val content = ContentExtractor.extract(intent)

                // 2. 分类
                val contentType = ContentClassifier.classify(
                    mimeType = content.mimeType,
                    contentText = content.body
                )

                // 3. 提取来源
                val source = ContentClassifier.extractSource(content.body)

                // 4. 生成 Markdown
                val markdown = MarkdownGenerator.generate(content, contentType, source)

                // 5. 写入文件
                val inboxDir = getInboxDirectory()
                val writer = FileWriter(contentResolver, inboxDir)
                val savedFile = writer.write(content, contentType, markdown)

                if (savedFile != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "已保存: ${savedFile.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "保存失败，请检查存储权限",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "保存失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                // 无论成功失败，都关闭Activity
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }

    /**
     * 处理多条分享 (多张图片等)
     */
    private fun handleMultipleShare(intent: Intent?) {
        if (intent == null) {
            finishWithToast("无法读取分享内容")
            return
        }

        scope.launch {
            var successCount = 0
            var failCount = 0

            try {
                val clipData = intent.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        val item = clipData.getItemAt(i)
                        val itemIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            type = intent.type
                            putExtra(Intent.EXTRA_TEXT, item.text)
                            putExtra(Intent.EXTRA_STREAM, item.uri)
                        }

                        try {
                            val content = ContentExtractor.extract(itemIntent)
                            val contentType = ContentClassifier.classify(
                                content.mimeType, content.body
                            )
                            val source = ContentClassifier.extractSource(content.body)
                            val markdown = MarkdownGenerator.generate(content, contentType, source)
                            val inboxDir = getInboxDirectory()
                            val writer = FileWriter(contentResolver, inboxDir)
                            val savedFile = writer.write(content, contentType, markdown)

                            if (savedFile != null) successCount++ else failCount++
                        } catch (e: Exception) {
                            failCount++
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "已保存 $successCount 项" + if (failCount > 0) ", $failCount 项失败" else "",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    /**
     * 显示设置界面
     */
    private fun showSettings() {
        val intent = Intent(this, com.obsidian.quickcapture.ui.SettingsActivity::class.java)
        startActivity(intent)
        finish()
    }

    /**
     * 快速关闭并提示
     */
    private fun finishWithToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    // ==================== 存储权限处理 ====================

    /**
     * 检查是否有足够的存储权限
     *
     * Android 10 以下: 检查 WRITE_EXTERNAL_STORAGE
     * Android 10+: 检查 MANAGE_EXTERNAL_STORAGE
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求存储权限
     */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 10+: 跳转到系统设置页授权"管理所有文件"
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "请授予「管理所有文件」权限，以便写入 Syncthing 文件夹",
                Toast.LENGTH_LONG
            ).show()
        } else {
            // Android 9 及以下
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE
            )
        }
        // 无法继续处理本次分享
        finish()
    }

    /**
     * 获取收件箱目录
     *
     * 按以下优先级尝试:
     * 1. 默认 Syncthing 路径
     * 2. 备用路径
     * 3. App 私有目录 (最后兜底)
     */
    private fun getInboxDirectory(): File {
        val default = FileWriter.DEFAULT_INBOX_PATH
        if (default.exists() || default.mkdirs()) return default

        val fallback = FileWriter.FALLBACK_INBOX_PATH
        if (fallback.exists() || fallback.mkdirs()) return fallback

        // 最终兜底: App 私有目录
        return File(filesDir, "inbox").also { it.mkdirs() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val REQUEST_CODE_STORAGE = 1001
    }
}
