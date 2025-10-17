pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
        maven {
            // A repository must be specified for some reason. "registry" is a dummy.
            url = uri("https://maven.pkg.github.com/revanced/registry")
            credentials {
                val hardcodedUser = ""
                val hardcodedToken = ""
                val gprUser: String? = providers.gradleProperty("gpr.user").orNull
                val gprKey: String? = providers.gradleProperty("gpr.key").orNull

                username = (if (hardcodedUser.isNotBlank()) hardcodedUser else System.getenv("GITHUB_ACTOR") ?: gprUser)

                password = (if (hardcodedToken.isNotBlank()) hardcodedToken else System.getenv("GITHUB_TOKEN") ?: gprKey)
            }
        }
    }
}

rootProject.name = "universal-revanced-manager"
include(":app", ":api")
