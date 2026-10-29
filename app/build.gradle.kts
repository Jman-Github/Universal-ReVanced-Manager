import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import com.android.build.api.variant.FilterConfiguration
import kotlin.random.Random
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.devtools)
    alias(libs.plugins.about.libraries)
    signing
}

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1215
// https://github.com/MorpheApp/morphe-manager/pull/1246
// https://github.com/MorpheApp/morphe-manager/pull/1302
val patcherHost: Configuration = configurations.create("patcherHost") {
    isCanBeConsumed = false
}
// Hybrid JVM layout adapted from Morphe PR #1302: keep the stable ARM32 JIT.
data class EmbeddedJvm(val feature: Int, val file: String, val sha256: String, val platform: String) {
    val url get() = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre$feature/$file"
}
val embeddedJvms = mapOf(
    "arm64-v8a" to EmbeddedJvm(25, "jre25-android-arm64.tar.xz", "d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37", "linux-aarch64"),
    "armeabi-v7a" to EmbeddedJvm(17, "jre17-android-arm.tar.xz", "4a9134f1ebf6340dd855805d351712462cddab6f3c2684da7e7da10ccf06648d", "linux-arm"),
    "x86_64" to EmbeddedJvm(25, "jre25-android-x86_64.tar.xz", "7fca862ee1b2d5fe23cd9c9c3d9b7ad3c241947ad1a6cc9464ef2e674867105d", "linux-amd64")
)

/**
 * The modules patching can reach: what the patcher and its libraries use, plus what ART offers patches
 * beyond that, so a patch that runs inside the app also runs in the JVM.
 */
val embeddedJvmModules = listOf(
    "java.base", "java.logging", "java.xml", "java.sql", "jdk.unsupported", "jdk.charsets", "jdk.crypto.ec",
    // GC collection time used to fail runs whose heap is too small.
    "java.management",
    // Apktool decodes nine-patch PNGs using Raster, BufferedImage and ImageIO.
    "java.desktop"
)

/**
 * What of the build the linked modules need, its native libraries above all. The 64 bit builds
 * ship the server VM, the 32-bit ARM one only the client VM.
 */
val embeddedJvmFiles = listOf(
    "lib/*/libjvm.so",
    "lib/libjava.so", "lib/libjimage.so", "lib/libzip.so", "lib/libnet.so", "lib/libnio.so",
    "lib/libverify.so", "lib/libmanagement.so", "lib/libprefs.so",
    // JDK 25 resolves java.base native symbols through this library.
    "lib/libsyslookup.so",
    // Headless AWT and ImageIO still need native image and font support on Android.
    "lib/libawt.so", "lib/libawt_headless.so", "lib/libjavajpeg.so",
    "lib/libfontmanager.so", "lib/libfreetype.so", "lib/liblcms.so", "lib/libmlib_image.so",
    "lib/fontconfig*", "lib/psfont*",
    "lib/jvm.cfg", "lib/tzdb.dat", "lib/security", "conf"
)

val embeddedJvmTasks = embeddedJvms.map { (abi, build) ->
    tasks.register<PrepareEmbeddedJvmTask>("prepareEmbeddedJvm${abi.replace("-", "").replace("_", "").replaceFirstChar(Char::uppercase)}") {
        group = "build"
        description = "Links the embedded JVM for $abi."
        url.set(build.url)
        sha256.set(build.sha256)
        targetPlatform.set(build.platform)
        modules.set(embeddedJvmModules)
        runtimeFiles.set(embeddedJvmFiles)
        jdk.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(build.feature)) })
        downloadCache.set(File(gradle.gradleUserHomeDir, "caches/urv-embedded-jvm"))
        outputArchive.set(layout.buildDirectory.file("intermediates/embeddedJvm/$abi.zip.xz"))
        outputImage.set(layout.buildDirectory.file("intermediates/embeddedJvm/$abi.modules"))
    }
}

/** The ABIs of [embeddedJvms] as a Java array literal for BuildConfig. */
val embeddedJvmAbis = embeddedJvms.keys.joinToString(prefix = "{", postfix = "}") { abi -> "\"$abi\"" }

/** Changes with whatever goes into the runtime, which the app then unpacks again. */
val embeddedJvmId = (listOf("shared-image-v1") + embeddedJvms.values.map { build -> build.sha256 } + embeddedJvmModules + embeddedJvmFiles)
    .hashCode().toUInt().toString(16)

/** Assets the embedded JVM runs from, read by JvmInstallation. */
val embeddedJvmAssets = tasks.register<EmbeddedJvmAssetsTask>("embeddedJvmAssets") {
    group = "build"
    description = "Lays out the embedded JVM runtimes and the patcher host as assets."
    runtimes.from(embeddedJvmTasks.map { it.flatMap { task -> task.outputArchive } })
    images.from(embeddedJvmTasks.map { it.flatMap { task -> task.outputImage } })
    baseAbi.set("arm64-v8a")
    hostJar.from(patcherHost)
    outputDirectory.set(layout.buildDirectory.dir("generated/embeddedJvm"))
}


