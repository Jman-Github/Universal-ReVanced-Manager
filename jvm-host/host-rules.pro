# Adapted from Morphe Manager PR #1215; see third-party/NOTICE.txt.
# Only remove unreachable code. SDK names, bytecode and metadata stay intact.
-dontobfuscate
-dontoptimize
-keepattributes *

# Engines, entry points, serializers and private SDK discovery methods are loaded
# reflectively. Bundles can reference SDK APIs that the manager never calls.
-keep class app.** { *; }
-keep class com.android.tools.smali.** { *; }
-keep class org.jf.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class com.google.common.** { *; }
-keep class com.reandroid.** { *; }
# Patch utility methods can expose these dependency types, including exceptions.
-keep class com.android.apksig.** { *; }
-keep class com.android.tools.build.apkzlib.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.apache.commons.** { *; }
-keep class org.antlr.runtime.** { *; }
-keep class org.xmlpull.** { *; }
-keep class org.json.** { *; }

# Security providers discover algorithms and key/certificate implementations by name.
-keep class org.bouncycastle.** { *; }

# XML parsers, signing algorithms and security providers use reflection.
-keepnames class com.android.apksig.internal.** { *; }
-keepnames class org.xmlpull.** { *; }
-keepclassmembers class com.android.apksig.internal.** { <init>(); <fields>; }

# Optional compiler tooling and Android-only APIs are not used by a JVM host.
-dontwarn org.antlr.tool.**
-dontwarn org.antlr.codegen.**
-dontwarn com.google.auto.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn dalvik.system.**
-dontwarn android.**
-dontwarn javax.annotation.**
# Kotlin metadata retains type aliases and pre-transformation atomicfu types;
# the JVM bytecode uses java.util.concurrent.atomic instead.
-dontwarn kotlin.concurrent.atomics.Atomic*
-dontwarn kotlinx.atomicfu.**
-dontwarn kotlin.jvm.internal.EnhancedNullability
# Signature-polymorphic calls have descriptors supplied by the JVM at runtime.
-dontwarn java.lang.invoke.VarHandle
-dontwarn java.lang.invoke.MethodHandle
# APKzlib brings test/mocking helpers whose optional integrations are unreachable.
-dontwarn org.mockito.**
-dontwarn net.bytebuddy.**
# XPP3 includes a legacy factory for a different XmlPullParserFactory version.
-dontwarn org.xmlpull.mxp1.MXParserFactory
# Legacy APKEditor also embeds optional CLI classes from another JCommander version.
-dontwarn com.beust.jcommander.**
-dontnote **
