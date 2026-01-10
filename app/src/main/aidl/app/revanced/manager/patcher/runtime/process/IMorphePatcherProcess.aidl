// IMorphePatcherProcess.aidl
package app.revanced.manager.patcher.runtime.process;

import app.revanced.manager.patcher.runtime.process.MorpheParameters;
import app.revanced.manager.patcher.runtime.process.IPatcherEvents;

interface IMorphePatcherProcess {
    // Returns BuildConfig.BUILD_ID, which is used to ensure the main app and runner process are running the same code.
    long buildId();
    // Makes the patcher process exit with code 0
    oneway void exit();
    // Starts patching.
    oneway void start(in MorpheParameters parameters, IPatcherEvents events);
}
