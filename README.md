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
  </picture>
</p>

# 💊 Universal ReVanced Manager

Application for using ReVanced on Android.

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

## 💪 Unique Features

Universal ReVanced Manager includes powerful features that the official ReVanced Manager does not:

<details>
<summary><strong>Patch Bundles & Customization</strong></summary>
<ul>
  <li><strong>Third-party patch support:</strong> Import any third-party API v4 patch bundle you want (including popular ones like inotia00's or anddea's), which the official ReVanced Manager does not support.</li>
  <li><strong>Custom bundle names:</strong> Set a custom display name for any imported patch bundle so you can tell them apart at a glance.</li>
  <li><strong>Smarter patch selection:</strong>
    <ul>
      <li>Global deselect all button</li>
      <li>Per-bundle deselect all button</li>
      <li>Per-bundle select all button</li>
      <li>Global select all button</li>
      <li>Patch profiles button to save patch selections and option states per app</li>
      <li>Patch profiles can store a persistent APK path for one-tap patching</li>
      <li>Patch confirmation screen showing selected bundles, patches, and sub-options</li>
      <li>Export all patch selections at once</li>
      <li>Latest patch bundle changelogs shown in bundle info</li>
      <li>Undo and redo buttons</li>
    </ul>
  </li>
  <li><strong>Bundle recommendation picker:</strong> Choose per-bundle suggested versions or override with any other supported version.</li>
  <li><strong>Suggestion toggle on Select-App:</strong> Bundle suggestions are grouped behind a toggle with inline dialogs to view additional supported versions.</li>
  <li><strong>Official bundle management:</strong> Delete the Official ReVanced patch bundle from the Patch Bundles tab and restore it from Advanced settings.</li>
  <li><strong>Export filename templates:</strong> Configure a filename template for exported patched APKs with placeholders for app and patch metadata.</li>
  <li><strong>Release link button:</strong> GitHub button on each bundle's info page opens the bundle repository's releases.</li>
  <li><strong>Bundle timestamps:</strong> Cards show Created and Updated times; exports and imports preserve these timestamps.</li>
  <li><strong>Organize bundles:</strong> "Organize" button to manually reorder bundles; exports and imports keep the custom order.</li>
  <li><strong>Force bundle redownload:</strong> Long-press the update check button on a bundle to force a full redownload.</li>
  <li><strong>Bundle discovery:</strong> Browse a patch bundle catalog and import external bundles directly from the app.</li>
  <li><strong>Improved UI:</strong> Settings, the Patch Bundles tab, the Apps tab, the app selection page, and the patch selection page all have an improved UI design.</li>
</ul>
</details>

<details>
<summary><strong>App Patching Flow</strong></summary>
<ul>
  <li><strong>Morphe patch bundles support:</strong> Supports the <a href="https://github.com/MorpheApp/morphe-patcher">Morphe Patcher</a> without needing a computer or another app.</li>
  <li><strong>Downloaded app source:</strong> Added a "Downloaded apps" source in the select source screen when patching. If the manager has cached an APK from a downloader plugin, you can pick it directly from there. This option only appears when that app is available.</li>
  <li><strong>Split APK support:</strong> .apkm, .apks, and .xapk file formats are automatically converted to the .apk format when patching. No need for outside tools.</li>
  <li><strong>Split merge sub-steps:</strong> Expandable sub-steps for the "Merging split APKs" step, plus sub-steps for "Writing patched APK".</li>
  <li><strong>Skip unused split modules:</strong> Optional Advanced setting that skips unnecessary split modules (like locale and density splits) when patching split APKs.</li>
  <li><strong>Advanced native library stripping:</strong> Optional advanced setting to strip unused native libraries (unsupported ABIs) from patched APKs during patching, helping reduce size.</li>
  <li><strong>Keystore support:</strong> Import and use JKS and PKCS12 keystores for signing patched APKs.</li>
  <li><strong>Patcher logs export:</strong> Export patcher logs from the patcher screen as a .txt file.</li>
  <li><strong>Export = auto-save:</strong> When you export a patched app to storage from the patching screen, the manager will now also automatically save that patched app under the "Apps" tab. Before, this only happened if you installed the patched app directly from that screen.</li>
  <li><strong>Installer management:</strong> A full installer management system with installer metadata, and configurable primary and fallback that applies everywhere across the app.</li>
  <li><strong>View applied patches:</strong> The "Apps" tab shows the applied patches for each saved patched APK and which patch bundle(s) were used.</li>
  <li><strong>Organize apps & profiles:</strong> Reorder saved patched apps in the Apps tab and patch profiles in the Patch Profiles tab.</li>
  <li><strong>Accidental exit protection:</strong> After patching, pressing the back button now shows a confirmation popup. It asks if you really want to leave and gives you the option to save the patched app for later (adds it to the "Apps" tab).</li>
  <li><strong>Missing patch recovery:</strong> If a selected patch no longer exists, a detailed dialog explains the issue and returns you to patch selection with missing patches highlighted.</li>
  <li><strong>Step auto-collapse:</strong> Completed patcher steps auto-collapse; toggle in Settings &gt; Advanced &gt; "Auto-collapse completed patcher steps".</li>
  <li><strong>Saved apps toggle:</strong> Option to disable saving patched apps and hide saved app delete actions.</li>
  <li><strong>Version tags:</strong> On the patch selection and app selection pages, each app or patch displays the versions it supports. Tapping a version chip opens a web search for that specific app and version.</li>
