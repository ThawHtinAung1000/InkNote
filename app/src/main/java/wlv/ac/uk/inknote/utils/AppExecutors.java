package wlv.ac.uk.inknote.utils;


import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppExecutors {

    private final ExecutorService ioExecutor;
    private final Executor mainExecutor;

    public AppExecutors() {
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "inknest-io"));

        Handler mainHandler = new Handler(Looper.getMainLooper());

        mainExecutor = command -> {
            boolean accepted = mainHandler.post(command);

            if (!accepted) {
                throw new IllegalStateException("Main thread is unavailable.");
            }
        };
    }

    public static void requireWorkerThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Run database and file operations " + "on AppExecutors.io().");
        }
    }

    /**
     * Application-scoped executor for database and file operations.
     */
    public ExecutorService io() {
        return ioExecutor;
    }

    /**
     * Executor for delivering results to the UI thread.
     */
    public Executor main() {
        return mainExecutor;
    }
}