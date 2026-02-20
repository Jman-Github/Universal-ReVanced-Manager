# v1.8.1-dev.02 (2026-02-20)


# Features


# Bug fixes

- Fixed issues with loading patches from patch bundles on the discovery


# v1.8.1-dev.01 (2026-02-19)


# Features

- Adjusted the wording for the search bar in the custom file picker from `Search folder` to `Search current directory` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/183
- Rename the `Show patch summary before patching` settings toggle to `Show patch confirmation screen` for consistency
- Made the filter selection states on the `Select an app` screen persist https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/193
- Added setting to disable the swipe gesture to switch between tabs on the main screen (located in Settings > General) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/196
- Added the android document providers directory sort filters to the custom file picker https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/182
- Added patch option & value preview cards to the patch selection screens patch widgets https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/188
- Made it so hold tapping on the top left back button on the patcher screen brings you back to the `Apps` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/190
- Improved the metadata loader for split APKs on the `App info` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/190
- Made the patch bundle bar on the patch selection screen show even when theres only one bundle available https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/192
- Added a versioned per-runtime framework cache keys and updated modern AAPT2 binaries to TechnoIndian builds that are used for SDK 35+ patching
- Added upstream changes https://github.com/ReVanced/revanced-manager/pull/2916
- Allow external apps to appear in the Android document provider as options to select files with https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/203


# Bug fixes

- Fixed pressing the system back button on the `Patch Profiles` and `Tools` tabs sending the user back to the Android home screen instead of a different tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/191
- Fixed the `Patch confirmation` screen's scroll bar being very large https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/186
- Fixed the Android document provider file picker not resolving intents to local paths https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/202 
- Fixed an issue where the export filename variables wouldn't be placed at the cursor position https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/187
- Allow external apps to appear in the Android document provider as options to select files with https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/203
- Fixed the `Effective memory limit` listing in the patcher logs using the `Requested memory limit value` (needs testing)


# v1.8.0-dev.12 (2026-02-14)


# Features

- Added a confirmation dialog for favoriting files with the custom file picker
- Added an image preview dialog that opens when you tap the small image icon on the left for image files in the custom file picker https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/176
- Made user-selected image backgrounds persistent by importing the selected image into the app’s internal storage, so the original file doesn’t need to remain on the device. Users who set a custom image background before dev.12 will need to reset and reselect their background for this change to take effect.
- Replaced `Image selected: <filename>` with a preview of the selected background image
- Added downloader support to the `Merge split APKs` tool
- Added signing to the `Merge split APKs` tool so the output APK is not unsigned
- Made the `Merge split APKs` tool always run in another process (due to the intensity of merging some split APKs). If a separate process can’t be used, it will fall back to running in-app


# Bug fixes

- Fixed issue with the patch selection screen causing crashes


# v1.8.0-dev.11 (2026-02-13)


# Features

- Made main screen tab titles wrap to prevent them from being cut off https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/175
- Added the ability to hide & disable `Patch Profiles` and its associated tab with a toggle in Settings > General
- Added the ability to hide the `Tools` tab with a toggle in Settings > General
- Added a `Keystore creator` tool to the `Tools` tab
- Added a `Keystore converter` tool to the `Tools` tab
- Made text wrap on the `Create custom YouTube icons & headers` tool screen
- Improved installer error diagnosis and messaging


# v1.8.0-dev.10 (2026-02-12)


# Features

