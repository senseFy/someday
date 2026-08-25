package saien.someday.integration.testkit

import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId

internal class TestSessionCredentialStore : SelfHostedSessionCredentialStore {
    private var current: SelfHostedSessionCredentials? = null
    private val byAuthority = mutableMapOf<String, SelfHostedSessionCredentials>()

    override fun load(): SelfHostedSessionCredentials? = current

    override fun save(credentials: SelfHostedSessionCredentials) {
        current = credentials
        byAuthority[credentials.authorityBindingId] = credentials
    }

    override fun clear() {
        current = null
    }

    override fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
        byAuthority[authorityBindingId]

    override fun saveForAuthority(authorityBindingId: String, credentials: SelfHostedSessionCredentials) {
        require(authorityBindingId == credentials.authorityBindingId)
        byAuthority[authorityBindingId] = credentials
    }

    override fun clearAuthority(authorityBindingId: String) {
        byAuthority.remove(authorityBindingId)
        if (current?.authorityBindingId == authorityBindingId) current = null
    }
}
