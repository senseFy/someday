package saien.someday.server.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception

class S3MediaBlobStoreTest {
    @Test
    fun usesThePrivateWorkspaceScopedKeyAndConditionalSingleObjectPut() = withServer { server ->
        createStore(server).use { store ->
            val bytes = "opaque-client-ciphertext".encodeToByteArray()
            val digest = sha256(bytes)

            assertEquals(MediaBlobPutResult.Stored(false), store.putImmutable(KEY, bytes, digest))
            assertEquals(MediaBlobMetadata(bytes.size.toLong(), digest), store.head(KEY))
            val read = checkNotNull(store.read(KEY, bytes.size))
            assertEquals(MediaBlobMetadata(bytes.size.toLong(), digest), read.metadata)
            assertContentEquals(bytes, read.bytes)

            val put = server.calls.single { it.method == "PUT" }
            assertEquals(EXPECTED_PATH, put.path)
            assertEquals("*", put.headers["if-none-match"])
            assertEquals(digest, put.headers["x-amz-meta-someday-ciphertext-sha256"])
            assertEquals("application/octet-stream", put.headers["content-type"])
            assertContentEquals(bytes, put.body)
            assertEquals(listOf("PUT", "HEAD", "GET"), server.calls.map(CapturedCall::method))
        }
    }

    @Test
    fun provesExactReplayFromActualBytesAfterConditionalPutCollision() = withServer { server ->
        createStore(server).use { store ->
            val bytes = ByteArray(97) { it.toByte() }
            val digest = sha256(bytes)

            assertEquals(MediaBlobPutResult.Stored(false), store.putImmutable(KEY, bytes, digest))
            assertEquals(MediaBlobPutResult.Stored(true), store.putImmutable(KEY, bytes, digest))

            assertEquals(listOf("PUT", "PUT", "GET"), server.calls.map(CapturedCall::method))
            assertContentEquals(bytes, server.objectAt(EXPECTED_PATH)?.bytes)
        }
    }

    @Test
    fun verifiesR2BucketLockConflictAgainstTheRetainedObject() = withServer { server ->
        server.existingPutStatus = 409
        server.existingPutErrorCode = "ObjectLockedByBucketPolicy"
        createStore(server).use { store ->
            val first = ByteArray(97) { it.toByte() }
            val second = ByteArray(first.size) { 42 }

            assertEquals(MediaBlobPutResult.Stored(false), store.putImmutable(KEY, first, sha256(first)))
            assertEquals(MediaBlobPutResult.Stored(true), store.putImmutable(KEY, first, sha256(first)))
            assertEquals(MediaBlobPutResult.ImmutableMismatch, store.putImmutable(KEY, second, sha256(second)))

            assertEquals(listOf("PUT", "PUT", "GET", "PUT", "GET"), server.calls.map(CapturedCall::method))
            assertContentEquals(first, server.objectAt(EXPECTED_PATH)?.bytes)
        }
    }

    @Test
    fun propagatesUnrelatedConflictInsteadOfTreatingItAsAnImmutableCollision() = withServer { server ->
        createStore(server).use { store ->
            val bytes = ByteArray(97) { it.toByte() }
            store.putImmutable(KEY, bytes, sha256(bytes))
            server.existingPutStatus = 409
            server.existingPutErrorCode = "BucketConflict"

            val failure = assertFailsWith<S3Exception> {
                store.putImmutable(KEY, bytes, sha256(bytes))
            }

            assertEquals(409, failure.statusCode())
            assertEquals("BucketConflict", failure.awsErrorDetails()?.errorCode())
            assertEquals(listOf("PUT", "PUT"), server.calls.map(CapturedCall::method))
        }
    }

    @Test
    fun neverTrustsMetadataOrEtagWhenAnExistingObjectsBytesDiverge() = withServer { server ->
        createStore(server).use { store ->
            val expected = ByteArray(64) { 1 }
            val expectedDigest = sha256(expected)
            store.putImmutable(KEY, expected, expectedDigest)

            server.replaceObjectBytes(EXPECTED_PATH, ByteArray(expected.size) { 2 })
            assertEquals(MediaBlobPutResult.ImmutableMismatch, store.putImmutable(KEY, expected, expectedDigest))

            assertEquals(MediaBlobMetadata(expected.size.toLong(), expectedDigest), store.head(KEY))
            assertNull(store.read(KEY, expected.size))
            assertContentEquals(ByteArray(expected.size) { 2 }, server.objectAt(EXPECTED_PATH)?.bytes)
        }
    }

