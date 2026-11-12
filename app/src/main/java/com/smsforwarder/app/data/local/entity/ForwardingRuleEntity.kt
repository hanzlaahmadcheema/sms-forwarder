package com.smsforwarder.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SenderFilterType
import java.util.UUID

@Entity(
    tableName = "forwarding_rules",
    indices = [
        Index(value = ["is_enabled"]),
        Index(value = ["target_destination_type"])
    ]
)
data class ForwardingRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "target_destination_type")
    val targetDestinationType: DestinationType,

    @ColumnInfo(name = "destination_endpoint")
    val destinationEndpoint: String,

    @ColumnInfo(name = "sender_filter_type")
    val senderFilterType: SenderFilterType = SenderFilterType.ALL,

    @ColumnInfo(name = "sender_filter_values")
    val senderFilterValues: String = "",

    @ColumnInfo(name = "keyword_filter_values")
    val keywordFilterValues: String = "",

    @ColumnInfo(name = "sim_slot_filter")
    val simSlotFilter: Int = -1, // -1 = Any, 0 = SIM 1, 1 = SIM 2

    @ColumnInfo(name = "custom_template")
    val customTemplate: String? = null,

    @ColumnInfo(name = "vault_alias_ref")
    val vaultAliasRef: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomainModel(): ForwardingRule = ForwardingRule(
        id = id,
        name = name,
        isEnabled = isEnabled,
        targetDestinationType = targetDestinationType,
        destinationEndpoint = destinationEndpoint,
        senderFilterType = senderFilterType,
        senderFilterValues = senderFilterValues,
        keywordFilterValues = keywordFilterValues,
        simSlotFilter = simSlotFilter,
        customTemplate = customTemplate,
        vaultAliasRef = vaultAliasRef,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomainModel(rule: ForwardingRule): ForwardingRuleEntity = ForwardingRuleEntity(
            id = rule.id,
            name = rule.name,
            isEnabled = rule.isEnabled,
            targetDestinationType = rule.targetDestinationType,
            destinationEndpoint = rule.destinationEndpoint,
            senderFilterType = rule.senderFilterType,
            senderFilterValues = rule.senderFilterValues,
            keywordFilterValues = rule.keywordFilterValues,
            simSlotFilter = rule.simSlotFilter,
            customTemplate = rule.customTemplate,
            vaultAliasRef = rule.vaultAliasRef,
            createdAt = rule.createdAt,
            updatedAt = rule.updatedAt
        )
    }
}
