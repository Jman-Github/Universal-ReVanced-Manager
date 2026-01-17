# Keep entrypoint invoked via reflection from the app.
-keep class app.revanced.manager.morphe.runtime.** { *; }

# Keep IPC classes used across process boundaries.
-keep class app.revanced.manager.patcher.runtime.process.** { *; }

# Keep Morphe patcher API classes that are accessed via reflection.
-keep class app.morphe.** { *; }

# Keep Kotlin runtime classes needed by patches loaded from external dex bundles.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Keep dexlib2/smali types used by Morphe patch loader via reflection.
-keep class com.android.tools.smali.** { *; }

# Keep shared patcher parcelables stable across processes.
-keep class app.revanced.manager.patcher.** { *; }

# Ignore desktop-only classes referenced by apktool/lib that aren't used on Android.
-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Image
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.Raster
-dontwarn java.awt.image.RenderedImage
-dontwarn java.awt.image.WritableRaster
-dontwarn javax.imageio.ImageIO