    @Test
    fun rejectsDifferentImmutableValuesAndLeavesTheFirstObjectUntouched() = withServer { server ->
        createStore(server).use { store ->
            val first = ByteArray(64) { 3 }
            val second = ByteArray(64) { 4 }
            store.putImmutable(KEY, first, sha256(first))

            assertEquals(MediaBlobPutResult.ImmutableMismatch, store.putImmutable(KEY, second, sha256(second)))
            assertContentEquals(first, server.objectAt(EXPECTED_PATH)?.bytes)
            assertTrue(
                server.calls.all { call ->
                    call.path == EXPECTED_PATH && call.method in setOf("PUT", "HEAD", "GET")
                },
            )
        }
    }

    @Test
    fun treatsMissingObjectsOrInvalidDigestMetadataAsUnavailable() = withServer { server ->
        createStore(server).use { store ->
            assertNull(store.head(KEY))
            assertNull(store.read(KEY, MAX_OBJECT_BYTES))

            val bytes = ByteArray(32) { 5 }
            store.putImmutable(KEY, bytes, sha256(bytes))
            server.replaceObjectMetadata(EXPECTED_PATH, "not-a-canonical-digest")
            assertNull(store.head(KEY))
            assertNull(store.read(KEY, MAX_OBJECT_BYTES))
        }
    }

    @Test
    fun propagatesAccessDeniedInsteadOfMisreportingItAsAMissingObject() = withServer { server ->
        server.denyObjectReads = true
        createStore(server).use { store ->
            val headFailure = assertFailsWith<S3Exception> { store.head(KEY) }
            val readFailure = assertFailsWith<S3Exception> { store.read(KEY, MAX_OBJECT_BYTES) }

            assertEquals(403, headFailure.statusCode())
            assertEquals(403, readFailure.statusCode())
        }
    }

    @Test
    fun enforcesUploadAndDownloadBoundsBeforeMaterializingUnboundedData() = withServer { server ->
        createStore(server, maxObjectBytes = 64).use { store ->
            val oversized = ByteArray(65)
            assertFailsWith<IllegalArgumentException> {
                store.putImmutable(KEY, oversized, sha256(oversized))
            }
            assertTrue(server.calls.isEmpty())

            val allowed = ByteArray(64) { 6 }
            store.putImmutable(KEY, allowed, sha256(allowed))
            assertFailsWith<IllegalArgumentException> { store.read(KEY, 63) }
        }
    }

    @Test
    fun failsClosedWhenTheConfiguredWholeCallTimeoutExpires() = withServer { server ->
        server.putDelay = Duration.ofSeconds(2)
        createStore(
            server = server,
            apiCallTimeout = Duration.ofMillis(300),
            apiCallAttemptTimeout = Duration.ofMillis(100),
        ).use { store ->
            val bytes = ByteArray(32) { 7 }
            assertFailsWith<SdkClientException> { store.putImmutable(KEY, bytes, sha256(bytes)) }
        }
    }

    @Test
    fun startupProbeUsesOneRetainedSystemKeyAndVerifiesImmutableReplay() = withServer { server ->
        createStore(server).use { store ->
            verifyMediaBlobStoreStartup(store)
            verifyMediaBlobStoreStartup(store)

            assertTrue(server.calls.isNotEmpty())
            assertEquals(
                setOf(STARTUP_PROBE_PATH, MISSING_STARTUP_PROBE_PATH),
                server.calls.map(CapturedCall::path).toSet(),
            )
            assertTrue(server.objectAt(STARTUP_PROBE_PATH) != null)
            assertNull(server.objectAt(MISSING_STARTUP_PROBE_PATH))
            assertTrue(server.calls.any { it.method == "HEAD" })
            assertTrue(server.calls.any { it.method == "GET" })
            val missingGets = server.calls.filter {
                it.method == "GET" && it.path == MISSING_STARTUP_PROBE_PATH
            }
            assertTrue(
                missingGets.isNotEmpty() && missingGets.all { it.headers["range"] == "bytes=0-0" },
            )
            assertTrue(server.calls.count { it.method == "PUT" } >= 6)
        }
    }

    @Test
    fun startupProbeFailsWhenMissingHeadAccessCannotProveAbsence() = withServer { server ->
        createStore(server).use { store ->
            verifyMediaBlobStoreStartup(store)
            assertTrue(server.objectAt(STARTUP_PROBE_PATH) != null)

            server.denyMissingHead = true
            val failure = assertFailsWith<S3Exception> { verifyMediaBlobStoreStartup(store) }

            assertEquals(403, failure.statusCode())
            assertTrue(server.objectAt(STARTUP_PROBE_PATH) != null)
        }
    }