- Added a `Use custom file picker` toggle in Settings > Advanced that when toggled off, disables the custom file picker and uses the built in android file picker (documents provider)
- Added a `Tools` tab
- Added a `Merge split APKs` tool in the `Tools` tab that just merges the selected split APK and allows the user to save it to storage after https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/171
- Added a `Create custom YouTube icons & headers` tool to the `Tools` tab (inspired by [Morphe Managers implementation](https://github.com/MorpheApp/morphe-manager/pull/138))


# Bug fixes

- Fixed the app language selector dialog layout having an extra bottom spacing/clipping near the `Cancel` button https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/172
- Fixed issues with patch bundle importing and loading https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/138


# v1.8.0-dev.09 (2026-02-11)


# Features

- Updated Patch Bundle Discovery to use the new `api/v2` & `latest?channel=` URLs while keeping backwards compatibility with `api/v1` URLs
- Added a draggable transparency adjustment bar to Settings > General for when a image is set as the background
- Made the state of the progress banner persist
- Made the collapsed version of the progress banner show a minimal view of progress https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/162
- Added bundle-aware APK version assessment that finds the best matching bundle/version for storage-selected APKs
- Added a universal fallback confirmation dialog (`Use universal patches?`) when only universal patches are compatible
- Added a specific blocked-state message when only universal patches match but universal patches are disabled
- Expanded safeguard dialog support to allow confirm/cancel actions


# Bug fixes

- Fixed latest bundles so they correctly resolve the true latest version
- Fixed Allow changing patch selection and options behavior:
  - When OFF: app-list and storage APK selections always use default selection (ignore saved custom selections)
  - When ON again: saved custom selections are restored automatically (if present)


# v1.8.0-dev.08 (2026-02-10)


# Features

- Added the ability to set a image of choice as the app background
- Added `Always create new saved app entry` toggle in Settings > Advanced that toggles saved patch app entries from being overwritten
- Added `Hide main tab labels` toggle in Settings > General that toggles the labels under the tab icons on the main screen
- Added to the app information screen shown after selecting an app or APK to patch a listing displaying the apps package name
- Made the `View patches` screen for patch bundles and the patch bundle discovery have tap to search package tags
- Made `Any package` tags not searchable for the `View patches` screens patch widgets (and also the `Any version` tag when the `Any package` tag exists with it)
- Added an update notice tag to saved patched apps when the imported patch bundle version is newer than the one used to patch the app https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/163


# Bug fixes

- Fixed Patch Bundle Discovery `Latest` imports getting stuck to release/pre-release and not actually the latest https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/169
- Fixed issues with the progress bar during update checks getting stuck indefinitely when a imported patch bundle is errored/not correctly imported
- Fixed mounting errors that where occuring for some users (again) (needs testing) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/166


# v1.8.0-dev.07 (2026-02-07)


# Features

- Added guards to the patcher logger to prevent massive patch log exports
- Made the expandable/collapsable FAB buttons on the `Apps` and `Patch Bundles` tabs states persist
- Made saved patched app entries in the `Apps` tab not overwrite each other unless the app has the same package name and was patched with the same patch bundle


# Bug fixes

- Fixes patching errors caused by missing framework APKs
- Fixed mounting errors that where occuring for some users (needs testing)
- Fixed mount buttons on the saved patched app widget not being in the correct state


# v1.8.0-dev.06 (2026-02-07)


# Features

- Implemented XML surrogate sanitization to all runtimes
- Added the ability to export all settings (not including the keystore) to a single JSON along with an option to import it https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/165
- Adjusted the arrow FAB button on the `Apps` and `Patch bundles` tabs to be up against the right edge, removing the awkward gap https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/164


# Bug fixes

- Fixed AAPT2 failures on newer resource qualifiers/types
- Fixed numerous patching errors caused by the ReVanced dependency bump by downgrading
- Fixed the `Official ReVanced Patches` bundle having the `Remote` tag on its widget instead of the `Pre-installed` tag


# v1.8.0-dev.05 (2026-02-05)


# Features

- Added support for [AmpleReVanced Patches](https://github.com/AmpleReVanced/revanced-patches) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/152
- Bumped ReVanced dependencies
- Bumped Morphe dependencies
- Added a bundle type field to the patch bundle information screen
- Made the FAB buttons on the `Apps` tab collapsible/expandable https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/155
- Improved and cleaned up the patchers log
- Added a popout animation when switching tabs on the main screen


# Bug fixes

- Fixed bundle recommendations not being available for split-apks https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/151
- Fixed `Skip unneeded split APKs` toggle breaking some patched apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/153
- Fixed patch options not saving correctly for split APKs
- Fixed issues with action buttons on the saved patched apps widget not responding to taps and the delete button not being functional sometimes https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/155
- Fixed issues with the saved patch apps widget `Open` button
- Fixed local patch bundles not having a tag on the top right like remote patch bundles have
- Fixed issues with Morphe Manager generated keystores not working https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/158
- Fixed issues with the `Use default recommendation` toggle in the `Choose bundle recommendation` dialog not working correctly


# v1.8.0-dev.04 (2026-01-31)


# Features

- Added a refresh/reload button to the custom file picker
- Improved the UI of export and saving dialogs for the custom file picker
- Updated the view patches screen for patch bundles on the `Discover patch bundles` page to use the same UI as the view patches screen for imported patch bundles
- Made version tags on patches on all view patch screens searchable with the user set search engine
- Added patch options/sub-options to the view patches screen on the `Discover patch bundles` page. This is currently only implemented for patch bundles imported from the discovery page as the API dose not currently support patch option fetching for non-imported bundles
- Make all view patch screens searchable by patch name and description
- Added a `Latest changelog` and `Previous changelogs` action buttons to the patch bundle widget with options to hide and rearrange them in the corresponding setting
- Improved the `Apps` tab saved patched app UI to follow the style of the other tabs
- Made all action buttons for saved patched apps quick action buttons on their widgets along with a setting to hide and rearrange said buttons


# Bug fixes

- Fixed issues with the split-apk merger where some apps would crash after being patched


# v1.8.0-dev.03 (2026-01-27)


# Features

- Added the ability to export saved patched apps to storage
- Added `Saved` dates to saved patched apps in the `Apps` tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/145


# Bug fixes

- Possible fix for false OOM errors https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/142
- Fixed issues with URV generated keystores from previous versions of the app not being imported correctly resulting in signing errors (again) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/147


# v1.8.0-dev.02 (2026-01-27)


# Features

- Added a `Latest` filter and option in the three dot menu to the `Patch bundle discovery`
- Updated the split-apk merger to use APKEditor instead of ARSCLib
- Improved split-apk merger validation, normalization and cleanup
- Made the two FAB buttons on the `Patch bundles` tab collapsible/expandable https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/146
- Increased the pill text box size of the tab titles so devices with smaller screens won't have the text cut off https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/146
- Centered patch profile & patch bundle widget action buttons https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/146
- Updated the patch profile widget to use the same button type as the patch bundle widgets https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/146
- Centered the patch action button menu and expanded the search bar properly on the patch selection screen for devices with larger screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/148


# Bug fixes

- Fixed the `Patch bunblde discovery` screen incorrectly displaying the shimmer effect on the loading elements
- Fixed missing shimmer element when tapping refresh for the `Keystore dagnostics` panel
- Fixed incorrect version listings on the patch selection screens patch widgets
- Fixed the miscolored status bar on patch bundle information screens
- Fixed issues with unicode characters causing resource compilation errors for certain apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/144
- Fixed the ReVanced patcher runtime using the incorrect Aapt2 binary occasionally
- Fixed `brut.androlib.exceptions.CantFindFrameworkResException` patching errors
- Fixed issues with keystores from older versions of URV not being able to be imported into the newer versions of URV without signing errors https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/147
- Fixed false OOM errors with patching on lower end devices (needs testing) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/142


# v1.8.0-dev.01 (2026-01-25)


# Features

- Redesigned and improved patch bundles widgets UI, moved the progress banner and improved tab switcher UI https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/135
- Redesigned and improved patch profiles widgets UI along with adding an app icon to patch profiles that have an APK selected for instant patching https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/135
- Added `Patch bundle action button order` setting in Settings > Advanced that lets the user disable and rearrange the action buttons on the patch bundles widget https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/135
- Added a backup and restore system for keystores to mitigate any future missing keystore errors
- Added a dialog that appears after missing keystore errors to give clarity to the user on what to do next
- Added an information section/dignonstic panel for keystores which lists the keystore alias and password
- Gave keystores its own section in Settings > `Import & Export` and moved relevant settings to that section
- Added a `Effective memory` pill under the experimental patcher toggle to clarify to the user the max memory the app can use


# Bug fixes

- Resolved redundancies within the `service.sh` script improved module regeneration https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/134
- Mitigated issues with having to regenerate keystores & persistent errors with signing (even after regenerate the keystore) for some users
- Fixed an issue where the experimental patcher was always on internally when patching with Morphe, and couldn't be turned off
- Fixed alignment of accent presets in `Settings > General`
- Fixed patch options/suboptions dialogs flickering in certain states


# v1.7.1-dev.06 (2026-01-20)


# Features

- Added support for `JKS` keystore types


# Bug fixes

- Fixed issues with keystores from before the dev.05 release not working unless regenerated


# v1.7.1-dev.05 (2026-01-20)


# Features

- Made the fallback installer actually functional. If an install fails with the primary installer, the fallback installer is prompted
- Improved the `Discover patch bundles` screens searching/filtering
- Added the ability to set a APK path that persists to one tap patch with patch profiles
- Added a patch confirmation screen showing the user what patch bundles, patches, and sub options they have selected and enabled/disabled
- Added an option to export all patch selections at once
- Added support for PKCS12 keystore types


# Bug fixes

- Fixed more issues with the `Saved patched apps for later` setting toggle & adjust its behavior
- Fixed null splitNames errors with the Rooted mount installer https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/124
- Fixed imported discovery patch bundle update checks not always detecting an update when it should be https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/125
- Fixed issues with version name checking with the `Rooted mount installer` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/126


# v1.7.1-dev.04 (2026-01-19)


# Features

- Improved loading speeds significantly for the `Discover patch bundles` screen
- Added import progress to the `Discover patch bundles` screen along with a import queue toast


# Bug fixes

- Fixed deep linking not always working with bundle update/updating notifications (needs testing)
- Fixed the `Saved patched apps for later` setting not actually disabling and deleting saved patched apps


# v1.7.1-dev.03 (2026-01-18)


# ⚠️ BREAKING CHANGES

The `Discover patch bundles` screen has been updated to use [Brosssh's new API](https://github.com/brosssh/revanced-external-bundles/blob/dev/docs/graphql-examples.md). As a result, you will need to reimport any patch bundles that were added via the Discovery system prior to this release to continue receiving updates from their remote sources.


# Features

- Added the ability to reorder/organize the listing order of saved patched apps in the `Apps` tab and patch profiles in the `Patch profiles` tabcollapsible/expandable
- Make the progress banner collapsiable/expandable and gave it animations
- Made the `Apps`, `Patch Bundles` and `Patch Profiles` tabs items searchable via a button on the nav bar https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/113
- Redesigned the patch bundle widgets UI
- Hold tapping the individual update check button on patch bundles will give you a prompt to force redownload the corresponding patch bundle
- Removed redundant `Reset patch bundles` button in `Developer options`
- Moved the `Release`/`Prerelease` toggle button to a three dot menu popout for each patch bundle listing on the `Discover patch bundles` screen
- Added the ability to copy the remote URLs for patch bundles on the `Discover patch bundles` screen from a three dot button menu popout
- Added the ability to download patch bundles to your devices storage from the `Discover patch bundles` screen through the three dot buttons menu popout
- Added a way to search/filter through patch bundles on the `Discover patch bundles` screen by app package name https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/113


# Bug fixes

- Fixed issues with the auto-remount system for after restarts on some devices
- Fixed a crash when leaving the app during patching


# v1.7.1-dev.02 (2026-01-16)


# Features

- Improved the `Rooted mount installer`'s auto remount handling


# Bug fixes

- Fixed false update prompts and incorrect update detection
- Fixed patch bundle ODEX cache invalidation and recovery


# v1.7.1-dev.01 (2026-01-16)


# Features

- Removed the `Discover patch bundles` banner and added a FAB button next to the plus button instead to access the `Discover patch bundles` page
- Added support for Morphe Patches (mixing of ReVanced and Morphe Patches in a single patch instance is not feasible, and not currently supported)
- Improved patcher logging/profiling and error surfacing
- Improved metadata reading for split APKs on the app info page
- Imrpoved metedata reading for regular APKs on the app info page
- Converted the `Save patched app` button, `Export` button on the `App info` screen for saved patched apps, and the `Export` button on the Download settings page to use the custom file picker
- Added a saving modal to the custom file picker
- Added a search bar in the custom file picker that filters the current directory
- Made the `Save patched apps for later` toggle in Settings > Advanced actually toggle the ability to save patched apps in the `Apps` tab
- Added expandable/collapsable sub-steps to the `Merging split APKs` step in the patcher, along with sub-steps for the `Writing patched APK` step
- Overall improved the patcher screen
- Added the ability to see previous changelogs within the app which are cached by the it everytime your imported patch bundle updates https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/108
- Added a toggle in Settings > Advanced that when enabled skips all unused splits when patching with a split APK (like locale/density splits)
- Updated the `Remove unused native libraries` toggle in Settings > Advanced to strip all native libraries but one (so only keep one supported library if applicable)
- Added a per bundle patch selection counter
- Made the `View patches` button auto-scroll on the Discover patch bundles page
- Added the ability to export patcher logs from the patcher screen as a `.txt`
- Added a filter option on the patch selection page to filter by universal patches, and by regular (non universal) patches
- Added a toggle to use the `Pure Black` theme instead of the `Dark` theme for the `Follow system` theme https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/109
- Tapping patch bundle updating/updated notifications now highlights the corresponding bundle in the patch bundles tab
- Switched back to the official ReVanced Patcher and Library from Brosssh's Patcher and Library (as using theres is no longer needed)
- The `Rooted mount installer` now auto-remounts at device startup https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/112
- Moved the progress banner so it hangs below the nav bar https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/117
- Stabilize patch bunlde progress banners and make them clearler and more consistent
- Removed the redundant filter button from the `Select an app` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/121
- Added the ability to edit exisiting remote patch bundles URLs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/122


# Bug fixes

- Fixed dev builds not being prompted to update when there are new releases
- Fixed crashes the would occur occasionally for apps when loading metadata on the app info page
- Fixed false "Universal patches disabled" and "This patch profile contains universal patches. Enable universal patches..." toast/dialogs
- Fixed patcher steps under the `Patching` section not being checked off and left blank until after the entire step is `Patching` section is completed
- Fixed an issue where canceling the patching process by tapping the back button on the `Patcher` screen was not actually immediately canceling/killing the patching process as it would continue to run in the background for a bit
- Fixed the app crashing when certain patch option types are opened https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/103
- Fixed applied patches list for saved patched apps not showing all applied patches under certain circumstances https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/105
- Fixed bundle recommendation selection and compatibility issues https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/104
- Fixed issues with the custom file picker and the `Downloads` folder on certain devices
- Fixed app startup crashes and crashes with the custom file picker and other parts of the app on devices running older Android versions
- Fixed issues with patching on older Android versions
- Fixed update patch bundle notifactions not always appearing
- Fixed patched apps being incorrectly patched resulting in startup crashes
- Fix saved patched apps in the `Apps` tab and the restore button not restoring patch options correctly
- Increased stability of the `Rooted mount installer` by fixing issues such as `Exception thrown on remote process`
- Fixed false reimport toasts and adjusted official bundle restore logic with importing patch bundles from a patch bundles export


# Docs

- Added the Discord server invite link to the `README.md`
- Added a Crowdin badge to the `README.md`
- Added the new unique features of this release to the `README.md`
- Added the new translators to the Contributors section of the `README.md`
- Redesign the Unique Features section of the `README.md`