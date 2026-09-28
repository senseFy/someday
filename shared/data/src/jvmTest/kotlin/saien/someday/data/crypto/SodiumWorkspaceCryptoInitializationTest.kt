package saien.someday.data.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SodiumWorkspaceCryptoInitializationTest {
    @Test
    fun concurrentFirstUseAndSubsequentInstancesCanEncryptAndDecrypt() {
        // Gradle's worker classpath alone omits the isolated test runtime dependencies.
        val classpath = (
            System.getProperty("java.class.path").split(File.pathSeparator) +
                generateSequence(javaClass.classLoader) { it.parent }
                    .filterIsInstance<URLClassLoader>()
                    .flatMap { it.urLs.asSequence() }
                    .map { File(it.toURI()).absolutePath }
                    .toList()
            ).distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath

        // A new OS process resets both the Kotlin wrapper and native libsodium state.
        // Running after another crypto test, or using only a new classloader, is not cold start.
        repeat(3) {
            val output = Files.createTempFile("someday-sodium-initialization-", ".log").toFile()
            val process = ProcessBuilder(java, "-cp", classpath, javaClass.name)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Cold-start probe timed out: ${output.readText()}")
                assertEquals(0, process.exitValue(), output.readText())
                assertTrue(output.readText().contains("Concurrent crypto initialization passed"))
            } finally {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                output.delete()
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            assertFalse(LibsodiumInitializer.isInitialized(), "Probe must start before any crypto initialization")
            val workers = 16
            val ready = CountDownLatch(workers)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(workers)
            try {
                val futures = (0 until workers).map { worker ->
                    executor.submit<Any> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        val crypto = SodiumWorkspaceCrypto()
                        val binding = LibsodiumInitializer.sodiumJna
                        val key = crypto.generateWorkspaceKey()
                        val encryptionKey = crypto.deriveSubkey(key, WorkspaceSubkey.OBJECTS)
                        val aad = "initialization-worker-$worker".encodeToByteArray()
                        val plaintext = "Independent message $worker — 初始化".encodeToByteArray()
                        val ciphertext = crypto.encryptAead(encryptionKey, aad, plaintext)

                        // A later instance must reuse initialization and interoperate with the first.
                        val reader = SodiumWorkspaceCrypto()
                        val decrypted = assertIs<CryptoResult.Success<ByteArray>>(
                            reader.decryptAead(encryptionKey, aad, ciphertext),
                        )
                        assertContentEquals(plaintext, decrypted.value)
                        assertEquals(
                            CryptoResult.AuthenticationFailed,
                            reader.decryptAead(encryptionKey, "wrong context".encodeToByteArray(), ciphertext),
                        )
                        binding
                    }
                }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                val bindings = futures.map { it.get(30, TimeUnit.SECONDS) }
                bindings.forEach {
                    assertSame(bindings.first(), it, "Every caller must share one native library initialization")
                }
                assertTrue(LibsodiumInitializer.isInitialized())
                println("Concurrent crypto initialization passed")
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        }
    }
}
