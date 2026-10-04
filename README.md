<p align="center">
  <picture>
    <source
      width="256px"
      media="(prefers-color-scheme: dark)"
      srcset="assets/icons/icon-circle.svg"
    >
    <img
      width="256px"
      src="assets/icons/icon-circle.svg"
      alt="Universal ReVanced Manager icon"
    />
  </picture>
</p>

# 💊 Universal ReVanced Manager

Universal ReVanced Manager (URV) is an Android manager for patching apps with ReVanced and Morphe in one place. Built from ReVanced Manager, URV expands the full patching workflow with third-party patch bundles, multiple patcher runtimes, saved apps and patch profiles, batch and automatic repatching, flexible APK sources, advanced installers, and standalone APK utilities.

URV supports both ReVanced and Morphe patch ecosystems, rooted and non-rooted workflows, local and remote patch bundle sources, split APK handling, downloader integrations, signature and keystore tools, detailed patching diagnostics, backups, storage controls, and extensive UI customization.

<p align="center">
  <img src="https://img.shields.io/badge/License-GPL%20v3-yellow.svg" alt="GPLv3 License" />
  &nbsp;
  <a href="https://crowdin.com/project/universal-revanced-manager" style="text-decoration:none;"><img src="https://badges.crowdin.net/universal-revanced-manager/localized.svg" alt="Crowdin" /></a>
  &nbsp;
  <a href="https://crowdin.com/project/universal-revanced-manager" style="text-decoration:none;"><img src="https://img.shields.io/badge/Crowdin-Join-2E3340?logo=crowdin&logoColor=white" alt="Crowdin" /></a>
  &nbsp;
  <a href="https://t.me/urv_chat" style="text-decoration:none;"><img src="https://img.shields.io/badge/Telegram-Chat-2CA5E0?logo=telegram&logoColor=white" alt="Telegram" /></a>
  &nbsp;
  <a href="https://discord.gg/xZAqRHSp3V" style="text-decoration:none;"><img src="https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

## 💪 Features

URV covers the full patching lifecycle, from choosing an APK source and patch bundle to patching, installing, saving, repatching, automating updates, and managing the files and tools around those workflows.

<details>
<summary><strong>Patch Bundles & Patcher Runtimes</strong></summary>
<ul>
  <li><strong>ReVanced and Morphe in one manager:</strong> Use ReVanced <code>.rvp</code> and Morphe <code>.mpp</code> patch bundles from the same app.</li>
  <li><strong>Third-party patch bundles:</strong> Add compatible local or remote patch bundles, including repository and release-based sources, instead of being limited to the pre-installed bundle.</li>
  <li><strong>Bundle discovery:</strong> Browse the external patch bundle catalog, search it, inspect supported apps and patches, and import bundles directly.</li>
  <li><strong>Remote updates:</strong> Check, download, and background-update remote bundles with progress, stable/pre-release channel handling, per-bundle update controls, and force-redownload support.</li>
  <li><strong>Full changelog history:</strong> View cached patch bundle changelog history with configurable loading and storage limits, plus release links when available.</li>
  <li><strong>Bundle organization:</strong> Rename imported bundles, reorder them, preserve metadata and timestamps, and export/import bundle configurations.</li>
  <li><strong>Official bundle recovery:</strong> Remove the pre-installed Official ReVanced bundle and restore it later from Advanced settings.</li>
  <li><strong>Built-in runtimes:</strong> Current ReVanced and Morphe patching runtimes are bundled with URV.</li>
  <li><strong>ReVanced v21 runtime plugin:</strong> Add the supported ReVanced v21 runtime plugin from a local APK or GitHub source and manage its trust, update channel, installed version, and stored plugin files.</li>
</ul>
</details>

<details>
<summary><strong>Patch Selection, Profiles & Patching</strong></summary>
<ul>
  <li><strong>Flexible patch selection:</strong> Select/deselect all patches globally or per bundle, undo/redo changes, filter new patches, inspect option values, and switch between bundle recommendations and other supported versions.</li>
  <li><strong>Patch confirmation:</strong> Review selected bundles, patches, options, and relevant configuration before patching.</li>
  <li><strong>Patch profiles:</strong> Save patch selections, option values, target versions, a reusable APK/split source, installer choice, signature workflow, auto-install behavior, and one-tap patching preferences.</li>
  <li><strong>Single and batch patching:</strong> Patch one app or build a multi-app queue with per-app configuration, reordering, cancellation, detailed progress/results, and install-all actions.</li>
  <li><strong>Automatic repatching:</strong> Periodically repatch opted-in saved apps when their patch bundles change, with configurable intervals, charging requirements, notifications, and optional Shizuku/Shevery installation.</li>
  <li><strong>Repatch source retention:</strong> Keep the original APK or split archive used for a saved app so future manual, batch, and automatic repatches can reuse it without asking for the source again.</li>
  <li><strong>Split APK preparation:</strong> Patch <code>.apkm</code>, <code>.apks</code>, <code>.xapk</code>, and compatible split archives with selectable modules and cleanup filters for unused languages, densities, and native libraries.</li>
  <li><strong>Install-target-aware patches:</strong> Morphe patch availability can adapt to Standard or Root Mount patching, including required/unavailable patches and optional GmsCore handling.</li>
  <li><strong>Signature metadata workflow:</strong> Optionally clone signature metadata from the original signed input into the final patched APK and remember that choice for saved apps, profiles, batch jobs, and automatic repatching.</li>
  <li><strong>Signing control:</strong> Use the manager keystore, import compatible keystores, or skip patched APK signing where supported.</li>
  <li><strong>Detailed patcher diagnostics:</strong> View elapsed time, app/runtime/device information, selected patches, split/native-library state, and expandable patcher steps and sub-steps.</li>
  <li><strong>Live resource monitoring:</strong> Optional memory, CPU, and storage I/O graphs are available during patching, with compact layouts and peak/current readings.</li>
  <li><strong>Patcher logs:</strong> Export detailed logs with app, device, runtime, selected patch, split, and failure information.</li>