val resolvedProjectVersion = if (version == "unspecified") "1.8.1" else version.toString()
fun artifactVersionName(versionName: String): String =
    "v${versionName.removePrefix("v")}"

fun androidVersionCode(versionName: String, developmentBuild: Boolean = false): Int {
    val normalizedVersion = versionName.removePrefix("v")
    val versionParts = normalizedVersion.substringBefore('-').split('.')
    require(versionParts.size == 3) { "Invalid version: $versionName" }

    val majorVersion = versionParts[0].toInt()
    val minorVersion = versionParts[1].toInt()
    val patchVersion = versionParts[2].toInt()
    require(majorVersion in 0..209) { "Major version must be in 0..209: $versionName" }
    require(minorVersion in 0..99) { "Minor version must be in 0..99: $versionName" }
    require(patchVersion in 0..99) { "Patch version must be in 0..99: $versionName" }

    val isExplicitPrerelease = normalizedVersion.contains('-')
    val prereleaseNumber = normalizedVersion
        .substringAfter('-', "")
        .split('.', '-')
        .mapNotNull { it.toIntOrNull() }
        .lastOrNull()
    val releaseOrdinal = when {
        isExplicitPrerelease -> prereleaseNumber ?: 0
        developmentBuild -> 0
        else -> 999
    }
    require(!isExplicitPrerelease || releaseOrdinal in 0..998) {
        "Prerelease number must be in 0..998; 999 is reserved for stable releases: $versionName"
    }

    val computedVersionCode = majorVersion.toLong() * 10_000_000L +
        minorVersion.toLong() * 100_000L +
        patchVersion.toLong() * 1_000L +
        releaseOrdinal
    require(computedVersionCode in 1..2_100_000_000L) {
        "Android versionCode is outside the supported range: $versionName -> $computedVersionCode"
    }
    return computedVersionCode.toInt()
}

val outputApkFileName = "universal-patch-manager-${artifactVersionName(resolvedProjectVersion)}-universal.apk"
val reseamRuntimeAssetsDir = layout.buildDirectory.dir("generated/reseam-runtime")
val morpheRuntimeAssetsDir = layout.buildDirectory.dir("generated/morphe-runtime")
val legalResourcesDir = layout.buildDirectory.dir("generated/legal-res")
val devVersionSuffix = providers.gradleProperty("devVersionSuffix")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "dev"
// PR builds share the normal app identity, with separate private storage.
val prTestBuild = providers.gradleProperty("prTestBuild")
    .map(String::toBoolean)
    .getOrElse(false)
// Use the published APK's code verbatim; deriving it from its version name can differ.
val prReleaseVersionCode = if (prTestBuild) {
    val code = providers.gradleProperty("prReleaseVersionCode").orNull?.toIntOrNull()
    require(code != null && code in 1..2_100_000_000) {
        "PR builds require -PprReleaseVersionCode=<published APK version code>. " +
            "Use .github/scripts/resolve-pr-version-code.py to read it."
    }
    code
} else null
val managerDatabaseVersion = 21
val includedMorpheRuntime = rootProject.findProject(":morphe-runtime") != null
val devVersionNameSuffix = if (resolvedProjectVersion.contains('-')) "" else "-$devVersionSuffix"
val libraryVersions = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun libraryVersion(alias: String): String =
    libraryVersions.findVersion(alias).get().requiredVersion

