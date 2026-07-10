# v1.8.1 (TBD)


# Features

- Added a Shizuku installer option that reports Google Play as the installation source https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/380
- Improved dialog titles, keystore converter messaging, and storage usage `Clear` button alignment for consistency https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/532 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/485 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/484
- Improved downloader and runtime revoke trust dialogs with consistent confirmation prompts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/497
- Added toast feedback when force downloading all patch bundles starts or downloads nothing https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/531
- Improved patch selection and reset patch bundle dialogs with centered content, a restore icon, and consistent `Confirm` actions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/529 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/530
- Added `v` prefixes to downloader and runtime plugin version labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/526
- Updated the debug log export toast to `Debug log exported` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/527
- Improved Patcher Runtimes plugin action button styling so `Update` matches `Delete`/`Settings` and `Revoke trust` uses the grey filled style https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/516
- Updated the local runtime plugin trust dialog secondary action from `Delete` to `Uninstall` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/521
- Updated downloader and runtime plugin trust dialog titles to `Trust` for consistency https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/522
- Updated runtime plugin delete and uninstall confirmation buttons to `Confirm` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/520
- Added specific copied URL toast messages for downloader plugins, runtime plugins, and patch bundles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/514
- Replaced local downloader and runtime plugin repository URL copy buttons with copy icons https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/523
- Moved the local downloader and runtime plugin `Latest` option below `Pre-release` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/517
- Improved downloader plugin action button styling so `Update` matches `Delete`/`Settings` and `Revoke trust` uses the grey filled style https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/515
- Removed redundant `downloader` text from downloader plugin names in trust dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/518
- Removed package/id details from runtime plugin trust dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/519
- Added a Split APK merger progress notification with the manager notification icon https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/304
- Added the manager icon to the patching progress notification https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/301
- Added separate Split APK merger step expansion settings for auto-collapse, auto-expand, and active-category-only expansion https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/302
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
- Pressing the system back buton on the `Apps` tab now exits the app
- Made search queries and filters on the custom file picker persist across sessions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/209
- Improved export filenames for patch selections and keystores https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/205
- Added persistent bundle sorting to the `Patch Bundle Discovery` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/206
- Added a toggle in Settings > General that disables the swipe guesture on the patch selection screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/212
- Added live patching progress foreground notifications https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/207
- Set keystores generated with the `Keystore creator` tool to use the maximum possible expiration date
- Improved the push notification system by using websockets (this avoids having to use FCM) to keep the notifcation worker alive
- Added push notifications for manager updates https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/214
- Renamed `Search bundles` on the `Discover patch bundles` screen to `Search by bundle name` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/219
- Improved bundle and manager update alerts and websocket status wording
- Added a `Split APK installer` tool to the `Tools` tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/189
- Redesigned and completely reorganized the settings screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/222
- Slight UI corrections and improvements in multiple parts of the app https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/223
- Added an option to choose an APK from URV downloaded apps for the Patch Profiles preset APK setting https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/224
- Made the package name of apps always shown on the `App info` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/225
- Improved the `Create custom YouTube icons & headers` tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/211
- Added a toggle in Settings > Patcher that disables/enables the expansion of the main categories on the patcher screen that are running https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/201
- Corrected a few inconsistencies in Settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/240
- Aligned patcher step naming in the notification with the steps on the patcher screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/253
- Added a loading screen that appears when selecting an app from storage from the `Select an app` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/255
- Added support for the ReVanced Patcher v22 while keeping backwards compatibility with v21 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/252
- Overall improved the stability to all patcher runtimes
- Added device architecture, Android version, device model, and patcher version to the patcher logs
- Removed the duplicate export button from the App info screen top bar, keeping the existing export action in the main action row https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/260
- Updated the Morphe runtimes aapt2 binaries to the ones used by the official ReVanced Manager https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/272
- Improved the existing `Previous changelog` system to backfill to a user set limit through settings
- Remove `AAPT2 version` and `AAPT sha256` from the patcher logs and added `AAPT2 selected`, `Environment`, `Device name` and `Selected patches` lines
- Bumped Morphe dependencies
- Improved the `Patching in progress` notification https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/278
- Renamed the manager keystore file to `urv_keystore.keystore` and added migration/restore support for legacy `manager.keystore` backups https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/268 
- Updated the ReVanced `libaapt2.so` binaries to the ones used by the official ReVanced Manager
- Added live substep counts to patcher progress group https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Improved app and patch search so closing search keeps filtered results visible, and both system back and top-bar back clear the search before leaving https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Improved patch option previews with a larger inline preview and a full preview dialog for long values like file paths https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Improved patch profile APK handling by preserving split archive extensions, loading split APK icons correctly and using detected APK versions for compatibilit https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Patch profiles can now use the version detected from a selected APK
- Added an option to only keep the latest plugin-downloaded APK per app
- Added a delete confirmation for selected downloaded apps in `Downloads`
- Updated the manager update notification and update banner text to use clearer URV-specific wording, including `URV Manager update found` and `A new manager update is available` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/274
- Increased the size of the status bar icon https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/230
- Improved patch bundle update notifications to use clearer "update found" wording and correct singular/plural grammar for available bundle updates https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/235
- Improved patch bundle update notifications with the manager icon, ordered bundle/version lists, and stacked grouped alerts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/299
- Added persistent tracking for the currently viewed manager update version so the icon state stays correct across app restarts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/232
- Reordered the General settings screen so Navigation & Tabs appears before Themes, while Themes and Background remain grouped together https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/234
- Renamed the screen shown after selecting an app to patch from App info to `Preparing to patch` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/264
- Updated the `Apps` and `Patch Profiles` tab search hints to use consistent wording of `Search by app name` and `Search by profile name` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/237
- Improved the Patch Profiles bundle selector with tabbed bundle switching and cleaner bundle source labeling https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/242
- Improved patch option viewing in Patch Profiles with a three-dots menu for patches with options
- Clarified changelog link wording by changing the external action to `View changelogs on GitHub` on the manager update and changelog settings screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/233
- Added a scrollable changelog preview directly to the manager update popup, so release notes can be read without opening the full updater screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/229
- Added a restart prompt after changing the in-app language https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/244
- Removed an extra UI separator from the `General` settings screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/287
- Improved patcher state handling across all patcher runtimes
- Added a `Prevent accidental touching` setting that protects against accidental page/tab swipes when enabled, and makes page/tab swipes easier with shorter drags when disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/286
- Added installed apps as a source for the Split APK merge tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/197
- Added a split selection step before merging so specific modules can be included or excluded https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/197
- mproved split merge loading, cancellation, and progress handling
- Added icons to settings subsections and refined the settings card styling for a cleaner, flatter look
- Improved the main Settings screen layout, pinned the About card to the bottom when space allows, and added button-only `Reset`/`Edit` or `Reset`/`Settings` controls for configurable settings
- Improved the dashboard header and main tab labels on smaller screens by keeping the title on one scrollable line and widening the selected tab highlight
- Added remote downloader plugin importing/updating with improved plugin naming and trust handling, seeded default remote downloaders on fresh install, and support for newer official downloader plugins
- Added a full ReVanced announcements system with announcement list/detail screens, tag filtering, archived announcements, unread badges, and dashboard announcement banners https://github.com/ReVanced/revanced-manager/pull/2948
- Added announcement push notifications with deep links to the specific announcement, integrated into the existing websocket/background update system
- Added settings to enable or disable announcements and announcement notifications
- Bumped the Morphe patcher dependency
- Removed the automatically imported remote downloader plugins
- Bumped Morphe Patcher to `1.3.3`
- Bumped ReVanced Patcher to `22.0.1`
- After importing settings, URV now requests any required runtime permissions for enabled features when they are not already granted
- Removed now unneeded extra `libaapt2.so`'s and aapt2 selector system
- Removed API status banner https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/372
- Added About credits and in-app licensing viewers
- Improved root service mount reliability and safety https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/370
- Added LITE, MEDIUM, and FULL build variants with matching profile-aware updater behavior
- Added patcher-style progress tracking and merge log copy/export support to the Merge split APKs tool, including excluded split details in exported logs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/388
- Updated the “No updates available” message https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/389
- Improved update dialog and changelog readability by reducing changelog heading/body sizes and tightening the update prompt text layout https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/381
- Removed the `MEDIUM` build profile as it included the same runtimes as `FULL`
- Improved the About screen layout and added version display copy behavior https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/399 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/400
- Updated English UI wording for merge tool titles, selected app counts, patch bundle empty-state text, and APK saved casing https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/396 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/398 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/293 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/319
- Updated patch/merge log copy messages, aligned delete/import wording, and made the merge log button follow patcher-style availability https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/404 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/403 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/405 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/406
- Added loading-aware dashboard app input gating so app selection actions stay disabled until patch bundles are ready, with a clearer inactive button state https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/402
- Updated the `LITE` build type to only exclude the ReVanced v21 runtime, and only include the ReVanced v22 and Morphe patcher runtimes
- Bumped Morphe Patcher to `1.4.1`
- Added a Morphe bytecode processing mode setting https://github.com/MorpheApp/morphe-manager/pull/403
- Made the About screen version row so long-press copy only targets the manager version value, not the "Version" label https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/424
- Added patch option import/export support for patch selection backups https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/417
- Blocked all in-app interaction while app/APK selection loading overlays are shown, including the Apps tab and app picker flow https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/429
- Updated dashboard delete confirmation dialogs to use the correct singular or plural wording for selected apps, patch bundles, and patch profiles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/430
- Improved text-entry UX by auto-focusing dialog and search inputs, opening the keyboard automatically, and keeping input dialogs visible above the IME across patch bundle, search, and settings flows https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/373 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/374
- Updated the split APK merge selection dialog to show the live selected module count in the Start merge action button https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/362
- Added a per-install installer selection mode that lets users choose the installer each time on the patcher and saved patched app flows https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/208
- Added a new Patcher logging setting with Default and Verbose modes https://github.com/ReVanced/revanced-manager/pull/3287
- Remember the selected split merge preset across app sessions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/303
- Added a Latest toggle to remote downloader plugin source settings so sources can update from the newest stable or pre-release GitHub release
- Bumped Morphe Patcher to `1.4.2`
- Improved UI strings, empty/search states, bundle changelog formatting, and import feedback order https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/436 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/437 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/441 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/435 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/434 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/432
- Improved string consistency https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/433
- Bumped Morphe Patcher to `1.5.0`
- Added automatic color code correction, normalization, and a color picker for patch options
- Moved the cursor to the end of prefilled input fields when dialogs and search fields open automatically https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/444
- Added a Storage and cache management settings screen with a storage usage breakdown, refreshable size tracking, and clear actions for cache, downloads, patch bundles, saved patched apps, patch profile inputs, signing files, plugin files, temporary workspaces, and external app folders https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/454
- Added a scheduled auto-clear cache system https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/407
- Updated the save-file icon on the `Choose splits to merge` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/456
- Removed the duplicate top-bar cancel action from the split merge selection screen and renamed Split APK installer to Split installer https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/457 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/455
- Added an Updates setting to switch the manager update popup between full release notes and the minimal version-only view https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/420
- Kept the screen awake while the split merge process is running https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/387
- Added a patcher setting to continue patching when individual patch errors occur https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/288
- Removed the `LITE` and `FULL` URV build system and replaced it with a plugin-based system. ReVanced and Morphe are bundled with URV by default, while support for ReVanced v21 requires installing its plugin
- Bumped Morphe Patcher to `1.5.1`
- Removed the AmpleReVanced runtime because Ample now uses the Morphe patcher instead of its own runtime
- Make dialogs icons more consistent https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/467
- Made the confirmation buttons across dialogs more consistent https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/466
- Adjusted the keystore converter error message text size for better consistency with other tool error messages https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/462
- Release and Pre-release filters can no longer be selected at the same time; use Latest to show the newest bundle across both channels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/473
- Reduced reorder hold delay by making app, bundle, and profile drag handles start dragging immediately https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/477
- Moved Bundle type to the top of bundle information before editable fields https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/481
- Close dashboard search bars when switching between Apps, Patches, and Profiles tabs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/469
- Center storage management confirmation dialog titles and descriptions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/461
- Improve YouTube asset guide ring contrast on both light and dark images https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/463
- Improved downloader and patcher runtime plugin source settings, including clearer runtime source details, repository URL copying, matching switch behavior, and the renamed ReVanced v21 runtime plugin https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/490 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/493
- Improved downloader and patcher runtime plugin trust dialogs with clearer wording, centered Plugin and Signature sections, consistent warning icons, Confirm actions, and corrected downloader plugin display names https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/491
- Polished runtime plugin labels, uninstall confirmation text, and About screen credit punctuation https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/486
- Improved runtime plugin dialog wording so delete and uninstall confirmations use consistent action labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/496
- Improved downloader and patcher runtime plugin labels for consistent naming across cards and dialogs, and updated the patch bundle discovery empty state to show `No bundles found` centered in the list https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/495 https://github.csues/494-Github/Universal-ReVanced-Manager/issues/494 https://github.coues/500-Github/Universal-ReVanced-Manager/issues/500
- Added CPU architecture labels to saved patched apps, showing values like ARM64, ARMv7, x86, Universal, or No native libs in the Apps tab and saved app details https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/251
- Improved bundle recommendation dialogs, runtime plugin cards, trust dialogs, source settings styling, and added an icon for the ReVanced v21 runtime plugin https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/498
- Centered the remaining patch selection and patcher result dialog text, including bundle action confirmations, patch defaults, incompatible patches, and install success dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/487
- Made the patch selection filter sheet open fully expanded so all filter options are visible without scrolling first https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/507
- Added patch selection sorting options for Z-A, enabled first, and disabled first https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/511
- Added app icon background image output and optional Morphe notification icon generation to the custom YouTube asset creator tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/330
- Increased the patch bundle discovery empty state text size for consistency with other search results https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/513
- Added a dashboard notification for newly installed runtime plugins and tightened notification card action padding https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/512
- Added support for experimental app version targets in Morphe patch bundles
- Improved exported log filename consistency for merger, patcher, debug, and installer logs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/524
- Compact saved Apps card patch bundle summaries and added a setting to hide bundle update badges https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/528
- Bumped Morphe Patcher to `1.5.2`
- Added an APK signer tool to the `Tools` tab for signing APKs with the manager's current signing certificate https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/504
- Centered dialog titles that were still left-aligned in several dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/534
- Added patch profile action button ordering and visibility settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/482
- Centered additional dialog titles that were still left-aligned https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/536
- Improved patch bundle and patch profile import result messages with updated and skipped counts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/386
- Centered install result dialog titles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/537
- Added a filename format setting for merged APKs saved from the split APK merger tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/318
- Centered body text in the downloader help and language restart dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/539
- Allowed combining cleanup filters in the split APK merge tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/360
- Standardized supported version dialog layouts and centered suggested version chips https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/540
- Added a default-enabled New patches filter that shows newly added patches from each bundle at the top of the patch selection screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/236
- Centered the signature mismatch dialog body text across the patcher, app info, and quick action install flows https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/544
- Updated newly generated manager signing keystores to use `alias` as the alias and `password` as the password while preserving compatibility with existing and legacy manager keystores https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/538
- Added support for importing remote patch bundles from GitHub and GitLab repository URLs by resolving root-level `patches-bundle.json` metadata https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/545
- Added support for Morphe `add-source` links, including opening them in URV with the import dialog prefilled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/545
- Improved the split APK downloader plugin flow by showing download progress in a loading screen before opening split selection
- Added optional live memory usage graphs to the patcher and split APK merger tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/567
- Force patching and split APK merging to run in separate processes on Android 11+ to prevent memory crashes https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/572
- Removed obsolete experimental patcher and memory-limit settings
- Added an instability warning for Android 10 and older
- Updating patch profiles no longer requires you to select their patch bundles again https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/577
- Added live progress percentages to the patcher and split APK merger with smoother step and substep tracking https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/568
- Added color previews to patch option presets https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/564
- Updated local downloader and runtime plugins to display "Loaded" instead of "Trusted" https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/562
- Standardized revoke trust dialog titles across downloader and runtime plugins https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/561
- Adjusted the supported versions dialog so it is not become too wide when opened from patch search https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/559
- Standardized installer log filename and timestamp formatting with other exported logs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/558
- Improved dialog text consistency and added clearer save confirmations for patched and merged APKs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/555 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/553 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/552
- Plugin notifications no longer remain after opening the Download or Patcher Runtimes settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/556
- Moved “Merged APK filename format” under “Export filename format” in Advanced system settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/551
- Added a Done button to the patcher screen after patching completes https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/580
- Added Shevery support for Shizuku-based installs, including detection, manager launching, installer icons, and updated installer labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/585
- Added suggested version dropdowns to app search results on the `Select an app` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/586
- Added split ordering options to the split APK merge selection screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/582


