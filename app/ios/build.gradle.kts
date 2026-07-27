import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

val generatedIosBuildConfigDir = layout.buildDirectory.dir("generated/sources/iosBuildConfig/iosMain/kotlin")
val iosDeveloperOptionsEnabled = providers
    .gradleProperty("someday.ios.developerOptions")
    .orElse("true")
val iosSystemV2ReleaseEnabled = providers
    .gradleProperty("someday.systemV2ReleaseEnabled")
    .orElse("false")
val iosSystemV2DevelopmentEnabled = providers
    .gradleProperty("someday.systemV2DevelopmentEnabled")
    .orElse("false")

val generateIosBuildConfig by tasks.registering {
    inputs.property("developerOptionsEnabled", iosDeveloperOptionsEnabled)
    inputs.property("systemV2ReleaseEnabled", iosSystemV2ReleaseEnabled)
    inputs.property("systemV2DevelopmentEnabled", iosSystemV2DevelopmentEnabled)
    outputs.dir(generatedIosBuildConfigDir)

    doLast {
        val enabled = iosDeveloperOptionsEnabled.get().toBooleanStrictOrNull()
            ?: error("someday.ios.developerOptions must be true or false.")
        val v2ReleaseEnabled = iosSystemV2ReleaseEnabled.get().toBooleanStrictOrNull()
            ?: error("someday.systemV2ReleaseEnabled must be true or false.")
        val v2DevelopmentEnabled = iosSystemV2DevelopmentEnabled.get().toBooleanStrictOrNull()
            ?: error("someday.systemV2DevelopmentEnabled must be true or false.")
        val outputDir = generatedIosBuildConfigDir.get().asFile
        outputDir.deleteRecursively()
        val packageDir = outputDir.resolve("saien/someday/app/ios")
        packageDir.mkdirs()
        packageDir.resolve("IosBuildConfig.kt").writeText(
            """
            package saien.someday.app.ios

            internal object IosBuildConfig {
                const val DEVELOPER_OPTIONS_ENABLED: Boolean = $enabled
                const val SOMEDAY_SYSTEM_V2_RELEASE_ENABLED: Boolean = $v2ReleaseEnabled
                const val SOMEDAY_SYSTEM_V2_DEVELOPMENT_ENABLED: Boolean = $v2DevelopmentEnabled
            }
            """.trimIndent(),
        )
    }
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "SomedayIos"
            isStatic = false
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:ui"))
            implementation(project(":shared:data"))
            implementation(compose.runtime)
            implementation(compose.ui)
        }
        iosMain.dependencies {
            implementation(project(":shared:sync"))
            implementation(libs.sqldelight.native.driver)
        }
        iosMain {
            kotlin.srcDir(generatedIosBuildConfigDir)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.matching { it.name.startsWith("compileKotlinIos") }.configureEach {
    dependsOn(generateIosBuildConfig)
}

tasks.register("iosShellSmoke") {
    group = "verification"
    description = "Links the iOS framework and runs the shared UI startup contract test."
    dependsOn("linkDebugFrameworkIosSimulatorArm64", "iosSimulatorArm64Test")
    doLast {
        println("iOS shell smoke: platform=ios shared-ui=shared:ui startup=SomedayApp material=Material3 tabs=Notes|Memories|Settings notes-reclick=opens-notebook-sheet add-entry=new-note settings=local-persistent markdown-source=plain-text preview=toggle toolbar=heading|bold|italic|list|quote|code-block|link wysiwyg-assist=selection-aware-toolbar+preview-feedback attachments=absent memories=calendar-counts|month-navigation|selected-day|prior-year location=system-coordinates|manual-place|permission-denied-usable|no-map-sdk platform-smoke=workspace-setup|unlock|create-note|markdown-preview|denied-location|restart-persistence search=local-title-body-active-only settings-sections=sync-mode-account|webdav-config|self-hosted-device-management|device-pairing|editor-preferences|theme-default-notebook|sync-status-last-error|export-entry-points workspace-pairing=one-use-invitation|qr-or-token|redacted-logs export=notes-notebooks|excludes-raw-keys-tokens-passwords-recovery-material")
    }
}
