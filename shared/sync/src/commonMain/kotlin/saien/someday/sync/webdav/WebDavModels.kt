@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package saien.someday.sync.webdav

import saien.someday.domain.settings.WebDavConnectionInput
import saien.someday.domain.settings.WebDavConnectionStatus
import saien.someday.domain.settings.WebDavConnectionTestResult
import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.domain.settings.normalizeWebDavAppDirectory
import kotlin.io.encoding.Base64

data class WebDavConfiguration(
    val endpoint: String,
    val username: String? = null,
    val password: String? = null,
    val appDirectory: String = "/someday/",
) {
    init {
        require(isSecureSyncEndpoint(endpoint)) {
            "WebDAV requires HTTPS unless the server is on this device's loopback interface."
        }
    }

    val normalizedEndpoint: String = endpoint.trim().trimEnd('/')
    val normalizedAppDirectory: String = normalizeWebDavAppDirectory(appDirectory)

    fun authorizationHeader(): String? {
        val user = username?.takeIf { it.isNotBlank() } ?: return null
        val secret = password?.takeIf { it.isNotBlank() } ?: return null
        return "Basic ${Base64.encode("$user:$secret".encodeToByteArray())}"
    }

    fun redactedDescription(): String =
        "endpoint=$normalizedEndpoint username=${username?.takeIf { it.isNotBlank() } ?: "anonymous"} " +
            "password=${if (password.isNullOrBlank()) "not-provided" else "redacted"} " +
            "appDirectory=$normalizedAppDirectory"

    companion object {
        fun fromConnectionInput(input: WebDavConnectionInput): WebDavConfiguration {
            val sanitized = input.sanitized()
            require(sanitized.validate().isEmpty()) {
                sanitized.validate().joinToString(separator = " ")
            }
            return WebDavConfiguration(
                endpoint = sanitized.endpoint,
                username = sanitized.username,
                password = sanitized.password,
                appDirectory = sanitized.appDirectory,
            )
        }
    }
}

data class WebDavRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val maxRequestBodyBytes: Int = MAX_WEBDAV_SYNC_BODY_BYTES,
    val maxResponseBodyBytes: Int = MAX_WEBDAV_SYNC_BODY_BYTES,
) {
    init {
        require(maxRequestBodyBytes > 0)
        require(maxResponseBodyBytes > 0)
        require(body == null || body.size <= maxRequestBodyBytes) {
            "WebDAV request exceeds the configured body limit."
        }
    }
}

const val MAX_WEBDAV_SYNC_BODY_BYTES: Int = 16 * 1_024 * 1_024
const val MAX_WEBDAV_BACKUP_BODY_BYTES: Int = 512 * 1_024 * 1_024

data class WebDavResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface WebDavTransport {
    fun execute(
        configuration: WebDavConfiguration,
        request: WebDavRequest,
    ): WebDavResponse
}

data class WebDavRemoteResource(
    val path: String,
    val etag: String? = null,
    val collection: Boolean = false,
)

data class WebDavRawStoredObject(
    val path: String,
    val etag: String?,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is WebDavRawStoredObject &&
        path == other.path && etag == other.etag && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * path.hashCode() + (etag?.hashCode() ?: 0)) + bytes.contentHashCode()
}

sealed interface WebDavRawUploadResult {
    data class Uploaded(val etag: String?) : WebDavRawUploadResult
    data class PreconditionConflict(val remote: WebDavRawStoredObject?, val safeMessage: String) : WebDavRawUploadResult
    data class Rejected(val safeMessage: String) : WebDavRawUploadResult
}

fun Throwable.toWebDavConnectionFailure(appDirectory: String): WebDavConnectionTestResult =
    WebDavConnectionTestResult(
        success = false,
        status = WebDavConnectionStatus(
            ready = false,
            message = toWebDavUserMessage(),
            appDirectory = appDirectory,
        ),
    )

internal fun webDavHttpFailureMessage(
    status: Int,
    operation: String,
    appDirectory: String,
): String =
    when (status) {
        401 -> "WebDAV authentication failed. Check the username and saved credential for this server; credentials redacted."
        403 -> "WebDAV permission denied. Check that this account can create and edit $appDirectory; credentials redacted."
        404 -> "WebDAV folder was not found. Check the server URL and WebDAV base path; credentials redacted."
        in 300..399 -> "WebDAV server redirected the request. Use the final WebDAV URL from your provider; credentials redacted."
        else -> "WebDAV server returned HTTP $status while $operation; credentials redacted."
    }

private fun Throwable.toWebDavUserMessage(): String {
    val raw = message.orEmpty()
    return if (raw.startsWith("WebDAV ") && !raw.contains("MKCOL") && !raw.contains("PROPFIND")) {
        raw
    } else {
        "WebDAV connection failed. Check the server URL, username, credential, and folder permission; credentials redacted."
    }
}
