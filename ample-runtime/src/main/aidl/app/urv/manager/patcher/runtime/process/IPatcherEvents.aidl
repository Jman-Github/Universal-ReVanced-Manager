// IPatcherEvents.aidl
package app.urv.manager.patcher.runtime.process;

import app.urv.manager.patcher.ProgressEventParcel;

// Interface for sending events back to the main app process.
oneway interface IPatcherEvents {
    void log(String level, String msg);
    void event(in ProgressEventParcel event);
    // The patching process has ended. The exceptionStackTrace is null if it finished successfully.
    void finished(String exceptionStackTrace);
}
