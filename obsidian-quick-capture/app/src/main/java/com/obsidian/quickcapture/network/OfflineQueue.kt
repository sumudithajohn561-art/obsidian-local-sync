package com.obsidian.quickcapture.network

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

// ======== Room Entity ========
@Entity(tableName = "pending_captures")
data class PendingCapture(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "url") val url: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "markdown_content") val markdownContent: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0
)

// ======== DAO ========
@Dao
interface PendingCaptureDao {
    @Query("SELECT * FROM pending_captures ORDER BY created_at ASC")
    suspend fun getAll(): List<PendingCapture>

    @Query("SELECT COUNT(*) FROM pending_captures")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(capture: PendingCapture)

    @Delete
    suspend fun delete(capture: PendingCapture)

    @Query("DELETE FROM pending_captures WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_captures WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long)
}

// ======== Database ========
@Database(entities = [PendingCapture::class], version = 1, exportSchema = false)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun pendingCaptureDao(): PendingCaptureDao
}

// ======== Queue Manager ========
class OfflineQueue(context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        CaptureDatabase::class.java,
        "capture_queue"
    ).build()

    private val dao = db.pendingCaptureDao()

    /** 加入离线队列 */
    suspend fun enqueue(
        title: String, body: String, url: String?,
        sourceType: String, source: String, markdownContent: String
    ) {
        val capture = PendingCapture(
            title = title, body = body, url = url,
            sourceType = sourceType, source = source,
            markdownContent = markdownContent
        )
        dao.insert(capture)
    }

    /** 获取所有待发送项 */
    suspend fun getAll(): List<PendingCapture> = dao.getAll()

    /** 队列数量 */
    suspend fun count(): Int = dao.count()

    /** 发送成功后删除 */
    suspend fun remove(capture: PendingCapture) = dao.delete(capture)

    /** 发送失败，增加重试次数 */
    suspend fun markRetry(capture: PendingCapture) {
        dao.insert(capture.copy(retryCount = capture.retryCount + 1))
    }

    /** 清理超过 500 条或 7 天前的记录 */
    suspend fun cleanup() {
        val count = dao.count()
        if (count > 500) {
            val oldest = dao.getAll().first()
            dao.deleteById(oldest.id)
        }
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        dao.deleteOlderThan(sevenDaysAgo)
    }

    /** 尝试发送队列中所有待发送项 */
    suspend fun flushAll(serverUrl: String): Int = withContext(Dispatchers.IO) {
        var sent = 0
        val items = dao.getAll()
        for (item in items) {
            val result = HttpSender.send(
                serverUrl, item.title, item.body,
                item.url, item.sourceType, item.source
            )
            if (result.success) {
                dao.delete(item)
                sent++
            } else {
                dao.insert(item.copy(retryCount = item.retryCount + 1))
            }
        }
        sent
    }
}
