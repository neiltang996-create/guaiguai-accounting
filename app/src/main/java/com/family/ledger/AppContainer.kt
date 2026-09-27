package com.family.ledger

import android.content.Context
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.csv.QianJiCsvRepository
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.repo.AssetRepository
import com.family.ledger.data.repo.CategoryRepository
import com.family.ledger.data.repo.FamilyRepository
import com.family.ledger.data.repo.LedgerRepository
import com.family.ledger.data.sync.SyncJournal
import com.family.ledger.sync.SyncRepository

/**
 * 手写依赖容器。
 * 刻意不引入 Hilt/Koin：本项目只需要一个单例图，少一个注解处理器就少一类构建问题。
 *
 * 本文件由 lead 独占维护；协作者请只读引用，不要修改。
 */
class AppContainer(context: Context, database: AppDatabase = AppDatabase.get(context), preferences: SettingsStore = SettingsStore(context)) {

    val appContext: Context = context.applicationContext
    val settings: SettingsStore = preferences
    val db: AppDatabase = database
    val journal: SyncJournal = SyncJournal(db, settings.deviceId)

    val ledger: LedgerRepository = LedgerRepository(db, journal, settings)
    val assets: AssetRepository = AssetRepository(db, journal, settings)
    val categories: CategoryRepository = CategoryRepository(db, journal)
    val family: FamilyRepository = FamilyRepository(appContext, db, journal, settings)
    val csv: QianJiCsvRepository = QianJiCsvRepository(appContext, db, ledger, assets, categories, family, journal)
    val sync: SyncRepository = SyncRepository(db, journal, settings)
}
