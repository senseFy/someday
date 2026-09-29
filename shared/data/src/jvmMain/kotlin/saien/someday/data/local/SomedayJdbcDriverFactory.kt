package saien.someday.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import saien.someday.data.local.db.SomedayDatabase
import org.sqlite.JDBC

fun createSomedayJdbcDriver(jdbcUrl: String): JdbcSqliteDriver {
    Class.forName(JDBC::class.java.name)
    // SQLDelight 2.1.0's schema-taking constructor silently accepts a future
    // user_version. Keep the entire schema lifecycle, including refusal, on
    // one shared-driver transaction before exposing the driver to any caller.
    val driver = JdbcSqliteDriver(url = jdbcUrl)
    try {
        val transacter = object : TransacterImpl(driver) {}
        transacter.transaction {
            val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
                check(cursor.next().value)
                QueryResult.Value(checkNotNull(cursor.getLong(0)))
            }, 0, null).value
            val schema = SomedayDatabase.Schema
            if (version > schema.version) throw UnsupportedLocalDatabaseVersion(version, schema.version)
            if (version == 0L) {
                schema.create(driver).value
            } else if (version < schema.version) {
                schema.migrate(driver, version, schema.version).value
            }
            if (version != schema.version) {
                driver.execute(null, "PRAGMA user_version = ${schema.version}", 0, null).value
            }
        }
        return driver
    } catch (failure: Throwable) {
        runCatching { driver.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

class UnsupportedLocalDatabaseVersion(val storedVersion: Long, val supportedVersion: Long) :
    IllegalStateException("Local database schema $storedVersion requires a newer application (supported: $supportedVersion).")
