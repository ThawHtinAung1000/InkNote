package wlv.ac.uk.inknote.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;

import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.databinding.ActivityMainBinding;
import wlv.ac.uk.inknote.ui.library.LibraryFragment;
import wlv.ac.uk.inknote.ui.welcome.WelcomeFragment;

public final class MainActivity extends AppCompatActivity {

    private MainViewModel startupViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WindowCompat.getInsetsController(getWindow(), binding.getRoot())
                .setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), binding.getRoot())
                .setAppearanceLightNavigationBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());

        // FragmentManager restores both configuration and process-death state.
        // A restored screen must never be replaced with a newly calculated startup route.
        if (getSupportFragmentManager().findFragmentById(R.id.fragment_container) == null) {
            startupViewModel = new ViewModelProvider(this).get(MainViewModel.class);
            holdNativeSplashUntilRouted(binding.getRoot(), startupViewModel);
            startupViewModel.getStartupDestination().observe(this, destination -> {
                showInitialScreenIfNeeded();
                showStartupErrorIfNeeded();
            });
            startupViewModel.getError().observe(this, error -> showStartupErrorIfNeeded());
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        // A result delivered after state saving is retried when transactions are safe again.
        showInitialScreenIfNeeded();
        showStartupErrorIfNeeded();
    }

    private void showInitialScreenIfNeeded() {
        if (startupViewModel == null || isFinishing()
                || getSupportFragmentManager().isStateSaved()
                || getSupportFragmentManager().findFragmentById(R.id.fragment_container) != null) {
            return;
        }
        MainViewModel.Destination destination = startupViewModel.getStartupDestination().getValue();
        if (destination == null) {
            return;
        }
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(
                        R.id.fragment_container,
                        destination == MainViewModel.Destination.LIBRARY
                                ? LibraryFragment.class : WelcomeFragment.class,
                        null
                )
                .commitNow();
    }

    private void showStartupErrorIfNeeded() {
        if (startupViewModel == null || isFinishing()
                || getSupportFragmentManager().isStateSaved()
                || getSupportFragmentManager().findFragmentById(R.id.fragment_container) == null) {
            return;
        }
        String message = startupViewModel.getError().getValue();
        if (message == null) {
            return;
        }
        Snackbar notice = Snackbar.make(
                findViewById(R.id.fragment_container), message, Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.welcome_dismiss, clicked -> { });
        TextView noticeText = notice.getView().findViewById(
                com.google.android.material.R.id.snackbar_text
        );
        noticeText.setMaxLines(Integer.MAX_VALUE);
        notice.show();
        startupViewModel.errorHandled();
    }

    private void holdNativeSplashUntilRouted(View root, MainViewModel viewModel) {
        // API 31+ keeps its native launch splash until the first application frame.
        // Delaying pre-draw avoids displaying Welcome before an asynchronous preference read.
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                boolean ready = viewModel.isRoutingComplete()
                        || getSupportFragmentManager().findFragmentById(R.id.fragment_container) != null;
                if (ready && root.getViewTreeObserver().isAlive()) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                return ready;
            }
        });
    }
}
