import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.sqldelight) apply false
}

/** Monorepo JVM baseline: bytecode, toolchains, and runtime for desktop/server/tests. */
val somedayJvmLanguageVersion: JavaLanguageVersion =
    JavaLanguageVersion.of(libs.versions.jvm.get().toInt())

subprojects {
    pluginManager.withPlugin("java-base") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(somedayJvmLanguageVersion)
            }
        }
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
}

val systemV3ReliabilityGateRequested = providers
    .gradleProperty("someday.systemV3ReliabilityGate")
    .map { value ->
        value.toBooleanStrictOrNull()
            ?: error("someday.systemV3ReliabilityGate must be true or false")
    }
    .orElse(false)

val systemV3ReliabilityTestTaskNames = setOf(
    "jvmTest",
    "testDebugUnitTest",
    "iosSimulatorArm64Test",
    "test",
    "integrationTest",
    "realRemoteTest",
)

subprojects {
    tasks.configureEach {
        if (name in systemV3ReliabilityTestTaskNames) {
            outputs.upToDateWhen { !systemV3ReliabilityGateRequested.get() }
        }
    }
}

val sourceHygieneCheck = tasks.register("sourceHygieneCheck") {
    group = "verification"
    description = "Checks tracked-source text conventions without rewriting files."
    val sourceFiles = fileTree(rootDir) {
        include(
            "**/*.kt",
            "**/*.kts",
            "**/*.md",
            "**/*.properties",
            "**/*.sh",
            "**/*.sq",
            "**/*.sql",
            "**/*.sqm",
            "**/*.swift",
            "**/*.tsv",
            "**/*.xml",
            "**/*.yaml",
            "**/*.yml",
            "**/Makefile",
            "scripts/*",
        )
        exclude(
            ".git/**",
            ".gradle/**",
            ".kotlin/**",
            "**/build/**",
            "**/DerivedData/**",
        )
    }
    inputs.files(sourceFiles)
    doLast {
        val failures = sourceFiles.files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
            .flatMap { file ->
                val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                val text = file.readText()
                buildList {
                    if ('\r' in text) add("$relative uses CR/CRLF line endings")
                    if (text.isNotEmpty() && !text.endsWith('\n')) add("$relative has no final newline")
                    text.lineSequence().forEachIndexed { index, line ->
                        if (line.endsWith(' ') || line.endsWith('\t')) {
                            add("$relative:${index + 1} has trailing whitespace")
                        }
                    }
                }
            }
        check(failures.isEmpty()) {
            "Source hygiene failures:\n${failures.joinToString("\n")}"
        }
    }
}

val privateReleaseMaterialCheck = tasks.register("privateReleaseMaterialCheck") {
    group = "verification"
    description = "Rejects repository-local signing keys, provisioning profiles, store artifacts, and private keys."
    val repositoryFiles = fileTree(rootDir) {
        exclude(
            ".git/**",
            ".gradle/**",
            ".kotlin/**",
            ".konan/**",
            ".idea/**",
            "**/build/**",
            "**/DerivedData/**",
            "artifacts/**",
        )
    }
    inputs.files(repositoryFiles)
    doLast {
        val blockedExtensions = setOf(
            "p8",
            "p12",
            "pfx",
            "cer",
            "mobileprovision",
            "provisionprofile",
            "developerprofile",
            "ipa",
        )
        val privateKeyTextExtensions = setOf("env", "json", "key", "pem")
        val failures = repositoryFiles.files.sortedBy {
            it.relativeTo(rootDir).invariantSeparatorsPath
        }.mapNotNull { file ->
            val relative = file.relativeTo(rootDir).invariantSeparatorsPath
            val lowercaseRelative = relative.lowercase()
            when {
                file.extension.lowercase() in blockedExtensions ->
                    "$relative is private release material"
                lowercaseRelative.split('/').any { it.endsWith(".xcarchive") } ->
                    "$relative is inside an Xcode archive"
                file.name.lowercase().startsWith("exportoptions") &&
                    file.extension.equals("plist", ignoreCase = true) ->
                    "$relative is generated export metadata"
                file.extension.lowercase() in privateKeyTextExtensions ||
                    file.name == ".env" ||
                    file.name.startsWith(".env.") -> {
                    val text = file.readText()
                    if (
                        Regex("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----").containsMatchIn(text) ||
                        Regex("\"private_key\"\\s*:\\s*\"-----BEGIN").containsMatchIn(text)
                    ) {
                        "$relative contains private-key material"
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
        check(failures.isEmpty()) {
            "Private release material failures:\n${failures.joinToString("\n")}"
        }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Runs root source and private-release-material hygiene in addition to subproject checks."
    dependsOn(sourceHygieneCheck, privateReleaseMaterialCheck)
}

tasks.register("clientPlatformSmoke") {
    group = "verification"
    description = "Compiles and smokes Android, iOS, macOS Desktop, and Windows/Linux desktop package readiness through shared UI startup contracts."
    dependsOn(
        ":app:android:androidShellSmoke",
        ":app:ios:iosShellSmoke",
        ":app:desktop:runUiSmoke",
        ":app:desktop:desktopWindowsLinuxPackageSmoke",
    )
}

tasks.register("sharedBehaviorTargetSmoke") {
    group = "verification"
    description = "Runs shared behavior tests across JVM Desktop, Android host, and iOS simulator targets for cross-platform consistency."
    dependsOn(
        ":shared:domain:jvmTest",
        ":shared:domain:testDebugUnitTest",
        ":shared:domain:iosSimulatorArm64Test",
        ":shared:data:jvmTest",
        ":shared:data:testDebugUnitTest",
        ":shared:data:iosSimulatorArm64Test",
        ":shared:sync:jvmTest",
        ":shared:sync:testDebugUnitTest",
        ":shared:sync:iosSimulatorArm64Test",
        ":shared:ui:jvmTest",
        ":shared:ui:testDebugUnitTest",
        ":shared:ui:iosSimulatorArm64Test",
        ":app:desktop:jvmTest",
        ":app:android:testDebugUnitTest",
        ":app:ios:iosSimulatorArm64Test",
    )
    doLast {
        println("Shared behavior target smoke: targets=jvm-desktop|android-host|ios-simulator coverage=notebook-note-crud|markdown-state|memories|tombstones|encryption-failure|conflict-decisions")
    }
}

tasks.register("endToEndPlatformValidation") {
    group = "verification"
    description = "Runs self-hosted E2E checks and cross-platform smoke validators for final platform readiness."
    dependsOn(
        ":server:integrationTest",
        ":integration-tests:realRemoteTest",
        "clientPlatformSmoke",
        "sharedBehaviorTargetSmoke",
    )
}
