# Intentionally empty. The API module publishes a consumer ProGuard file, and
# keeping this file present avoids validation noise from a missing configured path.

# Consumers load downloader plugins against the host app's Kotlin runtime via a
# parent-first class loader, so the host must keep the Kotlin stdlib methods
# that external plugins may call.
-keep class kotlin.** { *; }

-keep class app.revanced.manager.plugin.downloader.** { *; }
-keep class app.revanced.manager.downloader.** { *; }
-keep class app.urv.manager.plugin.downloader.** { *; }
-keep class app.urv.manager.downloader.** { *; }
