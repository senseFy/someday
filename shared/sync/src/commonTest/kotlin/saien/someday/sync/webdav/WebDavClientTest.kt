package saien.someday.sync.webdav

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebDavClientTest {
    @Test
    fun discoveryUsesPropfindUnderAppOwnedDirectory() {
        val transport = RecordingWebDavTransport()
        val client = client(transport)

        client.discover()

        assertTrue(transport.requests.any { it.method == "MKCOL" && it.path == "someday-test/" })
        assertTrue(transport.requests.any { it.method == "PROPFIND" && it.path == "someday-test/" })
        assertEquals("1", transport.requests.last { it.method == "PROPFIND" }.headers["Depth"])
    }

    @Test
    fun authenticationFailureRedactsCredentialsAndInternalMethods() {
        val result = WebDavClient(
            configuration = WebDavConfiguration(
                endpoint = "http://127.0.0.1:3182",
                username = "alice",
                password = "secret-token",
                appDirectory = "/someday-test/",
            ),
            transport = WebDavTransport { _, _ -> WebDavResponse(status = 401) },
        ).testConnection()

        assertFalse(result.success)
        assertTrue(result.status.message.contains("authentication failed"))
        assertTrue(result.status.message.contains("credentials redacted"))
        assertFalse(result.status.message.contains("MKCOL"))
        assertFalse(result.status.message.contains("secret-token"))
    }

    @Test
    fun rawRegistersUseConditionalCreateAndUpdateWithoutBlindOverwrite() {
        val transport = RecordingWebDavTransport()
        val client = client(transport)
        val path = client.pathResolver().v2EpochPointer()

        val created = assertIs<WebDavRawUploadResult.Uploaded>(
            client.uploadRawMutable(path, "pointer-one".encodeToByteArray(), previousEtag = null),
        )
        val updated = assertIs<WebDavRawUploadResult.Uploaded>(
            client.uploadRawMutable(
                path,
                "pointer-two".encodeToByteArray(),
                previousEtag = assertNotNull(created.etag),
            ),
        )
        val stale = client.uploadRawMutable(
            path,
            "blind-overwrite".encodeToByteArray(),
            previousEtag = "stale-etag",
        )

        assertNotNull(updated.etag)
        assertIs<WebDavRawUploadResult.PreconditionConflict>(stale)
        assertContentEquals("pointer-two".encodeToByteArray(), assertNotNull(client.getRawObject(path)).bytes)
        assertTrue(transport.requests.any { it.method == "PUT" && it.headers["If-None-Match"] == "*" })
        assertTrue(transport.requests.any { it.method == "PUT" && it.headers["If-Match"] == created.etag })
    }

    @Test
    fun immutableRawObjectsRejectDifferentDuplicateBytes() {
        val transport = RecordingWebDavTransport()
        val client = client(transport)
        val path = client.pathResolver().v2CheckpointManifest("epoch", "checkpoint")

        assertIs<WebDavRawUploadResult.Uploaded>(
            client.uploadRawAppendOnly(path, "manifest-one".encodeToByteArray()),
        )
        val replay = assertIs<WebDavRawUploadResult.PreconditionConflict>(
            client.uploadRawAppendOnly(path, "manifest-one".encodeToByteArray()),
        )
        val mismatch = assertIs<WebDavRawUploadResult.PreconditionConflict>(
            client.uploadRawAppendOnly(path, "manifest-two".encodeToByteArray()),
        )

        assertContentEquals("manifest-one".encodeToByteArray(), assertNotNull(replay.remote).bytes)
        assertContentEquals("manifest-one".encodeToByteArray(), assertNotNull(mismatch.remote).bytes)
    }

    private fun client(transport: RecordingWebDavTransport): WebDavClient =
        WebDavClient(
            configuration = WebDavConfiguration(
                endpoint = "http://127.0.0.1:3182",
                appDirectory = "/someday-test/",
            ),
            transport = transport,
        )
}

private class RecordingWebDavTransport : WebDavTransport {
    val requests = mutableListOf<WebDavRequest>()
    private val objects = mutableMapOf<String, Stored>()
    private val collections = mutableSetOf<String>()
    private var nextEtag = 1

    override fun execute(
        configuration: WebDavConfiguration,
        request: WebDavRequest,
    ): WebDavResponse {
        requests += request
        return when (request.method) {
            "MKCOL" -> {
                val created = collections.add(request.path)
                WebDavResponse(status = if (created) 201 else 405)
            }

            "PROPFIND" -> WebDavResponse(
                status = 207,
                body = multistatus(request.path).encodeToByteArray(),
            )

            "GET" -> objects[request.path]?.let { stored ->
                WebDavResponse(status = 200, headers = mapOf("ETag" to stored.etag), body = stored.body)
            } ?: WebDavResponse(status = 404)

            "PUT" -> put(request)
            else -> WebDavResponse(status = 405)
        }
    }

    private fun put(request: WebDavRequest): WebDavResponse {
        val existing = objects[request.path]
        if (request.headers["If-None-Match"] == "*" && existing != null) {
            return WebDavResponse(status = 412)
        }
        request.headers["If-Match"]?.let { expected ->
            if (existing?.etag != expected) return WebDavResponse(status = 412)
        }
        val stored = Stored("\"etag-${nextEtag++}\"", checkNotNull(request.body))
        objects[request.path] = stored
        return WebDavResponse(status = if (existing == null) 201 else 204, headers = mapOf("ETag" to stored.etag))
    }

    private fun multistatus(path: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/$path</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
          </D:response>
        </D:multistatus>""".trimIndent()

    private data class Stored(val etag: String, val body: ByteArray)
}
