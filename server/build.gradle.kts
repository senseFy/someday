import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

val s3IntegrationTestSourceSet = sourceSets.create("s3IntegrationTest") {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
configurations["s3IntegrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["s3IntegrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("saien.someday.server.ApplicationKt")
}

val launcherClasspath = files(tasks.named("jar"), configurations.runtimeClasspath)

fun registerLauncher(
    taskName: String,
    launcherName: String,
    entryPoint: String,
) = tasks.register<CreateStartScripts>(taskName) {
    applicationName = launcherName
    mainClass.set(entryPoint)
    classpath = launcherClasspath
    outputDir = layout.buildDirectory.dir("launchers/$launcherName").get().asFile
}

val bootstrapAdminStartScripts = registerLauncher(
    taskName = "bootstrapAdminStartScripts",
    launcherName = "bootstrap-admin",
    entryPoint = "saien.someday.server.AdminBootstrapKt",
)
val verifyMediaIntegrityStartScripts = registerLauncher(
    taskName = "verifyMediaIntegrityStartScripts",
    launcherName = "verify-media-integrity",
    entryPoint = "saien.someday.server.MediaIntegrityVerifierKt",
)

fun launcherFiles(
    launcherName: String,
    task: TaskProvider<CreateStartScripts>,
) = objects.fileCollection()
    .from(
        layout.buildDirectory.file("launchers/$launcherName/$launcherName"),
        layout.buildDirectory.file("launchers/$launcherName/$launcherName.bat"),
    )
    .builtBy(task)

distributions {
    named("main") {
        contents {
            from(launcherFiles("bootstrap-admin", bootstrapAdminStartScripts)) {
                into("bin")
                filePermissions {
                    unix("rwxr-xr-x")
                }
            }
            from(launcherFiles("verify-media-integrity", verifyMediaIntegrityStartScripts)) {
                into("bin")
                filePermissions {
                    unix("rwxr-xr-x")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.argon2.jvm)
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.sdk.url.connection.client)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hikari.cp)
    implementation(libs.java.jwt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)

    add("integrationTestImplementation", libs.ktor.server.test.host)
    add("integrationTestImplementation", project(":shared:sync"))
    add("integrationTestImplementation", project(":shared:data"))
}

tasks.withType<Test>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        showStandardStreams = true
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Ktor/PostgreSQL integration tests for the self-hosted server."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("s3IntegrationTest") {
    description = "Runs the media blob contract against a real S3-compatible service."
    group = "verification"
    testClassesDirs = s3IntegrationTestSourceSet.output.classesDirs
    classpath = s3IntegrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.register<JavaExec>("bootstrapAdmin") {
    description = "Creates one administrator account without enabling public registration."
    group = "application"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("saien.someday.server.AdminBootstrapKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("verifyMediaIntegrity") {
    description = "Verifies every PostgreSQL media record against actual bounded blob bytes."
    group = "verification"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("saien.someday.server.MediaIntegrityVerifierKt")
}
