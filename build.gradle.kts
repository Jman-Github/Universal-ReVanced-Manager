plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.devtools) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.about.libraries) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Only external SDK archives need the patcher merger transform. Mark project
// outputs too, including AGP's secondary class variants used by local unit tests.
// Otherwise those variants disappear from test classpaths, and clean builds can
// try to transform a project JAR before its producer task has run.
val patcherMergerCompatible = Attribute.of("app.upm.patcher-merger-compatible", Boolean::class.javaObjectType)
allprojects {
    configurations.configureEach {
        outgoing.attributes.attribute(patcherMergerCompatible, true)
        outgoing.variants.configureEach {
            attributes.attribute(patcherMergerCompatible, true)
        }
    }
}
