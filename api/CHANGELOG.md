# v1.8.0 (TBD)


# Features

- Redesigned and improved patch bundles widgets UI, moved the progress banner and improved tab switcher UI https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/135
- Redesigned and improved patch profiles widgets UI along with adding an app icon to patch profiles that have an APK selected for instant patching https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/135
- Added `Patch bundle action button order` setting in Settings > Advanced that lets the user disable and rearrange the action buttons on the patch bundles widget https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/135
- Added a backup and restore system for keystores to mitigate any future missing keystore errors
- Added a dialog that appears after missing keystore errors to give clarity to the user on what to do next
- Added an information section/dignonstic panel for keystores which lists the keystore alias and password
- Gave keystores its own section in Settings > `Import & Export` and moved relevant settings to that section
- Added a `Effective memory` pill under the experimental patcher toggle to clarify to the user the max memory the app can use
- Added more information to the patcher log such as bundle type, and whether the experimental patcher is toggled off or on
- Added a `Latest` filter and option in the three dot menu to the `Patch bundle discovery`
- Updated the split-apk merger to use APKEditor instead of ARSCLib
- Improved split-apk merger validation, normalization and cleanup
- Made the two FAB buttons on the `Patch bundles` tab collapsible/expandable https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/146
- Increased the pill text box size of the tab titles so devices with smaller screens won't have the text cut off https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/146
- Updated the patch profile widget to use the same button type as the patch bundle widgets https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/146
- Centered patch profile & patch bundle widget action buttons https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/146
- Centered the patch action button menu and expanded the search bar properly on the patch selection screen for devices with larger screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/148
- Added the ability to export saved patched apps to storage
- Added `Saved` dates to saved patched apps in the `Apps` tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/145
- Added a refresh/reload button to the custom file picker
- Improved the UI of export and saving dialogs for the custom file picker
- Updated the view patches screen for patch bundles on the `Discover patch bundles` page to use the same UI as the view patches screen for imported patch bundles
- Made version tags on patches on all view patch screens searchable with the user set search engine
- Added patch options/sub-options to the view patches screen on the `Discover patch bundles` page. This is currently only implemented for patch bundles imported from the discovery page as the API dose not currently support patch option fetching for non-imported bundles
- Make all view patch screens searchable by patch name and description
- Added a `Latest changelog` and `Previous changelogs` action buttons to the patch bundle widget with options to hide and rearrange them in the corresponding setting
- Improved the `Apps` tab saved patched app UI to follow the style of the other tabs
- Made all action buttons for saved patched apps quick action buttons on their widgets along with a setting to hide and rearrange said buttons
- Added support for [AmpleReVanced Patches](https://github.com/AmpleReVanced/revanced-patches) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/152
- Bumped ReVanced dependencies
- Bumped Morphe dependencies
- Added a bundle type field to the patch bundle information screen
- Made the FAB buttons on the `Apps` tab collapsible/expandable https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/155
- Improved and cleaned up the patcher log
- Added a popout animation when switching tabs on the main screen
- Implemented XML surrogate sanitization to all runtimes
- Added the ability to export all settings (not including the keystore) to a single JSON along with an option to import it https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/165
- Adjusted the arrow FAB button on the `Apps` and `Patch bundles` tabs to be up against the right edge, removing the awkward gap https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/164
- Added guards to the patcher logger to prevent massive patch log exports
- Made the expandable/collapsable FAB buttons on the `Apps` and `Patch Bundles` tabs states persist
- Made saved patched app entries in the `Apps` tab not overwrite each other unless the app has the same package name and was patched with the same patch bundle
- Added the ability to set a image of choice as the app background
- Added `Always create new saved app entry` toggle in Settings > Advanced that toggles saved patch app entries from being overwritten
- Added `Hide main tab labels` toggle in Settings > General that toggles the labels under the tab icons on the main screen
- Added to the app information screen shown after selecting an app or APK to patch a listing displaying the apps package name
- Made the `View patches` screen for patch bundles and the patch bundle discovery have tap to search package tags
- Made `Any package` tags not searchable for the `View patches` screens patch widgets (and also the `Any version` tag when the `Any package` tag exists with it)
- Added an update notice tag to saved patched apps when the imported patch bundle version is newer than the one used to patch the app https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/163
- Updated Patch Bundle Discovery to use the new `api/v2` & `latest?channel=` URLs while keeping backwards compatibility with `api/v1` URLs
- Added a draggable transparency adjustment bar to Settings > General for when a image is set as the background
- Made the state of the progress banner persist
- Made the collapsed version of the progress banner show a minimal view of progress https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/162
- Added bundle-aware APK version assessment that finds the best matching bundle/version for storage-selected APKs
- Added a universal fallback confirmation dialog (`Use universal patches?`) when only universal patches are compatible
- Added a specific blocked-state message when only universal patches match but universal patches are disabled
- Expanded safeguard dialog support to allow confirm/cancel actions
- Added a `Use custom file picker` toggle in Settings > Advanced that when toggled off, disables the custom file picker and uses the built in android file picker (documents provider)
- Added a `Tools` tab
- Added a `Merge split APKs` tool in the `Tools` tab that just merges the selected split APK and allows the user to save it to storage after https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/171
- Added a `Create custom YouTube icons & headers` tool to the `Tools` tab (inspired by [Morphe Managers implementation](https://github.com/MorpheApp/morphe-manager/pull/138))


# Bug fixes

- Resolved redundancies within the `service.sh` script improved module regeneration https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/134
- Mitigated issues with having to regenerate keystores & persistent errors with signing (even after regenerate the keystore) for some users
- Fixed an issue where the experimental patcher was always on internally when patching with Morphe, and couldn't be turned off
- Fixed alignment of accent presets in `Settings > General`
- Fixed patch options/suboptions dialogs flickering in certain states
- Fixed the `Patch bunblde discovery` screen incorrectly displaying the shimmer effect on the loading elements
- Fixed `Keystore diagnostics` not being able to be searched through settings search bar
- Fixed missing shimmer element when tapping refresh for the `Keystore diagnostics` panel
- Fixed incorrect version listings on the patch selection screens patch widgets
- Fixed the miscolored status bar on patch bundle information screens
- Fixed issues with unicode characters causing resource compilation errors for certain apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/144
- Fixed the ReVanced patcher runtime using the incorrect Aapt2 binary occasionally
- Fixed `brut.androlib.exceptions.CantFindFrameworkResException` patching errors
- Fixed issues with keystores from older versions of URV not being able to be imported into the newer versions of URV without signing errors https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/147
- Fixed false OOM errors with patching on lower end devices https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/142
- Possible fix for false OOM errors https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/142
- Fixed issues with URV generated keystores from previous versions of the app not being imported correctly resulting in signing errors (again) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/147
- Fixed issues with the split-apk merger where some apps would crash after being patched
- Fixed bundle recommendations not being available for split-apks https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/151
- Fixed `Skip unneeded split APKs` toggle breaking some patched apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/153
- Fixed patch options not saving correctly for split APKs
- Fixed issues with action buttons on the saved patched apps widget not responding to taps and the delete button not being functional sometimes https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/155
- Fixed issues with the saved patch apps widget `Open` button
- Fixed local patch bundles not having a tag on the top right like remote patch bundles have
- Fixed issues with Morphe Manager generated keystores not working https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/158
- Fixed issues with the `Use default recommendation` toggle in the `Choose bundle recommendation` dialog not working correctly
- Fixed AAPT2 failures on newer resource qualifiers/types
- Fixed numerous patching errors caused by the ReVanced dependency bump by downgrading
- Fixed the `Official ReVanced Patches` bundle having the `Remote` tag on its widget instead of the `Pre-installed` tag
- Fixes patching errors caused by missing framework APKs
- Fixed mounting errors that where occuring for some users
- Fixed mount buttons on the saved patched app widget not being in the correct state
- Fixed Patch Bundle Discovery `Latest` imports getting stuck to release/pre-release and not actually the latest https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/169
- Fixed issues with the progress bar during update checks getting stuck indefinitely when a imported patch bundle is errored/not correctly imported
- Fixed mounting errors that where occuring for some users (again) (needs testing) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/166
- Fixed latest bundles so they correctly resolve the true latest version
- Fixed Allow changing patch selection and options behavior:
  - When OFF: app-list and storage APK selections always use default selection (ignore saved custom selections)
  - When ON again: saved custom selections are restored automatically (if present)
- Fixed the app language selector dialog layout having an extra bottom spacing/clipping near the `Cancel` button https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/172
- Fixed issues with patch bundle importing and loading https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/138


# v1.7.1 (2026-01-22)


# ⚠️ BREAKING CHANGES

The `Discover patch bundles` screen has been updated to use [Brosssh's new API](https://github.com/brosssh/revanced-external-bundles/blob/dev/docs/graphql-examples.md). As a result, you will need to reimport any patch bundles that were added via the Discovery system prior to this release to continue receiving updates from their remote sources.

Additionally, due to a keystore system update, you may need to export and then re-import the Manager’s keystore to resolve a signing error during patching. This is a one-time step after installing version 1.7.1 for the first time.


# Features

- Removed the `Discover patch bundles` banner and added a FAB button next to the plus button instead to access the `Discover patch bundles` page
- Added support for Morphe Patches (mixing of ReVanced and Morphe Patches in a single patch instance is not feasible, and not currently supported)
- Improved patcher logging/profiling and error surfacing
- Improved metadata reading for split APKs on the app info page
- Improved metadata reading for regular APKs on the app info page
- Converted the `Save patched app` button, `Export` button on the `App info` screen for saved patched apps, and the `Export` button on the Download settings page to use the custom file picker
- Added a saving modal to the custom file picker
- Added a search bar in the custom file picker that filters the current directory
- Made the `Save patched apps for later` toggle in Settings > Advanced actually toggle the ability to save patched apps in the `Apps` tab
- Added expandable/collapsible sub-steps to the `Merging split APKs` step in the patcher, along with sub-steps for the `Writing patched APK` step
- Overall improved the patcher screen
- Added the ability to see previous changelogs within the app which are cached by the app every time your imported patch bundle updates https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/108
- Added a toggle in Settings > Advanced that when enabled skips all unused splits when patching with a split APK (like locale/density splits)
- Updated the `Remove unused native libraries` toggle in Settings > Advanced to strip all native libraries but one (so only keep one supported library if applicable)
- Added a per bundle patch selection counter
- Made the `View patches` button auto-scroll on the Discover patch bundles page
- Added the ability to export patcher logs from the patcher screen as a `.txt`
- Added a filter option on the patch selection page to filter by universal patches, and by regular (non universal) patches
- Added a toggle to use the `Pure Black` theme instead of the `Dark` theme for the `Follow system` theme https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/109
- Tapping patch bundle updating/updated notifications now highlights the corresponding bundle in the patch bundles tab
- Switched back to the official ReVanced Patcher and Library from Brosssh's Patcher and Library (as using theirs is no longer needed)
- The `Rooted mount installer` now auto-remounts at device startup https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/112
- Moved the progress banner so it hangs below the nav bar https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/117
- Stabilize patch bundle progress banners and make them clearer and more consistent
- Removed the redundant filter button from the `Select an app` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/121
- Added the ability to edit existing remote patch bundles URLs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/122
- Improved the `Rooted mount installer`'s auto remount handling
- Added the ability to reorder/organize the listing order of saved patched apps in the `Apps` tab and patch profiles in the `Patch profiles` tab
- Make the progress banner collapsible/expandable and gave it animations
- Made the `Apps`, `Patch Bundles` and `Patch Profiles` tabs items searchable via a button on the nav bar
- Redesigned the patch bundle widgets UI
- Hold tapping the individual update check button on patch bundles will give you a prompt to force redownload the corresponding patch bundle
- Removed redundant `Reset patch bundles` button in `Developer options`
- Moved the `Release`/`Prerelease` toggle button to a three dot menu popout for each patch bundle listing on the `Discover patch bundles` screen
- Added the ability to copy the remote URLs for patch bundles on the `Discover patch bundles` screen from a three dot button menu popout
- Added the ability to download patch bundles to your devices storage from the `Discover patch bundles` screen through the three dot buttons menu popout
- Added a way to search/filter through patch bundles on the `Discover patch bundles` screen by app package name https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/113
- Improved loading speeds significantly for the `Discover patch bundles` screen
- Added import progress to the `Discover patch bundles` screen along with an import queue system
- Made the fallback installer actually functional. If an install fails with the primary installer, the fallback installer is prompted
- Improved the `Discover patch bundles` screens searching/filtering
- Added the ability to set an APK path that persists to one tap patch with patch profiles
- Added a patch confirmation screen showing the user what patch bundles, patches, and sub options they have selected and enabled/disabled
- Added an option to export all patch selections at once
- Added support for `JKS` keystore types
- Added a `Last checked` badge to the `Discover patch bundles` screens patch bundle widgets
- Added support for PKCS12 keystore types
- Made the `Patch selection action buttons order` action buttons be listed vertically
- Added shimmers to several places in the UI
- Added the Gujarati, Hindi, Indonesian, and Brazilian Portuguese to the language selector


# Bug fixes

- Fixed dev builds not being prompted to update when there are new releases
- Fixed crashes that would occur occasionally for apps when loading metadata on the app info page
- Fixed false "Universal patches disabled" and "This patch profile contains universal patches. Enable universal patches..." toast/dialogs
- Fixed patcher steps under the `Patching` section not being checked off and left blank until after the entire step is `Patching` section is completed
- Fixed an issue where canceling the patching process by tapping the back button on the `Patcher` screen was not actually immediately canceling/killing the patching process as it would continue to run in the background for a bit
- Fixed the app crashing when certain patch option types are opened https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/103
- Fixed applied patches list for saved patched apps not showing all applied patches under certain circumstances https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/105
- Fixed bundle recommendation selection and compatibility issues https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/104
- Fixed issues with the custom file picker and the `Downloads` folder on certain devices
- Fixed app startup crashes and crashes with the custom file picker and other parts of the app on devices running older Android versions
- Fixed issues with patching on older Android versions
- Fixed update patch bundle notifications not always appearing
- Fixed patched apps being incorrectly patched resulting in startup crashes
- Fixed saved patched apps in the `Apps` tab and the restore button not restoring patch options correctly
- Increased stability of the `Rooted mount installer` by fixing issues such as `Exception thrown on remote process`
- Fixed false reimport toasts and adjusted official bundle restore logic with importing patch bundles from a patch bundles export
- Fixed false update prompts and incorrect update detection
- Fixed patch bundle ODEX cache invalidation and recovery
- Fixed issues with the auto-remount system for after restarts on some devices
- Fixed a crash when leaving the app during patching
- Fixed deep linking not always working with bundle update/updating notifications
- Fixed the `Saved patched apps for later` setting not actually disabling and deleting saved patched apps
- Fixed more issues with the `Saved patched apps for later` setting toggle & adjust its behavior
- Fixed null splitNames errors with the Rooted mount installer https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/124
- Fixed imported discovery patch bundle update checks not always detecting an update when it should be https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/125
- Fixed issues with version name checking with the `Rooted mount installer` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/126
- Fixed issues with keystores from before the dev.05 release not working unless regenerated
- Attempted to fix missing resources/AAPT2 errors
- Fixed UI issues on the patch selection screen for Android 8.1 and lower devices
- Fixed the `Continue` and `Cancel` buttons on the `Patch confirmation` screen being covered by the system navigation buttons
- Possibly fixed `NoSuchFileException` signing errors


# Docs

- Added the Discord server invite link to the `README.md`
- Added a Crowdin badge to the `README.md`
- Added the new unique features of this release to the `README.md`
- Added the new translators to the Contributors section of the `README.md`
- Redesign the Unique Features section of the `README.md`


# v1.7.0 (2025-12-31)


# Features

- Added the ability to favorite files and folders in the file picker page https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/77
- Added device ABI to version search queries https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/79
- Added a settings option under Settings > Advanced to change the search engine used for version search queries
- Dev builds now use `-dev` in their version numbers
- Updates are no longer prompted when using dev builds
- Updated the installation flow for the system installer to prompt the user to enable the "Install unknown apps" permission if not already granted by the user
- Removed the redundant `Apply` and `Cancel` buttons from the theme preview widgets in Settings > General
- Added a settings toggle in Settings > Advanced for the patch selection screen version tags
- Updated the "Configure updates" screen that appears on a fresh install from referring to the Official ReVanced Patches as "ReVanced Patches" to "Official ReVanced Patches" instead for consistency
- Updated the "Show & allow using universal patches" setting, when toggled off, to hide apps on the app selection screen that don't use any patches (so ones that only use universal patches)
- Converted the existing components of the app that use old file pickers to the new one, including save and overwrite warning logic
- Removed the "Show other versions" button on the select an app screen for apps that support all versions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/87
- Patch options menus now follow the same design as the rest of the patch selection page https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/84
- The custom file picker now remembers the last directory you were in and persists it across all instances of the file picker
- Added a discover bundle screen using Brosssh's external bundle API
- Added an option in `Developer Options` to disable the battery optimization banner that is shown if the user has battery optimization on
- Updated the patcher steps UI to match upstream improvements https://github.com/ReVanced/revanced-manager/pull/2805
- Added a new "Auto-expand running steps" setting for patcher progress widgets
- Upstreamed app info improvements https://github.com/ReVanced/revanced-manager/pull/2896
- Improved downloader plugin trust dialog design https://github.com/ReVanced/revanced-manager/pull/2420
- Added background bundle updates that can auto-download with a single progress notification, plus availability alerts for bundles set to manual updates. Enable in Settings > Updates. This also includes a background patching notification if you leave the app during patching https://github.com/ReVanced/revanced-manager/pull/2561
- Added individual patcher steps in the patcher screen https://github.com/ReVanced/revanced-manager/pull/2889
- Improved the experimental patcher with a faster APK write path during patching. ZIP sanitization before signing now runs only if initial signing fails
- Patch profiles empty state text now matches the apps tab styling
- Added a toggle to disable saving patched apps and hide saved‑app delete actions when disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/91
- Made the system installer more accurately detect failures or interruptions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/92
- With the custom file picker, you can now see APK file app icons
- Added the ability to manually select a patch bundle from a dialog for patch profiles using remote patch bundles that are marked as unavailable
- Added the ability to manually select a patch bundle for saved patched apps when the bundle is missing or unnamed
- Added a search bar to Settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/93


# Bug fixes

- Fixed issues with the experimental patcher where an error would be thrown saying a patch does not exist


# Docs

- Fixed the app icon in the `README.md` not showing
- Added a star history graph to the `README.md`
- Added new unique features to the `README.md`


# v1.6.1 (2025-12-19)


# Features

- Added a dialog that appears for apps with mismatched signatures https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/61
- Added a split APK unsupported guard for the Rooted mount installer
- Added additional guards and checks during patch bundle importing using a remote URL
- Added a "Use device language" option that uses the device's set language if available; if not, it falls back to English https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/69
- Importing patch bundles from a file now shows the progress of the import (similar to how importing remote bundles works)
- Improved monochrome icons
- Added the ability to disable patch bundles, which removes the bundle from the patch selection page, and grays out the bundle on the patch bundles tab https://github.com/ReVanced/revanced-manager/pull/2731
- Aligned the pencil button on the patch bundles tab to the other buttons (along with giving it the same size as the other buttons)
- Added a search button next to all version listings under "Show suggested versions" on the app selection page. Tapping the new button searches with Google the package name and the version number (example: com.google.android.youtube 20.51.38)
- Redesigned the UI of the app selection page
- Patch filter selections on the patch selection page now persist https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/73
- Improved patch selection screen UI
- Added chip tags on patches on the patch selection screen showing the versions the patch supports https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/75
- Added a "Get patch bundle URLs here" widget on the "Add a patch bundle" dialog that links to the "ReVanced-Patch-Bundles" repo
- Added Russian and Ukrainian translations https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/72
- Redesigned theme preview widgets in Settings > General under "Theme preview"


# Bug fixes

- Fixed `.xapk`, `.apkm` and `.apks` file types not being selectable from the select from storage screen(s) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/63
- Fixed an occasional crash that occurred with some users when opening the app and quickly going to the app selection page and opening a "Show suggested versions" expandable
- Fixed instability of Rooted mount installer
- Fixed an issue where the Rooted mount installer would be selectable for users who are non-root
- Fixed importing patch bundles from storage taking a long time https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/66
- Fixed the occasional issue where a patch profile without Universal Patches would claim it requires universal patches enabled in order to use that profile
- Fixed slow download speeds for remote patch bundles
- Fixed issues with deleting patch bundles during imports
- Fixed issues with importing remote/local patch bundles on top of each other (starting another import when one is already going on)

# Docs

- Added a new contributor to the "Contributors" section


# v1.6.0 (2025-12-17)


# Features

- Enhanced patcher log export with comprehensive information including timestamps, app metadata, split APK merging details, patching summary, and memory usage information
- Patch profiles now include a gear menu to set version overrides (or choose "All versions") per profile
- Added Korean manager string localization https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/42
- Split APKs now save in Settings > Downloads as merged APKs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/41
- Added a toggle in Settings > Downloads to disable automatically saving APKs fetched via downloader plugins
- Gave the GitHub PAT entry in Settings > Advanced the ability to be saved through the manager settings exports. This is a toggleable feature and is not on by default
- Updated the "Uninstall" button to "Unmount" and the "Update" button to "Remount" for saved patched apps in the "Apps" tab for apps installed by the rooted mount installer
- Added ability for users with root to mount patched apps by changing your primary installer to "Rooted mount installer" https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/40
- Added a button to the installation in progress dialog on the patcher screen allowing the user to "Leave anyway" and not wait for the installer to finish or timeout/fail
- Added an "External installer", "Rooted mount installer", "System installer" and "Shizuku" installation types to the app info page for saved patched apps in the "Apps" tab
- Added a confirmation dialog when tapping the back button during an install on the "App info" page for saved patched apps in the "Apps" tab
- Removed the "Default" app selection page filter and replaced it with a "Installed only" and "Patches available" filter, along with making them multiselectable https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/54
- Added new options under "Patch selection buttons order" in Settings > Advanced which allows the user to hide patch selection page action buttons
- Added 3 new filters to the patch selection page, being "Alphabetical", "Has settings" and "No settings" https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/50
- Patch bundle importing/updating now shows real-time per-bundle progress (download bytes/total & phases)
- Redesigned the patch selection pages action buttons so they are displayed horizontally from under the search bar to reduce clutter, and are now opened from a three dot button in the top right corner
- Redesigned Settings to M3 Expressive https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/46
- Added Japanese manager string localization https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/51


# Bug fixes

- Fixed patch profiles not saving the selected app version when the APK is provided by a downloader plugin
- Fixed metadata issues with saved patched apps that would sometimes occur
- Fixed issues with InstallerX Revived's silent installer and the manager not detecting an install and timing out instead (if the install made by InstallerX Revived fails, the manager cannot detect the failure. Either wait for the installer to timeout, or exit the patcher screen by pressing "Leave anyway" on the dialog) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/43
- Fixed the select from storage page not graying out non .apk, .apks, .apkm, or .xapk
- Changed the supported downloader plugins URLs to Brosssh's fork (which has released builds for all plugins)
- Fixed installer selection resetting to the system installer when a third-party installer (such as InstallerX Revived) is set as the device's default APK handler https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/26
- Fixed manually added custom installers not being removed from the installer selection menus sometimes after the user removes them from their saved custom installers
- Fixed patch bundle imports/updates sometimes crashing or hanging (empty bundles, PR artifacts missing `.rvp`, and stuck "0/1" updates) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/55
- Mitigated false "Installation failed" reports when Play Protect scanning delays installs (if a timeout dialog still appears, but it installs successfully, the successful install will supersede the false dialog) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/48
- Fixed Pure Black theme preset toggle from getting stuck in a disabled state


# Docs

- Added the new unique features to the README.md that were added in this release
- Added a new contributor to the "Contributors" section


# v1.5.1 (2025-11-15)
**Minimal changes & bug fixes**


# Features

- GitHub pull request integration - add patch bundles directly from GitHub pull request artifacts using a PAT, plus release/catalog links in bundle info https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/35
- Manager string localization (Chinese) - add Simplified Chinese strings and expose a user-selectable language toggle https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/33
- Vietnamese localization (new app language option) https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/38
- Revamped Settings > General theme presets: the System preset is now labeled "Follow system" (and is the default for new installs/resets), the Pure black option is simplified to "Pure black", every preset remains single-select so you can clear them to return to manual colors, Dynamic color is the only preset that blocks accent tweaks, and the preset description copy better explains how these toggles work https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/29
- Renamed the dynamic theme to "Material You"
- The GitHub icon buttons on each bundle's info and widget now open a bottom sheet with buttons for the release page and the patch-list catalog section (if available)
- Network requests now retry and respect server Retry-After headers when hit with HTTP 429 errors to reduce failed downloads
- Added an automatic "Merge split APK" step between loading patches and reading the APK so .apks, .apkm and .xapk archives are merged and patched without extra tools.
- Patch selection action buttons now remain visible at all times (graying out when unavailable) and automatically collapse when you scroll or switch bundles
- New Advanced setting lets you choose whether the patch selection action panel should auto-collapse after toggling patches
- Added an option in settings under Settings > Advanced "Patch selection action buttons order" that lets you reorder the patch selection action buttons
- Tap and hold the uninstall button on the app info page for saved patched apps to get the option to update that app (install over the existing one). The uninstall button still remains
- Added downloader help dialog explaining plugins and linking to supported list https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
- Updated to Liso’s patcher v22 (backwards compatible with existing patch bundles too) https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/39
- Moved the rearrange patch bundles button in the patch bundles tab to the top right, next to the settings gear
- Removed the old "patch does not exist" error handling system and replaced it with a simple warning dialog that tells the user the issue, before the patching process begins


# Bug fixes

- Correctly display pure black theme option - pure black toggle only shows when the app is in dark mode or following a dark system theme https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/30
- Typo - wording fixes for Theme color pickers and universal patches safeguard description https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/36
- Preserve applied patch counts in app details when bundles are unavailable so patched apps no longer show 0 patches applied https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/31
- Handle corrupted or empty pre-installed/remote patch bundles gracefully instead of crashing bundle loading https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/34
- Fixed the custom installer manager sometimes showing the android package installer twice
- Fixed occasional issues with importing patch bundles via remote
- Fixed pre-installed patch bundle sometimes ignoring the user's custom order when restoring large bundle imports
- Fixed patch profiles sub-options and values dialogs showing internal names instead of user-friendly names when the patch bundle used no longer exists in the app
- Patch selection screen buttons should now correctly align across different screen sizes
- Fixed the pre-installed patch bundle, resetting custom display names after restarting the app
- Patch profiles now record an app version even when saved before an APK is provided (e.g., downloader-based patch flows)
- Fixed the positioning and alignment of the patch selection menus action buttons on smaller screen sizes
- Fixed the "Auto-collapse completed patcher steps" setting under Settings > Advanced not being included in manager setting exports
- Fixed app sub option & value metadata not being reapplied/saved through the "Repatch" button on saved apps in the saved apps tab


# Docs

- Added the new unique features to the README.md that were added in this release
- Added a contributors section giving credit to those who have contributed to this repository


# v1.4.0 (2025-11-07)


# Features

- Added an export filename template for patched APKs with placeholders for app and patch metadata
- Added Shizuku as an installer option for silent installs when Shizuku/Sui is available https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/17
- Official patch bundle can now be deleted from the patch bundles tab, and restored from Advanced settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/18
- Primary and fallback installer menus now prevent selecting the same installer twice and grey out conflicting entries
- Advanced settings now support saving custom installer packages, including package-name lookup with autocomplete, and dedicated management for third-party installers https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/17
- Installer workflow now times out on stalled installs and automatically surfaces the system error dialog
- New bundle recommendation picker lets you choose per-bundle suggested versions or override them with any other supported version
- "Select an app" screen now groups bundle suggestions behind a toggle with inline dialogs for viewing additional supported versions
- The built-in Official ReVanced patch bundle now shows a dedicated "Pre-installed" origin label when viewed or restored
- Added a hyperlink in Settings > About that links to the unique features section of the README.md
- Changed the "Universal ReVanced Manager" title text on the main three tabs to "URV Manager"
- Updated the app icon of the manager to a custom one
- Removed the "Open source licenses" button & page in Settings > About


# Bug fixes

- Fixed patch option expandables in bundle patch lists collapsing or opening in sync when toggling multiple patches
- Fixed incorrect theming of certain UI elements with the pure black theme toggled on https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/15 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/20
- "Remove unused native libraries" setting should now actually remove all unnecessary/unused libraries completely when toggled on
- Fixed repatching through the "Apps" tab & using last applied patches & sub options on apps not saving https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/19
- Saved apps in the "Apps" tab should now stay (and not delete themselves automatically) when the user uninstalls the app directly from that page
- Fixed issues with installing directly from the patcher page https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/22


# Docs

- Updated the README.md to include the new unique features added in this release
- Added a section to the README.md which lists what downloader plugins that are currently supported by the manager


# v1.3.1 (2025-11-01)
**Minimal changes & bug fixes**


# Features

- Added a full installer management system with metadata, configurable primary/fallback choices that applies to patched apps, manager updates, etc. Configurable from Settings > Advanced (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/8)
- Updated the "Allow using universal patches" (now renamed to "Show & allow using universal patches") setting to also hide universal patches when toggled off and not just prevent the selection of them (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/9)
- Local patch bundle details show their bundle UID with a quick copy shortcut, imported & existing patch profiles automatically update their local patch bundle by using hashes, and the ability to manually edit the bundle UID for patch profiles that are using local patch bundles (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/11)
- Added the preinstalled, official ReVanced patch bundle user set display name to patch bundle exports
- Added the ability to edit/update existing patch profile names
- Prevent users from naming patch profiles the same as another per app (different apps patch profiles can only have the same names now)
- Remove obsolete add/plus button in the bottom right hand corner on the patch profiles tab
- Removed selection warning popup for toggling Universal Patches


# Bug fixes

- Made the patcher recover from out-of-memory exits caused by the user set memory limit with the experimental patcher process memory limit setting by automatically prompting the user to repatch, and lowering the memory limit (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/12)
- Cached bundle changelog responses so repeated requests fall back to a stored version instead of hitting GitHub rate limits (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/10)
- Fixed patch profiles duplicating instead of overlapping when imported multiple times
- Fixed delete confirmation menus not disappearing after confirming a deletion
- Fixed patch deselection shortcuts (deselect all & deselect all per bundle) not following patch selection safeguard settings
- Optimized patch bundles importing


# v1.3.0 (2025-10-26)


# Features

- Added the ability to uninstall downloader plugins from inside the manager via the downloads settings page
- Upstream with Official ReVanced Manager
  - Add pure black theme
  - Correct grammar mistakes
  - Prevent back presses during installation
- Added an advanced option to strip unused native libraries (unsupported ABIs) from patched APKs during the patching process (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/7)
- Added support for the manager to store multiple downloaded apps (ones downloaded through the downloader plugins) in the downloads settings & the ability to export the app to your devices storage
- Added a "Downloaded apps" option on the select source screen for patching apps that allows the user to select an APK that the manager has cached from downloader plugin downloads (this option will only appear if that app is downloaded; otherwise you won't see it)
- Added the ability to update an existing patch profiles through the save profile menu on the patch selection page
- Exporting a patched app to storage from the patching screen will now automatically save the patched app under the "Apps" tab. This previously only occurred when the user installed the app directly from the patching screen
- Added an accent color picker in appearance settings so users can choose a custom theme color (in addition to Material You and pure black)
- Added a confirmation popup when tapping the back button on the patching screen after the app has been successfully patched confirming the user wants to leave the screen. It also includes a option to save the patched app for later (saves it to the "Apps" tab) on the popup
- Added the ability to see the applied patches of a patched APK in the "Apps" tab, and the patch bundle(s) used
- Added the "View changelog" button to the changelog viewer in settings
- Added the ability to delete saved patched apps in the "Apps" tab (this will not uninstall them from your device)
- Removed redundant "View changelog" button at the top of the changelog screen popup


# Bug fixes

- A few grammatical errors
- Release workflow errors


# v1.2.1 (2025-10-23)
**Minimal changes & bug fixes**


# Features

- Added a changelog log section in remote/URL imported patch bundles information that shows the latest GitHub release changelog for said bundle
- Added a note on each patch bundle on whether they were imported via remote, or local (remote is via URL and local is via a file on your device)
- Removed redundant bundle counter on patches profile tab (there were two counters)


# Bug fixes

- (ci): incorrect version names on releases sometimes
- (ci): not uploading APK artifact to release
- Exporting patch bundles with locally imported patch bundles mixed with ones imported by a URL will now export (automatically excluding the locally imported ones from the export)


# v1.2.0 (2025-10-22)


# Features

- Added Patch Profiles; the ability to save individual patch selections per bundle(s) for a specific app to the new "Patch Profiles" tab
- Added a "Show actions" button that collapses/expands the action buttons in the patch selection menu
- Added the ability to export and import Patch Profiles to/from JSON files
- Added a copy patch bundle URL button in patch bundle options
- Added the ability to export and import the manager's settings from/to a JSON file (this only includes settings, not patch bundles, patch options, patch selections, etc)
- Adjusted the placement of the patch selection menu action buttons to go vertically instead of horizontally
- Upstream with the Official ReVanced Manager `dev` branch


# Bug fixes

- UI being cut off in patch bundle selection menus for resetting patch selection & options


# v1.1.1 (2025-10-20)
**Minimal changes & bug fixes**


# Features

- App launcher name is now "URV Manager" so the full name is displayed on different ROMs (name inside the app still remains the same)
- Selected patch counter shows count when scrolling in patch selection menu

# Bug fixes

- Incorrect keystore used on releases
- Incorrect patch count in patch selection menu


# v1.1.0 (2025-10-16)


# Features

- Added patch bundle exporting and importing support
- Added a deselect all per-bundle button in patch selection menu (the global deselect all button now has a different icon)
- Permanently enabled "Developer Options" in settings (removed the hidden flow to unlock them)
- Added a toggle in settings for updating the manager and patch bundles on metered connections
- Re-added the manager changelog app functions, screens, and buttons
- Added labels to the global patch deselection, per-bundle patch deselection, and reset to default buttons in the patch selection screen
- Renamed parts of the app from "Patch" or "Patches" to "Patch Bundle" to help with terminology clarity


# v1.0.0 (2025-10-13)


# Features
**Initial release**

- Added patch bundle display naming
- Added support for all 3rd party patch bundles
- Added the ability to deselect all patches in selection menu
