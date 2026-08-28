package saien.someday.server

import java.io.PrintStream
import kotlin.system.exitProcess
import saien.someday.server.media.MediaBlobStore
import saien.someday.server.media.MediaIntegrityIssue
import saien.someday.server.media.MediaIntegrityReport
import saien.someday.server.media.MediaIntegrityVerifier
import saien.someday.server.persistence.DatabaseConnectionPool
import saien.someday.server.persistence.MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES
import saien.someday.server.persistence.PostgresMediaIntegrityRecordSource

internal const val MEDIA_INTEGRITY_EXIT_OPERATIONAL_FAILURE = 1
internal const val MEDIA_INTEGRITY_EXIT_INVALID_RECOVERY_SET = 2

fun main() {
    val exitCode = runMediaIntegrityVerifierCli(
        environment = System.getenv(),
        standardOutput = System.out,
        standardError = System.err,
    )
    if (exitCode != 0) exitProcess(exitCode)
}

internal fun runMediaIntegrityVerifierCli(
    environment: Map<String, String>,
    standardOutput: PrintStream,
    standardError: PrintStream,
    verify: (ServerConfig, (MediaIntegrityIssue) -> Unit) -> MediaIntegrityReport = ::verifyConfiguredMedia,
): Int = try {
    val config = ServerConfig.fromEnvironment(environment)
    val report = verify(config) { issue -> standardError.println(issue.operatorMessage()) }
    if (report.isValid) {
        standardOutput.println(
            "Media integrity verification passed: ${report.checkedObjects} referenced object(s) are valid.",
        )
        0
    } else {
        standardError.println(
            "Media integrity verification failed: ${report.invalidObjects} of " +
                "${report.checkedObjects} referenced object(s) are invalid.",
        )
        MEDIA_INTEGRITY_EXIT_INVALID_RECOVERY_SET
    }
} catch (failure: Exception) {
    standardError.println(
        "Media integrity verification did not complete: " +
            (failure.message?.takeIf(String::isNotBlank) ?: failure::class.simpleName ?: "unknown failure"),
    )
    MEDIA_INTEGRITY_EXIT_OPERATIONAL_FAILURE
}

private fun verifyConfiguredMedia(
    config: ServerConfig,
    onIssue: (MediaIntegrityIssue) -> Unit,
): MediaIntegrityReport = DatabaseConnectionPool.create(config).use { connections ->
    createConfiguredMediaBlobStore(config).useStore { blobStore ->
        MediaIntegrityVerifier(
            records = PostgresMediaIntegrityRecordSource(connections),
            blobStore = blobStore,
            maxObjectBytes = MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES,
        ).verify(onIssue)
    }
}

private inline fun <T> MediaBlobStore.useStore(block: (MediaBlobStore) -> T): T {
    val closeable = this as? AutoCloseable ?: return block(this)
    return closeable.use { block(this) }
}

private fun MediaIntegrityIssue.operatorMessage(): String = buildString {
    append("invalid media object: reason=")
    append(reason.name.lowercase())
    append(" user=")
    append(record.key.userId)
    append(" workspace=")
    append(record.key.workspaceId)
    append(" media=")
    append(record.key.mediaId)
    append(" expected_bytes=")
    append(record.expected.bytes)
    append(" expected_sha256=")
    append(record.expected.sha256)
    actual?.let {
        append(" actual_bytes=")
        append(it.bytes)
        append(" actual_sha256=")
        append(it.sha256)
    }
}
