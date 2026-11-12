package com.smsforwarder.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smsforwarder.app.domain.model.SmsMessage
import java.util.UUID

@Entity(
    tableName = "sms_records",
    indices = [
        Index(value = ["deduplication_hash"], unique = true),
        Index(value = ["timestamp_millis"]),
        Index(value = ["created_at"])
    ]
)
data class SmsRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "sender")
    val sender: String,

    @ColumnInfo(name = "raw_body")
    val rawBody: String,

    @ColumnInfo(name = "timestamp_millis")
    val timestampMillis: Long,

    @ColumnInfo(name = "sim_slot_index")
    val simSlotIndex: Int, // 0 = SIM 1, 1 = SIM 2, -1 = Unknown

    @ColumnInfo(name = "sub_id")
    val subId: Int,

    @ColumnInfo(name = "carrier_name")
    val carrierName: String?,

    @ColumnInfo(name = "deduplication_hash")
    val deduplicationHash: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "contact_name", defaultValue = "NULL")
    val contactName: String? = null
) {
    fun toDomainModel(): SmsMessage = SmsMessage(
        id = id,
        sender = sender,
        rawBody = rawBody,
        timestampMillis = timestampMillis,
        simSlotIndex = simSlotIndex,
        subId = subId,
        carrierName = carrierName,
        deduplicationHash = deduplicationHash,
        createdAt = createdAt,
        contactName = contactName
    )

    companion object {
        fun fromDomainModel(sms: SmsMessage): SmsRecordEntity = SmsRecordEntity(
            id = sms.id,
            sender = sms.sender,
            rawBody = sms.rawBody,
            timestampMillis = sms.timestampMillis,
            simSlotIndex = sms.simSlotIndex,
            subId = sms.subId,
            carrierName = sms.carrierName,
            deduplicationHash = sms.deduplicationHash,
            createdAt = sms.createdAt,
            contactName = sms.contactName
        )
    }
}
