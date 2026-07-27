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

val syncV2ReliabilityGateRequested = providers
    .gradleProperty("someday.syncV2ReliabilityGate")
    .map { value ->
        value.toBooleanStrictOrNull()
            ?: error("someday.syncV2ReliabilityGate must be true or false")
    }
    .orElse(false)

val syncV2ReliabilityTestTaskNames = setOf(
    "jvmTest",
    "testDebugUnitTest",
    "iosSimulatorArm64Test",
    "test",
    "integrationTest",
    "realRemoteTest",
)

subprojects {
    tasks.configureEach {
        if (name in syncV2ReliabilityTestTaskNames) {
            outputs.upToDateWhen { !syncV2ReliabilityGateRequested.get() }
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

tasks.register("check") {
    group = "verification"
    description = "Runs root source hygiene in addition to subproject checks."
    dependsOn(sourceHygieneCheck)
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
    description = "Runs self-hosted/WebDAV E2E checks and cross-platform smoke validators for final platform readiness."
    dependsOn(
        ":server:integrationTest",
        ":integration-tests:realRemoteTest",
        "clientPlatformSmoke",
        "sharedBehaviorTargetSmoke",
    )
}
