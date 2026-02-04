package app.revanced.manager.patcher.split;

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
            List<String> order = listMergeOrder(modulesDir);
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
        merge(modulesDir, outputApk, skipModules, sortApkEntries);
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

    private static void merge(
            File apkDir,
            File outputApk,
            Set<String> skipModules,
            boolean sortApkEntries
    ) throws Exception {
        List<Closeable> closeables = new ArrayList<>();
        try {
            APKLogger logger = getLogger();
            ApkBundle bundle = new ApkBundle();
            bundle.setAPKLogger(logger);
            bundle.loadApkDirectory(apkDir);

            List<ApkModule> modules = bundle.getApkModuleList();
            if (modules.isEmpty()) {
                throw new FileNotFoundException("Nothing to merge, empty modules");
            }

            if (!skipModules.isEmpty()) {
                Set<String> skipLookup = new HashSet<>();
                for (String name : skipModules) {
                    skipLookup.add(normalizeModuleName(name));
                }
                ApkModule baseModule = bundle.getBaseModule();
                for (ApkModule module : new ArrayList<>(bundle.getApkModuleList())) {
                    if (module == baseModule) continue;
                    String normalized = normalizeModuleName(module.getModuleName());
                    if (skipLookup.contains(normalized)) {
                        bundle.removeApkModule(module.getModuleName());
                    }
                }
            }

            closeables.add(bundle);

            ApkModule mergedModule;
            try {
                mergedModule = bundle.mergeModules(false);
            } catch (Throwable error) {
                Throwable cause = error.getCause();
                if (error instanceof CoderMalfunctionError ||
                        error instanceof IllegalArgumentException && error.getMessage() != null && error.getMessage().contains("newPosition > limit") ||
                        cause instanceof CoderMalfunctionError ||
                        cause instanceof IllegalArgumentException && cause.getMessage() != null && cause.getMessage().contains("newPosition > limit")) {
                    throw new IOException(
                            "Failed to merge split APK resources. The split set may be incomplete, corrupted, or unsupported.",
                            error
                    );
                }
                throw error;
            }

            mergedModule.setAPKLogger(logger);
            mergedModule.setLoadDefaultFramework(false);
            closeables.add(mergedModule);

            if (sortApkEntries && mergedModule.hasTableBlock()) {
                mergedModule.getTableBlock().sortPackages();
                mergedModule.getTableBlock().refresh();
            }
            if (sortApkEntries) {
                mergedModule.getZipEntryMap().autoSortApkFiles();
            }

            SplitManifestCleaner.clean(mergedModule);
            applyExtractNativeLibs(mergedModule);

            outputApk.getParentFile().mkdirs();
            logger.logMessage("Writing merged APK");
            mergedModule.writeApk(outputApk);
        } finally {
            for (Closeable closeable : closeables) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static List<String> listMergeOrder(File apkDir) throws Exception {
        List<Closeable> closeables = new ArrayList<>();
        try {
            ApkBundle bundle = new ApkBundle();
            bundle.setAPKLogger(getLogger());
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
