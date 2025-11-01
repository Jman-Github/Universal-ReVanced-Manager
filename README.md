# 💊 Universal ReVanced Manager

![GPLv3 License](https://img.shields.io/badge/License-GPL%20v3-yellow.svg)

Application to use ReVanced on Android

## ❓ About

Universal ReVanced Manager is an application that uses [ReVanced Patcher](https://github.com/revanced/revanced-patcher) to patch Android apps.

## 💪 Unique Features

Universal ReVanced Manager includes powerful features that the official ReVanced Manager does not:

### 🔄 Patch Bundles & Customization
- 💉 **Third-Party Patch Support**: Import any third party API v4 patch bundle you want (including popular ones like inotia00's or anddea's) which the official ReVanced Manager does not support
- 🛠️ **Custom Bundle Names**: Set a custom display name for any imported patch bundle so you can tell them apart at a glance
- 🙂 **Smarter Patch Selection**:
  - Global "deselect all" button  
  - Per-bundle deselect button  
  - Patch profiles button to save patch selections and option states per app  
  - Latest patch bundle changelogs shown in bundle info

### 📦 App Patching Flow
- 🧠 **Downloaded App Source**: Added a "Downloaded apps" source in the select source screen when patching. If the manager has cached an APK from a downloader plugin, you can pick it directly from there. This option only appears when that app is available
- 🧹 **Advanced Native Library Stripping**: Optional advanced setting to strip unused native libraries (unsupported ABIs) from patched APKs during patching, helping reduce size
- 💾 **Export = Auto-Save**: When you export a patched app to storage from the patching screen, the manager will now also automatically save that patched app under the "Apps" tab. Before, this only happened if you installed the patched app directly from that screen
- 📲 **Installer Management**: A full installer management system with installer metadata, and configurable primary and fallback that applies everywhere across the app
- 📋 **View Applied Patches**: The "Apps" tab shows the applied patches for each saved patched APK and which patch bundle(s) were used
- 🛑 **Accidental Exit Protection**: After patching, pressing the back button now shows a confirmation popup. It asks if you really want to leave and gives you the option to save the patched app for later (adds it to the "Apps" tab)

### 📥 Downloader & Storage Management
- 📂 **Cached Downloads Management**: The manager can now keep multiple downloaded apps (from downloader plugins) inside the downloader settings. You can also export any of these APKs to your device storage whenever you want
- 🧼 **Plugin Cleanup**: You can uninstall downloader plugins directly from inside the manager via the download settings page. No manual cleanup needed

### 🎨 Appearance & Theming
- 🎯 **Accent Color Picker**: Appearance settings include an accent color picker so you can choose a custom theme color. This is in addition to Material You theming and the pure black theme

### 🌐 Network & Updates
- 🛜 **Metered Connection Control**: Toggle to allow updates on metered connections for both patch bundles and the manager itself, so you are not blocked on mobile data

### 🧑‍💻 Developer & Power Features
- 🧑‍💻 **Always-Visible Developer Options**: Developer Options are always available in Settings by default. No hidden or secret unlock flow
- 📤 **Robust Import / Export**: Export and import your patch bundles, your patch profiles, and your app settings to and from JSON files for easy backup, sharing, or migration between devices

## 🔽 Download

You can download the most recent version of Universal ReVanced Manager from [GitHub releases](https://github.com/Jman-Github/universal-revanced-manager/releases/latest).

## 📋 Patch Bundles

To import patch bundles into Universal ReVanced Manager, use my [ReVanced Patch Bundles](https://github.com/Jman-Github/ReVanced-Patch-Bundles) repository. It includes a detailed [catalog](https://github.com/Jman-Github/ReVanced-Patch-Bundles/blob/bundles/patch-bundles/PATCH-LIST-CATALOG.md) of all patches across 20+ tracked bundles, as well as [bundle URLs](https://github.com/Jman-Github/ReVanced-Patch-Bundles#-patch-bundles-urls) you can paste directly into Universal ReVanced Manager to import them. Keep in mind that only the patch bundles labeled "API v4" can be imported into the manager. Bundles without this label cannot be imported into the app.

## ⚖️ License

Universal ReVanced Manager is licensed under the GPLv3 license. Please see the [license file](https://github.com/Jman-Github/universal-revanced-manager/blob/main/LICENSE) for more information.
[tl;dr](https://www.tldrlegal.com/license/gnu-general-public-license-v3-gpl-3) you may copy, distribute and modify Universal ReVanced Manager as long as you track changes/dates in source files.
Any modifications to Universal ReVanced Manager must also be made available under the GPL, along with build & install instructions.

