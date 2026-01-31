# v1.8.0-dev.04 (2026-01-30)


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