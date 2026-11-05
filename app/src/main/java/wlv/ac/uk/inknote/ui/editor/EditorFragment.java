package wlv.ac.uk.inknote.ui.editor;

import android.content.ActivityNotFoundException;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import wlv.ac.uk.inknote.InkNoteApplication;
import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.databinding.FragmentEditorBinding;
import wlv.ac.uk.inknote.ui.library.LibraryContent;
import wlv.ac.uk.inknote.ui.library.LibraryFragment;
import wlv.ac.uk.inknote.ui.library.LocalThumbnailLoader;
import wlv.ac.uk.inknote.utils.InputValidator;

public final class EditorFragment extends Fragment {
    public static final String RESULT_CHANGED = "editor_changed";
    private FragmentEditorBinding binding;
    private EditorViewModel viewModel;
    private EditorFormatting formatting;
    private LocalThumbnailLoader images;
    private LocalThumbnailLoader.Request imageRequest;
    private AlertDialog tagDialog;
    private Snackbar notice;
    private boolean rendering;
    private String boundFormat, boundPhoto;
    private List<String> boundTags = Collections.emptyList();
    private int restoredStart = -1, restoredEnd = -1, restoredTitleStart = -1;
    private boolean restoreBodyFocus, restoreTitleFocus, selectionRestored;

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null && viewModel != null) viewModel.attachPhoto(uri);
            });

    public static EditorFragment newInstance(long postId) {
        EditorFragment fragment = new EditorFragment();
        Bundle arguments = new Bundle();
        arguments.putLong(EditorViewModel.ARG_POST_ID, postId);
        fragment.setArguments(arguments);
        return fragment;
    }

    @NonNull @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentEditorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(EditorViewModel.class);
        images = new LocalThumbnailLoader(requireContext(),
                ((InkNoteApplication) requireActivity().getApplication()).getContainer().getExecutors());
        if (savedInstanceState != null) {
            restoredStart = savedInstanceState.getInt("editor_selection_start", -1);
            restoredEnd = savedInstanceState.getInt("editor_selection_end", -1);
            restoredTitleStart = savedInstanceState.getInt("editor_title_selection", -1);
            restoreBodyFocus = savedInstanceState.getBoolean("editor_body_focus");
            restoreTitleFocus = savedInstanceState.getBoolean("editor_title_focus");
        }
        binding.titleInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(InputValidator.MAX_TITLE_LENGTH)});
        binding.bodyInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(InputValidator.MAX_BODY_LENGTH)});
        formatting = new EditorFormatting(binding.bodyInput, this::pullDocument);
        binding.bodyInput.setOnSelectionChangedListener(() -> {
            if (rendering || formatting == null) return;
            formatting.onSelectionChanged();
            refreshFormatting();
        });
        binding.titleInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { pullDocument(); }
        });
        binding.bodyInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!rendering) formatting.onTextInserted(start, count);
            }
            @Override public void afterTextChanged(Editable text) {
                pullDocument();
                refreshFormatting();
            }
        });

        binding.back.setOnClickListener(clicked -> requestExit());
        binding.save.setOnClickListener(clicked -> { pullDocument(); viewModel.save(); });
        binding.retrySave.setOnClickListener(clicked -> { pullDocument(); viewModel.retry(); });
        binding.cancelPhoto.setOnClickListener(clicked -> viewModel.cancelPendingPhoto());
        binding.addPhoto.setOnClickListener(clicked -> {
            pullDocument();
            hideKeyboard();
            try {
                photoPicker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
            } catch (ActivityNotFoundException | SecurityException failure) {
                showNotice(getString(R.string.editor_photo_picker_error));
            }
        });
        binding.removePhoto.setOnClickListener(clicked -> { pullDocument(); viewModel.removePhoto(); });
        binding.addTag.setOnClickListener(clicked -> showAddTag());
        binding.formatBold.setOnClickListener(clicked -> { formatting.toggleBold(); refreshFormatting(); });
        binding.formatItalic.setOnClickListener(clicked -> { formatting.toggleItalic(); refreshFormatting(); });
        binding.formatBullet.setOnClickListener(clicked -> { formatting.toggleBullets(); refreshFormatting(); });
        binding.formatQuote.setOnClickListener(clicked -> { formatting.toggleQuote(); refreshFormatting(); });
        binding.hideKeyboard.setOnClickListener(clicked -> hideKeyboard());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (root, insets) -> {
            if (binding != null) binding.hideKeyboard.setVisibility(
                    insets.isVisible(WindowInsetsCompat.Type.ime()) ? View.VISIBLE : View.GONE);
            // MainActivity owns the system/IME padding. Do not apply it a second time here.
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
        installTooltips();
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (binding == null) return;
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(binding.getRoot());
                if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) hideKeyboard();
                else requestExit();
            }
        });
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getExit().observe(getViewLifecycleOwner(), requested -> {
            if (Boolean.TRUE.equals(requested)) finishEditor();
        });
    }

    private void pullDocument() {
        if (rendering || binding == null || viewModel == null) return;
        String format = RichTextCodec.encode(binding.bodyInput.getText());
        boundFormat = format;
        viewModel.setDocument(binding.titleInput.getText() == null ? "" : binding.titleInput.getText().toString(),
                binding.bodyInput.getText() == null ? "" : binding.bodyInput.getText().toString(), format);
    }

    private void render(EditorViewModel.State state) {
        if (binding == null || state == null) return;
        rendering = true;
        try {
            String title = binding.titleInput.getText() == null ? "" : binding.titleInput.getText().toString();
            if (!title.equals(state.title)) replaceInput(binding.titleInput, state.title);
            String body = binding.bodyInput.getText() == null ? "" : binding.bodyInput.getText().toString();
            if (!body.equals(state.body) || !Objects.equals(boundFormat, state.bodyFormat)) {
                int selection = Math.max(0, binding.bodyInput.getSelectionStart());
                replaceInput(binding.bodyInput, RichTextCodec.decode(state.body, state.bodyFormat));
                formatting.onDocumentRestored();
                binding.bodyInput.setSelection(Math.min(selection, state.body.length()));
                boundFormat = state.bodyFormat;
            }
        } finally { rendering = false; }
        boolean editing = !state.loading && !state.loadFailed && !state.exiting;
        binding.titleInput.setEnabled(editing);
        binding.bodyInput.setEnabled(editing);
        binding.addTag.setEnabled(editing);
        binding.addPhoto.setEnabled(editing && !state.photoBusy);
        binding.removePhoto.setEnabled(editing && !state.photoBusy);
        binding.save.setEnabled(!state.loading && !state.photoBusy && !state.exiting);
        binding.formatBold.setEnabled(editing);
        binding.formatItalic.setEnabled(editing);
        binding.formatBullet.setEnabled(editing);
        binding.formatQuote.setEnabled(editing);
        binding.loading.setVisibility(state.loading || state.status == EditorViewModel.Status.SAVING ? View.VISIBLE : View.GONE);
        binding.screenTitle.setText(getArguments() != null && getArguments().getLong(EditorViewModel.ARG_POST_ID) > 0
                ? R.string.editor_edit_message : R.string.editor_new_message);
        int words = LibraryContent.wordCount(state.body);
        binding.wordCount.setText(getResources().getQuantityString(R.plurals.editor_words, words, words));
        binding.readTime.setText(getString(R.string.editor_read_time, words == 0 ? 0 : (words + 199) / 200));
        updateStatus(state);
        renderTags(state.tags, editing);
        renderPhoto(state.photoPath);
        refreshFormatting();
        restoreSelectionIfNeeded(state);
    }

    private static void replaceInput(android.widget.EditText input, CharSequence text) {
        // Legacy messages may predate current limits. Rendering must never truncate them.
        InputFilter[] filters = input.getFilters();
        input.setFilters(new InputFilter[0]);
        try { input.setText(text); }
        finally { input.setFilters(filters); }
    }

    private void updateStatus(EditorViewModel.State state) {
        int text;
        switch (state.status) {
            case LOADING: text = R.string.editor_loading; break;
            case UNSAVED: text = R.string.editor_unsaved_status; break;
            case SAVING: text = state.photoBusy ? R.string.editor_importing_status : R.string.editor_saving_status; break;
            case AUTOSAVED: text = R.string.editor_autosaved_status; break;
            case SAVED: text = R.string.editor_saved_status; break;
            case ERROR: text = R.string.editor_save_failed; break;
            default: text = R.string.editor_empty_status;
        }
        binding.statusText.setText(text);
        boolean saved = state.status == EditorViewModel.Status.SAVED;
        boolean autosaved = state.status == EditorViewModel.Status.AUTOSAVED;
        int statusColor = ContextCompat.getColor(requireContext(), state.error != null
                ? R.color.inknest_destructive_foreground : saved || autosaved
                ? R.color.inknest_success_foreground : R.color.inknest_muted_foreground);
        binding.statusText.setTextColor(statusColor);
        binding.draftBadge.setText(saved ? R.string.editor_saved_badge : autosaved
                ? R.string.editor_autosaved_badge : R.string.editor_unsaved_draft);
        binding.draftBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(),
                saved ? R.color.inknest_success : R.color.inknest_warning)));
        binding.draftBadge.setTextColor(ContextCompat.getColor(requireContext(), saved
                ? R.color.inknest_success_foreground : R.color.inknest_warning_foreground));
        binding.statusTime.setVisibility(state.savedAt > 0 ? View.VISIBLE : View.GONE);
        if (state.savedAt > 0) binding.statusTime.setText(getString(R.string.editor_last_saved,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(state.savedAt)));
        binding.save.setText(state.error != null ? R.string.editor_retry_save : R.string.editor_save);
        boolean banner = state.error != null || saved;
        binding.banner.setVisibility(banner ? View.VISIBLE : View.GONE);
        binding.retrySave.setVisibility(state.error != null ? View.VISIBLE : View.GONE);
        binding.cancelPhoto.setVisibility(state.error != null && state.pendingPhoto && !state.photoBusy ? View.VISIBLE : View.GONE);
        binding.cancelPhoto.setEnabled(state.status != EditorViewModel.Status.SAVING);
        if (state.error != null) {
            binding.bannerTitle.setText(state.loadFailed ? R.string.editor_loading_failed : R.string.editor_save_failed);
            binding.bannerTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.inknest_destructive_foreground));
            binding.banner.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.inknest_destructive)));
            binding.bannerMessage.setText(getString(R.string.editor_failure_body, state.error));
        } else if (saved) {
            binding.bannerTitle.setText(R.string.editor_saved_banner_title);
            binding.bannerTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.inknest_success_foreground));
            binding.banner.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.inknest_success)));
            binding.bannerMessage.setText(R.string.editor_saved_banner_body);
        }
    }

    private void renderTags(List<String> tags, boolean editing) {
        if (!boundTags.equals(tags)) {
            binding.tagsChipGroup.removeAllViews();
            for (String tag : tags) {
                Chip chip = new Chip(requireContext());
                chip.setText(tag);
                chip.setTextSize(13f);
                chip.setCheckable(false);
                chip.setCloseIconResource(R.drawable.ic_library_close);
                chip.setCloseIconVisible(true);
                chip.setCloseIconContentDescription(getString(R.string.editor_remove_tag, tag));
                chip.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.inknest_primary_container)));
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.inknest_on_primary_container));
                chip.setCloseIconTint(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.inknest_on_primary_container)));
                chip.setEnsureMinTouchTargetSize(true);
                chip.setOnCloseIconClickListener(clicked -> viewModel.removeTag(tag));
                binding.tagsChipGroup.addView(chip);
            }
            boundTags = new ArrayList<>(tags);
        }
        for (int index = 0; index < binding.tagsChipGroup.getChildCount(); index++) {
            binding.tagsChipGroup.getChildAt(index).setEnabled(editing);
        }
    }

    private void renderPhoto(String photo) {
        binding.photoSection.setVisibility(photo == null ? View.GONE : View.VISIBLE);
        if (Objects.equals(boundPhoto, photo)) return;
        if (imageRequest != null) imageRequest.cancel();
        boundPhoto = photo;
        if (photo == null) binding.photoPreview.setImageDrawable(null);
        else imageRequest = images.load(binding.photoPreview, photo);
    }

    private void refreshFormatting() {
        if (binding == null || formatting == null) return;
        binding.formatBold.setChecked(formatting.isBold());
        binding.formatItalic.setChecked(formatting.isItalic());
        binding.formatBullet.setChecked(formatting.isBullets());
        binding.formatQuote.setChecked(formatting.isQuote());
    }

    private void showAddTag() {
        hideKeyboard();
        TextInputLayout field = new TextInputLayout(requireContext());
        field.setPadding(dp(24), dp(8), dp(24), 0);
        field.setHint(getString(R.string.editor_tag_hint));
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tagDialog = new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.editor_add_tag)
                .setView(field).setNegativeButton(R.string.library_cancel, null)
                .setPositiveButton(R.string.editor_tag_add, null).create();
        tagDialog.setOnShowListener(dialog -> {
            tagDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(clicked -> {
                String error = viewModel.validateAndAddTag(input.getText() == null ? "" : input.getText().toString());
                field.setError(error);
                if (error == null) tagDialog.dismiss();
            });
            input.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                tagDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            });
        });
        tagDialog.show();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void installTooltips() {
        ViewCompat.setTooltipText(binding.formatBold, getString(R.string.editor_format_bold));
        ViewCompat.setTooltipText(binding.formatItalic, getString(R.string.editor_format_italic));
        ViewCompat.setTooltipText(binding.formatBullet, getString(R.string.editor_format_bullet));
        ViewCompat.setTooltipText(binding.formatQuote, getString(R.string.editor_format_quote));
        ViewCompat.setTooltipText(binding.addPhoto, getString(R.string.editor_add_photo));
        ViewCompat.setTooltipText(binding.hideKeyboard, getString(R.string.editor_hide_keyboard));
    }
    private void requestExit() { pullDocument(); hideKeyboard(); viewModel.saveAndExit(); }
    private void hideKeyboard() {
        if (binding != null) WindowCompat.getInsetsController(requireActivity().getWindow(), binding.getRoot())
                .hide(WindowInsetsCompat.Type.ime());
    }
    private void finishEditor() {
        if (binding == null || getParentFragmentManager().isStateSaved()) return;
        viewModel.exitHandled();
        getParentFragmentManager().setFragmentResult(RESULT_CHANGED, new Bundle());
        if (getParentFragmentManager().getBackStackEntryCount() > 0) getParentFragmentManager().popBackStack();
        else getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, LibraryFragment.class, null).commit();
    }
    private void showNotice(String message) {
        if (notice != null) notice.dismiss();
        notice = Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG);
        TextView text = notice.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        text.setMaxLines(Integer.MAX_VALUE);
        notice.show();
    }

    private void restoreSelectionIfNeeded(EditorViewModel.State state) {
        if (selectionRestored || state.loading || state.loadFailed) return;
        selectionRestored = true;
        binding.bodyInput.post(() -> {
            if (binding == null) return;
            if (restoredStart >= 0) binding.bodyInput.setSelection(Math.min(restoredStart, state.body.length()),
                    Math.min(Math.max(restoredStart, restoredEnd), state.body.length()));
            if (restoredTitleStart >= 0) binding.titleInput.setSelection(Math.min(restoredTitleStart, state.title.length()));
            if (restoreBodyFocus) binding.bodyInput.requestFocus();
            else if (restoreTitleFocus) binding.titleInput.requestFocus();
        });
    }

    @Override public void onResume() {
        super.onResume();
        if (viewModel != null && Boolean.TRUE.equals(viewModel.getExit().getValue())) finishEditor();
    }
    @Override public void onStop() {
        pullDocument();
        if (viewModel != null) viewModel.preserveDraft();
        super.onStop();
    }
    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        if (binding != null) {
            pullDocument();
            outState.putInt("editor_selection_start", binding.bodyInput.getSelectionStart());
            outState.putInt("editor_selection_end", binding.bodyInput.getSelectionEnd());
            outState.putInt("editor_title_selection", binding.titleInput.getSelectionStart());
            outState.putBoolean("editor_body_focus", binding.bodyInput.hasFocus());
            outState.putBoolean("editor_title_focus", binding.titleInput.hasFocus());
        }
        super.onSaveInstanceState(outState);
    }
    @Override public void onDestroyView() {
        if (tagDialog != null) tagDialog.dismiss();
        tagDialog = null;
        if (notice != null) notice.dismiss();
        notice = null;
        binding.bodyInput.setOnSelectionChangedListener(null);
        if (imageRequest != null) imageRequest.cancel();
        imageRequest = null;
        images.close(); images = null;
        formatting = null; boundFormat = null; boundPhoto = null;
        boundTags = Collections.emptyList(); selectionRestored = false;
        binding = null;
        super.onDestroyView();
    }
}
