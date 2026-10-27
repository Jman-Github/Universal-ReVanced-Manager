// Each patcher version has its own classpath and isolated class loader in the shared JVM.
plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll("-Xskip-metadata-version-check", "-Xskip-prerelease-check")
    }
    sourceSets.main {
        kotlin.srcDir("src/main/kotlin")
        kotlin.srcDir("../revanced.v21-runtime-plugin/src/main/java")
        kotlin.exclude(
            "app/urv/manager/patcher/patch/PatchBundle.kt",
            "app/urv/manager/revanced/runtime/RevancedPatchMetadata.kt"
        )
        kotlin.srcDir("../shared/merger/src/main/java")
        kotlin.srcDir("../shared/runtime-plugin/src/main/java")
        kotlin.include(
            "app/urv/manager/patcher/aapt/AaptSelector.kt",
            "app/urv/manager/patcher/runtime/FrameworkCacheResolver.kt",
            "app/urv/manager/patcher/split/Merger.kt",
            "app/urv/manager/patcher/split/SplitApkPreparer.kt",
            "app/urv/manager/patcher/split/SplitDexCompression.kt",
            "app/urv/manager/patcher/split/SplitApkFiles.kt",
            "app/urv/manager/patcher/split/SplitManifestInspector.kt",
            "app/urv/manager/patcher/split/SplitApkInspector.kt",
            "app/urv/manager/patcher/util/**",
            "app/urv/manager/revanced/runtime/**",
            "app/urv/manager/patcher/Session.kt",
            "app/urv/manager/patcher/patch/LegacyPatchBundle.kt",
            "app/urv/manager/patcher/patch/PatchBundle.kt"
        )
    }
}
usePatcherClassMergerCompatibility()

dependencies {
    testImplementation(kotlin("test-junit"))
    implementation(project(":jvm-host"))
    implementation("com.google.guava:guava:32.1.2-jre")
    implementation("app.revanced:revanced-patcher:19.3.1")
    implementation("com.android.tools.build:apkzlib:8.5.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xpp3)
    implementation(files("../libs/APKEditor-1.4.7.jar"))
}

val hostJar = tasks.register<Jar>("hostJar") {
    // Refresh fat JARs cached before framework/reflection packaging changed.
    inputs.property("hostPackagingVersion", 1)
    archiveFileName.set("${project.name}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
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
    restoreFrameworks.set(true)
    outputJar.set(layout.buildDirectory.file("shrunk-host/${project.name}.jar"))
}

configurations.create("hostJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(shrunkHostJar.flatMap { it.outputJar })
}
