pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "someday"

include(":shared:domain")
include(":shared:data")
include(":shared:sync")
include(":shared:ui")
include(":app:android")
include(":app:ios")
include(":app:desktop")
include(":server")
include(":integration-tests")
