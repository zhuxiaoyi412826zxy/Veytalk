package com.im.nativeapp.core.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * 本地消息/会话缓存（对齐 Web 端 utils/localdb 的职责）：
 * - 进入会话先渲染本地最近一屏，再拉服务端合并回写，杀进程重进消息不丢；
 * - 滚动加载本地凑得出整页就不发网络请求；
 * - 断网发送进 pending_message 队列，网络恢复后按 clientMsgId 幂等重提交。
 *
 * 消息表排序键 seqSort：服务端 seq 的数值；本地占位（还没拿到 seq）用 Long.MAX_VALUE，
 * 永远排在最后 = 最新一条。不能靠 sendTime 排：客户端时钟可能比服务端慢几分钟。
 */
@Entity(
    tableName = "message",
    indices = [
        Index(value = ["conversationId", "seqSort"]),
        Index(value = ["messageId"], unique = true),
        Index(value = ["clientMsgId"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val messageId: String? = null,
    val clientMsgId: String? = null,
    val conversationId: String,
    val fromUserId: String? = null,
    val fromNickname: String? = null,
    val fromAvatar: String? = null,
    val msgType: Int = 1,
    val content: String? = null,
    val extraJson: String? = null,
    val seqSort: Long = Long.MAX_VALUE,
    val status: Int = 1,
    val recalled: Boolean = false,
    val sendTime: String? = null,
    val self: Boolean = false,
    val readCount: Int? = null,
    /** 引用预览 JSON（QuotePreview 同形），本地缓存往返不丢引用块 */
    val quoteJson: String? = null
)

@Entity(tableName = "conversation", primaryKeys = ["conversationId"])
data class ConversationEntity(
    val conversationId: String,
    val type: Int = 1,
    val targetId: String? = null,
    val name: String = "",
    val avatar: String? = null,
    val online: Boolean = false,
    val unreadCount: Int = 0,
    val atFlag: Boolean = false,
    val lastMsgId: String? = null,
    val lastMsgContent: String? = null,
    val lastMsgType: Int? = null,
    val lastMsgTime: String? = null,
    val top: Boolean = false,
    val topTime: String? = null,
    val muted: Boolean = false,
    val lastAckSeq: String? = null,
    val memberCount: Int? = null,
    val myRole: Int? = null
)

/** 断网期间攒下的待发送消息，重连后按入队顺序补发（clientMsgId 幂等） */
@Entity(tableName = "pending_message", primaryKeys = ["clientMsgId"])
data class PendingMessageEntity(
    val clientMsgId: String,
    val conversationId: String,
    val msgType: Int,
    val content: String,
    val extraJson: String? = null,
    val quoteMsgId: String? = null,
    val atAll: Boolean = false,
    /** atUserIds 的 JSON 数组串 */
    val atUserIdsJson: String? = null,
    val createTime: Long = System.currentTimeMillis()
)

@Dao
interface MessageDao {
    /** 最近 N 条（按排序键倒序取再反转由调用方做） */
    @Query("SELECT * FROM message WHERE conversationId = :cid ORDER BY seqSort DESC, rowid DESC LIMIT :limit")
    suspend fun loadRecentDesc(cid: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM message WHERE conversationId = :cid AND seqSort < :beforeSeq ORDER BY seqSort DESC, rowid DESC LIMIT :limit")
    suspend fun loadBeforeDesc(cid: String, beforeSeq: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM message WHERE messageId = :messageId LIMIT 1")
    suspend fun byMessageId(messageId: String): MessageEntity?

    @Query("SELECT * FROM message WHERE conversationId = :cid AND clientMsgId = :clientMsgId LIMIT 1")
    suspend fun byClientMsgId(cid: String, clientMsgId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MessageEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MessageEntity): Long

    @Query("DELETE FROM message WHERE rowid = :rowid")
    suspend fun deleteByRowid(rowid: Long)

    @Query("DELETE FROM message WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    @Query("DELETE FROM message WHERE conversationId = :cid")
    suspend fun clearConversation(cid: String)

    /**
     * upsert：服务端 messageId 优先，其次本地占位的 clientMsgId。
     * REPLACE 插不进球因为主键是自增 rowid，必须先查再定插还是改。
     */
    @Transaction
    suspend fun upsert(item: MessageEntity) {
        val byId = item.messageId?.let { byMessageId(it) }
        val existing = byId ?: item.clientMsgId?.let { byClientMsgId(item.conversationId, it) }
        if (existing != null) {
            // 服务端权威数据合并进本地行：本地占位的 rowid 保留，列表位置不跳
            insert(item.copy(rowid = existing.rowid))
        } else {
            insert(item)
        }
    }

    @Transaction
    suspend fun upsertAll(items: List<MessageEntity>) {
        items.forEach { upsert(it) }
    }
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation")
    suspend fun all(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAll(items: List<ConversationEntity>)

    @Query("DELETE FROM conversation")
    suspend fun clear()

    @Transaction
    suspend fun reset(items: List<ConversationEntity>) {
        clear()
        replaceAll(items)
    }
}

@Dao
interface PendingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: PendingMessageEntity)

    @Query("SELECT * FROM pending_message ORDER BY createTime ASC")
    suspend fun list(): List<PendingMessageEntity>

    @Query("DELETE FROM pending_message WHERE clientMsgId = :clientMsgId")
    suspend fun remove(clientMsgId: String)
}

@Database(
    entities = [MessageEntity::class, ConversationEntity::class, PendingMessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ImDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun pendingDao(): PendingDao

    companion object {
        @Volatile private var instance: ImDatabase? = null

        fun get(context: Context): ImDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ImDatabase::class.java,
                    "im_native.db"
                )
                    // 开发期 schema 还会演进，破坏性迁移直接重建（对齐 Web 端 localdb 的 dev 体验）
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
