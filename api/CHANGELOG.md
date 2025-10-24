# v1.2.1 (2025-10-23)
**Minimal changes & bug fixes**


# Features

- Added a changelog log section in remote/URL imported patch bundles information that shows the latest GitHub release changelog for said bundle
- Added a note on each patch bunlde on whether they where imported via remote, or local (remote is via URL and local is via a file on your device)
- Removed reduntant bundle counter on patches profile tab (there were two counters)


# Bug fixes

- (ci): incorrect version names on releases sometimes
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