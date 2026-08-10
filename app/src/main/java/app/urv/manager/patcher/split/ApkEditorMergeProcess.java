package app.urv.manager.patcher.split;

import com.reandroid.apk.APKLogger;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.header.TableHeader;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.CoderMalfunctionError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ApkEditorMergeProcess {
    private static final String ACTION_MERGE = "merge";
    private static final String ACTION_LIST = "list";
    private static final String ORDER_PREFIX = "ORDER:";
    private static final String LOG_TAG = "APKEditor";
    private static final ThreadLocal<APKLogger> OVERRIDE_LOGGER = new ThreadLocal<>();

    public static void setLogger(APKLogger logger) {
        OVERRIDE_LOGGER.set(logger);
    }

    public static void clearLogger() {
        OVERRIDE_LOGGER.remove();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: merge|list <modulesDir> [outputApk] [skipModulesCsv] [sortApkEntries]");
            return;
        }
        String action = args[0];
        File modulesDir = new File(args[1]);
        if (ACTION_LIST.equals(action)) {
            List<String> order = listMergeOrder(modulesDir, getLogger());
            for (String name : order) {
                System.out.println(ORDER_PREFIX + name);
            }
            return;
        }
        if (!ACTION_MERGE.equals(action)) {
            System.err.println("Unknown action: " + action);
            return;
        }
        if (args.length < 3) {
            System.err.println("Missing output APK path");
            return;
        }
        File outputApk = new File(args[2]);
        String skipCsv = args.length > 3 ? args[3] : "";
        boolean sortApkEntries = args.length > 4 && Boolean.parseBoolean(args[4]);
        Set<String> skipModules = parseSkipModules(skipCsv);
        merge(modulesDir, outputApk, skipModules, sortApkEntries, getLogger());
    }

    private static Set<String> parseSkipModules(String csv) {
        Set<String> result = new HashSet<>();
        if (csv == null || csv.trim().isEmpty()) return result;
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public static void merge(
            File apkDir,
            File outputApk,
            Set<String> skipModules,
            boolean sortApkEntries,
            APKLogger logger
    ) throws Exception {
        merge(apkDir, outputApk, skipModules, sortApkEntries, logger, null);
    }

    public static void merge(
            File apkDir,
            File outputApk,
            Set<String> skipModules,
            boolean sortApkEntries,
            APKLogger logger,
            Runnable cancellationCheckpoint
    ) throws Exception {
        List<Closeable> closeables = new ArrayList<>();
        try {
            runCancellationCheckpoint(cancellationCheckpoint);
            ApkBundle bundle = new ApkBundle();
            closeables.add(bundle);
            bundle.setAPKLogger(logger);
            bundle.loadApkDirectory(apkDir);

            List<ApkModule> modules = bundle.getApkModuleList();
            if (modules.isEmpty()) {
                throw new FileNotFoundException("Nothing to merge, empty modules");
            }
            ApkModule baseModule = resolveBaseModule(bundle, modules);

            removeSkippedModules(
                    bundle,
                    baseModule,
                    skipModules,
                    cancellationCheckpoint
            );

            String expectedPackageName = baseModule.getPackageName();
            int expectedVersionCode = baseModule.getVersionCode();
            boolean expectedResourceTable = baseModule.hasTableBlock();
            ApkModule mergedModule = null;
            try {
                runCancellationCheckpoint(cancellationCheckpoint);
                // Keep manual split selection and the merger tool on the same bundle-level
                // merge path used by automatic split pruning in the patcher runtimes.
                mergedModule = bundle.mergeModules(false);
                writeAndVerifyMergedApk(
                        mergedModule,
                        outputApk,
                        sortApkEntries,
                        expectedPackageName,
                        expectedVersionCode,
                        expectedResourceTable,
                        logger,
                        cancellationCheckpoint
                );
            } catch (Exception | CoderMalfunctionError error) {
                throw normalizeMergeFailure(error);
            } finally {
                closeQuietly(mergedModule);
            }
        } finally {
            for (Closeable closeable : closeables) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static List<String> listMergeOrder(File apkDir, APKLogger logger) throws Exception {
        List<Closeable> closeables = new ArrayList<>();
        try {
            ApkBundle bundle = new ApkBundle();
            bundle.setAPKLogger(logger);
            bundle.loadApkDirectory(apkDir);
            List<ApkModule> modules = bundle.getApkModuleList();
            if (modules.isEmpty()) {
                throw new FileNotFoundException("Nothing to merge, empty modules");
            }
            closeables.addAll(modules);

            ApkModule baseModule = bundle.getBaseModule();
            if (baseModule == null) {
                baseModule = findLargestTableModule(modules);
            }
            if (baseModule == null) {
                baseModule = modules.get(0);
            }
            List<ApkModule> order = buildMergeOrder(modules, baseModule);
            List<String> result = new ArrayList<>(order.size());
            for (ApkModule module : order) {
                result.add(moduleDisplayName(module));
            }
            return result;
        } finally {
            for (Closeable closeable : closeables) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static List<ApkModule> buildMergeOrder(List<ApkModule> modules, ApkModule baseModule) {
        List<ApkModule> order = new ArrayList<>(modules.size());
        order.add(baseModule);
        for (ApkModule module : modules) {
            if (module != baseModule) {
                order.add(module);
            }
        }
        return order;
    }

    private static void removeSkippedModules(
            ApkBundle bundle,
            ApkModule baseModule,
            Set<String> skipModules,
            Runnable cancellationCheckpoint
    ) {
        if (skipModules.isEmpty()) return;

        Set<String> skipLookup = new HashSet<>();
        for (String name : skipModules) {
            skipLookup.add(normalizeModuleName(name));
        }
        for (ApkModule module : new ArrayList<>(bundle.getApkModuleList())) {
            runCancellationCheckpoint(cancellationCheckpoint);
            if (module == baseModule) continue;
            String normalized = normalizeModuleName(module.getModuleName());
            if (skipLookup.contains(normalized)) {
                bundle.removeApkModule(module.getModuleName());
            }
        }
    }

    private static void writeAndVerifyMergedApk(
            ApkModule mergedModule,
            File outputApk,
            boolean sortApkEntries,
            String expectedPackageName,
            int expectedVersionCode,
            boolean expectedResourceTable,
            APKLogger logger,
            Runnable cancellationCheckpoint
    ) throws IOException {
        mergedModule.setAPKLogger(logger);
        mergedModule.setLoadDefaultFramework(false);
        runCancellationCheckpoint(cancellationCheckpoint);

        if (sortApkEntries) {
            if (mergedModule.hasTableBlock()) {
                mergedModule.getTableBlock().sortPackages();
                mergedModule.getTableBlock().refresh();
            }
            mergedModule.getZipEntryMap().autoSortApkFiles();
        }

        SplitManifestCleaner.clean(mergedModule);
        applyExtractNativeLibs(mergedModule);
        runCancellationCheckpoint(cancellationCheckpoint);

        File parent = outputApk.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        logger.logMessage("Writing merged APK");
        mergedModule.writeApk(outputApk);
        runCancellationCheckpoint(cancellationCheckpoint);
        verifyMergedApk(
                outputApk,
                expectedPackageName,
                expectedVersionCode,
                expectedResourceTable,
                logger
        );
    }

    private static void verifyMergedApk(
            File outputApk,
            String expectedPackageName,
            int expectedVersionCode,
            boolean expectedResourceTable,
            APKLogger logger
    ) throws IOException {
        ApkModule verification = ApkModule.loadApkFile(logger, outputApk);
        try {
            if (!verification.hasAndroidManifest()) {
                throw new IOException("Merged APK is missing AndroidManifest.xml");
            }
            if (expectedResourceTable && !verification.hasTableBlock()) {
                throw new IOException("Merged APK is missing resources.arsc");
            }

            String actualPackageName = verification.getPackageName();
            if (expectedPackageName != null &&
                    !expectedPackageName.isBlank() &&
                    !expectedPackageName.equals(actualPackageName)) {
                throw new IOException(
                        "Merged APK package changed from " + expectedPackageName +
                                " to " + actualPackageName
                );
            }
            if (verification.getVersionCode() != expectedVersionCode) {
                throw new IOException(
                        "Merged APK version code changed from " + expectedVersionCode +
                                " to " + verification.getVersionCode()
                );
            }

            String splitName = verification.getSplit();
            if (splitName != null && !splitName.isBlank()) {
                throw new IOException("Merged APK still declares split name: " + splitName);
            }
        } finally {
            closeQuietly(verification);
        }
    }

    private static Exception normalizeMergeFailure(Throwable error) {
        Throwable cause = error.getCause();
        if (error instanceof CoderMalfunctionError ||
                error instanceof IllegalArgumentException &&
                        error.getMessage() != null &&
                        error.getMessage().contains("newPosition > limit") ||
                cause instanceof CoderMalfunctionError ||
                cause instanceof IllegalArgumentException &&
                        cause.getMessage() != null &&
                        cause.getMessage().contains("newPosition > limit")) {
            return new IOException(
                    "Failed to merge split APK resources. The split set may be incomplete, corrupted, or unsupported.",
                    error
            );
        }
        if (error instanceof Exception) {
            return (Exception) error;
        }
        return new IOException("Failed to merge split APK resources.", error);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static ApkModule resolveBaseModule(ApkBundle bundle, List<ApkModule> modules) {
        ApkModule baseModule = bundle.getBaseModule();
        if (baseModule == null) {
            baseModule = findLargestTableModule(modules);
        }
        return baseModule != null ? baseModule : modules.get(0);
    }

    private static void runCancellationCheckpoint(Runnable cancellationCheckpoint) {
        if (cancellationCheckpoint != null) {
            cancellationCheckpoint.run();
        }
    }

    private static ApkModule findLargestTableModule(List<ApkModule> modules) {
        ApkModule candidate = null;
        int largestSize = 0;
        for (ApkModule module : modules) {
            if (!module.hasTableBlock()) continue;
            TableHeader header = (TableHeader) module.getTableBlock().getHeaderBlock();
            int size = header.getChunkSize();
            if (candidate == null || size > largestSize) {
                largestSize = size;
                candidate = module;
            }
        }
        return candidate;
    }

    private static String moduleDisplayName(ApkModule module) {
        String name = module.getModuleName();
        return name.toLowerCase(Locale.ROOT).endsWith(".apk") ? name : name + ".apk";
    }

    private static String normalizeModuleName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") ? lower.substring(0, lower.length() - 4) : lower;
    }

    private static void applyExtractNativeLibs(ApkModule module) {
        AndroidManifestBlock manifest = module.hasAndroidManifest() ? module.getAndroidManifest() : null;
        Boolean value = manifest != null ? manifest.isExtractNativeLibs() : null;
        System.out.println(LOG_TAG + ": Applying: extractNativeLibs=" + value);
        module.setExtractNativeLibs(value);
    }

    private static APKLogger getLogger() {
        APKLogger logger = OVERRIDE_LOGGER.get();
        return logger != null ? logger : new ApkEditorLogger();
    }

    private static final class ApkEditorLogger implements APKLogger {
        @Override
        public void logMessage(String msg) {
            System.out.println(msg);
        }

        @Override
        public void logError(String msg, Throwable tr) {
            System.err.println(msg);
            if (tr != null) {
                tr.printStackTrace(System.err);
            }
        }

        @Override
        public void logVerbose(String msg) {
            System.out.println(msg);
        }
    }
}
