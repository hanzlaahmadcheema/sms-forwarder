package com.smsforwarder.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.smsforwarder.app.data.local.entity.DeliveryTaskEntity
import com.smsforwarder.app.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryTaskDao {

    @Query("SELECT * FROM delivery_tasks ORDER BY created_at DESC")
    fun getAllTasksFlow(): Flow<List<DeliveryTaskEntity>>

    @Query("SELECT * FROM delivery_tasks WHERE status = :status ORDER BY created_at DESC")
    fun getTasksByStatusFlow(status: TaskStatus): Flow<List<DeliveryTaskEntity>>

    @Query("SELECT * FROM delivery_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DeliveryTaskEntity?

    @Query("SELECT * FROM delivery_tasks WHERE status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPendingTasks(): List<DeliveryTaskEntity>

    @Query("SELECT * FROM delivery_tasks WHERE status = 'FAILED_RETRYABLE' AND next_retry_at IS NOT NULL AND next_retry_at <= :nowMillis ORDER BY next_retry_at ASC")
    suspend fun getDueRetryTasks(nowMillis: Long): List<DeliveryTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<DeliveryTaskEntity>)

    @Query("""
        UPDATE delivery_tasks 
        SET status = :status, 
            attempt_count = :attemptCount, 
            last_attempt_at = :lastAttemptAt, 
            next_retry_at = :nextRetryAt, 
            error_message = :errorMessage 
        WHERE id = :taskId
    """)
    suspend fun updateTaskStatus(
        taskId: String,
        status: TaskStatus,
        attemptCount: Int,
        lastAttemptAt: Long?,
        nextRetryAt: Long?,
        errorMessage: String?
    ): Int

    @Query("""
        UPDATE delivery_tasks 
        SET status = 'IN_PROGRESS', 
            last_attempt_at = :now 
        WHERE id = :taskId 
          AND (status = 'PENDING' OR (status = 'FAILED_RETRYABLE' AND (next_retry_at IS NULL OR next_retry_at <= :now)))
    """)
    suspend fun claimTaskForExecution(taskId: String, now: Long): Int

    @Query("UPDATE delivery_tasks SET status = 'PENDING' WHERE status = 'IN_PROGRESS' AND (last_attempt_at IS NULL OR last_attempt_at < :cutoffMillis)")
    suspend fun resetOrphanedInProgressTasks(cutoffMillis: Long): Int

    @Query("UPDATE delivery_tasks SET status = 'PENDING' WHERE status = 'IN_PROGRESS'")
    suspend fun resetInProgressTasksToPending(): Int

    @Query("""
        UPDATE delivery_tasks 
        SET status = 'PENDING', 
            next_retry_at = NULL, 
            error_message = NULL 
        WHERE id = :taskId
    """)
    suspend fun retryTaskManually(taskId: String): Int

    @Query("DELETE FROM delivery_tasks WHERE created_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM delivery_tasks WHERE status IN ('SUCCESS', 'FAILED_EXHAUSTED') AND created_at < :cutoffMillis")
    suspend fun deleteFinishedOlderThan(cutoffMillis: Long): Int

    @Query("""
        UPDATE delivery_tasks 
        SET status = 'PENDING', 
            next_retry_at = NULL, 
            error_message = NULL 
        WHERE status IN ('FAILED_RETRYABLE', 'FAILED_EXHAUSTED')
    """)
    suspend fun retryAllFailedTasks(): Int

    @Query("DELETE FROM delivery_tasks")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM delivery_tasks WHERE status = 'SUCCESS'")
    fun getSuccessCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM delivery_tasks WHERE status IN ('FAILED_RETRYABLE', 'FAILED_EXHAUSTED')")
    fun getFailureCountFlow(): Flow<Int>
}
