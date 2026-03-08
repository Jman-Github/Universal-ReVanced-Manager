import java.util.Properties

val localProps = Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun githubUser(): String? =
    localProps.getProperty("gpr.user")
        ?: providers.gradleProperty("gpr.user").orNull
        ?: System.getenv("GITHUB_ACTOR")

fun githubToken(): String? =
    localProps.getProperty("gpr.key")
        ?: providers.gradleProperty("gpr.key").orNull
        ?: System.getenv("GITHUB_TOKEN")

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
        maven {
            // From PR #39: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/39
            url = uri("https://maven.pkg.github.com/brosssh/registry")
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // AmpleReVanced packages are published to GitHub Packages.
            url = uri("https://maven.pkg.github.com/AmpleReVanced/registry")
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // A repository must be specified for some reason. "registry" is a dummy.
            url = uri("https://maven.pkg.github.com/revanced/registry")
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // Morphe library is published to its repository-specific GitHub Packages endpoint.
            url = uri("https://maven.pkg.github.com/MorpheApp/morphe-library")
            content {
                includeModule("app.morphe", "morphe-library")
            }
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // Morphe publishes multiple runtime artifacts under the app.morphe group from this endpoint.
            url = uri("https://maven.pkg.github.com/MorpheApp/morphe-patcher")
            content {
                includeGroup("app.morphe")
            }
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // Morphe multidexlib2 is also published to its own GitHub Packages endpoint.
            url = uri("https://maven.pkg.github.com/MorpheApp/multidexlib2")
            content {
                includeModule("app.morphe", "multidexlib2")
            }
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven("https://jitpack.io") {
            metadataSources {
                mavenPom()
                artifact()
            }
        }
    }
}

rootProject.name = "universal-revanced-manager"
include(":app", ":api", ":morphe-runtime", ":ample-runtime", ":revanced-runtime-v22")

