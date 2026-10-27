// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.patcher.runtime.jvm;

import app.urv.manager.patcher.runtime.jvm.JvmLaunch;

interface IPatcherService {
    // Starts the JVM, which loads the patch bundles while it waits for run, and returns the pid of
    // the process it runs in
    int prepare(in JvmLaunch launch, in String[] bundles);

    // Hands the started JVM the request written to requestFile. The run reports on events, which
    // closes once the process is gone
    void run(String requestFile, in ParcelFileDescriptor events);
}
