package com.smsforwarder.app.data.repository

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.core.util.DomainError
import com.smsforwarder.app.data.local.dao.ForwardingRuleDao
import com.smsforwarder.app.data.local.entity.ForwardingRuleEntity
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.repository.ForwardingRuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

class ForwardingRuleRepositoryImpl(
    private val forwardingRuleDao: ForwardingRuleDao
) : ForwardingRuleRepository {

    override fun getAllRulesFlow(): Flow<List<ForwardingRule>> {
        return forwardingRuleDao.getAllRulesFlow().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getActiveRules(): List<ForwardingRule> {
        return forwardingRuleDao.getActiveRules().map { it.toDomainModel() }
    }

    override suspend fun getRuleById(id: String): ForwardingRule? {
        return forwardingRuleDao.getById(id)?.toDomainModel()
    }

    override suspend fun insertOrUpdateRule(rule: ForwardingRule): AppResult<Unit> {
        return try {
            val entity = ForwardingRuleEntity.fromDomainModel(rule)
            forwardingRuleDao.insertOrUpdate(entity)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert/update forwarding rule")
            AppResult.Failure(DomainError.DatabaseError("Save rule failed", e))
        }
    }

    override suspend fun updateRuleEnabled(id: String, isEnabled: Boolean): AppResult<Unit> {
        return try {
            forwardingRuleDao.updateEnabled(id, isEnabled)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update rule enabled state")
            AppResult.Failure(DomainError.DatabaseError("Update rule state failed", e))
        }
    }

    override suspend fun deleteRule(id: String): AppResult<Unit> {
        return try {
            forwardingRuleDao.deleteById(id)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete forwarding rule")
            AppResult.Failure(DomainError.DatabaseError("Delete rule failed", e))
        }
    }

    override suspend fun deleteAllRules() {
        forwardingRuleDao.deleteAll()
    }
}
