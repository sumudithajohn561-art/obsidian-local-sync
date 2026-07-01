package com.obsidian.quickcapture

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.obsidian.quickcapture.storage.FileWriter
import com.obsidian.quickcapture.content.ContentClassifier
import com.obsidian.quickcapture.content.ContentExtractor
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import kotlinx.coroutines.*
import java.io.File

class ClipboardService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastClipText = ""
    private var alwaysSave = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFY_MONITOR, buildNotification("正在监听...", "复制链接后自动提示保存"))
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.addPrimaryClipChangedListener {
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return@addPrimaryClipChangedListener
            if (text.isBlank() || text == lastClipText) return@addPrimaryClipChangedListener
            lastClipText = text

            // 加载用户偏好
            val prefs = getSharedPreferences("capture", MODE_PRIVATE)
            val domain = extractDomain(text)
            val rule = prefs.getString("rule_$domain", null)

            when (rule) {
                "always_save" -> saveAndNotify(text, silent = true)
                "always_skip" -> { /* 静默跳过 */ }
                else -> showAskNotification(text)
            }
        }
    }

    private fun extractDomain(text: String): String {
        return try { java.net.URI(text).host ?: "text" } catch (_: Exception) { "text" }
    }

    private fun showAskNotification(text: String) {
        val domain = extractDomain(text)
        val title = if (text.startsWith("http")) "检测到链接" else "检测到文字内容"
        val body = text.take(100) + if (text.length > 100) "..." else ""

        val saveIntent = Intent(this, SaveReceiver::class.java).apply {
            putExtra("text", text)
            putExtra("action", "save")
        }
        val skipIntent = Intent(this, SaveReceiver::class.java).apply {
            putExtra("text", text)
            putExtra("action", "skip")
        }
        val alwaysIntent = Intent(this, SaveReceiver::class.java).apply {
            putExtra("text", text)
            putExtra("action", "always_save")
            putExtra("domain", domain)
        }

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "保存", PendingIntent.getBroadcast(this, 0, saveIntent, flags))
            .addAction(0, "跳过", PendingIntent.getBroadcast(this, 1, skipIntent, flags))
            .addAction(0, "始终保存", PendingIntent.getBroadcast(this, 2, alwaysIntent, flags))
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFY_ASK, notification)
    }

    private fun saveAndNotify(text: String, silent: Boolean) {
        scope.launch {
            try {
                val content = ContentExtractor.extract(Intent().apply {
                    putExtra(Intent.EXTRA_TEXT, text); type = "text/plain" })
                val type = ContentClassifier.classify(content.mimeType, content.body)
                val source = ContentClassifier.extractSource(content.body)
                val md = MarkdownGenerator.generate(content, type, source)
                val inboxDir = findInboxDir() ?: return@launch
                FileWriter(contentResolver, inboxDir).write(content, type, md)

                if (!silent) {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFY_DONE, NotificationCompat.Builder(this@ClipboardService, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_popup_reminder)
                            .setContentTitle("已保存")
                            .setContentText(content.title)
                            .setAutoCancel(true).build())
                }
            } catch (_: Exception) {}
        }
    }

    private fun findInboxDir(): File? {
        val candidates = listOf(
            File("/storage/emulated/0/Syncthing/Obsidian-Inbox"),
            File("/storage/emulated/0/Syncthing/obsidian-inbox"),
            File("/sdcard/Syncthing/Obsidian-Inbox"),
        )
        for (dir in candidates) { if (dir.exists() || dir.mkdirs()) return dir }
        return null
    }

    private fun buildNotification(title: String, body: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL_ID = "quick_capture_clipboard"
        const val NOTIFY_MONITOR = 1001
        const val NOTIFY_ASK = 1002
        const val NOTIFY_DONE = 1003
    }
}

// 广播接收器——处理通知按钮点击
class SaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val action = intent.getStringExtra("action") ?: return
        val domain = intent.getStringExtra("domain") ?: ""

        when (action) {
            "save" -> saveDirect(context, text)
            "always_save" -> {
                context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit()
                    .putString("rule_$domain", "always_save").apply()
                saveDirect(context, text)
            }
            "skip" -> { /* 不保存 */ }
        }
        // 取消通知
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(ClipboardService.NOTIFY_ASK)
    }

    private fun saveDirect(context: Context, text: String) {
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val content = ContentExtractor.extract(Intent().apply {
                    putExtra(Intent.EXTRA_TEXT, text); type = "text/plain" })
                val type = ContentClassifier.classify(content.mimeType, content.body)
                val source = ContentClassifier.extractSource(content.body)
                val md = MarkdownGenerator.generate(content, type, source)
                val inboxDir = findInboxDirStatic() ?: return@launch
                FileWriter(context.contentResolver, inboxDir).write(content, type, md)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private fun findInboxDirStatic(): File? {
            val candidates = listOf(
                File("/storage/emulated/0/Syncthing/Obsidian-Inbox"),
                File("/storage/emulated/0/Syncthing/obsidian-inbox"),
                File("/sdcard/Syncthing/Obsidian-Inbox"),
            )
            for (dir in candidates) { if (dir.exists() || dir.mkdirs()) return dir }
            return null
        }
    }
}
