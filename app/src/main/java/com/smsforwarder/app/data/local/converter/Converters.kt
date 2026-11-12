package com.smsforwarder.app.data.local.converter

import androidx.room.TypeConverter
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.SenderFilterType
import com.smsforwarder.app.domain.model.TaskStatus

class Converters {

    @TypeConverter
    fun fromDestinationType(value: DestinationType?): String? = value?.name

    @TypeConverter
    fun toDestinationType(value: String?): DestinationType? = value?.let {
        try {
            DestinationType.valueOf(it)
        } catch (_: Exception) {
            DestinationType.WEBHOOK
        }
    }

    @TypeConverter
    fun fromTaskStatus(value: TaskStatus?): String? = value?.name

    @TypeConverter
    fun toTaskStatus(value: String?): TaskStatus? = value?.let {
        try {
            TaskStatus.valueOf(it)
        } catch (_: Exception) {
            TaskStatus.PENDING
        }
    }

    @TypeConverter
    fun fromSenderFilterType(value: SenderFilterType?): String? = value?.name

    @TypeConverter
    fun toSenderFilterType(value: String?): SenderFilterType? = value?.let {
        try {
            SenderFilterType.valueOf(it)
        } catch (_: Exception) {
            SenderFilterType.ALL
        }
    }
}
