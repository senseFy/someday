@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.local

import app.cash.sqldelight.db.SqlDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import org.koin.dsl.module

fun createSomedayDatabase(driver: SqlDriver): SomedayDatabase = SomedayDatabase(driver)

fun localDataModule(
    driver: SqlDriver,
    deviceId: String,
) = module {
    single { createSomedayDatabase(driver) }
    single {
        SqlDelightLocalDataRepository(
            database = get(),
            deviceId = deviceId,
        )
    }
    single<ClientSettingsRepository> { SqlDelightClientSettingsRepository(get()) }
}
