# v1.5.0 (2025-11-15)


# Features

- Added a GitHub button in the top right corner next to the delete button in the "Patch bundles" tab on a bundles information page that links the user to the release page of the said patch bundle's repository
- Added a select all patches, and select all patches per bundle action buttons on the patch selection page
- Added a reset default per bundle (resets to default patch selection for the bundle you have selected) action button on the patch selection page
- Added a confirmation popup when tapping any of the action buttons on the patch selection page (with a setting toggle in Settings > Advanced to turn these popups off)
- Updated the "This version" filter on the patch selection page to be deselected by default when the "Disable version compatibility check" setting is toggled on, and/or when the "Require suggested app version" setting is toggled off (both in Settings > Advanced)
- Tightened the gaps between the action menu buttons and adjusted placement on the patch selection page
- Made slight adjustments to the app icon
- Added support for monochrome app icons https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/25
- Added a "Redo action" and "Undo action" action buttons on the patch selection page
- Added a progress bar with a percentage for patch bundle updates. update checks, and imports
- Unified the design of the manager download banner and the new patch bundle update banner so they both use the same themed card under the top bar
- Installer manager dialog is now a tabbed bottom sheet with separate "Saved", "Auto saved" and "Discover" lists, plus inline action icons
- Patch bundle list entries now use card layouts with a dedicated action column so GitHub/update/delete controls no longer crowd the metadata
- Patch profiles tab now mirrors the new patch bundle cards, with rounded metadata blocks, inline action chips, and the scrollbar layout
- Added an "Organize" button beside the patch bundle add action so bundles can be reordered manually, and exports/imports now keep that custom order
- Patch Bundles tab action buttons now collapse behind an arrow control, remember their state, and auto-hide while you scroll
- "Patcher process memeory limit" in Settings > Advanced now includes a "Reset to recommended" button which sets the limit back to 700
- Patch bundles enforce unique names to avoid duplicates in the list
- Made saved patched apps metadata partially persist even if the patch bundle used to patch the app is no longer available
- The "Apps" tab now uses the same rounded card layout as Patch Profiles and Patch Bundles so saved patched apps share the updated UI
- The "Official ReVanced Patches" bundle (pre-installed bundle) now saves to patch bundle exports (it's order state, auto updating toggle status, pre-release toggle status, deletion status, display name, etc)
- Patch bundles with auto updating toggled off will now display a note under the patch bundles metadata informing the user the bundle has an update available
- Copying the log on the patching screen now includes all logs, include ones before the patching process begins (such as loading the patches, and reading the APK file)
- When patching fails because a selected patch no longer exists in the current bundle, the manager now shows a detailed dialog explaining the issue and sends you back to patch selection with the missing patches highlighted so you can reselect them quickly
- The patcher screens progress/step expandable widget now auto collapse once their steps are completed. Toggle this off/on in Settings > Advanced > "Auto-collapse completed patcher steps"
- Added a pencil icon next to each patch bundles name on the patch bundles tab to allow quick access to edit the display names
- Now under "Show more" on patch profiles it shows the patch sub option selections and values
- Clarified where and what settings to toggle for the "Show suggested versions" safeguard notice to disappear and to be able to use the bundle-specific versions feature
- Patch bundle cards now surface "Created" and "Updated" timestamps, and exports/imports preserve those timestamps so custom bundles keep accurate metadata
- Patch profiles now track their creation time, keep it when exporting/importing, and display the friendly timestamp on each profile card
- All exports initiated from Settings > Import & export (patch bundles, profiles, settings, selections, etc.) now default to files prefixed with `urv_` for easier identification
- Appearance settings now present the System/Light/Dark choices styled like the mew accent presets, plus there is a new theme color pickers along with a live preview widget. The accent color picker and theme color pickers also have the option to manually enter hex codes now
- The "Show suggested versions" safeguard notice is now rendered as a card with a title so it no longer feels like a loose block of text under the expandable section. It is also collapsed by default and expandable even when the safeguard settings prevent the use of it
- Long names throughout the app (bundle cards, segmented buttons, tabs, etc.) now use horizontal swipes instead of auto-sliding/multiple lines so they stay still until you drag them


# Bug fixes

- Fixed the reset patch options & selections in Settings > Import & export not actually reseting anything
- Fixed patch bundle cards showing a rectangular press/hover state instead of respecting the rounded shape
- Fixed manual-only bundle update notices triggering on startup even when auto update is disabled or no update is available
- Fixed patcher crashes caused by oversized memory limits when selecting APKs by clamping the process heap to safe device values
- Fixed the status bar using the wrong app icon https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/24
- Fixed "Auto saved" labels being added to manually adeded installers through the custom installer menu
- Fixed patch bunlde delete confirmations not using the bundle display name
- Fixed incompatible patches still being hidden even after toggling on "Disable patch version compatibility check" https://github.com/ReVanced/revanced-manager/issues/2444
- Fixed patch bundle and applied patch lists crashing when duplicate patch names were present https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/27
- Fixed custom installers added by the user not being saved/set as primary/fallback installers for some users https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/26
- Fixed crash that sometimes occurs when going back and forth between the patch selection menu and tapping the select patches button on the app info screen
- Fixed crash that sometimes occurs when tapping the "Select from storage" option at the top of the screen on the "Select an app" page


# Docs

- Added the app icon at the top of the READMME.md
- Added the new unique features to the README.md that were added in this release
- Added our telegram link to the README.md


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
- Added a hyerplink in Settings > About that links to the unique features section of the README.md
- Changed the "Universal ReVanced Manager" title text on the main three tabs to "URV Manager"
- Updated the app icon of the manager to a custom one
- Removed the "Open souce licenses" button & page in Settings > About


# Bug fixes

- Fixed patch option expandables in bundle patch lists collapsing or opening in sync when toggling multiple patches
- Fixed incorrect themeing of certain UI elements with the pure black theme toggled on https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/15 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/20
- "Remove unused native libraries" setting should now actually remove all unnecessary/unused libraries completely when toggled on
- Fixed repatching through the "Apps" tab & using last applied patches & sub options on apps not saving https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/19
- Saved apps in the "Apps" tab should now stay (and not delete themselves automatically) when the user unisntalls the app directly from that page
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
- Upstream with Official ReVanced Mananger
  - Add pure black theme
  - Correct grammer mistakes
  - Prevent back presses during installation
- Added an advanced option to strip unused native libraries (unsupported ABIs) from patched APKs during the patching process (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/7)
- Added support for the manager to store multiple downloaded apps (ones downloaded through the downloader plugins) in the downloads settings & the ability to export the app to your devices storage
- Added a "Downloaded apps" option on the select source screen for patching apps that allows the user to select a APK that the manager has cached from a downloader plugins downloads (this option will only appear if the said app is downloaded, otherwise you won't see it)
- Added the ability to update an existing patch profiles through the save profile menu on the patch selection page
- Exporting a patched app to storage from the patching screen will now automatically save the patched app under the "Apps" tab. This previously only occurred when the user installed the app directly from the patching screen
- Added an accent color picker in appearance settings so users can choose a custom theme color (in addition to Material You and pure black)
- Added a confirmation popup when tapping the back button on the patching screen after the app has been successfully patched confirming the user wants to leave the screen. It also includes a option to save the patched app for later (saves it to the "Apps" tab) on the popup
- Added the ability to see the applied patches of a patched APK in the "Apps" tab, and the patch bundle(s) used
- Added the "View changelog" button to the chanelog viewer in settings
- Added the ability to delete saved patched apps in the "Apps" tab (this will not uninstall them from your device)
- Removed redundant "View changelog" button at the top of the changelog screen popup


# Bug fixes

- A few grammatical errors
- Release workflow errors


# v1.2.1 (2025-10-23)
**Minimal changes & bug fixes**


# Features

- Added a changelog log section in remote/URL imported patch bundles information that shows the latest GitHub release changelog for said bundle
- Added a note on each patch bunlde on whether they where imported via remote, or local (remote is via URL and local is via a file on your device)
- Removed reduntant bundle counter on patches profile tab (there were two counters)


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
- Added the ability to export and import the managers settings from/to a JSON file (this only includes settings, not patch bundles, patch options, patch selections, etc)
- Adjusted the placement of the patch selection menu action buttons to be go vertically instead of horizontally
- Upstrean with the Official ReVanced Manager `dev` branch


# Bug fixes

- UI being cut off in patch bundle selection menus for reseting patch selection & options


# v1.1.1 (2025-10-20)
**Minimal changes & bug fixes**


# Features

- App launcher name is now "URV Manager" so the full name is displayed on different ROMs (name isnide the app still remains the same)
- Selected patch counter shows count when scrolling in patch selection menu

# Bug fixes

- Incorrect keystore used on releases
- Incorrect patch count in patch selection menu


# v1.1.0 (2025-10-16)


# Features

- Added patch bundle exporting and importing support
- Added a deselect all per-bundle button in patch selection menu (the global deselect all button now has a different icon)
- Permentalty enabled "Developer Options" in setings (removed the hidden flow to unlock them)
- Added an toggle in settings for updating the manager and patch bundles on metered connections
- Re-added the manager changelog app functions, screens, and buttons
- Added labels to the global patch deselection, per-bundle patch deselection, and reset to default buttons in the patch selection screen
- Renamed parts of the app from "Patch" or "Patches" to "Patch Bundle" to help with termonalogy clarity


# v1.0.0 (2025-10-13)


# Features
**Initial release**

- Added patch bundle display naming
- Added support for all 3rd party patch bundles
- Added the ability to deselect all patches in selection menu
