package saien.someday.server.routes

import io.ktor.server.application.ApplicationCall
import java.util.UUID
import saien.someday.server.auth.AccountProtocolExpectation

const val ACCOUNT_PROTOCOL_HEADER = "X-Someday-Account-Protocol"
const val ACCOUNT_INCARNATION_HEADER = "X-Someday-Account-Incarnation"
const val ACCOUNT_ERROR_HEADER = "X-Someday-Error-Code"

fun ApplicationCall.accountProtocolExpectation(): AccountProtocolExpectation {
    val versions = request.headers.getAll(ACCOUNT_PROTOCOL_HEADER)
    val incarnations = request.headers.getAll(ACCOUNT_INCARNATION_HEADER)
    if (versions == null && incarnations == null) return AccountProtocolExpectation.Missing
    if (versions?.size != 1 || incarnations?.size != 1) return AccountProtocolExpectation.Invalid
    // HTTP adapters/proxies can coalesce duplicate fields into one comma-separated value.
    if (',' in versions.single() || ',' in incarnations.single()) return AccountProtocolExpectation.Invalid
    if (versions.single() != "1") return AccountProtocolExpectation.Unsupported
    val incarnation = canonicalAccountUuid(incarnations.single()) ?: return AccountProtocolExpectation.Invalid
    return AccountProtocolExpectation.V1(incarnation)
}

fun ApplicationCall.respondAccountIncarnation(incarnation: UUID) {
    response.headers.append(ACCOUNT_INCARNATION_HEADER, incarnation.toString())
}

fun canonicalAccountUuid(value: String): UUID? =
    if (!CANONICAL_UUID.matches(value)) null else runCatching { UUID.fromString(value) }.getOrNull()

private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
