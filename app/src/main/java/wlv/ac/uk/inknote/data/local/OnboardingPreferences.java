package wlv.ac.uk.inknote.data.local;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

import wlv.ac.uk.inknote.utils.AppExecutors;

/** Local introduction preference storage. Construction does not access preference files. */
public final class OnboardingPreferences {

    private static final String PREFERENCES_NAME = "inknest_onboarding";
    private static final String INTRODUCTION_COMPLETED = "introduction_completed";

    private final Context appContext;

    public OnboardingPreferences(Context context) {
        appContext = Objects.requireNonNull(context, "context").getApplicationContext();
    }

    public boolean isIntroductionCompleted() {
        AppExecutors.requireWorkerThread();
        return preferences().getBoolean(INTRODUCTION_COMPLETED, false);
    }

    public boolean completeIntroduction() {
        AppExecutors.requireWorkerThread();
        SharedPreferences preferences = preferences();
        boolean previouslyCompleted = Boolean.TRUE.equals(
                preferences.getAll().get(INTRODUCTION_COMPLETED)
        );

        try {
            boolean committed = preferences.edit()
                    .putBoolean(INTRODUCTION_COMPLETED, true)
                    .commit();
            if (!committed) {
                restoreAfterFailure(preferences, previouslyCompleted);
            }
            return committed;
        } catch (RuntimeException failure) {
            restoreAfterFailure(preferences, previouslyCompleted);
            throw failure;
        }
    }

    private SharedPreferences preferences() {
        return appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @SuppressLint("ApplySharedPref") // Synchronous rollback runs only on the I/O executor.
    private static void restoreAfterFailure(SharedPreferences preferences, boolean previous) {
        // commit() changes the in-memory map before its disk result is known.
        // Restore that map so a failed save cannot make a later local read skip the introduction.
        try {
            preferences.edit().putBoolean(INTRODUCTION_COMPLETED, previous).commit();
        } catch (RuntimeException ignored) {
            // The original operation still reports failure; no navigation is authorized.
        }
    }
}
