package wlv.ac.uk.inknote;

import android.app.Application;

public final class InkNoteApplication extends Application {

    private AppContainer container;

    @Override
    public void onCreate() {
        super.onCreate();

        container = new AppContainer(this);
    }

    public AppContainer getContainer() {
        return container;
    }
}
