package saien.someday.sync.selfhosted

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION

/** The same wire assertions run through the actual Ktor and JDK network stacks. */
class SelfHostedAccountTransportTest {
    @Test fun errorHeadersPrecedeBusiness409DecodingAndRequestsKeepCapturedIncarnation() = transports { f ->
        f.respond(409, """{"error":"account_incarnation_mismatch"}""", error = "account_incarnation_mismatch")
        val failure = assertFailsWith<SelfHostedSyncHttpException> { f.push() }
        assertEquals(SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, failure.errorCode)
        assertTrue(failure.protocol1)
        assertEquals("1", f.requests.single().protocol)
        assertEquals(INCARNATION, f.requests.single().incarnation)
        assertEquals("Bearer access-secret", f.requests.single().authorization)
        f.respond(409, """{"accepted":false,"acknowledgements":[],"error":"epoch_mismatch"}""")
        assertFalse(f.push().accepted)
        f.respond(409, """{"error":"account_incarnation_mismatch"}""")
        assertEquals(SelfHostedProtocolFailureReason.MISSING_ERROR_HEADER, assertFailsWith<SelfHostedProtocolException> { f.push() }.reason)
        f.respond(409, """{"accepted":false,"error":"account_incarnation_mismatch"}""")
        assertEquals(SelfHostedProtocolFailureReason.MISSING_ERROR_HEADER, assertFailsWith<SelfHostedProtocolException> { f.push() }.reason)
    }

    @Test fun mediaPutKeepsBusinessConflictAndClassifiesAccountConflictBeforeItsDto() = transports { f ->
        f.respond(409, """{"error":"workspace_incarnation_retired"}""", error = "workspace_incarnation_retired")
        val failure = assertFailsWith<SelfHostedSyncHttpException> { f.putMedia() }
        assertEquals(SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED, failure.errorCode)
        f.respond(409, """{"stored":false,"error":"media_object_conflict"}""")
        assertFalse(f.putMedia().stored)
        f.respond(409, """{"error":"workspace_incarnation_retired"}""")
        assertEquals(SelfHostedProtocolFailureReason.MISSING_ERROR_HEADER, assertFailsWith<SelfHostedProtocolException> { f.putMedia() }.reason)
    }

    @Test fun resetReplayUsesItsPersistedExpectedIncarnationAndChecksReceiptIdentity() = transports { f ->
        val request = SelfHostedAccountResetRequest(operationId = OPERATION, expectedIncarnation = INITIAL_ACCOUNT_INCARNATION, password = "password-secret")
        val receipt = """{"protocolVersion":1,"operationId":"$OPERATION","previousIncarnation":"$INITIAL_ACCOUNT_INCARNATION","newIncarnation":"$INCARNATION","committedAtEpochMillis":1234}"""
        f.respond(200, receipt)
        assertEquals(INCARNATION, f.control.resetAccountData(f.endpoint, "access-secret", request, KNOWN).newIncarnation)
        assertEquals(INITIAL_ACCOUNT_INCARNATION, f.requests.last().incarnation)
        assertFalse(request.toString().contains("password-secret"))
        f.respond(200, receipt.replace(OPERATION, DEVICE))
        assertFailsWith<SelfHostedProtocolException> { f.control.resetAccountData(f.endpoint, "access-secret", request, KNOWN) }
    }

    @Test fun genericErrorsAreStrictBoundedAndNeverRetainServerPayload() = transports { f ->
        val cases = listOf(
            Reply(409, """{"error":"account_incarnation_mismatch"}""", listOf("unknown_reset_error")) to SelfHostedProtocolFailureReason.UNKNOWN_ERROR_CODE,
            Reply(401, """{"error":"account_incarnation_mismatch"}""", listOf("account_incarnation_mismatch")) to SelfHostedProtocolFailureReason.ERROR_STATUS_MISMATCH,
            Reply(409, """{"error":"account_busy"}""", listOf("account_incarnation_mismatch")) to SelfHostedProtocolFailureReason.ERROR_BODY_MISMATCH,
            Reply(409, """{"error":"account_incarnation_mismatch","password":"secret-payload"}""", listOf("account_incarnation_mismatch")) to SelfHostedProtocolFailureReason.MALFORMED_BODY,
            Reply(409, """{"error":"account_incarnation_mismatch","error":"account_incarnation_mismatch"}""", listOf("account_incarnation_mismatch")) to SelfHostedProtocolFailureReason.MALFORMED_BODY,
            Reply(409, """{"error":"account_incarnation_mismatch"}""", listOf("account_incarnation_mismatch", "account_incarnation_mismatch")) to SelfHostedProtocolFailureReason.INVALID_ERROR_HEADER,
            Reply(409, "x".repeat(4097), listOf("account_incarnation_mismatch")) to SelfHostedProtocolFailureReason.BODY_TOO_LARGE,
        )
        cases.forEach { (reply, reason) ->
            f.reply = reply
            val failure = assertFailsWith<SelfHostedProtocolException> { f.push() }
            assertEquals(reason, failure.reason)
            assertFalse(failure.toString().contains("secret-payload"))
            assertNull(failure.cause)
        }
    }

