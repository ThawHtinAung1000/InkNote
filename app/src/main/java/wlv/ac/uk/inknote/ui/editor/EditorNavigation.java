package wlv.ac.uk.inknote.ui.editor;

import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.ui.library.LibraryFragment;

/** Uses the existing Fragment host; only implemented destinations are referenced. */
public final class EditorNavigation {
    private EditorNavigation() { }

    public static void open(Fragment source, long postId) {
        FragmentManager manager = source.getParentFragmentManager();
        if (manager.isStateSaved() || manager.isDestroyed()) return;
        Bundle arguments = new Bundle();
        arguments.putLong(EditorViewModel.ARG_POST_ID, postId);
        manager.beginTransaction().setReorderingAllowed(true)
                .replace(R.id.fragment_container, EditorFragment.class, arguments)
                .addToBackStack("editor")
                .commit();
    }

    public static void openFromWelcome(Fragment source) {
        FragmentManager manager = source.getParentFragmentManager();
        if (manager.isStateSaved() || manager.isDestroyed()) return;
        // Queue the library first so Back from the first editor returns to an implemented home.
        manager.beginTransaction().setReorderingAllowed(true)
                .replace(R.id.fragment_container, LibraryFragment.class, null).commit();
        open(source, 0);
    }
}
