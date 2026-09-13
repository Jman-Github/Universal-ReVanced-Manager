-dontobfuscate

# Downloader plugins are loaded with a parent-first class loader and resolve
# Kotlin runtime classes from the host app. Keep the host Kotlin stdlib intact
# so externally compiled plugins do not fail on stripped stdlib methods.
-keep class kotlin.** { *; }

# Keep the legacy ReVanced patcher/library API surface intact in the host app.
# Patch bundles loaded by the in-app v21 metadata/runtime path resolve these
# classes and methods reflectively against the manager APK itself.
-keep class app.revanced.patcher.** { *; }
-keep class app.revanced.library.** { *; }

# External v22 patch bundles compile against Patcher's relocated mutable
# Dexlib API. These calls are invisible to R8 because the bundles are loaded
# at runtime, so preserve the two relocated API packages they can reference.
-keep class app.revanced.com.android.tools.smali.dexlib2.iface.value.** { *; }
-keep class app.revanced.com.android.tools.smali.dexlib2.mutable.** { *; }

# ReVanced v22 remains the built-in default runtime and is reached through
# Revanced22RuntimeBridge reflection, so release shrinking must keep it.
-keep class app.urv.manager.revanced.runtime.** { *; }

-keep class com.android.tools.smali.** { *; }

-keep class app.urv.manager.patcher.runtime.process.* { *; }

# Invoked reflectively by app_process for the standalone split APK merger.
-keep class app.urv.manager.patcher.split.SplitMergeProcess {
    public static void main(java.lang.String[]);
}

# Invoked reflectively by app_process for signature metadata injection.
-keep class app.urv.manager.domain.manager.SignatureMetadataInjectorProcess {
    public static void main(java.lang.String[]);
}

-keep class app.revanced.manager.plugin.downloader.** { *; }
-keep class app.revanced.manager.downloader.** { *; }
-keep class app.urv.manager.plugin.downloader.** { *; }
-keep class app.urv.manager.downloader.** { *; }
-keepnames class com.android.apksig.internal.** { *; }
-keepnames class org.xmlpull.** { *; }

-dontwarn com.google.j2objc.annotations.*
-dontwarn app.revanced.patcher.PatcherResult
-dontwarn app.revanced.patcher.PatcherResult$PatchedDexFile
-dontwarn app.revanced.patcher.PatcherResult$PatchedResources
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.slf4j.**
