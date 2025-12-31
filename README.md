
<p align="center">
  <picture>
    <source
      width="256px"
      media="(prefers-color-scheme: dark)"
      srcset="assets/icons/icon-circle.png"
    >
    <img
      width="256px"
      src="assets/icons/icon-circle.png"
      alt="Universal ReVanced Manager icon"
    />
  
# 💊 Universal ReVanced Manager

Application to use ReVanced on Android

  <img src="https://img.shields.io/badge/License-GPL%20v3-yellow.svg" alt="GPLv3 License" />
  &nbsp;
  <a href="https://t.me/urv_chat">
      <picture>
         <source height="24px" media="(prefers-color-scheme: dark)" srcset="https://user-images.githubusercontent.com/13122796/178032213-faf25ab8-0bc3-4a94-a730-b524c96df124.png" />
         <img height="24px" src="https://user-images.githubusercontent.com/13122796/178032213-faf25ab8-0bc3-4a94-a730-b524c96df124.png" alt="Telegram" />
      </picture>
  </a>
</p>

## ❓ About

Universal ReVanced Manager (URV Manager) is an application that uses [ReVanced Patcher](https://github.com/revanced/revanced-patcher) to patch Android apps.

## 💪 Unique Features

Universal ReVanced Manager includes powerful features that the official ReVanced Manager does not:

### 🔄 Patch Bundles & Customization
- 💉 **Third-Party Patch Support**: Import any third-party API v4 patch bundle you want (including popular ones like inotia00's or anddea's), which the official ReVanced Manager does not support
- 🛠️ **Custom Bundle Names**: Set a custom display name for any imported patch bundle so you can tell them apart at a glance
- 🙂 **Smarter Patch Selection**:
  - Global deselect all button  
  - Per-bundle deselect all button
  - Per-bundle select all button
  - Global select all button 
  - Patch profiles button to save patch selections and option states per app  
  - Latest patch bundle changelogs shown in bundle info
  - Undo & redo buttons
- 🧭 **Bundle Recommendation Picker**: Choose per-bundle suggested versions or override with any other supported version
- 🔍 **Suggestion Toggle on Select-App**: Bundle suggestions are grouped behind a toggle with inline dialogs to view additional supported versions
- 🧹 **Official Bundle Management**: Delete the Official ReVanced patch bundle from the Patch Bundles tab and restore it from Advanced settings
- 📝 **Export Filename Templates**: Configure a filename template for exported patched APKs with placeholders for app and patch metadata
- 🌐 **Release Link Button**: GitHub button on each bundle’s info page opens the bundle repository’s releases
- 🕒 **Bundle Timestamps**: Cards show Created and Updated times; exports and imports preserve these timestamps
- 🧭 **Organize Bundles**: "Organize" button to manually reorder bundles; exports and imports keep the custom order
- 📱 **Improved UI**: Settings, the Patch Bundles tab, the Apps tab, the app selection page, and the patch selection page all have an improved UI design

### 📦 App Patching Flow
- ⚒️ **LisoUseInAIKyrios Patch Support**: Supports the [LisoUseInAIKyrios](https://github.com/LisoUseInAIKyrios/revanced-patches) patch bundle without needing a computer or another app
- 🧠 **Downloaded App Source**: Added a "Downloaded apps" source in the select source screen when patching. If the manager has cached an APK from a downloader plugin, you can pick it directly from there. This option only appears when that app is available
- 🧩 **Split APK Support**: `.apkm`, `.apks`, and `.xapk` file formats are automatically converted to the `.apk` format when patching. No need for outside tools
- 🧹 **Advanced Native Library Stripping**: Optional advanced setting to strip unused native libraries (unsupported ABIs) from patched APKs during patching, helping reduce size
- 💾 **Export = Auto-Save**: When you export a patched app to storage from the patching screen, the manager will now also automatically save that patched app under the "Apps" tab. Before, this only happened if you installed the patched app directly from that screen
- 📲 **Installer Management**: A full installer management system with installer metadata, and configurable primary and fallback that applies everywhere across the app
- 📋 **View Applied Patches**: The "Apps" tab shows the applied patches for each saved patched APK and which patch bundle(s) were used
- 🛑 **Accidental Exit Protection**: After patching, pressing the back button now shows a confirmation popup. It asks if you really want to leave and gives you the option to save the patched app for later (adds it to the "Apps" tab)
- 🧩 **Missing Patch Recovery**: If a selected patch no longer exists, a detailed dialog explains the issue and returns you to patch selection with missing patches highlighted
- 🧷 **Step Auto-Collapse**: Completed patcher steps auto-collapse; toggle in Settings > Advanced > "Auto-collapse completed patcher steps"
- 🔢 **Version Tags**: On the patch selection and app selection pages, each app or patch displays the versions it supports. Tapping a version chip opens a web search for that specific app and version

### 📥 Patch Bundle Updates & Imports
- ⏳ **Progress with Percentages**: Progress bars with percentage for bundle updates, update checks, and imports
- 🧩 **Installer Management**: Full installer management system covering app installs, saved app reinstalls, and manager updates
  - Metadata display for each installer
  - Configurable primary and fallback installers
  - Shizuku installer option for silent installs when Shizuku or Sui is available
  - Advanced settings support saving custom installer packages with package-name lookup and autocomplete, plus dedicated management for third-party installers
  - App mounting support for rooted users (rooted mount installer)

### 📥 Downloader & Storage Management
- 📂 **Cached Downloads Management**: The manager can now keep multiple downloaded apps (from downloader plugins) inside the downloader settings. You can also export any of these APKs to your device storage whenever you want
- 🧼 **Plugin Cleanup**: You can uninstall downloader plugins directly from inside the manager via the download settings page. No manual cleanup needed

### 🎨 Appearance & Theming
- 🎯 **Enhanced Theming**: Appearance settings include an accent color picker, theme color picker, color HEX code support, presets, and a live preview widget so you can choose a custom theme color and customize the app to your liking
- ⚫ **Monochrome App Icons**: Support for Android monochrome icons
- ↔️ **Better Long Names**: Long labels use horizontal swipe instead of auto-sliding or wrapping

### 🌐 Network & Updates
- 🛜 **Metered Connection Control**: Toggle to allow updates on metered connections for both patch bundles and the manager itself, so you are not blocked on mobile data

### 🧑‍💻 Developer & Power Features
- 🧑‍💻 **Always-Visible Developer Options**: Developer Options are always available in Settings by default. No hidden or secret unlock flow

- 📤 **Robust Import / Export**: Export and import your patch bundles, your patch profiles, and your app settings to and from JSON files for easy backup, sharing, or migration between devices

### 🌍 Localization
- **Simplified Chinese (zh-CN)**: User-selectable language option in settings
- **Vietnamese (vi)**: User-selectable language option in settings
- **Korean (ko)**: User-selectable language option in settings
- **Japanese (ja)**: User-selectable language option in settings
- **Russian (ru)**: User-selectable language option in settings
- **Ukrainian (uk)**: User-selectable language option in settings

## 🔽 Download

You can download the most recent version of Universal ReVanced Manager from [GitHub releases](https://github.com/Jman-Github/universal-revanced-manager/releases/latest).

## 📋 Patch Bundles

To import patch bundles into Universal ReVanced Manager, use my [ReVanced Patch Bundles](https://github.com/Jman-Github/ReVanced-Patch-Bundles) repository. It includes a detailed [catalog](https://github.com/Jman-Github/ReVanced-Patch-Bundles/blob/bundles/patch-bundles/PATCH-LIST-CATALOG.md) of all patches across 20+ tracked bundles, as well as [bundle URLs](https://github.com/Jman-Github/ReVanced-Patch-Bundles#-patch-bundles-urls) you can paste directly into Universal ReVanced Manager to import them. Keep in mind that only the patch bundles labeled "API v4" can be imported into the manager. Bundles without this label cannot be imported into the app.

## 🔌 Supported Downloader Plugins

[Play Store Downloader](https://github.com/brosssh/revanced-manager-downloaders) ❌  
[ApkMirror Downloader](https://github.com/brosssh/revanced-manager-downloaders) ✅  
[APKPure Downloader](https://github.com/brosssh/revanced-manager-downloaders) ✅  
[APKCombo Downloader](https://github.com/brosssh/revanced-manager-downloaders) ✅  

## 🤝 Contributors
<table>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2Fbrosssh.png&h=36&w=36&fit=cover&mask=circle" alt="brosssh avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;">
      <a href="https://github.com/brosssh">brosssh</a><br />
      Multiple PRs, top contributor
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2FTanakaLun.png&h=36&w=36&fit=cover&mask=circle" alt="TanakaLun avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;">
      <a href="https://github.com/TanakaLun">TanakaLun</a><br />
      Chinese localization
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2Fann9cht.png&h=36&w=36&fit=cover&mask=circle" alt="ann9cht avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;">
      <a href="https://github.com/ann9cht">ann9cht</a><br />
      Vietnamese localization
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2FKobeW50.png&h=36&w=36&fit=cover&mask=circle" alt="KobeW50 avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;">
      <a href="https://github.com/KobeW50">KobeW50</a><br />
      Proofreading strings & wording
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com/BlackGold8282.png&h=36&w=36&fit=cover&mask=circle" alt="BlackGold8282 avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;">
      <a href="https://github.com/BlackGold8282">BlackGold8282</a><br />
      Korean localization
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2FYuzuMikan404.png&h=36&w=36&fit=cover&mask=circle" alt="YuzuMikan404 avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;">
      <a href="https://github.com/YuzuMikan404">YuzuMikan404</a><br />
      Japanese localization
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2Fvippium.png&h=36&w=36&fit=cover&mask=circle" alt="vippium avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;">
      <a href="https://github.com/vippium">vippium</a><br />
      Monochrome icon improvements
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2FVertuhai.png&h=36&w=36&fit=cover&mask=circle" alt="Vertuhai avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;">
      <a href="https://github.com/Vertuhai">Vertuhai</a><br />
      Russian and Ukrainian localization
    </td>
  </tr>
</table>

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Jman-Github/Universal-ReVanced-Manager&type=date&legend=top-left)](https://www.star-history.com/#Jman-Github/Universal-ReVanced-Manager&type=date&legend=top-left)

## ⚖️ License

Universal ReVanced Manager is licensed under the GPLv3 license. Please see the [license file](https://github.com/Jman-Github/universal-revanced-manager/blob/main/LICENSE) for more information.
[tl;dr](https://www.tldrlegal.com/license/gnu-general-public-license-v3-gpl-3) you may copy, distribute and modify Universal ReVanced Manager as long as you track changes/dates in source files.
Any modifications to Universal ReVanced Manager must also be made available under the GPL, along with build & install instructions.