package wlv.ac.uk.inknote.ui.welcome;

import android.os.Bundle;
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

public final class WelcomeFragment extends Fragment {

    private FragmentWelcomeBinding binding;
    private Snackbar destinationNotice;

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
        int accentStart = brand.toString().indexOf("Nest");
        if (accentStart >= 0) {
            brand.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.inknest_primary)),
                    accentStart,
                    brand.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        binding.brandName.setText(brand);

        WelcomeViewModel viewModel = new ViewModelProvider(this).get(WelcomeViewModel.class);
        binding.startWriting.setOnClickListener(clicked -> viewModel.startWriting());
        binding.skipIntroduction.setOnClickListener(clicked -> viewModel.skipIntroduction());
        viewModel.getPendingDestination().observe(getViewLifecycleOwner(), destination -> {
            if (destination == null) {
                return;
            }

            // Screen designs arrive individually. Do not launch a missing destination or
            // persist onboarding completion until the corresponding screen is implemented.
            if (destinationNotice != null) {
                destinationNotice.dismiss();
            }
            int message = destination == WelcomeViewModel.Destination.EDITOR
                    ? R.string.welcome_editor_pending
                    : R.string.welcome_posts_pending;
            destinationNotice = Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.welcome_dismiss, clicked -> { });
            TextView noticeText = destinationNotice.getView().findViewById(
                    com.google.android.material.R.id.snackbar_text);
            noticeText.setMaxLines(Integer.MAX_VALUE);
            destinationNotice.show();
            viewModel.destinationHandled();
        });
    }

    @Override
    public void onDestroyView() {
        if (destinationNotice != null) {
            destinationNotice.dismiss();
            destinationNotice = null;
        }
        binding = null;
        super.onDestroyView();
    }
}
