package wlv.ac.uk.inknote.ui.welcome;

import android.os.Bundle;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;

import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.databinding.FragmentWelcomeBinding;
import wlv.ac.uk.inknote.ui.library.LibraryFragment;

public final class WelcomeFragment extends Fragment {

    private FragmentWelcomeBinding binding;
    private Snackbar destinationNotice;
    private WelcomeViewModel viewModel;
    private WelcomeMotion motion;
    private boolean entrancePlayed;

    @NonNull
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentWelcomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SpannableString brand = new SpannableString(getString(R.string.welcome_brand));
        int accentStart = brand.toString().startsWith("Ink") ? 3 : -1;
        if (accentStart >= 0) {
            brand.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.inknest_primary)),
                    accentStart,
                    brand.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        binding.brandName.setText(brand);

        entrancePlayed = savedInstanceState != null && savedInstanceState.getBoolean("welcome_entrance_played");
        motion = new WelcomeMotion(binding);
        viewModel = new ViewModelProvider(this).get(WelcomeViewModel.class);
        binding.startWriting.setOnClickListener(clicked -> viewModel.startWriting());
        binding.skipIntroduction.setOnClickListener(clicked -> viewModel.skipIntroduction());
        viewModel.getSaving().observe(getViewLifecycleOwner(), saving -> {
            boolean busy = Boolean.TRUE.equals(saving);
            binding.startWriting.setEnabled(!busy);
            binding.skipIntroduction.setEnabled(!busy);
            binding.skipIntroduction.setText(busy ? R.string.welcome_opening_library : R.string.welcome_skip);
        });
        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error == null) return;
            showNotice(error);
            viewModel.errorHandled();
        });
        viewModel.getPendingDestination().observe(getViewLifecycleOwner(), this::handleDestination);
    }

    private void handleDestination(WelcomeViewModel.Destination destination) {
        if (binding == null || destination == null || getParentFragmentManager().isStateSaved()) return;
        if (destination == WelcomeViewModel.Destination.POSTS) {
            androidx.fragment.app.FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
            AccessibilityManager accessibility = (AccessibilityManager) requireContext()
                    .getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (ValueAnimator.areAnimatorsEnabled()
                    && (accessibility == null || !accessibility.isTouchExplorationEnabled())) {
                transaction.setCustomAnimations(R.anim.welcome_library_enter, R.anim.welcome_library_exit);
            }
            transaction.setReorderingAllowed(true)
                    .replace(R.id.fragment_container, LibraryFragment.class, null)
                    .commit();
            viewModel.destinationHandled();
        } else {
            // The editor remains pending while supplied screens are implemented individually.
            showNotice(getString(R.string.welcome_editor_pending));
            viewModel.destinationHandled();
        }
    }

    private void showNotice(String message) {
        if (destinationNotice != null) destinationNotice.dismiss();
        destinationNotice = Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.welcome_dismiss, clicked -> { });
        TextView text = destinationNotice.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        text.setMaxLines(Integer.MAX_VALUE);
        destinationNotice.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (motion != null) {
            motion.start(!entrancePlayed);
            entrancePlayed = true;
        }
        if (viewModel != null) handleDestination(viewModel.getPendingDestination().getValue());
    }

    @Override
    public void onPause() {
        if (motion != null) motion.stop();
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean("welcome_entrance_played", entrancePlayed);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        if (motion != null) {
            motion.stop();
            motion = null;
        }
        if (destinationNotice != null) {
            destinationNotice.dismiss();
            destinationNotice = null;
        }
        binding = null;
        super.onDestroyView();
    }
}
