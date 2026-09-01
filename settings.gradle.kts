pluginManagement {
    repositories {
        google {
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

rootProject.name = "VocabularyBooster"

// iOS app module is enabled only on macOS hosts (Xcode toolchain required).
// Windows hosts build :shared (Android target) + :app only.
val isMacOsHost = System.getProperty("os.name").lowercase().contains("mac")
if (isMacOsHost) {
    include(":iosApp")
}

include(":app", ":shared")