    @Test fun headUsesHeaderOnlyAndKnownProtocol404CannotBecomeMissingMedia() = transports { f ->
        f.respond(409, "", error = "account_incarnation_mismatch")
        assertEquals(SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, assertFailsWith<SelfHostedSyncHttpException> { f.head() }.errorCode)
        f.respond(404, "", error = "media_object_not_found")
        assertNull(f.head())
        f.respond(404, "")
        assertEquals(SelfHostedProtocolFailureReason.MISSING_ERROR_HEADER, assertFailsWith<SelfHostedProtocolException> { f.head() }.reason)
        assertNull(f.media.headMediaObject(f.endpoint, "access-secret", WORKSPACE, MEDIA, SelfHostedAccountRequestContext()))
    }

    @Test fun controlPlaneRejectsProxy404AndKeepsLookup404Distinct() = transports { f ->
        f.respond(404, "<html>not found</html>", contentType = "text/html")
        assertFailsWith<SelfHostedProtocolException> { f.control.discoverAccountData(f.endpoint, "access-secret") }
        f.respond(404, "")
        assertEquals(SelfHostedAccountDiscoveryResult.LegacyCandidate404, f.control.discoverAccountData(f.endpoint, "access-secret"))
        assertNull(f.requests.last().incarnation)
        f.respond(404, """{"error":"not_found"}""", error = "not_found")
        assertNull(f.control.getAccountResetReceipt(f.endpoint, "access-secret", OPERATION, KNOWN))
        f.respond(404, "")
        assertFailsWith<SelfHostedProtocolException> { f.control.getAccountResetReceipt(f.endpoint, "access-secret", OPERATION, KNOWN) }
        f.respond(302, "", location = "/other")
        assertFailsWith<SelfHostedSyncHttpException> { f.control.discoverAccountData(f.endpoint, "access-secret") }
        assertFalse(f.requests.any { it.path == "/other" })
    }

    @Test fun authenticationCapturesOnlyIssuanceHeaderAndRedactsReturnedSecrets() = transports { f ->
        f.respond(200, AUTH_JSON, incarnation = INCARNATION)
        val auth = f.auth.login(f.endpoint, SelfHostedAuthRequest("a@example.com", "password"), KNOWN)
        assertEquals(INCARNATION, auth.accountIncarnation)
        assertEquals(1, auth.accountProtocolVersion)
        assertFalse(auth.toString().contains("access-secret"))
        f.respond(200, AUTH_JSON)
        assertEquals(SelfHostedProtocolFailureReason.MISSING_ISSUANCE_HEADER, assertFailsWith<SelfHostedProtocolException> {
            f.auth.login(f.endpoint, SelfHostedAuthRequest("a@example.com", "password"), KNOWN)
        }.reason)
        f.respond(200, AUTH_JSON, incarnation = INCARNATION.uppercase())
        assertFailsWith<SelfHostedProtocolException> { f.auth.login(f.endpoint, SelfHostedAuthRequest("a@example.com", "password")) }
    }

