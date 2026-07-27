@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.sync.webdav

import saien.someday.data.export.LocalDataExporter
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary
import saien.someday.data.export.LocalDataImporter
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.domain.settings.WebDavBackupResult
import saien.someday.domain.settings.WebDavBackupCatalogRunner
import saien.someday.domain.settings.WebDavBackupListResult
import saien.someday.domain.settings.WebDavBackupRunner
import saien.someday.domain.settings.WebDavBackupVersion
import saien.someday.domain.settings.WebDavConnectionInput
import saien.someday.domain.settings.WebDavConnectionTestResult
import saien.someday.domain.settings.WebDavConnectionTester
import saien.someday.domain.settings.WebDavRestoreResult
import saien.someday.domain.settings.WebDavRestoreRunner

class WebDavBackupService(
    private val localRepository: SqlDelightLocalDataRepository,
    private val transport: WebDavTransport,
    private val clock: () -> kotlin.time.Instant = { kotlin.time.Clock.System.now() },
    private val authoritativeDocumentProvider: ((kotlin.time.Instant) -> LocalDataExportDocument?)? = null,
    private val authoritativeImporter: ((LocalDataExportDocument) -> LocalDataImportSummary?)? = null,
) : WebDavConnectionTester,
    WebDavBackupRunner,
    WebDavBackupCatalogRunner,
    WebDavRestoreRunner {
    override fun testConnection(input: WebDavConnectionInput): WebDavConnectionTestResult =
        testWebDavConnection(input, transport)

    override fun backup(input: WebDavConnectionInput): WebDavBackupResult =
        runCatching {
            val sanitized = input.sanitized()
            val errors = sanitized.validate()
            require(errors.isEmpty()) { errors.joinToString(separator = " ") }

            val configuration = WebDavConfiguration.fromConnectionInput(sanitized)
            val exporter = LocalDataExporter(localRepository, clock, authoritativeDocumentProvider)
            val document = exporter.exportDocument()
            val payload = exporter.encodeDocument(document).encodeToByteArray()
            val client = WebDavBackupClient(configuration, transport)
            val version = client.uploadBackup(payload, clock())

            WebDavBackupResult(
                success = true,
                message = "WebDAV backup saved: ${document.notebooks.size} notebooks and ${document.notes.size} notes.",
                notebookCount = document.notebooks.size,
                noteCount = document.notes.size,
                version = version,
            )
        }.getOrElse { failure ->
            WebDavBackupResult.failure("WebDAV backup failed: ${failure.message ?: "unknown error"}")
        }

    override fun listBackups(input: WebDavConnectionInput): WebDavBackupListResult =
        runCatching {
            val sanitized = input.sanitized()
            val errors = sanitized.validate()
            require(errors.isEmpty()) { errors.joinToString(separator = " ") }

            val configuration = WebDavConfiguration.fromConnectionInput(sanitized)
            val versions = WebDavBackupClient(configuration, transport).listBackups()
            WebDavBackupListResult(
                success = true,
                message = if (versions.isEmpty()) {
                    "No WebDAV backups found."
                } else {
                    "Found ${versions.size} WebDAV backup versions."
                },
                versions = versions,
            )
        }.getOrElse { failure ->
            WebDavBackupListResult.failure("WebDAV backup list failed: ${failure.message ?: "unknown error"}")
        }

    override fun restore(
        input: WebDavConnectionInput,
        backupPath: String?,
    ): WebDavRestoreResult =
        runCatching {
            val sanitized = input.sanitized()
            val errors = sanitized.validate()
            require(errors.isEmpty()) { errors.joinToString(separator = " ") }

            val configuration = WebDavConfiguration.fromConnectionInput(sanitized)
            val client = WebDavBackupClient(configuration, transport)
            val payload = client.downloadBackup(backupPath)
                ?: return WebDavRestoreResult.failure("No WebDAV backup found.")
            val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)
            if (protocolStore.loadAuthoritativeEpoch() != null) {
                // Set before decoding/importing. A crash or invalid backup must
                // leave push fail-safe until a current authenticated pull.
                protocolStore.markBackupReconciliationPending(clock())
            }
            val summary = LocalDataImporter(localRepository, authoritativeImporter).importJson(payload.decodeToString())

            WebDavRestoreResult(
                success = true,
                message = summary.toRestoreMessage(),
                notebooksCreated = summary.notebooksCreated,
                notebooksReused = summary.notebooksReused,
                notesCreated = summary.notesCreated,
                notesUpdated = summary.notesUpdated,
                notesMerged = summary.notesMerged,
                noteConflictsCreated = summary.noteConflictsCreated,
                notesSkipped = summary.notesSkipped,
            )
        }.getOrElse { failure ->
            WebDavRestoreResult.failure("WebDAV restore failed: ${failure.message ?: "unknown error"}")
        }
}

private fun saien.someday.data.export.LocalDataImportSummary.toRestoreMessage(): String {
    val parts = buildList {
        if (notesCreated > 0) add("$notesCreated created")
        if (notesUpdated > 0) add("$notesUpdated updated")
        if (notesMerged > 0) add("$notesMerged merged")
        if (noteConflictsCreated > 0) add("$noteConflictsCreated conflict copies")
        if (notesSkipped > 0) add("$notesSkipped already present or older")
    }
    val details = parts.takeIf { it.isNotEmpty() }?.joinToString(separator = ", ") ?: "nothing changed"
    return "WebDAV backup restored: $details."
}

