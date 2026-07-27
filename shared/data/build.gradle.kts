import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("jvm") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(libs.koin.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.libsodium.bindings)
            implementation(libs.okio)
            implementation(libs.sqldelight.runtime)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("SomedayDatabase") {
            packageName.set("saien.someday.data.local.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            // SQLDelight 2.1.0's ObjectDiffer does not terminate on this
            // schema's foreign-key graph. The baseline verifier below checks
            // the generated SQLite schema without retaining old migrations.
            verifyMigrations.set(false)
        }
    }
}

val verifySomedayDatabaseV2Baseline by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the squashed V2-only SQLDelight baseline."
    commandLine(rootProject.file("scripts/verify-sqldelight-v2-baseline"))
    inputs.files(
        file("src/commonMain/sqldelight/databases/1.db"),
        file("src/commonMain/sqldelight/saien/someday/data/local/db/Someday.sq"),
        rootProject.file("scripts/verify-sqldelight-v2-baseline"),
    )
}

tasks.matching { it.name == "verifyCommonMainSomedayDatabaseMigration" }.configureEach {
    enabled = false
}

tasks.named("verifySqlDelightMigration") {
    dependsOn(verifySomedayDatabaseV2Baseline)
}

tasks.named("check") {
    dependsOn(verifySomedayDatabaseV2Baseline)
}

android {
    namespace = "saien.someday.shared.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
