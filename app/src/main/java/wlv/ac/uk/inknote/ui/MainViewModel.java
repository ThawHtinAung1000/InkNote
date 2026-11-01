package wlv.ac.uk.inknote.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import wlv.ac.uk.inknote.AppContainer;
import wlv.ac.uk.inknote.InkNoteApplication;
import wlv.ac.uk.inknote.utils.ServiceResult;

/** Retains one asynchronous startup route across configuration changes. */
public final class MainViewModel extends AndroidViewModel {

    public enum Destination {
        WELCOME,
        LIBRARY
    }

    private final MutableLiveData<Destination> startupDestination = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private boolean cleared;

    public MainViewModel(@NonNull Application application) {
        super(application);
        AppContainer container = ((InkNoteApplication) application).getContainer();
        try {
            container.getExecutors().io().execute(() -> {
                ServiceResult<Boolean> result = container.getOnboardingService()
                        .isIntroductionCompleted();
                container.getExecutors().main().execute(() -> {
                    if (cleared) {
                        return;
                    }
                    if (result.isSuccess()) {
                        startupDestination.setValue(result.getData()
                                ? Destination.LIBRARY : Destination.WELCOME);
                    } else {
                        startupDestination.setValue(Destination.WELCOME);
                        error.setValue(result.getFailure().getMessage());
                    }
                });
            });
        } catch (RuntimeException failure) {
            startupDestination.setValue(Destination.WELCOME);
            error.setValue("The introduction preference could not be loaded. You can continue here.");
        }
    }

    public LiveData<Destination> getStartupDestination() {
        return startupDestination;
    }

    public boolean isRoutingComplete() {
        return startupDestination.getValue() != null;
    }

    public LiveData<String> getError() {
        return error;
    }

    public void errorHandled() {
        error.setValue(null);
    }

    @Override
    protected void onCleared() {
        cleared = true;
        super.onCleared();
    }
}
