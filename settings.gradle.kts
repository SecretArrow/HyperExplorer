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

rootProject.name = "HyperExplorer"

include(":app")
include(":core-model")
include(":core-common")
include(":core-ui")
include(":data-local")
include(":data-remote")
include(":feature-browser")
include(":feature-tools")
include(":feature-media")
include(":feature-transfer")
include(":feature-network")
include(":feature-apps")
include(":feature-settings")
