package com.family.ledger.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.family.ledger.data.db.dao.*
import com.family.ledger.data.db.entity.*

@Database(
    entities = [
        FamilyEntity::class,
        DeviceEntity::class,
        FamilyMemberEntity::class,
        BookEntity::class,
        AssetEntity::class,
        BalanceAnchorEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        TxnEntity::class,
        PendingBillEntity::class,
        AutoBillLogEntity::class,
        MerchantRuleEntity::class,
        SyncOpEntity::class,
        SyncStateEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun familyDao(): FamilyDao
    abstract fun familyMemberDao(): FamilyMemberDao
    abstract fun bookDao(): BookDao
    abstract fun assetDao(): AssetDao
    abstract fun balanceAnchorDao(): BalanceAnchorDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun txnDao(): TxnDao
    abstract fun pendingBillDao(): PendingBillDao
    abstract fun autoBillLogDao(): AutoBillLogDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun syncDao(): SyncDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS device (id TEXT NOT NULL PRIMARY KEY, personId TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_device_personId ON device(personId)")
                db.execSQL("ALTER TABLE txn ADD COLUMN manuallyConfirmed INTEGER")
                db.execSQL("ALTER TABLE merchant_rule ADD COLUMN bookId TEXT")
                db.execSQL("ALTER TABLE merchant_rule ADD COLUMN consumerId TEXT")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN recoveryKind TEXT")
            }
        }
        const val DB_NAME = "family_ledger.db"

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
        }
    }
}
