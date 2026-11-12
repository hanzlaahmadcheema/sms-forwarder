package com.smsforwarder.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smsforwarder.app.data.local.converter.Converters
import com.smsforwarder.app.data.local.dao.DeliveryTaskDao
import com.smsforwarder.app.data.local.dao.ForwardingRuleDao
import com.smsforwarder.app.data.local.dao.SmsRecordDao
import com.smsforwarder.app.data.local.entity.DeliveryTaskEntity
import com.smsforwarder.app.data.local.entity.ForwardingRuleEntity
import com.smsforwarder.app.data.local.entity.SmsRecordEntity

@Database(
    entities = [
        SmsRecordEntity::class,
        ForwardingRuleEntity::class,
        DeliveryTaskEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsRecordDao(): SmsRecordDao
    abstract fun forwardingRuleDao(): ForwardingRuleDao
    abstract fun deliveryTaskDao(): DeliveryTaskDao

    companion object {
        private const val DATABASE_NAME = "sms_forwarder.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sms_records ADD COLUMN contact_name TEXT DEFAULT NULL")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
