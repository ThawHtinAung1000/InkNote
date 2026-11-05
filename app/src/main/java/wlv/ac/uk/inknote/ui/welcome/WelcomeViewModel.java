package wlv.ac.uk.inknote.ui.welcome;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import wlv.ac.uk.inknote.AppContainer;
import wlv.ac.uk.inknote.InkNoteApplication;
import wlv.ac.uk.inknote.utils.ServiceResult;

/** Keeps introduction persistence off the main thread and survives view recreation. */
public final class WelcomeViewModel extends AndroidViewModel {

    public enum Destination {
        EDITOR,
        POSTS
    }

    private final AppContainer container;
    private final MutableLiveData<Destination> pendingDestination = new MutableLiveData<>();
    private final MutableLiveData<Boolean> saving = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private long requestGeneration;
    private boolean cleared;

    public WelcomeViewModel(@NonNull Application application) {
        super(application);
        container = ((InkNoteApplication) application).getContainer();
    }

    public LiveData<Destination> getPendingDestination() {
        return pendingDestination;
    }

    public LiveData<Boolean> getSaving() {
        return saving;
    }

    public LiveData<String> getError() {
        return error;
    }

    public void startWriting() {
        completeIntroduction(Destination.EDITOR);
    }

    public void skipIntroduction() {
        completeIntroduction(Destination.POSTS);
    }

    private void completeIntroduction(Destination destination) {
        if (Boolean.TRUE.equals(saving.getValue())) {
            return;
        }
        long generation = ++requestGeneration;
        error.setValue(null);
        saving.setValue(true);
        try {
            container.getExecutors().io().execute(() -> {
                ServiceResult<Boolean> result = container.getOnboardingService()
                        .completeIntroduction();
                container.getExecutors().main().execute(() -> {
                    if (cleared || generation != requestGeneration) {
                        return;
                    }
                    saving.setValue(false);
                    if (result.isSuccess()) {
                        pendingDestination.setValue(destination);
                    } else {
                        error.setValue(result.getFailure().getMessage());
                    }
                });
            });
        } catch (RuntimeException failure) {
            saving.setValue(false);
            error.setValue("The introduction could not be saved. Please try again.");
        }
    }

    public void destinationHandled() {
        pendingDestination.setValue(null);
    }

    public void errorHandled() {
        error.setValue(null);
    }

    @Override
    protected void onCleared() {
        cleared = true;
        requestGeneration++;
        super.onCleared();
    }
}
