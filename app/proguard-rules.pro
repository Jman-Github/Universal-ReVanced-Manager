-dontobfuscate

# Downloader plugins are loaded with a parent-first class loader and resolve
# Kotlin runtime classes from the host app. Keep the host Kotlin stdlib intact
# so externally compiled plugins do not fail on stripped stdlib methods.
-keep class kotlin.** { *; }

-keep class app.revanced.manager.patcher.runtime.process.* { *; }
-keep class app.revanced.manager.plugin.downloader.** { *; }
-keep class app.revanced.manager.downloader.** { *; }
-keepnames class com.android.apksig.internal.** { *; }
-keepnames class org.xmlpull.** { *; }

-dontwarn com.google.j2objc.annotations.*
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.slf4j.**
