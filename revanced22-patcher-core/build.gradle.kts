// Each patcher version has its own classpath and isolated class loader in the shared JVM.
plugins { alias(libs.plugins.kotlin.jvm) }
sourceSets.main { java.srcDir("../app/src/main/java"); java.include("app/urv/manager/patcher/split/ApkEditorMergeProcess.java") }
kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll("-Xskip-metadata-version-check", "-Xskip-prerelease-check")
    }
    sourceSets.main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.srcDir("../shared/merger/src/main/java")
        kotlin.include(
            "app/urv/manager/patcher/aapt/AaptSelector.kt",
            "app/urv/manager/patcher/runtime/FrameworkCacheResolver.kt",
            "app/urv/manager/patcher/split/Merger.kt",
            "app/urv/manager/patcher/split/SplitApkPreparer.kt",
            "app/urv/manager/patcher/split/SplitApkFiles.kt",
            "app/urv/manager/patcher/split/SplitManifestInspector.kt",
            "app/urv/manager/patcher/split/SplitApkInspector.kt",
            "app/urv/manager/patcher/split/SplitDexCompression.kt",
            "app/urv/manager/patcher/util/**",
            "app/urv/manager/revanced/runtime/**",
            "app/urv/manager/patcher/host/ToolRuntimeEntry.kt",
            "app/urv/manager/domain/manager/SignatureMetadataArchiveEngine.kt",
            "app/urv/manager/domain/manager/SignatureMetadataModels.kt",
            "app/urv/manager/domain/manager/SignatureMetadataArscEngine.kt",
            "app/urv/manager/patcher/revanced/RevancedSession.kt",
            "app/urv/manager/patcher/revanced/RevancedPatchBundleLoader.kt",
            "app/urv/manager/patcher/split/ApkEditorMergeRuntime.kt",
            "app/urv/manager/patcher/split/SplitManifestCleaner.kt"
        )
    }
}
usePatcherClassMergerCompatibility()

dependencies {
    implementation(project(":jvm-host"))
    implementation("com.google.guava:guava:33.2.1-jre")
    implementation(libs.revanced.patcher.v22)
    implementation("com.android.tools.build:apkzlib:8.5.2")
    // The v22 SDK uses KClassesJvm in patch contexts and options.
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xpp3)
    implementation(libs.arsclib)
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
