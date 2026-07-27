package saien.someday.app.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class NoMapSdkDependencyTest {
    @Test
    fun sourceAndBuildFilesDoNotDeclareMapSdkDependencies() {
        val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val forbiddenTerms = listOf(
            "play-services-" + "maps",
            "maps-" + "compose",
            "map" + "box",
            "Map" + "Kit",
            "MK" + "MapView",
        )
        val scannedFiles = root.walkTopDown()
            .onEnter { directory ->
                directory.name !in setOf("build", ".gradle", ".git")
            }
            .filter { file ->
                file.isFile && file.extension in setOf("kt", "kts", "xml")
            }
            .toList()
        val offenders = scannedFiles.flatMap { file ->
            val text = file.readText()
            forbiddenTerms
                .filter { term -> text.contains(term, ignoreCase = false) }
                .map { term -> "${file.relativeTo(root).path}:$term" }
        }

        assertTrue(offenders.isEmpty(), "Map SDK/API references are forbidden: $offenders")
    }
}