</ul>
</details>

<details>
<summary><strong>Patch Bundle Updates & Imports</strong></summary>
<ul>
  <li><strong>Progress with percentages:</strong> Progress bars with percentage for bundle updates, update checks, and imports.</li>
  <li><strong>Background bundle updates:</strong> Auto-download bundles in the background with a single progress notification, plus update-available alerts for bundles set to manual updates.</li>
  <li><strong>Installer management:</strong> Full installer management system covering app installs, saved app reinstalls, and manager updates.
    <ul>
      <li>Metadata display for each installer</li>
      <li>Configurable primary and fallback installers</li>
      <li>Shizuku installer option for silent installs when Shizuku or Sui is available</li>
      <li>Advanced settings support saving custom installer packages with package-name lookup and autocomplete, plus dedicated management for third-party installers</li>
      <li>App mounting support for rooted users (rooted mount installer)</li>
    </ul>
  </li>
</ul>
</details>

<details>
<summary><strong>Downloader & Storage Management</strong></summary>
<ul>
  <li><strong>Cached downloads management:</strong> The manager can now keep multiple downloaded apps (from downloader plugins) inside the downloader settings. You can also export any of these APKs to your device's storage whenever you want.</li>
  <li><strong>Plugin cleanup:</strong> You can uninstall downloader plugins directly from inside the manager via the download settings page. No manual cleanup needed.</li>
  <li><strong>File picker favorites:</strong> Favorite files or folders in the custom file picker for quick access.</li>
</ul>
</details>

<details>
<summary><strong>Appearance & Theming</strong></summary>
<ul>
  <li><strong>Enhanced theming:</strong> Appearance settings include an accent color picker, theme color picker, color HEX code support, presets, and a live preview widget so you can choose a custom theme color and customize the app to your liking.</li>
  <li><strong>Monochrome app icons:</strong> Support for Android monochrome icons.</li>
  <li><strong>Better long names:</strong> Long labels use horizontal swipe instead of auto-sliding or wrapping.</li>
</ul>
</details>

<details>
<summary><strong>Network & Updates</strong></summary>
<ul>
  <li><strong>Metered connection control:</strong> Toggle to allow updates on metered connections for both patch bundles and the manager itself, so you are not blocked on mobile data.</li>
</ul>
</details>

<details>
<summary><strong>Developer & Power Features</strong></summary>
<ul>
  <li><strong>Always-visible developer options:</strong> Developer options are always available in Settings by default. No hidden or secret unlock flow.</li>
  <li><strong>Disable battery optimization banner:</strong> Developer option to hide the battery optimization warning banner.</li>
  <li><strong>Robust import and export:</strong> Export and import your patch bundles, your patch profiles, and your app settings to and from JSON files for easy backup, sharing, or migration between devices.</li>
</ul>
</details>

<details>
<summary><strong>Settings & Navigation</strong></summary>
<ul>
  <li><strong>Settings search:</strong> Search across settings categories with jump-to highlighting.</li>
  <li><strong>Tab search:</strong> Apps, Patch Bundles, and Patch Profiles tabs are searchable via a nav bar search button.</li>
</ul>
</details>

