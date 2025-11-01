# v1.3.1 (2025-11-01)
**Minimal changes & bug fixes**


# Features

- Added a full installer management system with metadata, configurable primary/fallback choices that applies to patched apps, manager updates, etc. Configurable from Settings > Advanced (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/8)
- Updated the "Allow using universal patches" (now renamed to "Show & allow using universal patches") settubg to also hide universal patches when toggled off and not just prevent the selection of them (https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/9)
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
- Fixed patch deselection shorcuts (deselect all & deselect all per bundle) not following patch selection safeguard settings
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
