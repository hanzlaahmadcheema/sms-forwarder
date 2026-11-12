package com.smsforwarder.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.smsforwarder.app.data.local.entity.SmsRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsRecordDao {

    @Query("SELECT * FROM sms_records ORDER BY timestamp_millis DESC")
    fun getAllSmsFlow(): Flow<List<SmsRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sms: SmsRecordEntity): Long

    @Query("SELECT * FROM sms_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SmsRecordEntity?

    @Query("SELECT * FROM sms_records WHERE deduplication_hash = :hash LIMIT 1")
    suspend fun findByDeduplicationHash(hash: String): SmsRecordEntity?

    @Query("""
        SELECT * FROM sms_records 
        WHERE sender = :sender 
          AND raw_body = :body 
          AND abs(timestamp_millis - :timestamp) <= :windowMillis 
        LIMIT 1
    """)
    suspend fun findRecentDuplicate(sender: String, body: String, timestamp: Long, windowMillis: Long): SmsRecordEntity?

    @Query("SELECT * FROM sms_records ORDER BY timestamp_millis DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SmsRecordEntity>

    @Query("DELETE FROM sms_records WHERE timestamp_millis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("UPDATE sms_records SET raw_body = '[PURGED]' WHERE id = :id")
    suspend fun purgeBodyById(id: String): Int

    @Query("DELETE FROM sms_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM sms_records")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM sms_records")
    fun getCountFlow(): Flow<Int>
}
