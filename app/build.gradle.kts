import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import io.github.z4kn4fein.semver.toVersion
import kotlin.random.Random
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

enum class UrvBuildProfile {
    LITE,
    FULL;

    companion object {
        fun from(value: String?): UrvBuildProfile =
            values().firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: FULL
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.devtools)
    alias(libs.plugins.about.libraries)
    signing
}

val urvBuildProfile = UrvBuildProfile.from(providers.gradleProperty("urvBuildProfile").orNull)
val urvBuildProfileName = urvBuildProfile.name
val resolvedProjectVersion = if (version == "unspecified") "1.8.1" else version.toString()
val outputApkFileName = "$urvBuildProfileName-universal-revanced-manager-v$resolvedProjectVersion-universal.apk"
val morpheRuntimeAssetsDir = layout.buildDirectory.dir("generated/morphe-runtime")
val ampleRuntimeAssetsDir = layout.buildDirectory.dir("generated/ample-runtime")
val revanced21RuntimeAssetsDir = layout.buildDirectory.dir("generated/revanced-runtime-v21")
val revanced22RuntimeAssetsDir = layout.buildDirectory.dir("generated/revanced-runtime-v22")
val legalResourcesDir = layout.buildDirectory.dir("generated/legal-res")
val devVersionSuffix = providers.gradleProperty("devVersionSuffix")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "dev"
val includedMorpheRuntime = rootProject.findProject(":morphe-runtime") != null
val includedAmpleRuntime = rootProject.findProject(":ample-runtime") != null
val includedRevanced21Runtime = rootProject.findProject(":revanced-runtime-v21") != null
val releaseProfileSuffix = "-$urvBuildProfileName"
val devProfileSuffix = "-$devVersionSuffix-$urvBuildProfileName"

fun artifactVersionName(versionName: String): String =
    versionName.removeSuffix(releaseProfileSuffix)
        .removeSuffix("-$urvBuildProfileName")

val apkEditorLib by configurations.creating

configurations.all {
    exclude(group = "xmlpull", module = "xmlpull")
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    resolutionStrategy.force(
        "com.android.tools.smali:smali-dexlib2:3.0.9",
        "com.android.tools.smali:smali-util:3.0.9",
        "com.android.tools.smali:smali:3.0.9",
        "com.android.tools.smali:smali-baksmali:3.0.9"
    )
}
val strippedApkEditorLib by tasks.registering(Jar::class) {
    archiveFileName.set("APKEditor-android.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doFirst {
        from(apkEditorLib.resolve().map { zipTree(it) })
    }
    exclude(
        "android/**",
        "com/android/tools/smali/**",
        "org/xmlpull/**",
        "antlr/**",
        "org/antlr/**",
        "com/beust/jcommander/**",
        "javax/annotation/**",
        "smali.properties",
        "baksmali.properties"
    )
}

val apkEditorMergeJar by tasks.registering(Jar::class) {
    archiveFileName.set("apkeditor-merge.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn("compileReleaseJavaWithJavac")
    from("$buildDir/intermediates/javac/release/classes") {
        include("app/urv/manager/patcher/split/ApkEditorMergeProcess*.class")
    }
}

dependencies {
    constraints {
        implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
        implementation("com.android.tools.smali:smali-util:3.0.9")
        implementation("com.android.tools.smali:smali:3.0.9")
        implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    }

    // AndroidX Core
    implementation(libs.androidx.ktx)
    implementation(libs.runtime.ktx)
    implementation(libs.runtime.compose)
    implementation(libs.splash.screen)
    implementation(libs.activity.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.preferences.datastore)
    implementation(libs.appcompat)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.livedata)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)

    // Accompanist
    implementation(libs.accompanist.drawablepainter)

    // Placeholder
    implementation(libs.placeholder.material3)

    // Coil (async image loading, network image)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.appiconloader)

    // KotlinX
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collection.immutable)
    implementation(libs.kotlinx.datetime)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    annotationProcessor(libs.room.compiler)
    ksp(libs.room.compiler)

    // ReVanced (PR #39: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/39)
    implementation(libs.revanced.patcher.v22) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation(libs.revanced.library) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "app.revanced", module = "revanced-patcher")
    }
    implementation("com.android.tools.build:apkzlib:8.5.2")
    compileOnly("com.google.guava:guava:33.2.1-jre")
    implementation(libs.xpp3)
    apkEditorLib(files("$rootDir/libs/APKEditor-1.4.7.jar"))
    implementation(files(strippedApkEditorLib))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Downloader plugins
    implementation(project(":api"))

    // Native processes
    implementation(libs.kotlin.process)

    // HiddenAPI
    compileOnly(libs.hidden.api.stub)
    implementation(libs.hidden.api.bypass)

    // Shizuku / Sui
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // LibSU
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.navigation)
    implementation(libs.koin.workmanager)

    // Licenses
    implementation(libs.about.libraries)

    // Ktor
    implementation(libs.ktor.core)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization)

    // Markdown
    implementation(libs.markdown.renderer)

    // Fading Edges
    implementation(libs.fading.edges)

    // Scrollbars
    implementation(libs.scrollbars)

    // EnumUtil
    implementation(libs.enumutil)
    ksp(libs.enumutil.ksp)

    // Reorderable lists
    implementation(libs.reorderable)

    // Compose Icons
    implementation(libs.compose.icons.fontawesome)

    // APK signing (supports JKS/PKCS12)
    implementation(libs.apksig)
    implementation(libs.bcprov)

    // Ackpine
    implementation(libs.ackpine.core)
    implementation(libs.ackpine.ktx)
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Semantic versioning string parser
        classpath(libs.semver.parser)
    }
}