private class WebDavBackupClient(
    private val configuration: WebDavConfiguration,
    private val transport: WebDavTransport,
) {
    private val pathResolver = WebDavPathResolver(configuration.normalizedAppDirectory)
    private val backupDirectory: String = pathResolver.root + "backups/"
    private val latestBackupPath: String = backupDirectory + latestFileName

    fun uploadBackup(
        payload: ByteArray,
        timestamp: kotlin.time.Instant,
    ): WebDavBackupVersion {
        ensureDirectory(pathResolver.root)
        ensureDirectory(backupDirectory)
        val version = timestamp.toBackupVersion()
        uploadPayload(path = version.path ?: latestBackupPath, payload = payload)
        uploadPayload(path = latestBackupPath, payload = payload)
        return version
    }

    fun downloadBackup(path: String?): ByteArray? {
        val backupPath = path?.takeIf { it.isNotBlank() } ?: latestBackupPath
        val response = execute(
            WebDavRequest(
                method = "GET",
                path = backupPath,
                maxResponseBodyBytes = MAX_WEBDAV_BACKUP_BODY_BYTES,
            ),
        )
        if (response.status == 404) {
            return null
        }
        require(response.status == 200) {
            webDavHttpFailureMessage(
                status = response.status,
                operation = "downloading the backup",
                appDirectory = configuration.normalizedAppDirectory,
            )
        }
        return response.body
    }

    fun listBackups(): List<WebDavBackupVersion> {
        ensureDirectory(pathResolver.root)
        ensureDirectory(backupDirectory)
        val response = execute(
            WebDavRequest(
                method = "PROPFIND",
                path = backupDirectory,
                headers = mapOf(
                    "Depth" to "1",
                    "Content-Type" to "application/xml; charset=utf-8",
                ),
                body = propfindBody,
            ),
        )
        require(response.status == 207) {
            webDavHttpFailureMessage(
                status = response.status,
                operation = "reading backup versions",
                appDirectory = configuration.normalizedAppDirectory,
            )
        }
        return parsePropfindResponse(response.body.decodeToString())
            .asSequence()
            .filterNot { it.collection }
            .mapNotNull { it.path.toBackupVersionOrNull() }
            .distinctBy { it.path }
            .sortedWith(compareByDescending<WebDavBackupVersion> { it.id == latestVersionId }.thenByDescending { it.id })
            .toList()
    }

    private fun ensureDirectory(path: String) {
        val response = execute(WebDavRequest(method = "MKCOL", path = path))
        require(response.status == 201 || response.status == 200 || response.status == 405) {
            webDavHttpFailureMessage(
                status = response.status,
                operation = "creating the backup folder",
                appDirectory = configuration.normalizedAppDirectory,
            )
        }
    }

    private fun execute(request: WebDavRequest): WebDavResponse {
        val auth = configuration.authorizationHeader()
        val authorizedRequest = if (auth == null) {
            request
        } else {
            request.copy(headers = request.headers + ("Authorization" to auth))
        }
        return transport.execute(configuration, authorizedRequest)
    }

    private fun uploadPayload(
        path: String,
        payload: ByteArray,
    ) {
        val response = execute(
            WebDavRequest(
                method = "PUT",
                path = path,
                headers = mapOf("Content-Type" to backupContentType),
                body = payload,
                maxRequestBodyBytes = MAX_WEBDAV_BACKUP_BODY_BYTES,
            ),
        )
        require(response.status == 200 || response.status == 201 || response.status == 204) {
            webDavHttpFailureMessage(
                status = response.status,
                operation = "uploading the backup",
                appDirectory = configuration.normalizedAppDirectory,
            )
        }
    }

    private fun kotlin.time.Instant.toBackupVersion(): WebDavBackupVersion {
        val id = toString()
            .replace("-", "")
            .replace(":", "")
            .replace(".", "")
        return WebDavBackupVersion(
            id = id,
            label = "Snapshot $id",
            path = "$backupDirectory$id.json",
        )
    }

    private fun String.toBackupVersionOrNull(): WebDavBackupVersion? {
        val relativePath = toRelativeBackupPath() ?: return null
        val fileName = relativePath.substringAfterLast('/')
        if (!fileName.endsWith(".json")) {
            return null
        }
        val id = fileName.removeSuffix(".json")
        return if (id == latestVersionId) {
            WebDavBackupVersion(
                id = latestVersionId,
                label = "Latest backup",
                path = null,
            )
        } else {
            WebDavBackupVersion(
                id = id,
                label = "Snapshot $id",
                path = relativePath,
            )
        }
    }

    private fun String.toRelativeBackupPath(): String? {
        val trimmed = trim().trimStart('/')
        val marker = backupDirectory.trimStart('/')
        val index = trimmed.indexOf(marker)
        val relativePath = if (index >= 0) trimmed.substring(index) else trimmed
        return relativePath.takeIf { it.startsWith(backupDirectory) }
    }

    private companion object {
        const val backupContentType = "application/vnd.someday.local-export.v2+json"
        const val latestFileName = "latest.json"
        const val latestVersionId = "latest"
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:getetag/>
                <D:resourcetype/>
              </D:prop>
            </D:propfind>
        """.trimIndent().encodeToByteArray()
    }
}
