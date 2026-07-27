@file:OptIn(ExperimentalForeignApi::class)

package saien.someday.app.ios

import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.decodeSelfHostedSessionCredentials
import saien.someday.domain.settings.encodeForSecureStorage
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

class IosSelfHostedSessionCredentialStore : SelfHostedSessionCredentialStore {
    override fun load(): SelfHostedSessionCredentials? =
        loadText(account)?.let(::decodeSelfHostedSessionCredentials)

    override fun save(credentials: SelfHostedSessionCredentials) {
        saveText(account, credentials.encodeForSecureStorage())
    }

    override fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
        loadText(authorityAccount(authorityBindingId))
            ?.let(::decodeSelfHostedSessionCredentials)
            ?.takeIf { saien.someday.domain.settings.selfHostedV2AuthorityBindingId(it.endpoint) == authorityBindingId }

    override fun saveForAuthority(authorityBindingId: String, credentials: SelfHostedSessionCredentials) {
        require(saien.someday.domain.settings.selfHostedV2AuthorityBindingId(credentials.endpoint) == authorityBindingId)
        saveText(authorityAccount(authorityBindingId), credentials.encodeForSecureStorage())
    }

    override fun clearAuthority(authorityBindingId: String) {
        withBaseQuery(authorityAccount(authorityBindingId)) { query -> SecItemDelete(query) }
    }

    override fun clear() {
        withServiceQuery { query -> SecItemDelete(query) }
    }

    private fun loadText(accountName: String): String? =
        withBaseQuery(accountName) { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) {
                    return@memScoped null
                }
                require(status == errSecSuccess) { keychainError("read", status) }
                val dataRef = result.value?.reinterpretData()
                    ?: error("iOS Keychain returned an unexpected self-hosted session payload.")
                try {
                    dataRef.toByteArray().decodeToString()
                } finally {
                    CFRelease(result.value)
                }
            }
        }

    private fun saveText(accountName: String, value: String) {
        val dataRef = value.toCFData()
        try {
            val updateStatus = withMutableDictionary { attributes ->
                CFDictionarySetValue(attributes, kSecValueData, dataRef)
                CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                withBaseQuery(accountName) { query ->
                    SecItemUpdate(query, attributes)
                }
            }
            if (updateStatus == errSecSuccess) {
                return
            }
            require(updateStatus == errSecItemNotFound) { keychainError("update", updateStatus) }

            val addStatus = withBaseQuery(accountName) { query ->
                CFDictionarySetValue(query, kSecValueData, dataRef)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                SecItemAdd(query, null)
            }
            if (addStatus == errSecDuplicateItem) {
                val retryStatus = withMutableDictionary { attributes ->
                    CFDictionarySetValue(attributes, kSecValueData, dataRef)
                    CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    withBaseQuery(accountName) { query ->
                        SecItemUpdate(query, attributes)
                    }
                }
                require(retryStatus == errSecSuccess) { keychainError("update", retryStatus) }
                return
            }
            require(addStatus == errSecSuccess) { keychainError("save", addStatus) }
        } finally {
            CFRelease(dataRef)
        }
    }

    private fun <T> withMutableDictionary(block: (CFMutableDictionaryRef) -> T): T {
        val dictionary = CFDictionaryCreateMutable(null, 0, null, null)
            ?: error("iOS Keychain query could not be created.")
        try {
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    }

    private fun <T> withServiceQuery(block: (CFMutableDictionaryRef) -> T): T {
        val serviceValue = service.toCFString()
        return withMutableDictionary { query ->
            try {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, serviceValue)
                block(query)
            } finally {
                CFRelease(serviceValue)
            }
        }
    }

    private fun <T> withBaseQuery(accountName: String, block: (CFMutableDictionaryRef) -> T): T {
        val accountValue = accountName.toCFString()
        return withServiceQuery { query ->
            try {
                CFDictionarySetValue(query, kSecAttrAccount, accountValue)
                block(query)
            } finally {
                CFRelease(accountValue)
            }
        }
    }

    private fun String.toCFString(): CFTypeRef =
        CFStringCreateWithCString(null, this, kCFStringEncodingUTF8)
            ?: error("iOS Keychain string value could not be created.")

    private fun String.toCFData(): CFTypeRef {
        val bytes = encodeToByteArray()
        return bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.convert())
                ?: error("iOS Keychain data value could not be created.")
        }
    }

    private fun CFTypeRef.reinterpretData(): CFDataRef =
        (this as CPointer<*>).reinterpret()

    private fun CFDataRef.toByteArray(): ByteArray {
        val size = CFDataGetLength(this).toInt()
        val output = ByteArray(size)
        output.usePinned { pinned ->
            memcpy(pinned.addressOf(0), CFDataGetBytePtr(this), size.convert())
        }
        return output
    }

    private companion object {
        const val service = "saien.someday.selfhosted.session"
        const val account = "default"

        fun authorityAccount(authorityBindingId: String): String = "authority:$authorityBindingId"

        fun keychainError(action: String, status: Int): String =
            "iOS Keychain could not $action the self-hosted session (status $status)."
    }
}
