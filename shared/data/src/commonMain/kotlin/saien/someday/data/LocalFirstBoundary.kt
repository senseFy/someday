@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import okio.Path
import okio.Path.Companion.toPath
import org.koin.dsl.module

@Serializable
data class LocalFirstBoundary(
    val storageEngine: String = "SQLDelight/SQLite",
    val localWritesRequireNetwork: Boolean = false,
    val requiredTables: List<String> = requiredLocalTables,
    val initializedAt: Instant = Instant.fromEpochSeconds(0),
) {
    fun appDirectory(): Path = "someday".toPath()

    fun smokeDescription(): String =
        "storage=$storageEngine offlineWritesRequireNetwork=$localWritesRequireNetwork tables=${requiredTables.joinToString("|")}"
}

val requiredLocalTables: List<String> = listOf(
    "notebooks",
    "notes",
    "note_versions",
    "tombstones",
    "locations",
    "sync_metadata",
    "settings",
    "devices",
)

val dataBoundaryModule = module {
    single { LocalFirstBoundary() }
}
