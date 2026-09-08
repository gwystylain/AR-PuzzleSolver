pluginManagement {
    repositories {
        google {
            // Restrict google() to the groups it actually serves, so Maven Central
            // artifacts are not searched for here first. Backslashes are doubled
            // because these are Kotlin string literals, not raw regex text.
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "PuzzleSolver"
include(":app")
include(":core")
