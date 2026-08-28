package saien.someday.server.persistence

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProductionDatabasePreflightTest {
    @Test
    fun acceptsOnlyTheSupportedPostgresMajorWithARlsRestrictedRole() {
        ProductionDatabasePreflight.validate(
            ProductionDatabaseFacts(
                serverMajorVersion = 17,
                superuser = false,
                bypassRls = false,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ProductionDatabasePreflight.validate(
                ProductionDatabaseFacts(16, superuser = false, bypassRls = false),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProductionDatabasePreflight.validate(
                ProductionDatabaseFacts(17, superuser = true, bypassRls = false),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProductionDatabasePreflight.validate(
                ProductionDatabaseFacts(17, superuser = false, bypassRls = true),
            )
        }
    }
}
