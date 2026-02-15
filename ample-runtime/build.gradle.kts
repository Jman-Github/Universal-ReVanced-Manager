import kotlin.random.Random
import org.gradle.api.tasks.Copy
import org.gradle.jvm.tasks.Jar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
}

val apkEditorLib by configurations.creating
val apkEditorAssetsDir = layout.buildDirectory.dir("generated/apkeditor-assets")

val apkEditorMergeJar by tasks.registering(Jar::class) {
    archiveFileName.set("apkeditor-merge.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn("compileReleaseJavaWithJavac")
    from("$buildDir/intermediates/javac/release/classes") {
        include("app/revanced/manager/patcher/split/ApkEditorMergeProcess*.class")
    }
}

val copyApkEditorAssets by tasks.registering(Copy::class) {
    dependsOn(apkEditorMergeJar)
    val targetDir = apkEditorAssetsDir.map { it.dir("apkeditor") }
    from(apkEditorLib) {
        rename { "APKEditor-1.4.7.jar" }
    }
    from(apkEditorMergeJar)
    into(targetDir)
}

android {
    namespace = "app.universal.revanced.manager.ample.runtime"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "app.universal.revanced.manager.ample.runtime"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }

    val releaseSigningConfig = signingConfigs.maybeCreate("release").apply {
        // Use debug signing if no keystore is provided.
        storeFile = rootProject.file("app/keystore.jks").takeIf { it.exists() }
        if (storeFile == null) {
            val debug = signingConfigs.getByName("debug")
            storeFile = debug.storeFile
            storePassword = debug.storePassword
            keyAlias = debug.keyAlias
            keyPassword = debug.keyPassword
        } else {
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEYSTORE_ENTRY_ALIAS")
            keyPassword = System.getenv("KEYSTORE_ENTRY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            buildConfigField("long", "BUILD_ID", "${Random.nextLong()}L")
            signingConfig = releaseSigningConfig
        }
        release {
            buildConfigField("long", "BUILD_ID", "0L")
            signingConfig = releaseSigningConfig
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(apkEditorAssetsDir)
}

dependencies {
    implementation(libs.ample.patcher) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation(libs.ample.library) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "app.revanced", module = "revanced-patcher")
    }
    apkEditorLib(files("$rootDir/libs/APKEditor-1.4.7.jar"))
    compileOnly(files("$rootDir/libs/APKEditor-1.4.7.jar"))
    implementation("app.revanced:arsclib:1.3.9.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    compileOnly(libs.hidden.api.stub)
}

tasks.matching { it.name.endsWith("Assets") && it.name.startsWith("merge") }.configureEach {
    dependsOn(copyApkEditorAssets)
}

tasks.matching { it.name.contains("lintVital", ignoreCase = true) }.configureEach {
    dependsOn(copyApkEditorAssets)
}
