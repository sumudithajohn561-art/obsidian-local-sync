package com.obsidian.quickcapture

import java.io.File

/** 统一的收件箱路径——确保所有地方读写同一文件夹 */
object InboxDir {
    val paths = listOf(
        "/storage/emulated/0/Syncthing/Obsidian-Inbox",
        "/storage/emulated/0/Syncthing/obsidian-inbox",
        "/sdcard/Syncthing/Obsidian-Inbox"
    )

    fun find(): File? {
        for (p in paths) {
            val d = File(p)
            if (d.exists() || d.mkdirs()) return d
        }
        return null
    }
}
