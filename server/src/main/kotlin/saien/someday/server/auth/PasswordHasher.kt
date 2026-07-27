package saien.someday.server.auth

import de.mkammerer.argon2.Argon2
import de.mkammerer.argon2.Argon2Factory
import java.util.concurrent.Semaphore

interface PasswordHasher {
    fun hash(password: String): String
    fun verify(hash: String, password: String): Boolean
}

class CredentialWorkUnavailableException :
    IllegalStateException("Credential hashing capacity is temporarily unavailable.")

class Argon2idPasswordHasher(
    private val iterations: Int = 3,
    private val memoryKiB: Int = 64 * 1024,
    private val parallelism: Int = 1,
    maxConcurrent: Int = 2,
    private val argon2Factory: () -> Argon2 = {
        Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    },
) : PasswordHasher {
    private val permits = Semaphore(maxConcurrent, true)

    init {
        require(iterations > 0)
        require(memoryKiB > 0)
        require(parallelism > 0)
        require(maxConcurrent > 0)
    }

    override fun hash(password: String): String =
        withCredentialPermit {
            val argon2 = argon2Factory()
            val passwordChars = password.toCharArray()
            try {
                argon2.hash(iterations, memoryKiB, parallelism, passwordChars)
            } finally {
                argon2.wipeArray(passwordChars)
            }
        }

    override fun verify(hash: String, password: String): Boolean =
        withCredentialPermit {
            val argon2 = argon2Factory()
            val passwordChars = password.toCharArray()
            try {
                argon2.verify(hash, passwordChars)
            } finally {
                argon2.wipeArray(passwordChars)
            }
        }

    private inline fun <T> withCredentialPermit(block: () -> T): T {
        if (!permits.tryAcquire()) throw CredentialWorkUnavailableException()
        return try {
            block()
        } finally {
            permits.release()
        }
    }
}
