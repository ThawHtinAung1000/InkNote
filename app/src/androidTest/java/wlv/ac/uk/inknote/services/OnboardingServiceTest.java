package wlv.ac.uk.inknote.services;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import wlv.ac.uk.inknote.data.local.OnboardingPreferences;
import wlv.ac.uk.inknote.utils.ServiceResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Uses only uniquely named test preferences; never reads or changes real introduction state. */
@RunWith(AndroidJUnit4.class)
public final class OnboardingServiceTest {
    private Context targetContext;
    private String prefix;
    private final Set<String> preferenceNames = new HashSet<>();

    @Before public void setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "onboarding-test-" + UUID.randomUUID() + "-";
    }

    @After public void tearDown() {
        for (String name : preferenceNames) {
            assertTrue(targetContext.deleteSharedPreferences(name));
        }
    }

    @Test
    public void firstLaunchDefaultsToIncompleteAndCompletionSurvivesNewServiceInstance() {
        Context isolated = new IsolatedPreferencesContext(targetContext);
        OnboardingService first = new OnboardingService(new OnboardingPreferences(isolated));

        ServiceResult<Boolean> initial = first.isIntroductionCompleted();
        assertTrue(initial.isSuccess());
        assertFalse(initial.getData());

        ServiceResult<Boolean> completed = first.completeIntroduction();
        assertTrue(completed.isSuccess());
        assertTrue(completed.getData());

        OnboardingService reopened = new OnboardingService(new OnboardingPreferences(
                new IsolatedPreferencesContext(targetContext)));
        ServiceResult<Boolean> restored = reopened.isIntroductionCompleted();
        assertTrue(restored.isSuccess());
        assertTrue(restored.getData());
        assertEquals(1, preferenceNames.size());
    }

    @Test
    public void failedCommitReportsFailureAndDoesNotLeaveAnInMemoryCompletion() {
        FailingPreferences preferences = new FailingPreferences();
        Context isolated = new ContextWrapper(targetContext) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences;
            }
        };
        OnboardingService service = new OnboardingService(new OnboardingPreferences(isolated));

        ServiceResult<Boolean> result = service.completeIntroduction();

        assertFalse(result.isSuccess());
        assertEquals(ServiceResult.ErrorCode.FILE_SYSTEM, result.getFailure().getCode());
        ServiceResult<Boolean> afterFailure = service.isIntroductionCompleted();
        assertTrue(afterFailure.isSuccess());
        assertFalse(afterFailure.getData());
    }

    private final class IsolatedPreferencesContext extends ContextWrapper {
        IsolatedPreferencesContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            String isolatedName = prefix + name;
            preferenceNames.add(isolatedName);
            return super.getSharedPreferences(isolatedName, mode);
        }
    }

    /**
     * Models Android's important commit-failure behavior: the memory map can change
     * even when commit returns false. There is no disk or application preference access.
     */
    private static final class FailingPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            return values.containsKey(key) ? new HashSet<>((Set<String>) values.get(key)) : fallback;
        }
        @Override public int getInt(String key, int fallback) {
            return values.containsKey(key) ? (Integer) values.get(key) : fallback;
        }
        @Override public long getLong(String key, long fallback) {
            return values.containsKey(key) ? (Long) values.get(key) : fallback;
        }
        @Override public float getFloat(String key, float fallback) {
            return values.containsKey(key) ? (Float) values.get(key) : fallback;
        }
        @Override public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() { return new FailingEditor(); }

        private final class FailingEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override public Editor putString(String key, String value) {
                if (value == null) return remove(key);
                pending.put(key, value);
                return this;
            }
            @Override public Editor putStringSet(String key, Set<String> value) {
                if (value == null) return remove(key);
                pending.put(key, new HashSet<>(value));
                return this;
            }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { removals.add(key); pending.remove(key); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(pending);
                return false;
            }
            @Override public void apply() {
                throw new UnsupportedOperationException("This fixture requires checked synchronous commits.");
            }
        }
    }
}
