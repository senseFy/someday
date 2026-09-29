package saien.someday.server.api

import kotlinx.serialization.Serializable

@Serializable
class AccountDataResetRequest(
    val protocolVersion: Int,
    val operationId: String,
    val expectedIncarnation: String,
    val confirmation: String,
    val password: String,
) {
    override fun toString(): String = "AccountDataResetRequest(protocolVersion=$protocolVersion, password=<redacted>)"
}

@Serializable
data class AccountDataStateResponse(
    val protocolVersion: Int = 1,
    val accountIncarnation: String,
    val resetAvailable: Boolean = false,
    val resetUnavailableReason: String? = "deployment_not_ready",
)

@Serializable
data class AccountDataResetReceiptResponse(
    val protocolVersion: Int = 1,
    val operationId: String,
    val previousIncarnation: String,
    val newIncarnation: String,
    val committedAtEpochMillis: Long,
)