<details>
<summary><strong>Localization</strong></summary>
<ul>
  <li><strong>Chinese Simplified (zh-CN):</strong> User-selectable language option in settings.</li>
  <li><strong>Vietnamese (vi):</strong> User-selectable language option in settings.</li>
  <li><strong>Korean (ko):</strong> User-selectable language option in settings.</li>
  <li><strong>Japanese (ja):</strong> User-selectable language option in settings.</li>
  <li><strong>Russian (ru):</strong> User-selectable language option in settings.</li>
  <li><strong>Ukrainian (uk):</strong> User-selectable language option in settings.</li>
  <li><strong>Brazilian Portuguese (pt-BR):</strong> User-selectable language option in settings.</li>
  <li><strong>Indonesian (id):</strong> User-selectable language option in settings.</li>
  <li><strong>Fillipino (fil):</strong> User-selectable language option in settings.</li>
</ul>
</details>

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
    <td style="vertical-align:middle;padding-right:24px;">
      <a href="https://github.com/brosssh">brosssh</a><br />
      Multiple PRs, top contributor
    </td>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2FTanakaLun.png&h=36&w=36&fit=cover&mask=circle" alt="TanakaLun avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:0;">
      <a href="https://github.com/TanakaLun">TanakaLun</a><br />
      Chinese localization
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2Fann9cht.png&h=36&w=36&fit=cover&mask=circle" alt="ann9cht avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:24px;">
      <a href="https://github.com/ann9cht">ann9cht</a><br />
      Vietnamese localization
    </td>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2FKobeW50.png&h=36&w=36&fit=cover&mask=circle" alt="KobeW50 avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:0;">
      <a href="https://github.com/KobeW50">KobeW50</a><br />
      Proofreading strings & wording
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com/BlackGold8282.png&h=36&w=36&fit=cover&mask=circle" alt="BlackGold8282 avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:24px;">
      <a href="https://github.com/BlackGold8282">BlackGold8282</a><br />
      Korean localization
    </td>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2FYuzuMikan404.png&h=36&w=36&fit=cover&mask=circle" alt="YuzuMikan404 avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:0;">
      <a href="https://github.com/YuzuMikan404">YuzuMikan404</a><br />
      Japanese localization
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2Fvippium.png&h=36&w=36&fit=cover&mask=circle" alt="vippium avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:24px;">
      <a href="https://github.com/vippium">vippium</a><br />
      Monochrome icon improvements
    </td>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2FVertuhai.png&h=36&w=36&fit=cover&mask=circle" alt="Vertuhai avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:0;">
      <a href="https://github.com/Vertuhai">Vertuhai</a><br />
      Russian and Ukrainian localization
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=github.com%2Fr7reiz.png&h=36&w=36&fit=cover&mask=circle" alt="r7reiz avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:24px;">
      <a href="https://github.com/r7reiz">r7reiz</a><br />
      Brazilian Portuguese localization
    </td>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=crowdin-static.cf-downloads.crowdin.com/avatar/13430318/large/73204ec2b41cc713d81dc88824059dea.jpg&h=36&w=36&fit=cover&mask=circle" alt="naokoshoto avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:0;">
      <a href="https://crowdin.com/profile/naokoshoto">naokoshoto</a><br />
      Indonesian localization
    </td>
  </tr>
  <tr>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=crowdin-static.cf-downloads.crowdin.com/avatar/16366724/large/e0afadb935d2fa9b972ca1697169c806.png&h=36&w=36&fit=cover&mask=circle" alt="mesazane avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:24px;">
      <a href="https://crowdin.com/profile/mesazane">Mesazane</a><br />
      Indonesian localization
    </td>
    <td style="vertical-align:middle;padding-right:8px;">
      <img src="https://images.weserv.nl/?url=crowdin-static.cf-downloads.crowdin.com/avatar/17524508/large/b5f2c910b17aae07cdfc69bcd973fe31.png&h=36&w=36&fit=cover&mask=circle" alt="fielph avatar" width="36" height="36" />
    </td>
    <td style="vertical-align:middle;padding-right:0;">
      <a href="https://crowdin.com/profile/fielph">fielph</a><br />
      Fillipino localization
    </td>
  </tr>
</table>

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Jman-Github/Universal-ReVanced-Manager&type=date&legend=top-left)](https://www.star-history.com/#Jman-Github/Universal-ReVanced-Manager&type=date&legend=top-left)

## ⚖️ License

Universal ReVanced Manager is licensed under the GPLv3 license. Please see the [license file](https://github.com/Jman-Github/universal-revanced-manager/blob/main/LICENSE) for more information.
[tl;dr](https://www.tldrlegal.com/license/gnu-general-public-license-v3-gpl-3) you may copy, distribute and modify Universal ReVanced Manager as long as you track changes/dates in source files.
Any modifications to Universal ReVanced Manager must also be made available under the GPL, along with build & install instructions.