val arscLib by configurations.creating {
    isTransitive = false
}

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
val androidArscLib by tasks.registering(Jar::class) {
    archiveFileName.set("ARSCLib-android.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ arscLib.map { zipTree(it) } })
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

usePatcherClassMergerCompatibility()

dependencies {
    // Bundle format detection inspects DEX superclasses before loading a patcher SDK.
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    patcherHost(project(":upm-patcher-core", "hostJar"))
    patcherHost(project(":tools-patcher-core", "hostJar"))
    implementation("org.tukaani:xz:1.11")
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
    debugImplementation(libs.compose.ui.tooling)
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
    implementation(libs.coil.network.okhttp)
    implementation(libs.app.icon.loader)

    // KotlinX
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collection.immutable)
    implementation(libs.kotlinx.datetime)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    annotationProcessor(libs.room.compiler)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)

    // ReVanced (PR #39: https://github.com/Jman-Github/Universal-Patch-Manager/pull/39)
    implementation(libs.revanced.library) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "app.revanced", module = "revanced-patcher")
    }
    implementation("com.android.tools.build:apkzlib:8.5.2")
    compileOnly("com.google.guava:guava:33.2.1-jre")
    implementation(libs.xpp3)
    arscLib(libs.arsclib)
    implementation(files(androidArscLib))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.documentfile)

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

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.arsclib)
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
    namespace = "app.universal.patch.manager"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    // Pin to NDK r25c to restore 32-bit x86 support (NDK r27 dropped it).
    ndkVersion = "25.2.9519653"

    defaultConfig {
        buildConfigField("String[]", "EMBEDDED_JVM_ABIS", embeddedJvmAbis)
        buildConfigField("int", "EMBEDDED_JVM_64_BIT_FEATURE", "25")
        buildConfigField("int", "EMBEDDED_JVM_32_BIT_FEATURE", "17")
        buildConfigField("String", "EMBEDDED_JVM_ID", "\"$embeddedJvmId\"")
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_shared"
        applicationId = "app.universal.patch.manager"
        buildConfigField("boolean", "IS_PR_TEST_BUILD", prTestBuild.toString())
        buildConfigField("long", "PR_BUILD_TIMESTAMP", "${if (prTestBuild) System.currentTimeMillis() else 0L}L")
        buildConfigField("int", "DATABASE_VERSION", managerDatabaseVersion.toString())
        manifestPlaceholders["databaseVersion"] = managerDatabaseVersion
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val versionStr = resolvedProjectVersion
        versionName = versionStr
        versionCode = prReleaseVersionCode ?: androidVersionCode(versionStr)
        vectorDrawables.useSupportLibrary = true
        buildConfigField("boolean", "HAS_MORPHE_RUNTIME", includedMorpheRuntime.toString())
        buildConfigField(
            "String",
            "MORPHE_PATCHER_VERSION",
            "\"${libraryVersion("morphe-patcher")}\""
        )
        buildConfigField(
            "String",
            "REVANCED_PATCHER_V21_VERSION",
            "\"${libraryVersion("revanced-patcher")}\""
        )
        buildConfigField(
            "String",
            "REVANCED_PATCHER_V22_VERSION",
            "\"${libraryVersion("revanced-patcher-v22")}\""
        )
        ndk {
            // Include x86 now that the NDK is pinned to a version that still supports it.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    val keystoreFile = file("keystore.jks")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val keystoreEntryAlias = System.getenv("KEYSTORE_ENTRY_ALIAS")
    val keystoreEntryPassword = System.getenv("KEYSTORE_ENTRY_PASSWORD")
    val hasReleaseSigningCredentials = keystoreFile.exists() &&
        !keystorePassword.isNullOrBlank() &&
        !keystoreEntryAlias.isNullOrBlank() &&
        !keystoreEntryPassword.isNullOrBlank()
    val signAsDebug = project.hasProperty("signAsDebug")
    if (System.getenv("CI").toBoolean() && !signAsDebug && !hasReleaseSigningCredentials) {
        throw GradleException(
            "Release signing credentials are required in CI. Set KEYSTORE_PASSWORD, " +
                "KEYSTORE_ENTRY_ALIAS, and KEYSTORE_ENTRY_PASSWORD, or pass -PsignAsDebug for debug signing."
        )
    }
    val releaseSigningConfig = if (signAsDebug || !hasReleaseSigningCredentials) {
        signingConfigs.getByName("debug")
    } else {
        signingConfigs.create("release") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keystoreEntryAlias
            keyPassword = keystoreEntryPassword
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
            versionNameSuffix = devVersionNameSuffix
            signingConfig = releaseSigningConfig
            buildConfigField("long", "BUILD_ID", "${Random.nextLong()}L")
        }

        create("dev") {
            initWith(getByName("release"))
            versionNameSuffix = devVersionNameSuffix
            signingConfig = releaseSigningConfig
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("long", "BUILD_ID", "${Random.nextLong()}L")
        }

        release {
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
            noCompress += listOf("xz", "apk")
        }
    }

    sourceSets.getByName("main").kotlin.directories.add(rootProject.file("shared/runtime-framework/src/main/java").path)

    sourceSets {
        getByName("main").kotlin.directories.add(rootProject.file("shared/merger/src/main/java").path)
        getByName("main").assets.directories.add(morpheRuntimeAssetsDir.get().asFile.path)
        getByName("main").assets.directories.add(reseamRuntimeAssetsDir.get().asFile.path)
        getByName("main").res.directories.add(legalResourcesDir.get().asFile.path)
        getByName("androidTest").assets.directories.add(file("$projectDir/schemas").path)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(embeddedJvmAssets, EmbeddedJvmAssetsTask::outputDirectory)
        val developmentBuild = variant.buildType == "debug" || variant.buildType == "dev"
        variant.outputs.forEach { output ->
            if (prReleaseVersionCode != null) {
                output.versionCode.set(prReleaseVersionCode)
            } else if (developmentBuild && !resolvedProjectVersion.contains('-')) {
                output.versionCode.set(
                    androidVersionCode(resolvedProjectVersion, developmentBuild = true)
                )
            }
            val abiSuffix = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?: "universal"
            output.outputFileName.set(
                output.versionName.orElse(resolvedProjectVersion).map { resolvedVersionName ->
                    "universal-patch-manager-${artifactVersionName(resolvedVersionName)}-$abiSuffix.apk"
                }
            )
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

    val copyReseamRuntimeApk by registering(Sync::class) {
        into(reseamRuntimeAssetsDir)
        dependsOn(":reseam-runtime:assembleRelease")
        from(project(":reseam-runtime").layout.buildDirectory.file("outputs/apk/release/reseam-runtime.apk"))
        rename { "reseam-runtime.apk" }
    }

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
        dependsOn(copyMorpheRuntimeApk, copyReseamRuntimeApk)
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
