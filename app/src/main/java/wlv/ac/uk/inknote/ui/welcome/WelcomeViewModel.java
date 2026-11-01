package wlv.ac.uk.inknote.ui.welcome;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/** Welcome has no editable data and does not create posts or open the database. */
public final class WelcomeViewModel extends ViewModel {

    public enum Destination {
        EDITOR,
        POSTS
    }

    private final MutableLiveData<Destination> pendingDestination = new MutableLiveData<>();

    public LiveData<Destination> getPendingDestination() {
        return pendingDestination;
    }

    public void startWriting() {
        pendingDestination.setValue(Destination.EDITOR);
    }

    public void skipIntroduction() {
        pendingDestination.setValue(Destination.POSTS);
    }

    public void destinationHandled() {
        // Consumed on the main thread so recreating the view cannot replay a handled action.
        pendingDestination.setValue(null);
    }
}