    @Test fun renewalMismatchStopsBeforeDeviceRegistration() = transports { f ->
        f.respond(200, AUTH_JSON, incarnation = INCARNATION)
        val learned = mutableListOf<String>()
        val client = SelfHostedSyncClient(f.endpoint, f.auth, onProtocol1 = { _, user -> learned += user })
        val failure = assertFailsWith<SelfHostedSyncHttpException> {
            client.loginAndReconnectBound("a@example.com", "password", "device", "desktop", USER, DEVICE, INITIAL_ACCOUNT_INCARNATION)
        }
        assertEquals(SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, failure.errorCode)
        assertEquals(listOf(USER), learned)
        assertEquals(listOf("/auth/login"), f.requests.map { it.path })
    }

    @Test fun legacyAuthRequiresExactDiscoveryAndSameCredentialMeAndCannotRetagFromDiscovery() = transports { f ->
        f.handler = { path -> when (path) {
            "/auth/login" -> Reply(200, AUTH_JSON)
            "/account/data-state" -> Reply(200, """{"protocolVersion":1,"accountIncarnation":"$INCARNATION","resetAvailable":true,"resetUnavailableReason":null}""")
            else -> error("Must stop before registering or querying me")
        } }
        val learned = mutableListOf<String>()
        val client = SelfHostedSyncClient(f.endpoint, f.auth, onProtocol1 = { _, user -> learned += user })
        assertEquals(SelfHostedProtocolFailureReason.MISSING_ISSUANCE_HEADER, assertFailsWith<SelfHostedProtocolException> {
            client.loginAndConnect("a@example.com", "password", "device", "desktop", DEVICE)
        }.reason)
        assertEquals(listOf(USER), learned)
        f.requests.clear()
        f.handler = { path -> when (path) {
            "/auth/login" -> Reply(200, AUTH_JSON)
            "/account/data-state" -> Reply(404, "")
            "/me" -> Reply(200, """{"id":"$USER","email":"a@example.com","deviceId":null,"scopes":["auth"]}""")
            "/devices/register" -> Reply(200, """{"device":{"id":"$DEVICE","name":"device","platform":"desktop","revoked":false},"accessToken":"device-access","refreshToken":"device-refresh","expiresInSeconds":60}""")
            else -> error("Unexpected path $path")
        } }
        val legacy = SelfHostedSyncClient(f.endpoint, f.auth).loginAndConnect("a@example.com", "password", "device", "desktop", DEVICE)
        assertEquals(INITIAL_ACCOUNT_INCARNATION, legacy.accountIncarnation)
        assertNull(legacy.accountProtocolVersion)
        assertEquals(listOf("/auth/login", "/account/data-state", "/me", "/devices/register"), f.requests.map { it.path })
        assertTrue(f.requests.drop(1).all { it.authorization == "Bearer access-secret" })
    }

    @Test fun typedFailuresRememberCapabilityOnlyWhenAccountIdentityIsKnown() = transports { f ->
        val learned = mutableListOf<String>()
        val client = SelfHostedSyncClient(f.endpoint, f.auth, onProtocol1 = { _, user -> learned += user })
        f.respond(401, """{"error":"invalid_credentials"}""", error = "invalid_credentials")
        assertFailsWith<SelfHostedSyncHttpException> { client.loginAndConnect("a@example.com", "password", "device", "desktop", DEVICE) }
        assertTrue(learned.isEmpty()) // A failed initial password login supplies no account id.
        assertFailsWith<SelfHostedSyncHttpException> { client.loginAndReconnectBound("a@example.com", "password", "device", "desktop", USER, DEVICE) }
        assertEquals(listOf(USER), learned)
        learned.clear()
        val session = SelfHostedSyncSession(f.endpoint, USER, "a@example.com", DEVICE, "device", "desktop", "access-secret", "refresh-secret")
        assertFailsWith<SelfHostedSyncHttpException> { client.refresh(session) }
        assertEquals(listOf(USER), learned)
        for (failingPath in listOf("/account/data-state", "/me", "/devices/register")) {
            learned.clear()
            f.handler = { path -> when (path) {
                failingPath -> Reply(503, """{"error":"account_busy"}""", listOf("account_busy"))
                "/auth/login" -> Reply(200, AUTH_JSON)
                "/account/data-state" -> Reply(404, "")
                "/me" -> Reply(200, """{"id":"$USER","email":"a@example.com","deviceId":null,"scopes":["auth"]}""")
                else -> error("Unexpected path $path")
            } }
            assertEquals(SelfHostedErrorCode.ACCOUNT_BUSY, assertFailsWith<SelfHostedSyncHttpException> {
                client.loginAndConnect("a@example.com", "password", "device", "desktop", DEVICE)
            }.errorCode)
            assertEquals(listOf(USER), learned)
        }
    }

