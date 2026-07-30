package saien.someday.sync.webdav

import saien.someday.domain.settings.WebDavConnectionInput
import saien.someday.domain.settings.WebDavConnectionStatus
import saien.someday.domain.settings.WebDavConnectionTestResult
import saien.someday.domain.settings.webDavV2AuthorityBindingId

class WebDavClient(
    private val configuration: WebDavConfiguration,
    private val transport: WebDavTransport,
) {
    private val pathResolver = WebDavPathResolver(configuration.normalizedAppDirectory)

    /** Contains no credential; used only to prevent silent endpoint authority changes. */
    fun v2AuthorityBindingId(): String =
        webDavV2AuthorityBindingId(configuration.normalizedEndpoint, configuration.normalizedAppDirectory)

    fun discover(): List<WebDavRemoteResource> {
        ensureAppDirectory()
        return propfind(pathResolver.root)
            .filter { resource ->
                resource.path.contains("/${pathResolver.root}") || resource.path.contains(pathResolver.root)
            }
    }

    fun discoverRecursively(maxDepth: Int = 6): List<WebDavRemoteResource> {
        ensureAppDirectory()
        val discovered = mutableListOf<WebDavRemoteResource>()
        val visitedCollections = mutableSetOf<String>()

        fun visit(collectionPath: String, depth: Int) {
            val normalizedCollection = collectionPath.asCollectionPath()
            if (depth > maxDepth || !visitedCollections.add(normalizedCollection)) {
                return
            }
            propfind(normalizedCollection).forEach { resource ->
                val relativePath = pathResolver.relativePath(resource.path) ?: return@forEach
                if (relativePath == normalizedCollection) {
                    return@forEach
                }
                val normalized = if (resource.collection) {
                    relativePath.asCollectionPath()
                } else {
                    relativePath
                }
                discovered += resource.copy(path = normalized)
                if (resource.collection) {
                    visit(normalized, depth + 1)
                }
            }
        }

        visit(pathResolver.root, depth = 0)
        return discovered.distinctBy { it.path }
    }

    fun listDirectory(path: String): List<WebDavRemoteResource> {
        val collectionPath = path.asCollectionPath()
        ensureDirectoryTree(collectionPath)
        return propfind(collectionPath)
            .mapNotNull { resource ->
                val relativePath = pathResolver.relativePath(resource.path) ?: return@mapNotNull null
                val normalized = if (resource.collection) {
                    relativePath.asCollectionPath()
                } else {
                    relativePath.trimEnd('/')
                }
                resource.copy(path = normalized)
            }
            .filterNot { resource -> resource.path == collectionPath }
            .distinctBy { it.path }
    }

    fun testConnection(): WebDavConnectionTestResult =
        runCatching {
            ensureAppDirectory()
            discover()
            WebDavConnectionTestResult(
                success = true,
                status = WebDavConnectionStatus(
                    ready = true,
                    message = "WebDAV connection succeeded for ${configuration.normalizedAppDirectory}; credentials redacted.",
                    appDirectory = configuration.normalizedAppDirectory,
                ),
            )
        }.getOrElse { failure ->
            failure.toWebDavConnectionFailure(configuration.normalizedAppDirectory)
        }

    fun uploadRawAppendOnly(path: String, bytes: ByteArray): WebDavRawUploadResult {
        require(bytes.size <= MAX_WEBDAV_SYNC_BODY_BYTES) { "WebDAV V2 object exceeds the encoded body limit." }
        ensureParentDirectories(path)
        val response = execute(
            WebDavRequest(
                method = "PUT",
                path = path,
                headers = mapOf(
                    "If-None-Match" to "*",
                    "Content-Type" to encryptedObjectContentType,
                ),
                body = bytes,
            ),
        )
        return when {
            response.status.isSuccessfulWrite() -> WebDavRawUploadResult.Uploaded(response.etag())
            response.status == 412 -> WebDavRawUploadResult.PreconditionConflict(
                getRawObject(path),
                "Remote WebDAV path already exists and must be authenticated before replay.",
            )
            else -> WebDavRawUploadResult.Rejected(
                webDavHttpFailureMessage(response.status, "uploading V2 data", configuration.normalizedAppDirectory),
            )
        }
    }

    fun uploadRawMutable(path: String, bytes: ByteArray, previousEtag: String?): WebDavRawUploadResult {
        require(bytes.size <= MAX_WEBDAV_SYNC_BODY_BYTES) { "WebDAV V2 object exceeds the encoded body limit." }
        ensureParentDirectories(path)
        val headers = buildMap {
            put("Content-Type", encryptedObjectContentType)
            if (previousEtag == null) put("If-None-Match", "*")
            else put("If-Match", previousEtag.asStrongIfMatchValidator())
        }
        val response = execute(WebDavRequest("PUT", path, headers, bytes))
        return when {
            response.status.isSuccessfulWrite() -> WebDavRawUploadResult.Uploaded(response.etag())
            response.status == 412 -> WebDavRawUploadResult.PreconditionConflict(
                getRawObject(path),
                "Remote WebDAV register changed before conditional publication.",
            )
            else -> WebDavRawUploadResult.Rejected(
                webDavHttpFailureMessage(response.status, "updating V2 data", configuration.normalizedAppDirectory),
            )
        }
    }

    fun getRawObject(path: String): WebDavRawStoredObject? {
        val response = execute(WebDavRequest(method = "GET", path = path))
        if (response.status == 404) return null
        require(response.status == 200) {
            webDavHttpFailureMessage(
                status = response.status,
                operation = "downloading V2 data",
                appDirectory = configuration.normalizedAppDirectory,
            )
        }
        return WebDavRawStoredObject(path, response.etag(), response.body)
    }

    /**
     * Deletes one exact immutable object and fails closed if its bytes changed
     * or the server does not provide an ETag for a conditional DELETE.
     */
    fun deleteRawObjectIfUnchanged(
        path: String,
        expectedBytes: ByteArray,
    ): WebDavRawDeleteResult {
        val current = getRawObject(path) ?: return WebDavRawDeleteResult.Deleted(alreadyAbsent = true)
        if (!current.bytes.contentEquals(expectedBytes)) {
            return WebDavRawDeleteResult.Rejected(
                "Remote WebDAV object changed before exact checkpoint cleanup.",
            )
        }
        val etag = current.etag ?: return WebDavRawDeleteResult.Rejected(
            "Remote WebDAV server omitted the ETag required for exact checkpoint cleanup.",
        )
        if (etag.startsWith("W/", ignoreCase = true)) {
            return WebDavRawDeleteResult.Rejected(
                "Remote WebDAV server supplied only a weak ETag for exact checkpoint cleanup.",
            )
        }
        val response = execute(
            WebDavRequest(
                method = "DELETE",
                path = path,
                headers = mapOf("If-Match" to etag),
            ),
        )
        return when {
            response.status.isSuccessfulWrite() -> WebDavRawDeleteResult.Deleted(alreadyAbsent = false)
            response.status == 404 -> WebDavRawDeleteResult.Deleted(alreadyAbsent = true)
            response.status == 412 -> WebDavRawDeleteResult.Rejected(
                "Remote WebDAV object changed during exact checkpoint cleanup.",
            )
            else -> WebDavRawDeleteResult.Rejected(
                webDavHttpFailureMessage(response.status, "cleaning an obsolete V2 checkpoint", configuration.normalizedAppDirectory),
            )
        }
    }

    fun pathResolver(): WebDavPathResolver = pathResolver

    private fun ensureAppDirectory() {
        ensureDirectory(pathResolver.root)
    }

    private fun ensureParentDirectories(path: String) {
        pathResolver.parentDirectories(path).forEach(::ensureDirectory)
    }

    private fun ensureDirectoryTree(path: String) {
        val markerFile = path.asCollectionPath() + ".dir"
        ensureParentDirectories(markerFile)
    }

    private fun ensureDirectory(path: String) {
        val response = execute(WebDavRequest(method = "MKCOL", path = path))
        require(response.status == 201 || response.status == 200 || response.status == 405) {
            webDavHttpFailureMessage(
                status = response.status,
                operation = "creating the app folder",
                appDirectory = configuration.normalizedAppDirectory,
            )
        }
    }

    private fun propfind(path: String): List<WebDavRemoteResource> {
        val response = execute(
            WebDavRequest(
                method = "PROPFIND",
                path = path,
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
                operation = "reading the app folder",
                appDirectory = configuration.normalizedAppDirectory,
            )
        }
        return parsePropfindResponse(response.body.decodeToString())
    }

    private fun execute(request: WebDavRequest): WebDavResponse {
        val auth = configuration.authorizationHeader()
        return transport.execute(
            configuration,
            if (auth == null) {
                request
            } else {
                request.copy(headers = request.headers + ("Authorization" to auth))
            },
        )
    }

    private fun WebDavResponse.etag(): String? = header("ETag")

    private fun Int.isSuccessfulWrite(): Boolean = this == 200 || this == 201 || this == 204

    private fun String.asStrongIfMatchValidator(): String =
        removePrefix("W/").removePrefix("w/")

    companion object {
        private const val encryptedObjectContentType = "application/vnd.someday.sync-object+json"
        private val propfindBody = """
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

private fun String.asCollectionPath(): String =
    trimStart('/').let { path -> if (path.endsWith("/")) path else "$path/" }

fun testWebDavConnection(
    input: WebDavConnectionInput,
    transport: WebDavTransport,
): WebDavConnectionTestResult {
    val errors = input.validate()
    if (errors.isNotEmpty()) {
        return WebDavConnectionTestResult.validationFailed(errors)
    }
    return WebDavClient(
        configuration = WebDavConfiguration.fromConnectionInput(input),
        transport = transport,
    ).testConnection()
}

internal fun parsePropfindResponse(xml: String): List<WebDavRemoteResource> {
    val responseRegex = Regex(
        pattern = """<[^>]*response[^>]*>(.*?)</[^>]*response>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val hrefRegex = Regex(
        pattern = """<[^>]*href[^>]*>\s*(.*?)\s*</[^>]*href>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val etagRegex = Regex(
        pattern = """<[^>]*getetag[^>]*>\s*(.*?)\s*</[^>]*getetag>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    return responseRegex.findAll(xml).mapNotNull { match ->
        val block = match.groupValues[1]
        val href = hrefRegex.find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        WebDavRemoteResource(
            path = href,
            etag = etagRegex.find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() },
            collection = block.contains("collection", ignoreCase = true),
        )
    }.toList()
}
