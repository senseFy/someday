import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

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
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    jvm("jvm") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
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
            api(libs.okio)
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
        named("jvmTest") { resources.srcDir("src/commonMain/sqldelight/databases") }
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
            // schema's foreign-key graph. The replacement verifier below runs
            // a seeded snapshot through the real JVM driver migration and
            // compares its full catalog and preserved data with a fresh DB.
            verifyMigrations.set(false)
        }
    }
}

val verifySomedayDatabaseMigrationSnapshots by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies frozen SQLDelight history and migrated/fresh SQLite catalogs."
    commandLine(rootProject.file("scripts/verify-sqldelight-v2-baseline"))
    inputs.files(
        file("src/commonMain/sqldelight/databases/1.db"),
        file("src/commonMain/sqldelight/databases/2.db"),
        file("src/commonMain/sqldelight/databases/3.db"),
        file("src/commonMain/sqldelight/saien/someday/data/local/db/1.sqm"),
        file("src/commonMain/sqldelight/saien/someday/data/local/db/2.sqm"),
        file("src/commonMain/sqldelight/saien/someday/data/local/db/Someday.sq"),
        rootProject.file("scripts/verify-sqldelight-v2-baseline"),
    )
}

val verifySomedayDatabaseMigration by tasks.registering(Test::class) {
    group = "verification"
    description = "Upgrades nonempty v1/v2 databases through the shared driver and verifies v3 data/catalogs."
    val jvmTests = tasks.named<Test>("jvmTest")
    dependsOn("jvmTestClasses", verifySomedayDatabaseMigrationSnapshots)
    testClassesDirs = jvmTests.get().testClassesDirs
    classpath = jvmTests.get().classpath
    filter { includeTestsMatching("saien.someday.data.local.SomedayDatabaseMigrationTest") }
}

tasks.matching { it.name == "verifyCommonMainSomedayDatabaseMigration" }.configureEach {
    enabled = false
}

tasks.named("verifySqlDelightMigration") {
    dependsOn(verifySomedayDatabaseMigration)
}

tasks.named("check") {
    dependsOn(verifySomedayDatabaseMigration)
}

android {
    namespace = "saien.someday.shared.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
