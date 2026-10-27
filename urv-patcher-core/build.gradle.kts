import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Universal ReVanced Manager patcher core (Morphe engine)"

kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    sourceSets.main {
        kotlin.srcDir("../morphe-runtime/src/main/java")
        kotlin.srcDir("../shared/merger/src/main/java")
        kotlin.include(
            "app/urv/manager/morphe/runtime/MorpheRuntimeEntry.kt",
            "app/urv/manager/morphe/runtime/MorpheRuntimeCallback.kt",
            "app/urv/manager/patcher/morphe/MorpheSession.kt",
            "app/urv/manager/patcher/morphe/MorphePatchBundleLoader.kt",
            "app/urv/manager/patcher/split/SplitApkPreparer.kt",
            "app/urv/manager/patcher/split/Merger.kt",
            "app/urv/manager/patcher/split/SplitDexCompression.kt",
            "app/urv/manager/patcher/util/NativeLibStripper.kt",
            "app/urv/manager/patcher/util/XmlSurrogateSanitizer.kt",
            "app/urv/manager/patcher/runtime/FrameworkCacheResolver.kt"
        )
    }
}
usePatcherClassMergerCompatibility()

dependencies {
    implementation(project(":jvm-host"))
    implementation(libs.morphe.patcher) {
        exclude(group = "com.github.REAndroid", module = "arsclib")
    }
    implementation(libs.morphe.library) {
        exclude(group = "com.github.REAndroid", module = "arsclib")
    }
    implementation(libs.arsclib)
    implementation("com.android.tools.build:apkzlib:8.5.2")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xpp3)
}

// Like upstream, built-in hosts use Kotlin's standard reflection fallback.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

val hostJar = tasks.register<Jar>("hostJar") {
    // Refresh fat JARs cached before framework/reflection packaging changed.
    inputs.property("hostPackagingVersion", 1)
    archiveFileName.set("${project.name}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("frameworks/**")
    // QName comes from java.xml on the embedded JVM, not an Android compatibility JAR.
    exclude("javax/xml/namespace/**")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**")
}

// Code adapted from Morphe Manager PR #1215; see third-party/NOTICE.txt.
val proguard: Configuration = configurations.create("proguard") {
    isCanBeConsumed = false
}
dependencies {
    proguard(libs.proguard)
    // ProGuard's default reader predates the Kotlin metadata emitted by this project.
    proguard(libs.kotlin.metadata.jvm)
}

val shrunkHostJar = tasks.register<ShrinkJarTask>("shrinkHostJar") {
    group = "build"
    description = "Shrinks the isolated JVM host while retaining patch bundle APIs."
    inputJar.set(hostJar.flatMap { it.archiveFile })
    rules.from(rootProject.file("jvm-host/host-rules.pro"))
    excludes.addAll("META-INF/proguard/**", "META-INF/com.android.tools/**", "META-INF/maven/**")
    jdk.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    proguardClasspath.from(proguard)
    restoreFrameworks.set(false)
    outputJar.set(layout.buildDirectory.file("shrunk-host/${project.name}.jar"))
}

configurations.create("hostJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(shrunkHostJar.flatMap { it.outputJar })
}
