package saien.someday.sync.selfhosted

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import saien.someday.data.account.SqlDelightAccountStateRepository
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId

/** Capability history belongs to the installation, independently of workspace replacement. */
class SelfHostedAccountDiscoveryService(
    private val transport: SelfHostedAccountControlTransport,
    private val states: SqlDelightAccountStateRepository,
) {
    private val proofMutex = Mutex()
    private val legacyProofs = mutableListOf<SelfHostedSessionCredentials>()

    fun recordIssuance(credentials: SelfHostedSessionCredentials) {
        if (credentials.accountProtocolVersion == 1) states.markProtocol1(credentials.endpoint, credentials.userId)
    }

    fun discover(credentials: SelfHostedSessionCredentials): SelfHostedAccountDiscoveryResult = discoverObserved(credentials)

    fun isVerifiedLegacy(credentials: SelfHostedSessionCredentials): Boolean {
        if (credentials.accountProtocolVersion == 1 || states.hasProtocol1(credentials.endpoint, credentials.userId)) return false
        val cached = withProofCache { legacyProofs.any { it == credentials } }
        // Database/product locks and HTTP never nest under the short cache mutex.
        // Sticky protocol history is checked again after observing the cache.
        if (cached) return !states.hasProtocol1(credentials.endpoint, credentials.userId)
        val candidate = discoverObserved(credentials) == SelfHostedAccountDiscoveryResult.LegacyCandidate404
        return candidate && !states.hasProtocol1(credentials.endpoint, credentials.userId)
    }

    private fun <T> withProofCache(block: () -> T): T = runBlocking { proofMutex.withLock { block() } }

    private fun discoverObserved(credentials: SelfHostedSessionCredentials): SelfHostedAccountDiscoveryResult = try {
        recordIssuance(credentials)
        val context = credentials.accountRequestContext().copy(
            protocol1Known = credentials.accountProtocolVersion == 1 || states.hasProtocol1(credentials.endpoint, credentials.userId),
        )
        when (val result = transport.discoverAccountData(credentials.endpoint, credentials.accessToken, context)) {
            is SelfHostedAccountDiscoveryResult.Protocol1 -> {
                SelfHostedAccountWire.validateState(result.state)
                states.markProtocol1(credentials.endpoint, credentials.userId)
                withProofCache { legacyProofs.removeAll { it.authorityBindingId == credentials.authorityBindingId } }
                result
            }
            SelfHostedAccountDiscoveryResult.LegacyCandidate404 -> {
                if (context.protocol1Known) throw SelfHostedProtocolException(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
                val me = SelfHostedAccountWire.validateMe(transport.accountMe(credentials.endpoint, credentials.accessToken, context))
                if (me.id != credentials.userId) throw SelfHostedProtocolException(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
                // Issuance or a typed protocol response can establish capability
                // while /me is in flight. Linearize the legacy decision here.
                if (states.hasProtocol1(credentials.endpoint, credentials.userId)) {
                    throw SelfHostedProtocolException(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
                }
                // This short in-memory proof authorizes only this exact credential.
                // It never suppresses a subsequently persisted protocol-1 capability.
                withProofCache {
                    legacyProofs.removeAll { it.authorityBindingId == credentials.authorityBindingId }
                    if (legacyProofs.size == MAX_LEGACY_PROOFS) legacyProofs.removeAt(0)
                    legacyProofs += credentials
                }
                if (states.hasProtocol1(credentials.endpoint, credentials.userId)) {
                    throw SelfHostedProtocolException(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
                }
                result
            }
        }
    } catch (failure: SelfHostedSyncHttpException) {
        if (failure.protocol1) states.markProtocol1(credentials.endpoint, credentials.userId)
        throw failure
    }

    private companion object { const val MAX_LEGACY_PROOFS = 8 }
}
