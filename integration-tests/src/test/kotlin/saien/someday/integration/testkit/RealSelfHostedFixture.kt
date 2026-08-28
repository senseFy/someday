package saien.someday.integration.testkit

import java.util.UUID

/** Owns one real server account, transport, and explicitly closed client installations. */
internal class RealSelfHostedFixture private constructor(
    val endpoint: String,
    val account: TestAccount,
) : AutoCloseable {
    val transport = ProbingSelfHostedTransport()
    private val devices = mutableListOf<TestDevice>()

    fun newDevice(label: String, platform: String): TestDevice =
        TestDevice(endpoint, account, transport, label, platform).also(devices::add)

    fun readOpaqueRowsForAccount(): String = readOpaqueServerRows(account.email)

    override fun close() {
        devices.asReversed().forEach(TestDevice::close)
        devices.clear()
    }

    companion object {
        fun create(prefix: String): RealSelfHostedFixture {
            val unique = UUID.randomUUID().toString()
            return RealSelfHostedFixture(
                endpoint = requiredEnvironment("SOMEDAY_E2E_ENDPOINT"),
                account = TestAccount(
                    email = "$prefix-$unique@example.com",
                    password = "System-V3-$prefix-$unique",
                ),
            )
        }
    }
}

internal data class TestAccount(
    val email: String,
    val password: String,
)

internal fun requiredEnvironment(name: String): String =
    System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("$name is required by :integration-tests:realRemoteTest.")
