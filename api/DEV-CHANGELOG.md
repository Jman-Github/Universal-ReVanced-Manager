# v1.7.1-dev.05 (2026-01-21)


# Features

- Made the fallback installer actually functional. If an install fails with the primary installer, the fallback installer is prompted
- Improved the `Discover patch bundles` screens searching/filtering
- Added the ability to set a APK path that persists to one tap patch with patch profiles


# Bug fixes

- Fixed more issues with the `Saved patched apps for later` setting toggle & adjust its behavior


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