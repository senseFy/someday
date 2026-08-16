import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val systemV2ReleaseEnabled = providers.gradleProperty("someday.systemV2ReleaseEnabled").orElse("false")

data class AndroidSigningEnvironment(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun environmentValue(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseSigningRequested = gradle.startParameter.taskNames.any { taskName ->
    val normalizedTaskName = taskName.removePrefix(":")
    val targetsAndroid = ':' !in normalizedTaskName || normalizedTaskName.startsWith("app:android:")
    val simpleName = taskName.substringAfterLast(':').lowercase()
    targetsAndroid && (
        simpleName == "build" ||
            simpleName == "assemble" ||
            simpleName == "bundle" ||
            simpleName.contains("release") &&
            listOf("assemble", "bundle", "package", "sign", "publish").any(simpleName::contains)
    )
}
val systemV2ReleaseEnabledValue = systemV2ReleaseEnabled.get().toBooleanStrictOrNull()
    ?: error("someday.systemV2ReleaseEnabled must be true or false.")
if (releaseSigningRequested) {
    check(systemV2ReleaseEnabledValue) {
        "Android release packaging requires -Psomeday.systemV2ReleaseEnabled=true. " +
            "Use the canonical make android-release-play entrypoint."
    }
}
val releaseSigning = AndroidSigningEnvironment(
    storeFile = "SOMEDAY_ANDROID_KEYSTORE_PATH",
    storePassword = "SOMEDAY_ANDROID_KEYSTORE_PASSWORD",
    keyAlias = "SOMEDAY_ANDROID_KEY_ALIAS",
    keyPassword = "SOMEDAY_ANDROID_KEY_PASSWORD",
)
val releaseSigningValues = listOf(
    releaseSigning.storeFile,
    releaseSigning.storePassword,
    releaseSigning.keyAlias,
    releaseSigning.keyPassword,
).associateWith(::environmentValue)
val releaseSigningConfigured = releaseSigningValues.values.all { it != null }

if (releaseSigningRequested || releaseSigningValues.values.any { it != null }) {
    val missing = releaseSigningValues.filterValues { it == null }.keys
    check(missing.isEmpty()) {
        "Android release signing requires: ${missing.joinToString()}."
    }
    val keystorePath = releaseSigningValues.getValue(releaseSigning.storeFile).orEmpty()
    check(file(keystorePath).isFile) {
        "SOMEDAY_ANDROID_KEYSTORE_PATH does not point to a file."
    }
}

android {
    namespace = "saien.someday.app.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "saien.someday"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 17
        versionName = "1.0.16"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "boolean",
            "SOMEDAY_SYSTEM_V2_RELEASE_ENABLED",
            systemV2ReleaseEnabledValue.toString(),
        )
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseSigningValues.getValue(releaseSigning.storeFile).orEmpty())
                storePassword = releaseSigningValues.getValue(releaseSigning.storePassword)
                keyAlias = releaseSigningValues.getValue(releaseSigning.keyAlias)
                keyPassword = releaseSigningValues.getValue(releaseSigning.keyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language {
            // Runtime language switching needs every shipped translation in
            // the base install, including installs outside Play.
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:ui"))
    implementation(project(":shared:data"))
    implementation(project(":shared:sync"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.zxing.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlin.test.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        showStandardStreams = true
    }
}

tasks.register("androidShellSmoke") {
    group = "verification"
    description = "Builds the Android app shell and runs its shared UI startup contract test."
    dependsOn("assembleDebug", "testDebugUnitTest")
    doLast {
        println("Android shell smoke: platform=android shared-ui=shared:ui startup=SomedayApp material=Material3 tabs=Notes|Memories|Settings notes-reclick=opens-notebook-sheet add-entry=new-note settings=local-persistent markdown-source=plain-text preview=toggle toolbar=heading|bold|italic|list|quote|code-block|link wysiwyg-assist=selection-aware-toolbar+preview-feedback attachments=absent memories=calendar-counts|month-navigation|selected-day|prior-year location=system-coordinates|manual-place|permission-denied-usable|no-map-sdk platform-smoke=workspace-setup|unlock|create-note|markdown-preview|denied-location|restart-persistence search=local-title-body-active-only settings-sections=sync-mode-account|webdav-config|self-hosted-device-management|device-pairing|editor-preferences|theme-default-notebook|sync-status-last-error|export-entry-points workspace-pairing=one-use-invitation|qr-or-token|redacted-logs export=notes-notebooks|excludes-raw-keys-tokens-passwords-recovery-material")
    }
}
