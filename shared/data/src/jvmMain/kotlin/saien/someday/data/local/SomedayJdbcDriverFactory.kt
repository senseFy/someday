package saien.someday.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import saien.someday.data.local.db.SomedayDatabase
import org.sqlite.JDBC

fun createSomedayJdbcDriver(jdbcUrl: String): JdbcSqliteDriver {
    Class.forName(JDBC::class.java.name)
    return JdbcSqliteDriver(
        url = jdbcUrl,
        schema = SomedayDatabase.Schema,
    )
}
