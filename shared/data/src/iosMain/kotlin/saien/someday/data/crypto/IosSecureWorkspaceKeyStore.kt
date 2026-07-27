package saien.someday.data.crypto

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

@OptIn(ExperimentalForeignApi::class)
class IosSecureWorkspaceKeyStore(
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
) : SecureWorkspaceKeyStore {
    override fun put(
        alias: String,
        workspaceKey: WorkspaceMasterKey,
    ) {
        require(alias.isNotBlank()) { "Secure storage alias must not be blank." }
        val dataRef = workspaceKey.rawBytesCopy().toCFData()
        try {
            val updateStatus = withMutableDictionary { attributes ->
                CFDictionarySetValue(attributes, kSecValueData, dataRef)
                CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                withBaseQuery(alias) { query ->
                    SecItemUpdate(query, attributes)
                }
            }
            if (updateStatus == errSecSuccess) {
                return
            }
            require(updateStatus == errSecItemNotFound) { keychainError("update", updateStatus) }

            val addStatus = withBaseQuery(alias) { query ->
                CFDictionarySetValue(query, kSecValueData, dataRef)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                SecItemAdd(query, null)
            }
            if (addStatus == errSecDuplicateItem) {
                val retryStatus = withMutableDictionary { attributes ->
                    CFDictionarySetValue(attributes, kSecValueData, dataRef)
                    withBaseQuery(alias) { query -> SecItemUpdate(query, attributes) }
                }
                require(retryStatus == errSecSuccess) { keychainError("update", retryStatus) }
                return
            }
            require(addStatus == errSecSuccess) { keychainError("save", addStatus) }
        } finally {
            CFRelease(dataRef)
        }
    }

    override fun get(alias: String): WorkspaceMasterKey? =
        runCatching {
            withBaseQuery(alias) { query ->
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
                        ?: error("iOS Keychain returned an unexpected workspace key payload.")
                    try {
                        crypto.workspaceKeyFromBytes(dataRef.toByteArray())
                    } finally {
                        CFRelease(result.value)
                    }
                }
            }
        }.getOrNull()

    override fun remove(alias: String) {
        withBaseQuery(alias) { query ->
            SecItemDelete(query)
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

    private fun <T> withBaseQuery(
        alias: String,
        block: (CFMutableDictionaryRef) -> T,
    ): T {
        val serviceValue = service.toCFString()
        val accountValue = alias.toCFString()
        return withMutableDictionary { query ->
            try {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, serviceValue)
                CFDictionarySetValue(query, kSecAttrAccount, accountValue)
                block(query)
            } finally {
                CFRelease(accountValue)
                CFRelease(serviceValue)
            }
        }
    }

    private fun String.toCFString(): CFTypeRef =
        CFStringCreateWithCString(null, this, kCFStringEncodingUTF8)
            ?: error("iOS Keychain string value could not be created.")

    private fun ByteArray.toCFData(): CFTypeRef =
        usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.convert())
                ?: error("iOS Keychain data value could not be created.")
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
        const val service = "saien.someday.workspace.key"

        fun keychainError(action: String, status: Int): String =
            "iOS Keychain could not $action the workspace key (status $status)."
    }
}