    @Test
    fun startupProbeFailsWhenMissingGetAccessCannotProveAbsence() = withServer { server ->
        createStore(server).use { store ->
            verifyMediaBlobStoreStartup(store)

            server.denyMissingGet = true
            val failure = assertFailsWith<S3Exception> { verifyMediaBlobStoreStartup(store) }

            assertEquals(403, failure.statusCode())
            assertTrue(
                server.calls.any { call ->
                    call.method == "GET" && call.path == MISSING_STARTUP_PROBE_PATH
                },
            )
        }
    }

    @Test
    fun startupProbeRejectsAnOccupiedMissingKeyWithMalformedMetadata() = withServer { server ->
        createStore(server).use { store ->
            verifyMediaBlobStoreStartup(store)
            server.seedObject(
                MISSING_STARTUP_PROBE_PATH,
                FakeStoredObject("occupied".encodeToByteArray(), "malformed-digest"),
            )

            assertFailsWith<IllegalStateException> { verifyMediaBlobStoreStartup(store) }
        }
    }

    @Test
    fun validatesFailClosedClientConfiguration() {
        val base = S3MediaBlobStoreConfig(
            bucket = BUCKET,
            region = "us-east-1",
            maxObjectBytes = MAX_OBJECT_BYTES,
        )
        assertFailsWith<IllegalArgumentException> { base.copy(bucket = " bucket") }
        assertFailsWith<IllegalArgumentException> { base.copy(region = " ") }
        assertFailsWith<IllegalArgumentException> { base.copy(endpoint = URI("ftp://storage.example")) }
        assertFailsWith<IllegalArgumentException> {
            base.copy(apiCallTimeout = Duration.ofSeconds(1), apiCallAttemptTimeout = Duration.ofSeconds(2))
        }
    }

    private fun createStore(
        server: FakeS3Server,
        maxObjectBytes: Int = MAX_OBJECT_BYTES,
        apiCallTimeout: Duration = Duration.ofSeconds(5),
        apiCallAttemptTimeout: Duration = Duration.ofSeconds(2),
    ): S3MediaBlobStore = S3MediaBlobStore(
        config = S3MediaBlobStoreConfig(
            bucket = BUCKET,
            region = "us-east-1",
            endpoint = server.endpoint,
            pathStyleAccess = true,
            maxObjectBytes = maxObjectBytes,
            apiCallTimeout = apiCallTimeout,
            apiCallAttemptTimeout = apiCallAttemptTimeout,
        ),
        credentialsProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test-access-key", "test-secret-key"),
        ),
    )

    private fun withServer(block: (FakeS3Server) -> Unit) {
        FakeS3Server().use(block)
    }

    private companion object {
        const val BUCKET = "someday-private-media"
        const val MAX_OBJECT_BYTES = 1024
        const val WORKSPACE = "workspace-0123456789abcdef0123456789abcdef"
        val KEY = MediaBlobKey(
            userId = UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
            workspaceId = WORKSPACE,
            mediaId = "0123456789abcdef".repeat(4),
        )
        val EXPECTED_PATH = "/$BUCKET/media/v1/${KEY.userId}/$WORKSPACE/${KEY.mediaId}.bin"
        val STARTUP_PROBE_PATH = "/$BUCKET/media/v1/.someday-system/startup-probe-v1.bin"
        val MISSING_STARTUP_PROBE_PATH =
            "/$BUCKET/media/v1/.someday-system/startup-probe-missing-v1.bin"
    }
}

