package wlv.ac.uk.inknote.services;

import android.os.SystemClock;

import java.util.Objects;

import wlv.ac.uk.inknote.data.local.OnboardingPreferences;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.AppLogger;
import wlv.ac.uk.inknote.utils.ServiceResult;

/** Persists onboarding independently of post content and the SQLite schema. */
public final class OnboardingService {

    private final OnboardingPreferences preferences;

    public OnboardingService(OnboardingPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public ServiceResult<Boolean> isIntroductionCompleted() {
        AppExecutors.requireWorkerThread();
        long startedAt = SystemClock.elapsedRealtime();
        try {
            boolean completed = preferences.isIntroductionCompleted();
            AppLogger.success(
                    AppLogger.Operation.READ_ONBOARDING,
                    SystemClock.elapsedRealtime() - startedAt
            );
            return ServiceResult.success(completed);
        } catch (RuntimeException failure) {
            AppLogger.failure(AppLogger.Operation.READ_ONBOARDING, failure);
            return ServiceResult.failure(
                    ServiceResult.ErrorCode.FILE_SYSTEM,
                    "The introduction preference could not be loaded. You can continue here.",
                    failure
            );
        }
    }

    public ServiceResult<Boolean> completeIntroduction() {
        AppExecutors.requireWorkerThread();
        long startedAt = SystemClock.elapsedRealtime();
        try {
            if (!preferences.completeIntroduction()) {
                AppLogger.rejected(
                        AppLogger.Operation.COMPLETE_ONBOARDING,
                        ServiceResult.ErrorCode.FILE_SYSTEM
                );
                return ServiceResult.failure(
                        ServiceResult.ErrorCode.FILE_SYSTEM,
                        "The introduction could not be saved. Please try again.",
                        null
                );
            }
            AppLogger.success(
                    AppLogger.Operation.COMPLETE_ONBOARDING,
                    SystemClock.elapsedRealtime() - startedAt
            );
            return ServiceResult.success(true);
        } catch (RuntimeException failure) {
            AppLogger.failure(AppLogger.Operation.COMPLETE_ONBOARDING, failure);
            return ServiceResult.failure(
                    ServiceResult.ErrorCode.FILE_SYSTEM,
                    "The introduction could not be saved. Please try again.",
                    failure
            );
        }
    }
}
