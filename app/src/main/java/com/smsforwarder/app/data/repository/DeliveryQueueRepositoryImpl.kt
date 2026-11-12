package com.smsforwarder.app.data.repository

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.core.util.DomainError
import com.smsforwarder.app.data.local.dao.DeliveryTaskDao
import com.smsforwarder.app.data.local.entity.DeliveryTaskEntity
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.TaskStatus
import com.smsforwarder.app.domain.repository.DeliveryQueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

class DeliveryQueueRepositoryImpl(
    private val deliveryTaskDao: DeliveryTaskDao
) : DeliveryQueueRepository {

    override fun getAllTasksFlow(): Flow<List<DeliveryTask>> {
        return deliveryTaskDao.getAllTasksFlow().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override fun getTasksByStatusFlow(status: TaskStatus): Flow<List<DeliveryTask>> {
        return deliveryTaskDao.getTasksByStatusFlow(status).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getTaskById(id: String): DeliveryTask? {
        return deliveryTaskDao.getById(id)?.toDomainModel()
    }

    override suspend fun getPendingTasks(): List<DeliveryTask> {
        return deliveryTaskDao.getPendingTasks().map { it.toDomainModel() }
    }

    override suspend fun getDueRetryTasks(nowMillis: Long): List<DeliveryTask> {
        return deliveryTaskDao.getDueRetryTasks(nowMillis).map { it.toDomainModel() }
    }

    override suspend fun insertTasks(tasks: List<DeliveryTask>): AppResult<Unit> {
        return try {
            val entities = tasks.map { DeliveryTaskEntity.fromDomainModel(it) }
            deliveryTaskDao.insertAll(entities)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert delivery tasks")
            AppResult.Failure(DomainError.DatabaseError("Insert delivery tasks failed", e))
        }
    }

    override suspend fun updateTaskStatus(
        taskId: String,
        status: TaskStatus,
        attemptCount: Int,
        lastAttemptAt: Long?,
        nextRetryAt: Long?,
        errorMessage: String?
    ): AppResult<Unit> {
        return try {
            deliveryTaskDao.updateTaskStatus(
                taskId = taskId,
                status = status,
                attemptCount = attemptCount,
                lastAttemptAt = lastAttemptAt,
                nextRetryAt = nextRetryAt,
                errorMessage = errorMessage
            )
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update delivery task status")
            AppResult.Failure(DomainError.DatabaseError("Update task status failed", e))
        }
    }

    override suspend fun claimTask(taskId: String, now: Long): Boolean {
        return try {
            val rows = deliveryTaskDao.claimTaskForExecution(taskId, now)
            rows > 0
        } catch (e: Exception) {
            Timber.e(e, "Failed to claim task $taskId for execution")
            false
        }
    }

    override suspend fun resetInProgressTasksToPending(): Int {
        return deliveryTaskDao.resetInProgressTasksToPending()
    }

    override suspend fun resetOrphanedInProgressTasks(cutoffMillis: Long): Int {
        return deliveryTaskDao.resetOrphanedInProgressTasks(cutoffMillis)
    }

    override suspend fun retryTaskManually(taskId: String): AppResult<Unit> {
        return try {
            deliveryTaskDao.retryTaskManually(taskId)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to trigger manual retry for task $taskId")
            AppResult.Failure(DomainError.DatabaseError("Manual retry failed", e))
        }
    }

    override suspend fun retryAllFailedTasks(): AppResult<Int> {
        return try {
            val count = deliveryTaskDao.retryAllFailedTasks()
            AppResult.Success(count)
        } catch (e: Exception) {
            Timber.e(e, "Failed to retry all failed tasks")
            AppResult.Failure(DomainError.DatabaseError("Retry all failed tasks failed", e))
        }
    }

    override suspend fun deleteTasksOlderThan(cutoffMillis: Long): Int {
        return deliveryTaskDao.deleteOlderThan(cutoffMillis)
    }

    override suspend fun deleteFinishedTasksOlderThan(cutoffMillis: Long): Int {
        return deliveryTaskDao.deleteFinishedOlderThan(cutoffMillis)
    }

    override suspend fun deleteAllTasks() {
        deliveryTaskDao.deleteAll()
    }
}
