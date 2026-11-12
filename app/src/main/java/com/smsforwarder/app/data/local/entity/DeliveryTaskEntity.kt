package com.smsforwarder.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.TaskStatus
import java.util.UUID

@Entity(
    tableName = "delivery_tasks",
    foreignKeys = [
        ForeignKey(
            entity = SmsRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["sms_record_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ForwardingRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["sms_record_id"]),
        Index(value = ["rule_id"]),
        Index(value = ["status", "next_retry_at"]),
        Index(value = ["created_at"])
    ]
)
data class DeliveryTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "sms_record_id")
    val smsRecordId: String,

    @ColumnInfo(name = "rule_id")
    val ruleId: String?,

    @ColumnInfo(name = "destination_type")
    val destinationType: DestinationType,

    @ColumnInfo(name = "destination_endpoint")
    val destinationEndpoint: String,

    @ColumnInfo(name = "status")
    val status: TaskStatus = TaskStatus.PENDING,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "vault_secret_alias")
    val vaultSecretAlias: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomainModel(): DeliveryTask = DeliveryTask(
        id = id,
        smsRecordId = smsRecordId,
        ruleId = ruleId,
        destinationType = destinationType,
        destinationEndpoint = destinationEndpoint,
        status = status,
        attemptCount = attemptCount,
        lastAttemptAt = lastAttemptAt,
        nextRetryAt = nextRetryAt,
        errorMessage = errorMessage,
        vaultSecretAlias = vaultSecretAlias,
        createdAt = createdAt
    )

    companion object {
        fun fromDomainModel(task: DeliveryTask): DeliveryTaskEntity = DeliveryTaskEntity(
            id = task.id,
            smsRecordId = task.smsRecordId,
            ruleId = task.ruleId,
            destinationType = task.destinationType,
            destinationEndpoint = task.destinationEndpoint,
            status = task.status,
            attemptCount = task.attemptCount,
            lastAttemptAt = task.lastAttemptAt,
            nextRetryAt = task.nextRetryAt,
            errorMessage = task.errorMessage,
            vaultSecretAlias = task.vaultSecretAlias,
            createdAt = task.createdAt
        )
    }
}
