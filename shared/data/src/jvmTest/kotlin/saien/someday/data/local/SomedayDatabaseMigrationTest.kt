package saien.someday.data.local

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION

class SomedayDatabaseMigrationTest {
    @Test
    fun nonemptyVersionOneUpgradesThroughSharedDriverWithoutChangingAnyLegacyValue() = withDirectory { directory ->
        val upgraded = directory.resolve("upgraded.db")
        copyResource("1.db", upgraded)
        val before = connect(upgraded).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                // SQLDelight schema snapshots omit installation user_version.
                statement.execute("PRAGMA user_version=1")
                resource("schema-v1-nonempty.sql").split(';').filter(String::isNotBlank).forEach(statement::execute)
            }
            assertHealthy(connection)
            legacyData(connection).also { tables ->
                assertEquals(23, tables.size)
                assertTrue(tables.values.all { it.rows.isNotEmpty() }, "Every original table must exercise preservation")
            }
        }
        createSomedayJdbcDriver(url(upgraded)).use { driver ->
            val database = SomedayDatabase(driver)
            assertEquals(INITIAL_ACCOUNT_INCARNATION, database.somedayQueries.selectLocalAuthoritySystemV2().executeAsOne().account_incarnation)
        }
        val fresh = directory.resolve("fresh.db")
        createSomedayJdbcDriver(url(fresh)).close()
        val snapshot = directory.resolve("snapshot.db")
        copyResource("3.db", snapshot)
        connect(upgraded).use { connection ->
            assertEquals(3L, version(connection))
            assertEquals(before, legacyData(connection, before.mapValues { it.value.columns }))
            assertHealthy(connection)
            connect(fresh).use { freshConnection -> assertEquals(catalog(freshConnection), catalog(connection)) }
            connect(snapshot).use { snapshotConnection -> assertEquals(catalog(snapshotConnection), catalog(connection)) }
            listOf("account_reset_intents", "account_workspace_gates", "account_protocol_capabilities").forEach { table ->
                assertEquals(listOf(listOf("0")), rows(connection, "SELECT count(*) FROM $table"))
            }
        }
        // A second open is a no-op, including the G0 media proof tuple and DAG BLOBs.
        createSomedayJdbcDriver(url(upgraded)).close()
        connect(upgraded).use { assertEquals(before, legacyData(it, before.mapValues { entry -> entry.value.columns })) }
    }

    @Test
    fun nonemptyVersionTwoRetainsPendingResetAndBackfillsNoDiscardConsent() = withDirectory { directory ->
        val upgraded = directory.resolve("version-two.db")
        copyResource("2.db", upgraded)
        val before = connect(upgraded).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA user_version=2")
                val seed = resource("schema-v1-nonempty.sql").replace(
                    "INSERT INTO sync_local_authority_system_v2 VALUES",
                    "INSERT INTO sync_local_authority_system_v2(singleton_id,remote_profile,epoch_id,local_writer_device_id,authority_binding_id,pointer_digest,updated_at) VALUES",
                )
                (seed + resource("schema-v2-account-state.sql")).split(';').filter(String::isNotBlank).forEach(statement::execute)
            }
            assertHealthy(connection)
            legacyData(connection).also { tables ->
                assertEquals(26, tables.size)
                assertTrue(tables.values.all { it.rows.isNotEmpty() })
            }
        }
        createSomedayJdbcDriver(url(upgraded)).close()
        val fresh = directory.resolve("fresh-three.db")
        createSomedayJdbcDriver(url(fresh)).close()
        val snapshot = directory.resolve("snapshot-three.db")
        copyResource("3.db", snapshot)
        connect(upgraded).use { connection ->
            assertEquals(3L, version(connection))
            assertEquals(before, legacyData(connection, before.mapValues { it.value.columns }))
            assertEquals(listOf(listOf(null)), rows(connection, "SELECT discard_target_incarnation FROM account_workspace_gates"))
            assertHealthy(connection)
            connect(fresh).use { assertEquals(catalog(it), catalog(connection)) }
            connect(snapshot).use { assertEquals(catalog(it), catalog(connection)) }
        }
        createSomedayJdbcDriver(url(upgraded)).close()
        connect(upgraded).use { assertEquals(before, legacyData(it, before.mapValues { entry -> entry.value.columns })) }
    }

    @Test
    fun futureSchemaIsRejectedBeforeAnySchemaOrDataMutation() = withDirectory { directory ->
        val future = directory.resolve("future.db")
        createSomedayJdbcDriver(url(future)).close()
        connect(future).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO settings VALUES ('sentinel','must survive',42)")
                statement.execute("PRAGMA user_version=4")
            }
        }
        val before = Files.readAllBytes(future)
        val failure = assertFailsWith<UnsupportedLocalDatabaseVersion> { createSomedayJdbcDriver(url(future)) }
        assertEquals(4L, failure.storedVersion)
        assertEquals(3L, failure.supportedVersion)
        assertTrue(before.contentEquals(Files.readAllBytes(future)), "Future database must remain byte-for-byte unchanged")
        connect(future).use { connection ->
            assertEquals(4L, version(connection))
            assertEquals(listOf(listOf("must survive")), rows(connection, "SELECT value FROM settings WHERE id='sentinel'"))
        }
    }

    private data class TableData(val columns: List<String>, val rows: List<List<String?>>)

    private fun legacyData(connection: Connection, selectedColumns: Map<String, List<String>>? = null): Map<String, TableData> {
        val tables = selectedColumns?.keys ?: rows(connection,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).map { requireNotNull(it.single()) }.toSet()
        return tables.associateWith { table ->
            val columns = selectedColumns?.get(table) ?: rows(connection, "PRAGMA table_info('$table')").map { requireNotNull(it[1]) }
            TableData(columns, rows(connection, "SELECT ${columns.joinToString { "\"$it\"" }} FROM \"$table\"").sortedBy { it.toString() })
        }
    }

    private fun catalog(connection: Connection): Map<String, List<List<String?>>> {
        val master = rows(connection,
            "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name",
        )
        return buildMap {
            put("sqlite_master", master.map { row -> row.take(3) + row[3]?.let(::normalizeSql) })
            master.filter { it[0] == "table" }.forEach { row ->
                val table = requireNotNull(row[1])
                put("$table:columns", rows(connection, "PRAGMA table_info('$table')"))
                put("$table:foreign_keys", rows(connection, "PRAGMA foreign_key_list('$table')"))
                put("$table:indexes", rows(connection, "PRAGMA index_list('$table')").sortedBy { it[1] })
            }
            master.filter { it[0] == "index" }.forEach { row ->
                val index = requireNotNull(row[1])
                put("$index:index_columns", rows(connection, "PRAGMA index_xinfo('$index')"))
            }
        }
    }

    private fun normalizeSql(sql: String): String =
        Regex("""'(?:''|[^'])*'|"(?:""|[^"])*"|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|\S""")
            .findAll(sql).joinToString(" ") { match ->
                match.value.let { if (it.startsWith("'")) it else it.lowercase(Locale.ROOT).trim('"') }
            }

    private fun rows(connection: Connection, sql: String): List<List<String?>> = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            buildList {
                while (result.next()) add((1..result.metaData.columnCount).map { index ->
                    when (val value = result.getObject(index)) {
                        is ByteArray -> "blob:" + value.joinToString("") { "%02x".format(it) }
                        else -> value?.toString()
                    }
                })
            }
        }
    }

    private fun assertHealthy(connection: Connection) {
        assertEquals(listOf(listOf("ok")), rows(connection, "PRAGMA integrity_check"))
        assertTrue(rows(connection, "PRAGMA foreign_key_check").isEmpty())
    }
    private fun version(connection: Connection): Long = requireNotNull(rows(connection, "PRAGMA user_version").single().single()).toLong()
    private fun url(path: Path) = "jdbc:sqlite:${path.toAbsolutePath()}"
    private fun connect(path: Path): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection(url(path))
    }
    private fun resource(name: String): String = requireNotNull(javaClass.classLoader.getResourceAsStream(name)).bufferedReader().use { it.readText() }
    private fun copyResource(name: String, target: Path) {
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)).use { Files.copy(it, target) }
    }
    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("someday-schema-upgrade-")
        try { block(directory) } finally { directory.toFile().deleteRecursively() }
    }
}
