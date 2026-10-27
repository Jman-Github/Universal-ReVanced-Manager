plugins { `kotlin-dsl` }
repositories { mavenCentral(); gradlePluginPortal() }
dependencies {
    implementation("org.tukaani:xz:1.11")
    implementation("org.ow2.asm:asm-tree:9.9.1")
}
