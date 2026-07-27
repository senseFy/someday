package saien.someday.integration

import saien.someday.domain.client.clientShellSemanticsFor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleTopologyTest {
    @Test
    fun sharedNavigationContractIsVisibleToIntegrationTests() {
        val semantics = clientShellSemanticsFor("integration")

        assertEquals(listOf("Notes", "Memories", "Settings"), semantics.tabLabels)
    }

    @Test
    fun kotlinSourcesUseCanonicalNamespaceAndMatchingPaths() {
        val projectRoot = projectRoot()
        val kotlinFiles = listOf("app", "shared", "server", "integration-tests")
            .flatMap { module -> sourceFilesUnder(projectRoot.resolve(module), ".kt") }
            .filter { path -> projectRoot.relativize(path).toString().replace('\\', '/').contains("/src/") }
        val mismatches = kotlinFiles.mapNotNull { path ->
            val relativePath = projectRoot.relativize(path).toString().replace('\\', '/')
            val packagePath = relativePath
                .substringAfter("/kotlin/", missingDelimiterValue = "")
                .substringBeforeLast('/', missingDelimiterValue = "")
                .replace('/', '.')
            val packageDeclaration = Files.readAllLines(path)
                .firstOrNull { line -> line.startsWith("package ") }
            when {
                !packagePath.startsWith("saien.someday") -> "$relativePath is outside the canonical namespace"
                packageDeclaration != "package $packagePath" ->
                    "$relativePath declares ${packageDeclaration ?: "no package"}; expected package $packagePath"
                else -> null
            }
        }

        assertTrue(kotlinFiles.isNotEmpty(), "Expected Kotlin source files under project modules")
        assertTrue(mismatches.isEmpty(), "Kotlin namespace/path mismatches: $mismatches")
    }

    @Test
    fun clientVisibleReleaseVersionsStayAligned() {
        val projectRoot = projectRoot()
        val declarations = mapOf(
            "Android" to versionDeclarations(
                projectRoot.resolve("app/android/build.gradle.kts"),
                Regex("""versionName\s*=\s*"([^"]+)"""),
            ),
            "iOS" to versionDeclarations(
                projectRoot.resolve("iosApp/Someday.xcodeproj/project.pbxproj"),
                Regex("""MARKETING_VERSION\s*=\s*([^;]+);"""),
            ),
            "Desktop" to versionDeclarations(
                projectRoot.resolve("app/desktop/build.gradle.kts"),
                Regex("""packageVersion\s*=\s*"([^"]+)"""),
            ),
        )
        val malformedDeclarations = declarations.filterValues { versions -> versions.size != 1 }
        val versions = declarations.values.flatten().toSet()

        assertTrue(
            malformedDeclarations.isEmpty(),
            "Expected one visible release version per platform: $malformedDeclarations",
        )
        assertEquals(1, versions.size, "Client visible release versions must match: $declarations")
        assertTrue(
            versions.single().matches(Regex("""\d+\.\d+\.\d+""")),
            "Client release version must use major.minor.patch: $versions",
        )
    }

    @Test
    fun platformCodeDoesNotOwnLocalDatabaseSchemaMigration() {
        val projectRoot = projectRoot()
        val platformSourceRoots = listOf(
            projectRoot.resolve("app/android/src/main/kotlin"),
            projectRoot.resolve("app/ios/src/iosMain/kotlin"),
            projectRoot.resolve("app/desktop/src/jvmMain/kotlin"),
        )
        val forbiddenSnippets = listOf(
            "PRAGMA " + "user_version",
            "SomedayDatabase.Schema." + "create",
            "SomedayDatabase.Schema." + "migrate",
            "JdbcSqliteDriver(",
        )

        assertFilesDoNotContain(
            files = platformSourceRoots.flatMap { sourceFilesUnder(it, ".kt") },
            projectRoot = projectRoot,
            forbiddenSnippets = forbiddenSnippets,
            message = "Platform code must only provide SqlDriver; schema migration belongs to SQLDelight/shared data",
        )
    }

    @Test
    fun jvmTestsUseSharedSchemaAwareDatabaseFactories() {
        val projectRoot = projectRoot()
        val testSourceRoots = listOf(
            projectRoot.resolve("shared/data/src/jvmTest/kotlin"),
            projectRoot.resolve("shared/sync/src/jvmTest/kotlin"),
            projectRoot.resolve("integration-tests/src/test/kotlin"),
        )
        val forbiddenSnippets = listOf(
            "SomedayDatabase.Schema." + "create",
            "SomedayDatabase.Schema." + "migrate",
        )

        assertFilesDoNotContain(
            files = testSourceRoots.flatMap { sourceFilesUnder(it, ".kt") },
            projectRoot = projectRoot,
            forbiddenSnippets = forbiddenSnippets,
            message = "JVM tests must use shared schema-aware driver factories instead of owning schema lifecycle",
        )
    }

    @Test
    fun localSqlDelightMigrationsAreDeterministicAfterTheInitialBaseline() {
        val projectRoot = projectRoot()
        val migrationRoot = projectRoot.resolve("shared/data/src/commonMain/sqldelight")
        val migrationFiles = sourceFilesUnder(migrationRoot, ".sqm")
        val baseline = projectRoot.resolve("shared/data/src/commonMain/sqldelight/databases/1.db")
        assertTrue(
            migrationFiles.isNotEmpty() || Files.isRegularFile(baseline),
            "Expected numbered SQLDelight migrations or the documented initial databases/1.db baseline.",
        )

        assertFilesDoNotContain(
            files = migrationFiles,
            projectRoot = projectRoot,
            forbiddenSnippets = listOf("IF EXISTS", "IF NOT EXISTS"),
            message = "SQLDelight migrations must encode a deterministic version-to-version transition",
        )
    }

    @Test
    fun serverFlywayMigrationsAreDeterministic() {
        val projectRoot = projectRoot()
        val migrationRoot = projectRoot.resolve("server/src/main/resources/db/migration")
        val migrationFiles = sourceFilesUnder(migrationRoot, ".sql")
        assertTrue(migrationFiles.isNotEmpty(), "Expected Flyway migration files under $migrationRoot")

        assertFilesDoNotContain(
            files = migrationFiles,
            projectRoot = projectRoot,
            forbiddenSnippets = listOf("IF EXISTS", "IF NOT EXISTS"),
            message = "Flyway migrations must encode a deterministic version-to-version transition",
        )
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Could not find project root from ${Path.of("").toAbsolutePath()}")
        }
        return current
    }

    private fun sourceFilesUnder(root: Path, suffix: String): List<Path> =
        if (!Files.exists(root)) {
            emptyList()
        } else {
            Files.walk(root).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { path -> path.toString().endsWith(suffix) }
                    .toList()
            }
        }

    private fun versionDeclarations(path: Path, pattern: Regex): List<String> =
        pattern.findAll(Files.readString(path))
            .map { match -> match.groupValues[1].trim() }
            .distinct()
            .toList()

    private fun assertFilesDoNotContain(
        files: List<Path>,
        projectRoot: Path,
        forbiddenSnippets: List<String>,
        message: String,
    ) {
        val normalizedSnippets = forbiddenSnippets.map { snippet -> snippet to snippet.uppercase() }
        val offenders = files.flatMap { path ->
            val normalizedText = Files.readString(path).uppercase()
            normalizedSnippets
                .filter { (_, normalizedSnippet) -> normalizedText.contains(normalizedSnippet) }
                .map { (snippet, _) -> "${projectRoot.relativize(path)} contains $snippet" }
        }

        assertTrue(offenders.isEmpty(), "$message: $offenders")
    }
}
