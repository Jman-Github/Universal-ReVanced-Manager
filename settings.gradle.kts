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
        maven("https://jitpack.io") {
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        maven {
            // From PR #39: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/39
            url = uri("https://maven.pkg.github.com/brosssh/registry")
            credentials {
                val hardcodedUser = ""
                val hardcodedToken = ""
                val gprUser: String? = providers.gradleProperty("gpr.user").orNull
                val gprKey: String? = providers.gradleProperty("gpr.key").orNull

                username = (if (hardcodedUser.isNotBlank()) hardcodedUser else System.getenv("GITHUB_ACTOR") ?: gprUser).orEmpty().ifBlank { "anonymous" }
                password = (if (hardcodedToken.isNotBlank()) hardcodedToken else System.getenv("GITHUB_TOKEN") ?: gprKey).orEmpty()
            }
        }
        maven {
            // A repository must be specified for some reason. "registry" is a dummy.
            url = uri("https://maven.pkg.github.com/revanced/registry")
            credentials {
                val hardcodedUser = ""
                val hardcodedToken = ""
                val gprUser: String? = providers.gradleProperty("gpr.user").orNull
                val gprKey: String? = providers.gradleProperty("gpr.key").orNull

                username = (if (hardcodedUser.isNotBlank()) hardcodedUser else System.getenv("GITHUB_ACTOR") ?: gprUser).orEmpty().ifBlank { "anonymous" }
                password = (if (hardcodedToken.isNotBlank()) hardcodedToken else System.getenv("GITHUB_TOKEN") ?: gprKey).orEmpty()
            }
        }
    }
}

rootProject.name = "universal-revanced-manager"
include(":app", ":api")
