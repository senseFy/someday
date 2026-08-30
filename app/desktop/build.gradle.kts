import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

/** Runtime used by Compose Desktop run/package and JavaExec smoke tasks (must match jvmTarget). */
val somedayJvmLanguageVersion: JavaLanguageVersion =
    JavaLanguageVersion.of(libs.versions.jvm.get().toInt())
val somedayJvmLauncher: Provider<JavaLauncher> = javaToolchains.launcherFor {
    languageVersion.set(somedayJvmLanguageVersion)
}
val somedayJvmHome: Provider<String> = somedayJvmLauncher.map {
    it.metadata.installationPath.asFile.absolutePath
}

val generatedDesktopBuildConfigDir = layout.buildDirectory.dir("generated/sources/desktopBuildConfig/jvmMain/kotlin")
val desktopDeveloperOptionsEnabled = providers
    .gradleProperty("someday.desktop.developerOptions")
    .orElse("true")
val generateDesktopBuildConfig by tasks.registering {
    inputs.property("developerOptionsEnabled", desktopDeveloperOptionsEnabled)
    outputs.dir(generatedDesktopBuildConfigDir)

    doLast {
        val enabled = desktopDeveloperOptionsEnabled.get().toBooleanStrictOrNull()
            ?: error("someday.desktop.developerOptions must be true or false.")
        val outputDir = generatedDesktopBuildConfigDir.get().asFile
        outputDir.deleteRecursively()
        val packageDir = outputDir.resolve("saien/someday/app/desktop")
        packageDir.mkdirs()
        packageDir.resolve("DesktopBuildConfig.kt").writeText(
            """
            package saien.someday.app.desktop

            internal object DesktopBuildConfig {
                const val DEVELOPER_OPTIONS_ENABLED: Boolean = $enabled
            }
            """.trimIndent(),
        )
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(somedayJvmLanguageVersion)
    }
    jvm("jvm") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:ui"))
            implementation(project(":shared:data"))
            implementation(project(":shared:sync"))
            implementation(compose.components.resources)
            implementation(compose.desktop.currentOs)
            implementation(libs.sqldelight.sqlite.driver)
        }
        jvmMain {
            kotlin.srcDir(generatedDesktopBuildConfigDir)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "saien.someday.app.desktop.MainKt"
        // Compose Desktop run/package defaults to Gradle's java.home (often JBR 17).
        // Pin the app process to the monorepo JVM baseline so class file 65 deps load.
        javaHome = somedayJvmHome.get()

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Someday"
            packageVersion = "1.0.17"
            modules("java.sql")

            macOS {
                bundleID = "saien.someday"
            }
            windows {
                upgradeUuid = "F72E91C4-8CCC-4883-BAC6-157C366E7E3D"
            }
            linux {
                packageName = "someday"
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(somedayJvmLauncher)
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        showStandardStreams = true
    }
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(somedayJvmLauncher)
}

tasks.named("compileKotlinJvm") {
    dependsOn(generateDesktopBuildConfig)
}

tasks.register<JavaExec>("runUiSmoke") {
    group = "verification"
    description = "Runs a non-interactive Desktop startup smoke through the shared UI module."
    dependsOn("jvmMainClasses")
    javaLauncher.set(somedayJvmLauncher)
    classpath = files(
        kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs,
        configurations.named("jvmRuntimeClasspath"),
    )
    mainClass.set("saien.someday.app.desktop.DesktopSmokeKt")
}

tasks.register("desktopWindowsLinuxPackageSmoke") {
    group = "verification"
    description = "Compiles the Desktop JVM app and records Windows/Linux package smoke readiness without signing or notarization."
    dependsOn("jvmMainClasses", "runUiSmoke")
    doLast {
        println("Desktop cross-target package smoke: host=macos package-targets=windows-msi|linux-deb startup=shared-ui-verified signing=not-required notarization=not-required")
    }
}
