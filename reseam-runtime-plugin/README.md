# Reseam runtime plugin

Version 1.0.0 hosts [Reseam SDK 0.19.0](https://git.reseam.app/reseam/reseam/src/tag/v0.19.0/sdk/README.md).
The SDK supports bundles built for the 0.19 release line. Other bundle engine lines need a matching runtime.

Download `reseam-plugin.apk` from the same URV release as the Manager, then import it from the runtime plugins screen. The existing file and download-link import flows both work. The plugin uses the ReVanced v21 icon and is a separate release asset; Manager APKs do not embed the SDK or this plugin.

After trusting the runtime plugin, import a local `.reseam` file, add a bundle's `patches.json` publishing-index URL, or paste a public Forgejo/Gitea repository URL such as `https://git.reseam.app/reseam/patches`. Repository imports discover release assets automatically and support stable, prerelease, and latest channels. Custom GitLab hosts are also supported. Local imports retain their `.reseam` filename hint; MIME and signed-catalog markers identify renamed archives. Routing hints never bypass SDK signature or format validation. Reseam's single-release API response is also accepted. The index's publisher key is pinned when provided; local bundles pin their signer on first inspection. Signature failures and publisher-key changes reject updates before replacing the installed bundle. To accept a changed publisher key, remove and import the source again.

## Host behavior

- Metadata uses the SDK's static catalog inspector. Hidden dependencies are executed by the engine and omitted from selectable patch rows. IDs disambiguate patches with identical names.
- Explicit selections and options are passed with preset NONE. Integer and floating-point options preserve the SDK's 64-bit precision. PATH options use URV's existing file picker.
- Enabled Reseam sources supply cross-bundle dependencies. A selected source takes precedence over unselected copies of the same bundle. Select patches from only one copy per bundle; disable duplicate dependency sources when no selected source identifies which copy to use. Reseam selections cannot be combined with another engine's patches.
- Runs use URV's existing isolated process transport on every supported Android version. Native calls are synchronous and the SDK has no cancellation API; cancellation terminates the worker process.
- Read, execute, individual-patch, split preparation, and write events use URV's existing progress contract. CPU, I/O, and heap resource samples continue through the existing monitor. The heap graph and heap limit describe the Java heap; Rust allocations are outside that limit.
- Split archives are extracted without merging. The SDK receives the base APK and retained splits separately and writes each component back separately. Manual split selection and automatic trimming remain available. URV signs every output component with the same key and exports the set as `.apks`. System and built-in Shizuku installs submit the complete set in one installation session; external installers receive the split archive. Original cached APKs are preserved when stripping native libraries.
- The SDK signs its temporary output. The adapter removes the v2 signing block without rewriting ZIP entries, preserving alignment and compression. URV then applies its normal final signing, signature metadata, skip-signing, and install flow.
- With continue-on-patch-error enabled, the SDK initially refuses output after failures. The adapter rebuilds from the clean input with successful selections so failed edits cannot leak into the published APK. Completed progress rows are not restarted.

## Packaging and validation

The release, debug-artifact, and pull-request workflows build and upload this APK separately. Shared progress, split-merger, and library-stripping helpers live in `shared/runtime-plugin`; ReVanced v21 uses the same unchanged helpers.

The focused tests cover marker detection, publishing-index parsing, option conversion, dependency event ordering, failures, retry progress, and byte-for-byte ZIP preservation when removing signing blocks. Device validation should cover plugin file/link import, catalog import, patching and cancellation, split inputs, and final installation.
