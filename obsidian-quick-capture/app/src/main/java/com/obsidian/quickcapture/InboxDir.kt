package com.obsidian.quickcapture

import android.os.Environment
import java.io.File

object InboxDir {
    /** 找到或创建收件箱目录 */
    fun find(): File? {
        for (p in candidatePaths()) {
            val d = File(p)
            if (d.exists() && d.isDirectory) return d
        }
        // 都不存在 → 尝试创建第一个
        for (p in candidatePaths()) {
            val d = File(p)
            if (d.mkdirs()) return d
        }
        return null
    }

    /** 所有候选路径 */
    fun candidatePaths(): List<String> {
        val paths = mutableListOf<String>()
        val root = Environment.getExternalStorageDirectory().absolutePath

        // Syncthing 常见路径
        paths.add("$root/Syncthing/Obsidian-Inbox")
        paths.add("$root/Syncthing/obsidian-inbox")
        paths.add("$root/Obsidian-Inbox")
        paths.add("$root/Documents/Obsidian-Inbox")
        paths.add("$root/Download/Obsidian-Inbox")

        // /sdcard 别名
        paths.add("/sdcard/Syncthing/Obsidian-Inbox")
        paths.add("/sdcard/Obsidian-Inbox")

        // /storage 直接尝试
        paths.add("/storage/emulated/0/Syncthing/Obsidian-Inbox")
        paths.add("/storage/emulated/0/Obsidian-Inbox")

        return paths.distinct()
    }

    /** 未找到时的诊断信息 */
    fun diagnostic(): String {
        val tried = candidatePaths().joinToString("\n") { p ->
            val exists = File(p).exists()
            "${if (exists) "✅" else "❌"} $p"
        }
        return "尝试了以下路径:\n$tried"
    }
}