    private fun transports(block: (Fixture) -> Unit) {
        Fixture(false).use(block)
        Fixture(true).use(block)
    }

    private data class Reply(val status: Int, val body: String, val errors: List<String> = emptyList(), val incarnation: String? = null, val contentType: String = "application/json", val location: String? = null)
    private data class Request(val path: String, val authorization: String?, val protocol: String?, val incarnation: String?)
    private class Fixture(ktor: Boolean) : AutoCloseable {
        @Volatile var reply = Reply(200, "{}")
        @Volatile var handler: ((String) -> Reply)? = null
        val requests = CopyOnWriteArrayList<Request>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests += Request(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst(SELF_HOSTED_ACCOUNT_PROTOCOL_HEADER), exchange.requestHeaders.getFirst(SELF_HOSTED_ACCOUNT_INCARNATION_HEADER))
                exchange.requestBody.use { it.readBytes() }
                val response = handler?.invoke(exchange.requestURI.path) ?: reply
                response.errors.forEach { exchange.responseHeaders.add(SELF_HOSTED_ERROR_CODE_HEADER, it) }
                response.incarnation?.let { exchange.responseHeaders.add(SELF_HOSTED_ACCOUNT_INCARNATION_HEADER, it) }
                response.location?.let { exchange.responseHeaders.add("Location", it) }
                exchange.responseHeaders.add("Content-Type", response.contentType)
                if (exchange.requestMethod == "HEAD" || response.body.isEmpty()) exchange.sendResponseHeaders(response.status, -1) else {
                    val bytes = response.body.toByteArray()
                    exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            start()
        }
        val endpoint = "http://127.0.0.1:${server.address.port}"
        private val ktorClient = if (ktor) HttpClient(OkHttp) { followRedirects = true } else null
        val auth: SelfHostedSyncTransport = if (ktor) KtorSelfHostedSyncTransport(ktorClient!!) else JdkSelfHostedSyncTransport()
        val control = auth as SelfHostedAccountControlTransport
        val media = auth as SelfHostedMediaTransportV3
        fun respond(status: Int, body: String, error: String? = null, incarnation: String? = null, contentType: String = "application/json", location: String? = null) {
            reply = Reply(status, body, listOfNotNull(error), incarnation, contentType, location)
        }
        fun push() = (auth as SelfHostedSyncTransportV2).v2Push(endpoint, "access-secret", SelfHostedV2PushRequest("epoch", 2, emptyList(), WORKSPACE), KNOWN)
        fun head() = media.headMediaObject(endpoint, "access-secret", WORKSPACE, MEDIA, KNOWN)
        fun putMedia(): SelfHostedMediaPutResponseV3 {
            val bytes = ByteArray(64) { it.toByte() }
            val digest = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val prepared = SelfHostedPreparedMediaObjectV3(
                SelfHostedMediaObjectMetadataV3(mediaId = MEDIA, mediaType = "image/png", pixelWidth = 1, pixelHeight = 1, plaintextBytes = 1, plaintextSha256 = digest),
                bytes, digest,
            )
            return media.putMediaObject(endpoint, "access-secret", WORKSPACE, MEDIA, prepared, KNOWN)
        }
        override fun close() {
            (auth as? JdkSelfHostedSyncTransport)?.close()
            (auth as? KtorSelfHostedSyncTransport)?.close()
            ktorClient?.close()
            server.stop(0)
        }
    }
    private companion object {
        const val INCARNATION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val USER = "11111111-1111-4111-8111-111111111111"
        const val DEVICE = "22222222-2222-4222-8222-222222222222"
        const val OPERATION = "33333333-3333-4333-8333-333333333333"
        const val WORKSPACE = "workspace-11111111111111111111111111111111"
        val MEDIA = "a".repeat(64)
        val KNOWN = SelfHostedAccountRequestContext(INCARNATION, true)
        val AUTH_JSON = """{"accessToken":"access-secret","refreshToken":"refresh-secret","expiresInSeconds":60,"user":{"id":"$USER","email":"a@example.com"}}"""
    }
}
