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
    "workspace_entity_versions_v2",
    "workspace_entity_version_parents_v2",
    "workspace_entity_heads_v2",
    "note_projections_system_v2",
    "notebook_projections_v2",
    "workspace_preferences_projection_v2",
    "media_assets",
    "settings",
    "devices",
)

val dataBoundaryModule = module {
    single { LocalFirstBoundary() }
}