</ul>
</details>

<details>
<summary><strong>APK Sources, Downloaders & Saved Apps</strong></summary>
<ul>
  <li><strong>Multiple APK sources:</strong> Start from installed apps, local storage, manager-downloaded APKs, downloader plugins, supported helper apps, or split archives depending on the workflow.</li>
  <li><strong>Downloader plugins:</strong> Install, update, configure, trust/revoke, and remove compatible downloader plugins directly in URV.</li>
  <li><strong>APK download helper apps:</strong> Use supported standalone helper apps as APK sources, with helper discovery, signer trust, GitHub source management, and install/update controls.</li>
  <li><strong>Download cache management:</strong> Keep multiple downloaded APKs, export or delete individual entries, and optionally retain only the newest download for each app.</li>
  <li><strong>Saved patched apps:</strong> Keep patched APKs in the Apps tab for later install/export/repatch workflows and inspect the patches, bundles, and related metadata used to create them.</li>
  <li><strong>Saved app organization:</strong> Search and reorder saved apps, manage update badges, and control whether patched apps are automatically saved.</li>
</ul>
</details>

<details>
<summary><strong>Installation & Root Workflows</strong></summary>
<ul>
  <li><strong>Central installer manager:</strong> Configure primary and fallback installers and choose installers per install or patching result, saved app, patch profile, or tool workflow.</li>
  <li><strong>Multiple install methods:</strong> Use the Android system installer, Shizuku/Shevery/Sui, supported custom installers, rooted installation, or Root Mount where compatible.</li>
  <li><strong>Google Play attribution:</strong> Supported system, Shizuku/Shevery, and rooted install modes can record Google Play Store as the installation source.</li>
  <li><strong>Root Mount:</strong> Mount compatible patched APKs over installed stock apps with stock-source checks, split-input compatibility, post-patch routing, and install-target-aware patch selection.</li>
  <li><strong>Root recovery:</strong> Track mounted apps across reboots, repair broken mounts, recover stock sources where possible, and export diagnostics when a mount needs attention.</li>
  <li><strong>Installation controls:</strong> Cancel in-progress installs, handle signature conflicts with clearer recovery options, and automatically install successful patch results when configured.</li>
</ul>
</details>

<details>
<summary><strong>Tools & System Utilities</strong></summary>
<ul>
  <li><strong>Split APK merger:</strong> Merge <code>.xapk</code>, <code>.apkm</code>, <code>.apks</code>, and compatible ZIP archives into a single APK with selectable split filters, signing, install/save actions, progress sub-steps, and live resource monitoring.</li>
  <li><strong>Split installer:</strong> Install compatible split packages directly without first merging them into a single APK.</li>
  <li><strong>APK signer:</strong> Sign APKs with the manager's signing configuration from a standalone tool.</li>
  <li><strong>Signature metadata injector &amp; cloner:</strong> Inspect sources and clone/inject legacy signature metadata into APK or split targets with selectable signing/output behavior.</li>
  <li><strong>Keystore creator:</strong> Generate new signing keystores in supported formats with configurable credentials and long-lived certificates.</li>
  <li><strong>Keystore converter:</strong> Convert supported keystore formats directly in the app.</li>
  <li><strong>Custom YouTube assets:</strong> Build custom adaptive icons and header assets with positioning, color, preset, and export controls.</li>
  <li><strong>LSPosed module management:</strong> Optional rooted tab for adding, updating, installing, and organizing user-added LSPosed-compatible modules from GitHub or local storage.</li>
</ul>
</details>

<details>
<summary><strong>Storage, Backups & File Management</strong></summary>
<ul>
  <li><strong>Storage dashboard:</strong> Inspect URV storage usage across caches, patch bundles, runtime/downloader plugin files, saved APKs, repatch inputs, patch-profile inputs, signing files, LSPosed modules, backgrounds, and other app data.</li>
  <li><strong>Targeted cleanup:</strong> Clear individual storage areas, clear recreatable caches, or configure scheduled automatic cache cleanup.</li>
  <li><strong>Settings and data export/import:</strong> Back up and restore manager settings and supported URV configuration data, including patch-related preferences and organization state.</li>
  <li><strong>Signing-file handling:</strong> Import/export compatible keystores while keeping signing material out of Android cloud backups.</li>
  <li><strong>Custom file picker:</strong> Favorite paths, remember folders per workflow, persist sorting/search preferences, show hidden files, and use Android document providers where supported.</li>
