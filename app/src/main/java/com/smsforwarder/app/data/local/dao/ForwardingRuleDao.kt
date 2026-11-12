package com.smsforwarder.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.smsforwarder.app.data.local.entity.ForwardingRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ForwardingRuleDao {

    @Query("SELECT * FROM forwarding_rules ORDER BY created_at DESC")
    fun getAllRulesFlow(): Flow<List<ForwardingRuleEntity>>

    @Query("SELECT * FROM forwarding_rules WHERE is_enabled = 1")
    suspend fun getActiveRules(): List<ForwardingRuleEntity>

    @Query("SELECT * FROM forwarding_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ForwardingRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(rule: ForwardingRuleEntity)

    @Update
    suspend fun update(rule: ForwardingRuleEntity)

    @Query("UPDATE forwarding_rules SET is_enabled = :isEnabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateEnabled(id: String, isEnabled: Boolean, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM forwarding_rules WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM forwarding_rules")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM forwarding_rules WHERE is_enabled = 1")
    fun getActiveRulesCountFlow(): Flow<Int>
}