android {
    namespace = "app.universal.revanced.manager"
    compileSdk = 36
    buildToolsVersion = "35.0.1"
    // Pin to NDK r25c to restore 32-bit x86 support (NDK r27 dropped it).
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "app.universal.revanced.manager"
        minSdk = 26
        targetSdk = 35

        val versionStr = if (version == "unspecified") "1.8.1" else version.toString()
        versionName = versionStr
        versionCode = with(versionStr.toVersion()) {
            major * 10_000_000 +
                    minor * 10_000 +
                    patch * 100 +
                    (preRelease?.substringAfterLast('.')?.toInt() ?: 0)
        }
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "URV_BUILD_PROFILE", "\"$urvBuildProfileName\"")
        buildConfigField("boolean", "HAS_MORPHE_RUNTIME", includedMorpheRuntime.toString())
        buildConfigField("boolean", "HAS_AMPLE_RUNTIME", includedAmpleRuntime.toString())
        buildConfigField("boolean", "HAS_REVANCED_V21_RUNTIME", includedRevanced21Runtime.toString())
        ndk {
            // Include x86 now that the NDK is pinned to a version that still supports it.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    val keystoreFile = file("keystore.jks")
    val releaseSigningConfig = if (project.hasProperty("signAsDebug") || !keystoreFile.exists()) {
        signingConfigs.getByName("debug")
    } else {
        signingConfigs.create("release") {
            storeFile = keystoreFile
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEYSTORE_ENTRY_ALIAS")
            keyPassword = System.getenv("KEYSTORE_ENTRY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
            versionNameSuffix = devProfileSuffix
            signingConfig = releaseSigningConfig
            buildConfigField("long", "BUILD_ID", "${Random.nextLong()}L")
        }

        create("dev") {
            initWith(getByName("release"))
            versionNameSuffix = devProfileSuffix
            signingConfig = releaseSigningConfig
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("long", "BUILD_ID", "${Random.nextLong()}L")
        }

        release {
            versionNameSuffix = releaseProfileSuffix
            if (!project.hasProperty("noProguard")) {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }

            signingConfig = releaseSigningConfig
            buildConfigField("long", "BUILD_ID", "0L")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    applicationVariants.all {
        val resolvedVersionName = versionName.orEmpty().ifBlank {
            if (version == "unspecified") "1.8.1" else version.toString()
        }
        outputs.all {
            this as com.android.build.gradle.internal.api.ApkVariantOutputImpl

            val abi = getFilter(com.android.build.OutputFile.ABI)
            val abiSuffix = when (abi) {
                "arm64-v8a" -> "arm64-v8a"
                "armeabi-v7a" -> "armeabi-v7a"
                "x86" -> "x86"
                "x86_64" -> "x86_64"
                null -> "universal"
                else -> abi
            }
            outputFileName =
                "$urvBuildProfileName-universal-revanced-manager-v${artifactVersionName(resolvedVersionName)}-$abiSuffix.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes.addAll(
            listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/**.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "org/bouncycastle/pqc/**.properties",
                "org/bouncycastle/x509/**.properties",
            )
        )
        jniLibs {
            useLegacyPackaging = true
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    android {
        androidResources {
            generateLocaleConfig = true
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(morpheRuntimeAssetsDir)
        getByName("main").assets.srcDir(ampleRuntimeAssetsDir)
        getByName("main").assets.srcDir(revanced21RuntimeAssetsDir)
        getByName("main").assets.srcDir(revanced22RuntimeAssetsDir)
        getByName("main").res.srcDir(legalResourcesDir)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("aboutlibraries")
    }
    library {
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.EXACT
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

tasks {
    whenTaskAdded {
        if (name.startsWith("lintVital")) {
            enabled = false
        }
    }

    // Needed by gradle-semantic-release-plugin.
    // Tracking: https://github.com/KengoTODA/gradle-semantic-release-plugin/issues/435.
    val publish by registering {
        group = "publishing"
        description = "Build the release APK"

        dependsOn("assembleRelease")

        val apk = project.layout.buildDirectory.file("outputs/apk/release/${outputApkFileName}")
        val ascFile = apk.map { it.asFile.resolveSibling("${it.asFile.name}.asc") }

        inputs.file(apk).withPropertyName("inputApk")
        outputs.file(ascFile).withPropertyName("outputAsc")

        doLast {
            signing {
                useGpgCmd()
                sign(apk.get().asFile)
            }
        }
    }

    val copyRuntimeTasks = mutableListOf<TaskProvider<out org.gradle.api.Task>>()

    val copyMorpheRuntimeApk by registering(Sync::class) {
        into(morpheRuntimeAssetsDir)
        if (includedMorpheRuntime) {
            val runtimeProject = project(":morphe-runtime")
            val runtimeApk = runtimeProject.layout.buildDirectory.file(
                "outputs/apk/release/morphe-runtime-release.apk"
            )
            dependsOn("${runtimeProject.path}:assembleRelease")
            from(runtimeApk)
            rename { "morphe-runtime.apk" }
        }
    }
    copyRuntimeTasks += copyMorpheRuntimeApk

    val copyAmpleRuntimeApk by registering(Sync::class) {
        into(ampleRuntimeAssetsDir)
        if (includedAmpleRuntime) {
            val runtimeProject = project(":ample-runtime")
            val runtimeApk = runtimeProject.layout.buildDirectory.file(
                "outputs/apk/release/ample-runtime-release.apk"
            )
            dependsOn("${runtimeProject.path}:assembleRelease")
            from(runtimeApk)
            rename { "ample-runtime.apk" }
        }
    }
    copyRuntimeTasks += copyAmpleRuntimeApk

    val copyRevancedRuntimeV21Apk by registering(Sync::class) {
        into(revanced21RuntimeAssetsDir)
        if (includedRevanced21Runtime) {
            val runtimeProject = project(":revanced-runtime-v21")
            val runtimeApk = runtimeProject.layout.buildDirectory.file(
                "outputs/apk/release/revanced-runtime-v21-release.apk"
            )
            dependsOn("${runtimeProject.path}:assembleRelease")
            from(runtimeApk)
            rename { "revanced-runtime-v21.apk" }
        }
    }
    copyRuntimeTasks += copyRevancedRuntimeV21Apk

    val copyRevanced22RuntimeAssets by registering(Sync::class) {
        dependsOn(apkEditorMergeJar)
        into(revanced22RuntimeAssetsDir)
        from(apkEditorLib) {
            into("apkeditor")
            rename { "APKEditor-1.4.7.jar" }
        }
        from(apkEditorMergeJar) {
            into("apkeditor")
        }
    }

    val copyNoticeFile by registering(Copy::class) {
        from(rootProject.file("third-party/NOTICE.txt"))
        into(legalResourcesDir.map { it.dir("raw") })
        rename { "notice.txt" }
    }

    val copyAboutLibrariesJson by registering(Copy::class) {
        dependsOn("prepareLibraryDefinitionsRelease")
        from(layout.buildDirectory.file("generated/aboutLibraries/release/res/raw/aboutlibraries.json"))
        into(legalResourcesDir.map { it.dir("raw") })
        rename { "licenses_index.json" }
    }

    named("preBuild") {
        dependsOn(copyNoticeFile, copyAboutLibrariesJson)
        copyRuntimeTasks.forEach(::dependsOn)
    }

    matching { it.name.endsWith("Assets") && it.name.startsWith("merge") }.configureEach {
        dependsOn(copyRevanced22RuntimeAssets)
    }

    matching { it.name.contains("lintVital", ignoreCase = true) }.configureEach {
        dependsOn(copyRevanced22RuntimeAssets)
    }

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
