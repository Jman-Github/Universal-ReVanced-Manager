# v1.7.1-dev.01 (TBD)


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


# Docs

- Added the Discord server invite link to the `README.md`
- Added a Crowdin badge to the `README.md`
- Added the new unique features of this release to the `README.md`
- Added the new translators to the Contributors section of the `README.md`
- Redesign the Unique Features section of the `README.md`