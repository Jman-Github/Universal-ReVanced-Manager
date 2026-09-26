# Changelog

Release notes from the Semantic Release migration onward are generated automatically from Conventional Commits.

Historical manually maintained release notes remain in `api/CHANGELOG.md` and `api/DEV-CHANGELOG.md`.

# [1.9.0-dev.2](https://github.com/Jman-Github/Universal-ReVanced-Manager/compare/v1.9.0-dev.1...v1.9.0-dev.2) (2026-09-20)


### Bug Fixes

* **morphe:** correctly apply patch results and isolate workspace ([3aacd3a](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/3aacd3aba8a418c50692428dfb9fb9f94e051827))

# v1.9.0-dev.1

# Features

- Added elapsed patching time to the Patcher Information widget for both regular and batch patching
- Added Repatch APK source retention so URV automatically reuses the original APK or split archive when repatching, with support for saved apps, batch patching, missing-source fallback, and Storage Management cleanup
- Added a delete action to individual saved unpatched app cards in Downloads, with a confirmation prompt before removing the stored APK
- Bumped Morphe Patcher to `1.9.0`
- Added configurable Play Store installation source modes for the system and rooted mount installers, adapted from https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
- Bumped Morphe Patcher to `1.12.0`
- Bumped Morphe Patcher to `1.14.0`
- Patch bundle changelogs can now display the full changelog history, depending on your changelog loading and cache limit settings. This replaces the previous `Previous changelogs` section
- Added patcher-style installation feedback to the Split APK Merger, including installation cancellation, a success dialog, and distinct patched/merged app installation messages https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/666 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/671 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/672


# Bug fixes

- Fixed patch bundle discovery preferring stale primary results by selecting the newest available data, falling back to the dev service, and importing bundles through the v3 API
- Fixed saved patched apps installed through Shizuku-based custom installers showing Shell instead of the selected installer
- Fixed pre-release updates not being detected for some patch bundles imported through GitHub or GitLab URLs
- Fixed patch bundle tabs showing inaccurate counts by displaying selected and available patches as a selected/total value
- Fixed Rooted Mount being unavailable in post-patch installer choosers for compatible Standard patches
- Fixed the Patcher Information widget sometimes showing the wrong app version instead of the version of the APK actually being patched
- Fixed single and batch patching sometimes using or reporting stale patch selections, options, app metadata, and runtime information, especially after missing patches were removed or the patcher screen was restored
- Fixed system Play Store installs still appearing as installed by URV Manager in Android settings
- Fixed some Morphe patch bundles failing to load in process mode with an incorrect corrupted or incomplete error
- Fixed patcher runtimes not consistently honoring `Continue after patch errors`, including process runtimes continuing after fatal patch errors when it is disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/685
- Fixed the Split APK Merger progress notification being able to reappear after cancellation when concurrent merge state or progress updates raced with cancellation cleanup https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/669
- Fixed returning from a cancelled local APK patch leaving the app name and icon blank when the patcher's temporary input had already been deleted https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/665
- Fixed issues with remote patch bundles when using GitHub or GitLab URLs sometimes not detecting prereleases correctly
- Fixed incomplete patch bundle changelogs and missing history entries, including hidden release notes, overwritten descriptions, mixed release channels, and refresh errors not appearing when cached history is available
- Fixed ReVanced 21 and 22 patching failing before patches are applied when using process mode
- Provide a writable temporary workspace for Morphe patches in both in-process and process-mode patching
- Fixed patch bundle import and update feedback by preserving bundle name casing, identifying the selected release channel, and using correct singular/plural progress wording https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/601 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/632 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/633
- Fixed incorrect singular/plural wording when removing saved patches and displaying patch bundle update badges https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/605 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/680
- Fixed cancelling APK preparation incorrectly displaying a failure dialog https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/610
- Improved split selection dialogs by removing outdated guidance and aligning the Cancel action consistently https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/613 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/618
- Fixed manager, installed app, and applied patch bundle version displays to consistently use a single `v` prefix https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/704
- Fixed the Process Memory Limit dialog title alignment https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/676
- Fixed unsupported keystore converter errors wrapping unnaturally https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/673


# CI

- Added a manual debug build workflow that uploads dev APK artifacts with a `-debug` version suffix without publishing a release
- Updated GitHub Actions dependencies to current Node 24-compatible versions, resolving Node.js 20 and deprecated action warnings
- Converted repository to semantic release based system instead of the manual mess I had before
