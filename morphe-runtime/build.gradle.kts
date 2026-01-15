import kotlin.random.Random

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "app.universal.revanced.manager.morphe.runtime"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "app.universal.revanced.manager.morphe.runtime"
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
}

dependencies {
    implementation(libs.morphe.patcher) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation(libs.morphe.library) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation("io.github.reandroid:ARSCLib:1.3.8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    compileOnly(libs.hidden.api.stub)
}
