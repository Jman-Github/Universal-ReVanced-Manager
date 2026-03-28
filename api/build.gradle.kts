import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.binary.compatibility.validator)
    `maven-publish`
    signing
}

group = "app.revanced"

dependencies {
    implementation(libs.androidx.ktx)
    implementation(libs.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
}

android {
    namespace = "app.universal.revanced.manager.plugin.downloader"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("dev") {
            initWith(getByName("release"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

apiValidation {
    nonPublicMarkers += "app.urv.manager.plugin.downloader.PluginHostApi"
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            // Publish to the current GitHub repository packages by default.
            // Fallback keeps local publishing possible when the env var is absent.
            val ghRepo = System.getenv("GITHUB_REPOSITORY") ?: "Jman-Github/Universal-ReVanced-Manager"
            url = uri("https://maven.pkg.github.com/$ghRepo")
            credentials {
                // Priority: hardcoded fallback -> environment -> Gradle properties
                val hardcodedUser = ""
                val hardcodedToken = ""
                val gprUser = providers.gradleProperty("gpr.user").orNull
                val gprKey = providers.gradleProperty("gpr.key").orNull

                username = if (hardcodedUser.isNotBlank()) hardcodedUser
                else System.getenv("GITHUB_ACTOR") ?: gprUser

                password = if (hardcodedToken.isNotBlank()) hardcodedToken
                else System.getenv("GITHUB_TOKEN") ?: gprKey
            }
        }
    }

    publications {
        create<MavenPublication>("Api") {
            afterEvaluate {
                from(components["release"])
            }

            groupId = "app.revanced"
            artifactId = "universal-revanced-manager-api"
            version = project.version.toString()

            pom {
                name = "ReVanced Manager API"
                description = "API for ReVanced Manager."
                url = "https://revanced.app"

                licenses {
                    license {
                        name = "GNU General Public License v3.0"
                        url = "https://www.gnu.org/licenses/gpl-3.0.en.html"
                    }
                }
                developers {
                    developer {
                        id = "ReVanced"
                        name = "ReVanced"
                        email = "contact@revanced.app"
                    }
                }
                scm {
                    val ghRepo = System.getenv("GITHUB_REPOSITORY") ?: "Jman-Github/Universal-ReVanced-Manager"
                    connection = "scm:git:git://github.com/$ghRepo.git"
                    developerConnection = "scm:git:git@github.com:$ghRepo.git"
                    url = "https://github.com/$ghRepo"
                }
            }
        }
    }
}

signing {
    // Disable signing by default in CI environments unless explicitly enabled.
    // Enable by setting env SIGNING_REQUIRED=true or Gradle property signing.required=true
    val requiredFromEnv = System.getenv("SIGNING_REQUIRED")?.toBoolean()
    val requiredFromProp = (findProperty("signing.required") as String?)?.toBoolean()
    isRequired = (requiredFromEnv ?: requiredFromProp) ?: false

    if (isRequired) {
        useGpgCmd()
    }
    sign(publishing.publications["Api"])
}

tasks.matching { it.name.contains("lintVital", ignoreCase = true) }.configureEach {
    enabled = false
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Aidl") }.configureEach {
    doLast {
        val generatedRoot = layout.buildDirectory.dir("generated/aidl_source_output_dir").get().asFile
        if (!generatedRoot.exists()) return@doLast
        generatedRoot.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                val original = file.readText()
                val sanitized = original.lineSequence().joinToString(separator = "\n") { line ->
                    if (line.startsWith(" * Using: ")) line.replace('\\', '/')
                    else line
                }
                if (sanitized != original) {
                    file.writeText(sanitized)
                }
            }
    }
}