</ul>
</details>

<details>
<summary><strong>Appearance, Navigation & Search</strong></summary>
<ul>
  <li><strong>Theme customization:</strong> Choose theme/accent colors with presets, HEX input, and live previews, plus monochrome and pure-black options where supported.</li>
  <li><strong>Custom backgrounds:</strong> Import a background image, adjust its transparency, and keep a private app copy so it remains available if the original moves.</li>
  <li><strong>Navigation controls:</strong> Configure tab visibility/labels, swipe navigation behavior, action layouts, and other interaction preferences.</li>
  <li><strong>Search throughout URV:</strong> Search settings with jump-to highlighting and search the Apps, Patch Bundles, Patch Profiles, discovery, patch selection, and file-picker workflows.</li>
  <li><strong>Patch-selection layouts:</strong> Use standard or more compact/minimal patch selection views and keep relevant filters/presets across sessions.</li>
</ul>
</details>

<details>
<summary><strong>Updates, Notifications & Advanced Controls</strong></summary>
<ul>
  <li><strong>Manager updates:</strong> Check for new URV releases, preview changelogs in-app, and receive update notifications.</li>
  <li><strong>Patch bundle notifications:</strong> Get grouped update alerts and background download progress for bundle updates, including manual and automatic delivery modes.</li>
  <li><strong>Metered-network controls:</strong> Decide whether manager and patch bundle update checks/downloads may run on metered connections.</li>
  <li><strong>Developer options:</strong> Advanced controls remain directly accessible for patcher behavior, process/runtime settings, logging, memory handling, notification behavior, and diagnostics.</li>
  <li><strong>External automation:</strong> When external batch actions are enabled, automation apps can request batch patching, while launcher shortcuts can trigger update/repatch workflows.</li>
</ul>
</details>

<details>
<summary><strong>Localization</strong></summary>
<ul>
  <li><strong>Device language support:</strong> Follow the device language when a supported translation is available, with English fallback behavior.</li>
  <li><strong>Selectable app languages:</strong> English, German, Spanish, French, Chinese (Simplified), Indonesian, Hindi, Gujarati, Portuguese (Brazil), Vietnamese, Korean, Japanese, Russian, Turkish, and Ukrainian.</li>
  <li><strong>In-app switching:</strong> Change languages from Settings with a restart prompt when needed.</li>
</ul>
</details>

## 🔽 Download

You can download the most recent version of Universal ReVanced Manager from [GitHub releases](https://github.com/Jman-Github/universal-revanced-manager/releases).

## 📋 Patch Bundles

URV can add compatible ReVanced (<code>.rvp</code>) and Morphe (<code>.mpp</code>) patch bundles from local files, supported remote URLs, repository/release sources, and the in-app discovery catalog.

For a maintained third-party bundle catalog, use the [ReVanced Patch Bundles](https://github.com/Jman-Github/ReVanced-Patch-Bundles) repository. It includes a detailed [patch catalog](https://github.com/Jman-Github/ReVanced-Patch-Bundles/blob/bundles/patch-bundles/PATCH-LIST-CATALOG.md) and [bundle URLs](https://github.com/Jman-Github/ReVanced-Patch-Bundles#-patch-bundles-urls) that can be imported into URV. For bundles from the discovery/catalog flow, use entries marked <strong>API v4</strong> or <strong>Morphe</strong>; API v3 discovery entries are unsupported.

## 🔌 Supported Downloader Plugins

URV supports compatible downloader plugins and standalone APK download helper apps as reusable APK sources. They can be installed or imported from supported GitHub repository, release, or APK asset URLs, managed from Downloader settings, and used directly in patching and tool workflows.

The [ReVanced Manager Downloaders](https://github.com/brosssh/revanced-manager-downloaders/releases) repository provides compatible downloader plugins for sources such as APKMirror, APKPure, and APKCombo. URV also supports standalone APK download helper apps, such as [Helper For Morphe](https://github.com/rushiranpise/helper-for-morphe/releases).

## ⭐ Star History

<a href="https://star-history.dera.page/#Jman-Github/Universal-ReVanced-Manager">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=Jman-Github/Universal-ReVanced-Manager&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=Jman-Github/Universal-ReVanced-Manager" />
   <img alt="Star History Chart" src="https://star-history.dera.page/svg?repos=Jman-Github/Universal-ReVanced-Manager" />
 </picture>
</a>

## ⚖️ License

Universal ReVanced Manager is licensed under the GPLv3 license. Please see the [license file](https://github.com/Jman-Github/universal-revanced-manager/blob/main/LICENSE) for more information.
[tl;dr](https://www.tldrlegal.com/license/gnu-general-public-license-v3-gpl-3) you may copy, distribute and modify Universal ReVanced Manager as long as you track changes/dates in source files.
Any modifications to Universal ReVanced Manager must also be made available under the GPL, along with build & install instructions.
