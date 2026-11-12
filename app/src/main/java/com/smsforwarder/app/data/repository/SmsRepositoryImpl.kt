package com.smsforwarder.app.data.repository

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.core.util.DomainError
import com.smsforwarder.app.data.local.dao.SmsRecordDao
import com.smsforwarder.app.data.local.entity.SmsRecordEntity
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

class SmsRepositoryImpl(
    private val smsRecordDao: SmsRecordDao
) : SmsRepository {

    override fun getAllSmsFlow(): Flow<List<SmsMessage>> {
        return smsRecordDao.getAllSmsFlow().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun insertSms(sms: SmsMessage): AppResult<Long> {
        return try {
            val entity = SmsRecordEntity.fromDomainModel(sms)
            val rowId = smsRecordDao.insert(entity)
            AppResult.Success(rowId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert SMS record")
            AppResult.Failure(DomainError.DatabaseError("Insert SMS failed", e))
        }
    }

    override suspend fun getSmsById(id: String): SmsMessage? {
        return smsRecordDao.getById(id)?.toDomainModel()
    }

    override suspend fun findByDeduplicationHash(hash: String): SmsMessage? {
        return smsRecordDao.findByDeduplicationHash(hash)?.toDomainModel()
    }

    override suspend fun findRecentDuplicate(sender: String, body: String, timestamp: Long, windowMillis: Long): SmsMessage? {
        return smsRecordDao.findRecentDuplicate(sender, body, timestamp, windowMillis)?.toDomainModel()
    }

    override suspend fun getRecentSms(limit: Int): List<SmsMessage> {
        return smsRecordDao.getRecent(limit).map { it.toDomainModel() }
    }

    override suspend fun deleteSmsOlderThan(cutoffMillis: Long): Int {
        return smsRecordDao.deleteOlderThan(cutoffMillis)
    }

    override suspend fun purgeSmsBody(id: String): AppResult<Unit> {
        return try {
            smsRecordDao.purgeBodyById(id)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to purge SMS body for id $id")
            AppResult.Failure(DomainError.DatabaseError("Purge SMS body failed", e))
        }
    }

    override suspend fun deleteSmsById(id: String): AppResult<Unit> {
        return try {
            smsRecordDao.deleteById(id)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete SMS for id $id")
            AppResult.Failure(DomainError.DatabaseError("Delete SMS failed", e))
        }
    }

    override suspend fun deleteAllSms() {
        smsRecordDao.deleteAll()
    }
}
