package wlv.ac.uk.inknote.utils;



import android.util.Log;

import wlv.ac.uk.inknote.BuildConfig;

public final class AppLogger {

    private static final String TAG = "InkNest";

    public enum Operation {
        CREATE_POST,
        GET_POST,
        LIST_POSTS,
        UPDATE_POST,
        SEARCH_POSTS,
        DELETE_POST,
        DELETE_POSTS,
        CLEANUP_IMAGES
    }

    private AppLogger() {
    }

    public static void success(
            Operation operation,
            long durationMillis
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                    TAG,
                    operation.name()
                            + " success durationMs="
                            + durationMillis
            );
        }
    }

    public static void rejected(
            Operation operation,
            ServiceResult.ErrorCode code
    ) {
        if (BuildConfig.DEBUG) {
            Log.w(
                    TAG,
                    operation.name()
                            + " rejected code="
                            + code.name()
            );
        }
    }

    public static void failure(
            Operation operation,
            Throwable cause
    ) {
        /*
         * Raw exception messages and stack traces may contain
         * SQL arguments or private paths. Only log the type.
         */
        Log.e(
                TAG,
                operation.name()
                        + " failed causeType="
                        + cause.getClass().getSimpleName()
        );
    }

    public static void cleanupPending() {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "CLEANUP_IMAGES pending");
        }
    }
}