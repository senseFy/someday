package saien.someday.sync.webdav

import kotlin.time.Instant
import kotlinx.serialization.SerializationException

fun interface WebDavSyncDiagnosticLogger {
    fun log(message: String)

    companion object {
        val NoOp = WebDavSyncDiagnosticLogger { }
    }
}

internal const val WEB_DAV_SYNC_DIAGNOSTICS_LAST_RUN_KEY = "client.sync.webdav_diagnostics.last_run"

internal class WebDavSyncDiagnosticBuffer(
    private val clock: () -> Instant,
) : WebDavSyncDiagnosticLogger {
    private val lines = ArrayDeque<String>()

    override fun log(message: String) {
        lines.addLast("${clock()} ${message.take(MaxDiagnosticLineLength)}")
        while (lines.size > MaxDiagnosticLines) {
            lines.removeFirst()
        }
    }

    fun snapshot(): String =
        lines.joinToString(separator = "\n")
}

internal fun WebDavSyncDiagnosticLogger.tee(
    other: WebDavSyncDiagnosticLogger,
): WebDavSyncDiagnosticLogger =
    WebDavSyncDiagnosticLogger { message ->
        runCatching { log(message) }
        runCatching { other.log(message) }
    }

internal fun WebDavSyncDiagnosticLogger.webDavSyncDiagnostic(
    event: String,
    vararg fields: Pair<String, Any?>,
) {
    val message =
        buildString {
            append("event=")
            append(event)
            fields.forEach { (key, value) ->
                append(' ')
                append(key)
                append('=')
                append(value.toDiagnosticValue())
            }
        }
    runCatching { log(message) }
}

internal fun redactedDiagnosticHash(
    scope: String,
    value: String?,
): String =
    value
        ?.takeIf { it.isNotBlank() }
        ?.let { stableWebDavShardHash(scope, it) }
        ?: "none"

internal fun Throwable.webDavDiagnosticCode(): String =
    when {
        isWebDavRemoteAuthenticationFailure() -> "remote_authentication"
        this is SerializationException -> "serialization"
        this is IllegalArgumentException -> "illegal_argument"
        this is IllegalStateException -> "illegal_state"
        else -> "failure"
    }

internal fun Throwable.isWebDavRemoteAuthenticationFailure(): Boolean =
    this is SerializationException &&
        message.orEmpty().contains("could not be authenticated", ignoreCase = true)

private fun Any?.toDiagnosticValue(): String =
    when (this) {
        null -> "null"
        is Boolean,
        is Number,
        -> toString()

        else -> toString()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^A-Za-z0-9_.,:;=+@%/-]"), "?")
            .take(MaxDiagnosticValueLength)
    }

private const val MaxDiagnosticValueLength = 160
private const val MaxDiagnosticLineLength = 512
private const val MaxDiagnosticLines = 256
