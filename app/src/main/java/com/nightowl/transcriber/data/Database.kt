package com.nightowl.transcriber.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 转写记录：支持首页“最近转写”回看（即使 App 被杀，文字已入库） */
@Entity(tableName = "transcripts")
data class Transcript(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val durationMs: Long,
    val charCount: Int,
    val createdAt: Long,
    val savePath: String,
    val text: String
)

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts ORDER BY createdAt DESC LIMIT 50")
    fun recent(): Flow<List<Transcript>>

    @Insert
    suspend fun insert(item: Transcript)

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun delete(id: Int)
}

@Database(entities = [Transcript::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transcriber.db"
                ).build().also { INSTANCE = it }
            }
    }
}
