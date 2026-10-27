# Universal ReVanced Manager patching runtime

Universal ReVanced Manager (URV) runs patchers in an embedded Java runtime or an
Android Runtime (ART) worker. This document describes engine isolation, memory
management, diagnostics, and the build requirements.

The `urv-patcher-core` module packages the Morphe engine and supplies the shared
JVM host. ReVanced engines use separate versioned core JARs.

## Runtime selection

URV requires Android 8.0 (API 26) or newer. It bundles Android builds of OpenJDK 17
for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. The runtime matches the manager's
process ABI, including when a 64-bit device runs the manager as a 32-bit app.

| Engine or operation | Execution |
| --- | --- |
| Morphe | Embedded JVM, with ART fallback when JVM startup is unavailable |
| ReVanced v22 | Isolated engine in the embedded JVM, with ART fallback |
| ReVanced v21 | Versioned host from the installed v21 runtime plugin |
| Legacy ReVanced class-based bundles | Isolated SDK 19.3.1 host carried by the v21 runtime plugin (current version: 1.7.1) |
| Split merging, installer preparation, signature metadata injection | ReVanced v22 tools host |
| ReSeam, in builds with ReSeam integration | Disposable ART worker for its Android JNI SDK |

**Settings > Patcher > Use JVM for patching** enables the embedded JVM by default.
Turn it off to use ART for compatible engines; ReSeam always uses ART. The switch
is disabled on unsupported devices. Unsupported JVM ABIs, insufficient address space,
and JVM startup failures use bound ART workers. Trusted plugins that do not carry
a JVM host also use ART. An engine failure after patching starts is reported to
the user without automatically repeating the operation.

Registry runtime labels identify compatibility families. Historical bundles may
require an SDK older than the one their family name suggests; SDK 19.3.1 support
does not guarantee compatibility with every older ReVanced bundle. Legacy
class-based patches and newer DSL patches cannot be combined in one run.

## Process and engine isolation

Each JVM run starts in a disposable bound worker process with only its selected
engine host on the classpath. SDKs, Kotlin singletons, patch instances, and option
values cannot leak between runs. Bundle class loaders use their engine as the
parent, so lazy extension resources resolve against the matching SDK. JVM runs
remain serialized.

Cancellation ends the worker process, including native operations that cannot
cooperate with a cancellation callback. Completing or exhausting a run also ends
its worker, releasing its heap without closing the manager.

Only JVM startup failures fall back to ART. An engine failure after patching
starts is reported without repeating the operation. Older plugins without the
disposable-worker protocol use ART until updated. When available, a JVM crash
report is included in the next patching log.

## Memory and JVM options

Heap size is automatic. A 64-bit process uses one third of physical RAM, bounded
between 1.5 and 3 GiB. A 32-bit process requests 1 GiB and may receive a smaller
heap after URV checks contiguous address space and reserves non-heap headroom.
If less than 512 MiB is available for that heap, URV falls back to ART.

The 32-bit ARM runtime uses the client VM with Serial GC and a 64 MiB code-cache
cap. The 64-bit runtimes use the server VM with C1 compilation. Debug options,
when enabled, are applied after these defaults.

There is no manual memory slider or ART heap-property override. ART workers use
the heap limit Android assigns to them. A JVM run that spends at least 60% of a
two-minute window collecting garbage fails with an out-of-memory error instead
of continuing to crawl. GC monitoring ends with its run; terminating the worker
releases all of its memory.

## Runtime packaging

The APK carries one shared Java module image and small, verified differences for
the other CPU architectures. URV rebuilds the matching image during first-use
installation, before making the runtime available. This reduces download size
without changing the Java classes or memory budget available to patchers.

Native runtime libraries use XZ compression with the matching CPU branch filter.
The JVM hosts are shrunk with ProGuard 7.8.1 against JDK 17. Names, bytecode,
metadata, SDK entry points and bundle-facing APIs are preserved. The built-in
Morphe host uses Kotlin's standard reflection fallback. ReVanced hosts retain
full Kotlin reflection because their SDKs use it in patch contexts and options.

The built-in hosts reuse the identical ARSCLib framework resources already in
the manager APK. Plugin hosts retain their own framework resources so installing
a different plugin version does not change its resource decoder.

## Patching logs and stall diagnostics

The patching log includes:

- `Host VM:`: the actual VM name, version, architecture, and available processors.
- `Patcher process:`: the process ID, engine, bitness, ABIs, and memory budget.
- `Patcher JVM:`: the selected VM library and device-specific JVM options.
- Bundle loading times and patch progress.

After 60 seconds without a patching progress event, the host logs a warning and
collects its Java thread stacks and heap usage. It can repeat this up to five
times per run, at least a minute apart. Ordinary log lines and periodic resource
samples do not reset the timer. A warning does not cancel the operation: a slow
decode or compilation can legitimately take longer than a minute.

The watcher stops when its run ends. Diagnostic messages from an earlier run
cannot be appended to a later run's log.

Debug APKs accept one JVM option per line in `jvm-options.txt` in the manager's
private files directory. Blank lines and lines beginning with `#` are ignored.
Release APKs do not read this file. Options take effect on the next JVM run.

## Building

A full JDK 17 installation is required for Gradle, Kotlin compilation, and the
`jimage`, `jmod`, and `jlink` tools. Configure `JAVA_HOME` to point to that JDK,
then build the release artifacts with the Gradle wrapper:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ORG_GRADLE_JAVA_HOME = $env:JAVA_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleRelease
```

On Linux and macOS, use `./gradlew assembleRelease` with `JAVA_HOME` set to JDK 17.
Dependencies published through GitHub Packages require the repository's configured
package credentials.

`buildSrc/PrepareEmbeddedJvmTask` relinks the Android runtime's own classes and
native libraries. The archives are pinned by SHA-256 in `app/build.gradle.kts`.
The desktop JDK used for linking must have the same feature version as the
embedded runtime.

The portable host and event protocol live in `jvm-host`. Android sends its SDK
level, build fingerprint, ABIs, locales, and screen density to each run; desktop
host properties do not determine APK preparation. Changes to progress messages
must preserve the event contract between the Android UI and engine hosts.

## Attribution and licenses

The runtime architecture and diagnostics adapt Morphe Manager
[PR #1202](https://github.com/MorpheApp/morphe-manager/pull/1202),
[PR #1212](https://github.com/MorpheApp/morphe-manager/pull/1212),
[PR #1215](https://github.com/MorpheApp/morphe-manager/pull/1215),
[PR #1223](https://github.com/MorpheApp/morphe-manager/pull/1223), and
[PR #1246](https://github.com/MorpheApp/morphe-manager/pull/1246), plus the
[heap-budget fix](https://github.com/MorpheApp/morphe-manager/commit/77af30c2e2711d6b745ffa435a5a7fa534277449)
and [GC-overhead guard](https://github.com/MorpheApp/morphe-manager/commit/76b5477eca7a97b393a95cc5880d90d1ab47354a).
Original copyright headers and source attribution are retained. The required
Morphe notice is available in the app and in [NOTICE.txt](../third-party/NOTICE.txt).

The Android OpenJDK builds come from
[AngelAuraMC](https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/tag/download_jre17).
Their build recipes and source references are published in that repository.
OpenJDK is licensed under GPLv2 with the Classpath Exception. Each linked module's
legal notices are included in the runtime archive under `legal/`, and OpenJDK
is listed in the app's open-source licenses.
