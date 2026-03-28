// IAmplePatcherProcess.aidl
package app.urv.manager.patcher.runtime.process;

import app.urv.manager.patcher.runtime.process.AmpleParameters;
import app.urv.manager.patcher.runtime.process.IPatcherEvents;

interface IAmplePatcherProcess {
    // Returns BuildConfig.BUILD_ID, which is used to ensure the main app and runner process are running the same code.
    long buildId();
    // Makes the patcher process exit with code 0
    oneway void exit();
    // Starts patching.
    oneway void start(in AmpleParameters parameters, IPatcherEvents events);
}
