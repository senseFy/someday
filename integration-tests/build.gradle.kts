import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation(project(":shared:domain"))
    testImplementation(project(":shared:data"))
    testImplementation(project(":shared:sync"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.postgresql)
    testImplementation(libs.sqldelight.sqlite.driver)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        showStandardStreams = true
    }
}

tasks.named<Test>("test") {
    description = "Runs hermetic repository topology and architecture tests."
    include("**/GradleTopologyTest.class")
}

tasks.register<Test>("realRemoteTest") {
    description = "Runs real WebDAV and self-hosted end-to-end tests; explicit service environment is required."
    group = "verification"
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    include("**/WorkspaceV2RealProfilesIntegrationTest.class")
    include("**/SelfHostedV2ConflictLiveTest.class")
    shouldRunAfter(tasks.test)
}