private data class CapturedCall(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

private data class FakeStoredObject(
    val bytes: ByteArray,
    val sha256Metadata: String?,
)

private class FakeS3Server : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "fake-s3").apply { isDaemon = true }
    }
    private val objects = ConcurrentHashMap<String, FakeStoredObject>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@FakeS3Server.executor
        createContext("/", ::handle)
        start()
    }

    val calls = CopyOnWriteArrayList<CapturedCall>()
    val endpoint: URI = URI("http://127.0.0.1:${server.address.port}")

    @Volatile
    var putDelay: Duration = Duration.ZERO

    @Volatile
    var existingPutStatus: Int = 412

    @Volatile
    var existingPutErrorCode: String = "PreconditionFailed"

    @Volatile
    var denyObjectReads: Boolean = false

    @Volatile
    var denyMissingHead: Boolean = false

    @Volatile
    var denyMissingGet: Boolean = false

    fun objectAt(path: String): FakeStoredObject? = objects[path]

    fun seedObject(path: String, stored: FakeStoredObject) {
        check(objects.putIfAbsent(path, stored) == null)
    }

    fun replaceObjectBytes(path: String, bytes: ByteArray) {
        objects.compute(path) { _, previous ->
            checkNotNull(previous).copy(bytes = bytes.copyOf())
        }
    }

    fun replaceObjectMetadata(path: String, sha256: String?) {
        objects.compute(path) { _, previous ->
            checkNotNull(previous).copy(sha256Metadata = sha256)
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        runCatching {
            val headers = exchange.requestHeaders.entries.associate { (name, values) ->
                name.lowercase() to values.joinToString(",")
            }
            val encodedBody = exchange.requestBody.use { it.readAllBytes() }
            val body = if (headers["content-encoding"]?.contains("aws-chunked") == true) {
                decodeAwsChunked(encodedBody)
            } else {
                encodedBody
            }
            val call = CapturedCall(
                method = exchange.requestMethod,
                path = exchange.requestURI.rawPath,
                headers = headers,
                body = body,
            )
            calls += call
            when (call.method) {
                "PUT" -> put(exchange, call)
                "HEAD" -> head(exchange, call.path)
                "GET" -> get(exchange, call.path)
                else -> errorResponse(exchange, 405, "MethodNotAllowed")
            }
        }.onFailure {
            runCatching { exchange.close() }
        }
    }

    private fun put(exchange: HttpExchange, call: CapturedCall) {
        if (!putDelay.isZero) Thread.sleep(putDelay.toMillis())
        if (call.headers["if-none-match"] != "*") {
            errorResponse(exchange, 400, "InvalidRequest")
            return
        }
        val candidate = FakeStoredObject(
            bytes = call.body.copyOf(),
            sha256Metadata = call.headers["x-amz-meta-someday-ciphertext-sha256"],
        )
        val existing = objects.putIfAbsent(call.path, candidate)
        if (existing != null) {
            errorResponse(
                exchange,
                existingPutStatus,
                existingPutErrorCode,
            )
            return
        }
        exchange.responseHeaders.add("ETag", "\"not-a-content-digest\"")
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }

    private fun head(exchange: HttpExchange, path: String) {
        if (denyObjectReads) {
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return
        }
        val stored = objects[path]
        if (stored == null) {
            exchange.sendResponseHeaders(if (denyMissingHead) 403 else 404, -1)
            exchange.close()
            return
        }
        stored.sha256Metadata?.let {
            exchange.responseHeaders.add("x-amz-meta-someday-ciphertext-sha256", it)
        }
        exchange.responseHeaders.add("Content-Length", stored.bytes.size.toString())
        exchange.responseHeaders.add("ETag", "\"not-a-content-digest\"")
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }

    private fun get(exchange: HttpExchange, path: String) {
        if (denyObjectReads) {
            errorResponse(exchange, 403, "AccessDenied")
            return
        }
        val stored = objects[path]
        if (stored == null) {
            if (denyMissingGet) {
                errorResponse(exchange, 403, "AccessDenied")
            } else {
                errorResponse(exchange, 404, "NoSuchKey")
            }
            return
        }
        stored.sha256Metadata?.let {
            exchange.responseHeaders.add("x-amz-meta-someday-ciphertext-sha256", it)
        }
        exchange.responseHeaders.add("ETag", "\"not-a-content-digest\"")
        exchange.sendResponseHeaders(200, stored.bytes.size.toLong())
        exchange.responseBody.use { it.write(stored.bytes) }
    }

    private fun errorResponse(exchange: HttpExchange, status: Int, code: String) {
        val body = "<Error><Code>$code</Code><Message>$code</Message></Error>".encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/xml")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun decodeAwsChunked(encoded: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (true) {
            val lineEnd = encoded.indexOfCrlf(offset)
            check(lineEnd >= 0) { "Malformed aws-chunked request." }
            val header = encoded.decodeToString(offset, lineEnd)
            val size = header.substringBefore(';').toInt(16)
            offset = lineEnd + 2
            if (size == 0) break
            check(offset + size + 2 <= encoded.size) { "Truncated aws-chunked request." }
            output.write(encoded, offset, size)
            offset += size
            check(encoded[offset] == '\r'.code.toByte() && encoded[offset + 1] == '\n'.code.toByte()) {
                "Malformed aws-chunked request delimiter."
            }
            offset += 2
        }
        return output.toByteArray()
    }
}

private fun ByteArray.indexOfCrlf(start: Int): Int {
    for (index in start until lastIndex) {
        if (this[index] == '\r'.code.toByte() && this[index + 1] == '\n'.code.toByte()) return index
    }
    return -1
}

private fun sha256(bytes: ByteArray): String =
    "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"