# Bug fixes

- Fixed bundle update notifications disappearing after repeat checks while respecting intentionally dismissed alerts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/535
- Fixed the Patch Bundles tab empty state so searching with no added bundles still shows the no-bundles message https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/483
- Fixed pressing the system back button on the `Patch Profiles` and `Tools` tabs sending the user back to the Android home screen instead of a different tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/191
- Fixed the `Patch confirmation` screen's scroll bar being very large https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/186
- Fixed the Android document provider file picker not resolving intents to local paths https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/202 
- Fixed an issue where the export filename variables wouldn't be placed at the cursor position https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/187
- Fixed the `Effective memory limit` listing in the patcher logs using the `Requested memory limit value` (needs testing)
- Fixed issues with loading patches from patch bundles on the discovery
- Fixed an issue where saved app entries would be duplicated when installing a entry marked as `Saved`
- Fixed issues with loading metadata for certain APKs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/216
- Fixed missing-split install failures when patching apps that are installed as split APKs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/217
- Fixed issues with memeory on Android 10 and lower devices (needs testing) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/150
- Fixed the `Patch confirmation` screen on older Android versions being covered by the three button navigation
- Fixed issues with the patcher screen being "frozen" and showing no progress or anyting at all for a few seconds on older Android versions
- Fixed UI lagging/buffering issues on the `Download APK file` step
- Fixed issues where downloading certain APK files using the downloader plugins would cause an error https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/221
- Fixed issues with the swiping to switch tabs guesture on the main screen and patch selection screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/204
- Fixed an issue where exported keystores would have the `.json` file extension https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/238
- Fixed several UI issues and visual bugs on the `Create custom YouTube icons & headers` tool screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/243
- Fixed an issue where the `Tools` tab wasn't scrollable
- Fixed the wrong UI being shown when switching tabs quickly on the main screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/245
- Clarified the patch profile APK placeholder text from `No APK selected` to `No APK for this package` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/241
- Fixed issues with the patching notification not appearing immediately when patching starts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/256
- Fixed an issue where the patching notification would disappear before patching was actually finished https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/257
- Fixed an issue where using the Android Documents Provider to export apps from `Downloads` would cause a crash https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/258
- Fixed an issue where the patcher process would die for some users https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/267
- Fixed an issue where the ReVanced v22 patcher runtime wouldn't follow the experimental patcher setting
- Fixed an issue where the patching process would die (needs testing) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/267
- Fixed issues where the patcher screen would not show the progress of patching accurately
- Fixed issues where the ReVanced v22 runtime would patch incorrectly resulting in broken patched apps
- Fixed an issue where if you patched an app with multiple patch bundles they sometimes would not be listed on the `App info` and `Applied patches` screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/262
- Fixed an issue where the hold tap guesture would not work for some apps in the `Apps` tab
- Fixed an issue where you couldn't select certain apps in the `Apps` tab on multiselection mode
- Fixed dependent patching flow settings so they are disabled when inactive and automatically reset when their parent toggle is turned off
- Fixed the `Export filename format` editor so tapping the text field no longer jumps the view to the end, and its helper content now scrolls separately without disrupting cursor placement https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/269
- Fixed issues where there would be a pause between the patchers main categories/steps
- Fixed more issues with patching on certain runtimes
- Fixed issues with resuming manager updates with the in-app manager updater https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/277
- Fixed manager update asset selection so the updater correctly resolves both ABI-specific APKs
- Fixed issues with the `Always create a new saved app entry` setting https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed the patcher screens pause between `Load patches` and `Read APK file` sub-steps
- Fixed issues with progress being reordered live during split APK merging for the AmpleReVanced runtime
- Fixed the `Export filename format` dialog so the single-line text field slides with the cursor https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/276
- Fixed issues with the fullscreen back gesture causing UI glitches and showing the wrong screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/280
- Fixed an issue where the `AAPT2 selected` line in patcher logs would always be listed as `Unknown`
- Fixed RGB inputs in the custom YouTube color picker so typed values are visible and use numeric keyboard input https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Fixed previous patch bundle changelog history so it resets when a bundle source changes, fetches the correct number of older entries, and only shows historical changelog actions for bundle sources that actually supports them
- Fixed an issue where the `Patching in progress` notification would clear early https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/257
- Fixed an issue where the `Patchng in progress` notification would appear late https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/285
- Fixed an issue where on fresh installs the user would be required to regenerate the keystore manually
- Fixed background bundle and manager update checks being rescheduled on every app launch
- Fixed websocket-triggered update checks so newer refreshes are not dropped behind older queued work
- Fixed stale bundle update notifications not clearing when no updates remain
- Fixed manager update notifications being suppressed after notification permission is turned off and back on
- Clarified package selection text to use generic app package wording instead of APK-only wording for local files and patch profiles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/264
- Fixed (hopefully) the fullscreen back gesture showing the wrong screen preview so the back animation now matches the actual destination screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/280
- Fixed an issue where the `Patching in progress` notification could remain visible after canceling patching https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/285`
- Fixed patch profiles and last used patch selections/options from overriding each other
 - Fixed saved patched app entries being overwritten, duplicated, or showing incorrect version/date metadata when repatching installed apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed stale merged APK output being offered after a later merge failure
- Fixed installed split archive creation to fail cleanly when APK parts are missing
- Fixed uninstall failures sometimes showing as Installation failed instead of Uninstall failed
- Fixed the uninstall service error messaging
- Fixed fullscreen back gestures showing the wrong screen preview on some devices and OEM ROMs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/280
- Fixed patched app installs sometimes creating duplicate `Saved` and installed entries in the `Apps` tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed patcher warnings breaking progress on the patcher screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/267#issuecomment-4028751450
- Fixed multiple patching and split APK merge issues across runtimes, including bundle loading regressions, cancellation/cleanup problems, merge failures, and write-progress glitches https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/308
- Fixed new announcement banners not appearing on the dashboard until the screen or app was reloaded
- Fixed issues where the patching in progress notifaction would lag behind, or where the patcher UI would lag behind the notification https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/305 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/320
- Fixed the patch bundle URL editor sometimes needing an extra `OK` press after editing https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/324
- Fixed downloader plugin signature verification failures potentially crashing URV during launch, reload, or update checks https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/332
- Fixed patcher runtimes holding loaded patch dex objects in memory longer than necessary by scoping patch loading closer to execution
- Possibly resolved issues around patching Google Photos with `De-ReVanced` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/349 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/343
- Fixed valid APK files being rejected in various app flows https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/333
- Fixed mislabeled image resources and invalid decoded manifest resource references breaking patching across the ReVanced, ReVanced v22, and Ample runtimes https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/346
- Fixed `.rvp` patch bundles being mislabeled as Ample without positive Ample markers https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/358
- Fixed older ReVanced `.rvp` bundles staying identified as ReVanced even when metadata loading fails https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/358
- Preserve replaced variants when reinstalling saved apps, and clarify that the “Always create a new saved app entry” option only affects patcher saves https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed fullscreen bundle/profile dialogs showing a mismatched status bar strip above the top bar https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/341
- Fixed the patcher source selector showing a false untrusted downloader plugin warning on clean installs with no downloader plugins added or installed https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/353
- Fixed the patch bundle URL editor sometimes requiring two OK taps after editing https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/339
- Possibily resolved OOM and crashing errors that are occuring for certain users when patching Google Photos with the `De-ReVanced` patch bundle https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/343 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/349
- Fixed several split APK merger edge cases across runtimes, including duplicate extracted split filenames, incorrect density split skipping, and process-mode merge sorting not honoring its setting
- Improved patching stability by hardening worker shutdown and notification handling during restarts and app closure and added safer recovery for stale patch progress snapshots https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/365
- Fixed the `Read APK file` step showing extra `Initializing patcher` subtext on some runtimes
- Fixed `Prepare split APK` substep ordering so skipped and non-skipped merge rows stay grouped consistently
- Reworked patcher progress handling across all runtimes so `Write patched APK` uses structured grouped progress instead of inconsistent fallback row creation
- Fixed the patch selector action popup collapsing after toggling patches even when Collapse actions after toggling patches is disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/338
- Fixed patch selector action popup behavior so patch toggles no longer dismiss it as an outside click when auto-collapse is turned off https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/338
- Fixed patch bundle update notifications showing the default bundle name instead of the user’s custom bundle name after a successful update https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/364
- Fixed bundle auto-update progress text so custom bundle names remain consistent throughout checking, downloading, and finalizing stages https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/364
- Fixed the `Recommended for this device` merge split APK preset including extra ABI splits by preferring the device’s primary ABI 
- Fixed merge split tools progress grouping so skipped split rows remain grouped separately from non-skipped rows
- Fixed the native library stripping toggle handler on the choose-splits dialog to use a stable explicit toggled state path
- Fixed merge split APK filter switching so selecting `Remove extra native libraries` resets previous preset exclusions first, instead of carrying over exclusions from filters like `Exclude unused languages` or `Recommended for this device` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/361
- Fixed Settings and Update action buttons wrapping awkwardly under Android screen zoom https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/379
- Fixed URV sometimes crashing when patching ran out of memory with the experimental patcher disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/349
- Fixed patcher progress sometimes lagging behind the foreground notification during the early Write patched APK phase https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/390
- Fixed a few UI edge cases, including hidden RGB values in the custom YouTube asset color picker, clearer patch-profile empty-state wording for downloaded apps, and duplicate saved-entry normalization when overwrite protection is enabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/291 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Hardened remote patch bundle updating, API fallback handling, and bundle refresh state management https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/372
- Fixed version assessment so universal-fallback APKs still respect the universal patches safeguard even when suggested-version enforcement is relaxed
- Fixed bundle and profile count text so selected, enabled/disabled, and import/export messages use the correct singular or plural wording https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/385 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/384
- Renamed the split merge native-libraries filter to `Exclude extra native libraries` and fixed patch bundle update banner grammar for singular vs plural counts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/363 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/359
- Improved installer settings consistency by adding blocked-installer notes, fixing PAT dialog link punctuation, fixed thw remaining issues with missing patch bundle update plural strings, and cleaning up installer wording/status labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/378 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/375 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/377 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/395
- Fixed rooted mount installs creating a second visible saved-app entry for the same patched app https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed inconsistent dialog button alignment so export, patcher, color picker, and related settings dialogs now place actions on the right like the rest of the app https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/376
- Fixed local APK source handling so returning from the patcher preserves the selected file correctly
- Fixed patching notification timing and cancellation behavior so it appears immediately and clears correctly on cancel/close https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/401 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/409 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/292
- Fixed downloader plugin edge cases by improving failed-source recovery, accepting valid plugin results without a reported version, showing download progress for size-less plugins, and expanding the downloader import URL field correctly on larger screens
- Fixed rooted mount installs creating an unused empty legacy directory under `/data/adb/revanced/<package>` for newly mounted apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/411
- Fixed the Export & Import settings flow by moving export actions before import, cleaning up wording and punctuation, correcting app count pluralization, and updating the manager version copy toast https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/418 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/425 https://github.com/ Jman-Github/Universal-ReVanced-Manager/issues/416 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/413 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/415
- Fixed patcher progress resume/replay syncing with notifications, improved Write APK DEX substep restoration, and improved app icon/label fallback handling https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/419
- Fixed the dashboard "Select from storage" flow briefly showing the wrong page while the selected APK is being loaded https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/421
- Fixed dashboard selection toolbars and selection state not clearing cleanly when switching between Apps, Bundles, and Profiles tabs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/423 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/422
- Fixed incorrect gesture-back preview behavior on screens that intercept back for in-app UI state changes instead of real navigation https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/280
- Fixed framework cache recovery and bundled framework handling across all runtimes to prevent missing or corrupted framework cache patching failures
- Fixed duplicate Saved/Installed entries by collapsing matching saved variants after install when saved app overwrite is enabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed split archive metadata resolution to use stricter device-matching split selection for app labels and icons
- Fixed split APK merging so remove-extras and native library cleanup preserve required ABI, language, and DPI splits when only one compatible config is available
- Fixed split APK tool settings so installed-app filters persist/export correctly, installer logs use clearer copy/export labels, and the duplicate GitHub PAT export toggle is removed https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/439 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/440 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/442 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/443
- Fixed duplicate Apps tab entries being created after installing patched apps with custom installers https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed patch selection action buttons collapsing while scrolling, swiping bundles, or pressing actions when auto-collapse is disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/448
- Re-added the legacy/modern AAPT2 selector to resolve AAPT2 based resource compilation errors https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/460
- Fixed bundle update notifications being cleared after a follow-up background check when the same manual bundle update was still available but had already been reported https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/470
- Fixed the Apps tab "Update available" badge for patch bundles so stable releases correctly outrank matching prerelease/dev versions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/468
- Resolved more issues with saved patched apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed orphaned saved patched APKs taking up storage after they were no longer shown in the Apps tab
- Fixed bundle discovery searches showing the loading state indefinitely when no matching bundles are found https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/499
- Fixed patch option dialogs showing the color picker for file/path options https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/508
- Fixed bundle version selection dialogs showing cached patch bundle names instead of custom bundle display names https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/502
- Fixed ReVanced patching failures by retrying the Write patched APK step with the alternate AAPT2 binary when the selected AAPT2 fails https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/460
- Fixed ReVanced v22 AAPT2 selection so it uses the sanitized APK input before opening the patcher session
- Fixed color option detection so path-like fields are not mistaken for color values
- Fixed Android back navigation on the patch selector so it matches the toolbar back behavior https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/408
- Fixed the saved app bundle update badge toggle appearing enabled while disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/547
- Fixed merger collapsible sections not respecting the auto-collapse and auto-expand settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/548
- Fixed merge split APK filter combinations not always being remembered across sessions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/563
- Fixed bundle update notifications sometimes appearing again after being tapped https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/571
- Fixed split APK merger progress briefly moving backward when merging starts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/570
- Fixed the merge notification remaining visible after cancellation on some devices https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/549
- Fixed the Bundle update label sometimes missing from saved app cards https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/566
- Fixed the Tools screen briefly appearing when holding Back after merging or saving a split APK https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/560
- Fixed rooted mounted apps sometimes starting without patches after reboot https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/578
- Fixed patcher and split APK merger progress bars appearing partially filled before progress begins https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/569
- Fixed issues with the split APK merger during corrupting and breaking some APKs
- Fixed bundle update labels not appearing when a saved app's patch bundle was imported again through settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/581


# Refactors

- Migrated URV’s internal source namespace from `app.revanced.manager` to `app.urv.manager` across the app, API, and runtime modules


# CI

- All prereleases/releases are published with a universal (all ABI) APK, and the ABI specific APKs. The in-app updater now automatically picks the APK that matches your ABI
- Updated manager release APK filenames to use canonical ABI labels such as arm64-v8a, armeabi-v7a, and universal, and updated the in-app updater/workflows to match the new asset names while preserving compatibility with older releases https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/428


# v1.8.0 (2026-02-15)


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
- Made main screen tab titles wrap to prevent them from being cut off https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/175
- Added the ability to hide & disable `Patch Profiles` and its associated tab with a toggle in Settings > General
- Added the ability to hide the `Tools` tab with a toggle in Settings > General
- Added a `Keystore creator` tool to the `Tools` tab
- Added a `Keystore converter` tool to the `Tools` tab
- Made text wrap on the `Create custom YouTube icons & headers` tool screen
- Added a confirmation dialog for favoriting files with the custom file picker
- Added an image preview dialog that opens when you tap the small image icon on the left for image files in the custom file picker https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/176
- Made user-selected image backgrounds persistent by importing the selected image into the app’s internal storage, so the original file doesn’t need to remain on the device. Users who set a custom image background before dev.12 will need to reset and reselect their background for this change to take effect.
- Replaced `Image selected: <filename>` with a preview of the selected background image
- Added downloader support to the `Merge split APKs` tool
- Added signing to the `Merge split APKs` tool so the output APK is not unsigned
- Made the `Merge split APKs` tool always run in another process (due to the intensity of merging some split APKs). If a separate process can’t be used, it will fall back to running in-app
- Added French to the in app language selector dialog
- Synced Crowdin


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
- Fixed issue with the patch selection screen causing crashes
- Fixed storage root detection on older Android versions by removing hidden API reflection and using public APIs only (improves Android 15+ compatibility)
- Fixed AAPT2 runtime detection on older Android versions by replacing API 33-only byte reading with a minSdk-safe implementation
- Fixed the `Effective memory limit` listing in patcher logs incorrectly reporting memory limits and using the `Requested memory limit` value instead


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
