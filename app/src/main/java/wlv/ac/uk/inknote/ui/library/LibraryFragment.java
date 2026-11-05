package wlv.ac.uk.inknote.ui.library;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import wlv.ac.uk.inknote.InkNoteApplication;
import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.databinding.FragmentLibraryBinding;
import wlv.ac.uk.inknote.databinding.ItemLibraryTabBinding;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.ui.editor.EditorFragment;
import wlv.ac.uk.inknote.ui.editor.EditorNavigation;

public final class LibraryFragment extends Fragment implements LibraryAdapter.Actions {
    private FragmentLibraryBinding binding;
    private LibraryViewModel viewModel;
    private LibraryAdapter adapter;
    private LocalThumbnailLoader images;
    private AlertDialog activeDialog;
    private PopupMenu popup;
    private Snackbar notice;
    private ItemLibraryTabBinding[] tabs;

    @NonNull @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLibraryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(LibraryViewModel.class);
        SpannableString brand = new SpannableString(getString(R.string.welcome_brand));
        int accent = brand.toString().startsWith("Ink") ? 3 : -1;
        if (accent >= 0) brand.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(),
                R.color.inknest_primary)), accent, brand.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.brandName.setText(brand);

        images = new LocalThumbnailLoader(requireContext(),
                ((InkNoteApplication) requireActivity().getApplication()).getContainer().getExecutors());
        adapter = new LibraryAdapter(this, images);
        binding.messages.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.messages.setAdapter(adapter);
        binding.messages.setItemAnimator(null);
        binding.messages.setContentDescription(getString(R.string.library_title));
        // MainActivity already applies system-bar insets to the Fragment container.
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation, (navigation, insets) -> insets);
        binding.bottomNavigation.setPadding(0, 0, 0, 0);
        binding.libraryAppBar.addOnOffsetChangedListener(
                (com.google.android.material.appbar.AppBarLayout.OnOffsetChangedListener) (appBar, offset) -> {
                    if (binding == null) return;
                    int visibleHeader = Math.max(0, appBar.getHeight() + offset);
                    // ScrollingViewBehavior measures behind the collapsible header. Keep the
                    // empty/error state inside the currently visible portion of the feed.
                    binding.statePanel.setPadding(0, 0, 0, visibleHeader);
                });

        getParentFragmentManager().setFragmentResultListener(EditorFragment.RESULT_CHANGED, getViewLifecycleOwner(),
                (key, result) -> viewModel.refresh());
        setupTabs();
        binding.search.setText(viewModel.getQuery());
        binding.search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                viewModel.setQuery(text.toString());
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        binding.search.setOnEditorActionListener((text, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            viewModel.refresh();
            hideKeyboard();
            return true;
        });
        binding.offlineBanner.setVisibility(viewModel.isBannerVisible() ? View.VISIBLE : View.GONE);
        binding.dismissBanner.setOnClickListener(clicked -> {
            viewModel.dismissBanner();
            binding.offlineBanner.setVisibility(View.GONE);
        });
        binding.filterOptions.setOnClickListener(clicked -> showFilters());
        binding.sort.setOnClickListener(clicked -> sort());
        binding.profile.setOnClickListener(clicked -> showNotice(getString(R.string.library_profile_pending)));
        binding.newMessage.setOnClickListener(clicked -> { hideKeyboard(); EditorNavigation.open(this, 0); });
        binding.retry.setOnClickListener(clicked -> viewModel.refresh());
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_library) return true;
            showNotice(getString(item.getItemId() == R.id.nav_uploads
                    ? R.string.library_uploads_pending : R.string.library_settings_pending));
            return false;
        });
        ViewCompat.setTooltipText(binding.newMessage, getString(R.string.library_new_message));
        ViewCompat.setTooltipText(binding.profile, getString(R.string.library_profile));
        ViewCompat.setTooltipText(binding.filterOptions, getString(R.string.library_filter_options));

        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getActionError().observe(getViewLifecycleOwner(), error -> {
            if (error == null) return;
            showNotice(error);
            viewModel.errorHandled();
        });
        viewModel.getNotice().observe(getViewLifecycleOwner(), result -> {
            if (result == null) return;
            int message;
            switch (result) {
                case FAVOURITE_ADDED: message = R.string.library_favourite_added; break;
                case FAVOURITE_REMOVED: message = R.string.library_favourite_removed; break;
                case DRAFT_SAVED: message = R.string.library_draft_saved; break;
                case MARKED_SAVED: message = R.string.library_marked_saved; break;
                default: message = R.string.library_deleted;
            }
            showNotice(getString(message));
            viewModel.noticeHandled();
        });
        viewModel.getShare().observe(getViewLifecycleOwner(), post -> {
            if (post == null) return;
            viewModel.shareHandled();
            boolean hasTitle = LibraryContent.wordCount(post.getTitle()) > 0;
            Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, hasTitle ? post.getTitle() : getString(R.string.library_untitled))
                    .putExtra(Intent.EXTRA_TEXT, hasTitle ? post.getTitle() + "\n\n" + post.getBody() : post.getBody());
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.library_share_text)));
            } catch (ActivityNotFoundException | SecurityException ignored) {
                showNotice(getString(R.string.library_share_failed));
            }
            // Opening a Sharesheet does not prove the user sent or published anything.
        });
    }

    private void setupTabs() {
        int[] labels = { R.string.library_all, R.string.library_drafts, R.string.library_favourites };
        tabs = new ItemLibraryTabBinding[labels.length];
        for (int index = 0; index < labels.length; index++) {
            tabs[index] = ItemLibraryTabBinding.inflate(getLayoutInflater());
            tabs[index].tabLabel.setText(labels[index]);
            binding.filterTabs.addTab(binding.filterTabs.newTab().setCustomView(tabs[index].getRoot()), false);
        }
        binding.filterTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                LibraryContent.Filter filter = LibraryContent.Filter.values()[tab.getPosition()];
                if (viewModel.getFilter() != filter) viewModel.setFilter(filter);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    private void render(LibraryViewModel.State state) {
        if (binding == null || state == null) return;
        int[] counts = {state.allCount, state.draftCount, state.favouriteCount};
        for (int index = 0; index < counts.length; index++) {
            tabs[index].tabCount.setText(String.valueOf(counts[index]));
            boolean selected = state.filter.ordinal() == index;
            tabs[index].tabLabel.setTextColor(ContextCompat.getColor(requireContext(),
                    selected ? R.color.inknest_primary : R.color.inknest_muted_foreground));
            tabs[index].tabLabel.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            TabLayout.Tab tab = binding.filterTabs.getTabAt(index);
            if (tab != null) {
                tab.setContentDescription(getString(R.string.library_tab_description,
                        tabs[index].tabLabel.getText(), counts[index]));
                if (selected && !tab.isSelected()) tab.select();
            }
        }
        binding.sort.setContentDescription(getString(R.string.library_sort_title) + ", " + sortLabels()[state.sort.ordinal()]);
        adapter.show(state.posts, state.filter);
        boolean showState = state.loading || state.error != null || state.posts.isEmpty();
        binding.statePanel.setVisibility(showState ? View.VISIBLE : View.GONE);
        binding.messages.setVisibility(showState ? View.INVISIBLE : View.VISIBLE);
        binding.loading.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.retry.setVisibility(state.error != null ? View.VISIBLE : View.GONE);
        if (state.loading) {
            binding.stateTitle.setText(R.string.library_loading);
            binding.stateDescription.setText("");
        } else if (state.error != null) {
            binding.stateTitle.setText(R.string.library_error_title);
            binding.stateDescription.setText(state.error);
        } else if (state.posts.isEmpty()) {
            boolean filtered = !viewModel.getQuery().isEmpty() || state.filter != LibraryContent.Filter.ALL;
            binding.stateTitle.setText(filtered ? R.string.library_empty_filtered_title : R.string.library_empty_title);
            binding.stateDescription.setText(filtered ? R.string.library_empty_filtered_body : R.string.library_empty_body);
        }
    }

    private String[] sortLabels() {
        return new String[]{ getString(R.string.library_sort_newest), getString(R.string.library_sort_oldest),
                getString(R.string.library_sort_title_order) };
    }

    private void showFilters() {
        hideKeyboard();
        activeDialog = new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.library_filter_options)
                .setSingleChoiceItems(new String[]{getString(R.string.library_all), getString(R.string.library_drafts),
                        getString(R.string.library_favourites)}, viewModel.getFilter().ordinal(), (dialog, which) -> {
                    viewModel.setFilter(LibraryContent.Filter.values()[which]);
                    dialog.dismiss();
                }).setNegativeButton(R.string.library_cancel, null).show();
    }

    @Override public void sort() {
        hideKeyboard();
        activeDialog = new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.library_sort_title)
                .setSingleChoiceItems(sortLabels(), viewModel.getSort().ordinal(), (dialog, which) -> {
                    viewModel.setSort(LibraryContent.Sort.values()[which]);
                    dialog.dismiss();
                }).setNegativeButton(R.string.library_cancel, null).show();
    }

    @Override public void open(BlogPost post) {
        if (post.isDraft()) { hideKeyboard(); EditorNavigation.open(this, post.getId()); }
        else showNotice(getString(R.string.library_detail_pending));
    }
    @Override public void favourite(BlogPost post) { viewModel.setFavourite(post); }
    @Override public void seeAllFavourites() { viewModel.setFilter(LibraryContent.Filter.FAVOURITES); }

    @Override public void more(View anchor, BlogPost post) {
        if (popup != null) popup.dismiss();
        popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, 1, 0, post.isDraft() ? R.string.library_mark_saved : R.string.library_mark_draft);
        popup.getMenu().add(0, 2, 1, R.string.library_share_text);
        popup.getMenu().add(0, 3, 2, R.string.library_delete);
        popup.getMenu().add(0, 4, 3, R.string.editor_edit_action);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) viewModel.setDraft(post);
            else if (item.getItemId() == 2) viewModel.shareText(post.getId());
            else if (item.getItemId() == 4) { hideKeyboard(); EditorNavigation.open(this, post.getId()); }
            else confirmDelete(post);
            return true;
        });
        popup.show();
    }

    private void confirmDelete(BlogPost post) {
        activeDialog = new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.library_delete_title)
                .setMessage(R.string.library_delete_body)
                .setNegativeButton(R.string.library_cancel, null)
                .setPositiveButton(R.string.library_delete, (dialog, which) -> viewModel.delete(post.getId())).show();
    }

    private void showNotice(String message) {
        if (binding == null) return;
        if (notice != null) notice.dismiss();
        notice = Snackbar.make(binding.contentCoordinator, message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.newMessage)
                .setAction(R.string.welcome_dismiss, clicked -> { });
        TextView text = notice.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        text.setMaxLines(Integer.MAX_VALUE);
        notice.show();
    }

    private void hideKeyboard() {
        if (binding == null) return;
        WindowCompat.getInsetsController(requireActivity().getWindow(), binding.getRoot())
                .hide(WindowInsetsCompat.Type.ime());
        binding.search.clearFocus();
    }

    @Override public void onDestroyView() {
        if (activeDialog != null) activeDialog.dismiss();
        activeDialog = null;
        if (popup != null) popup.dismiss();
        popup = null;
        if (notice != null) notice.dismiss();
        notice = null;
        binding.messages.setAdapter(null);
        adapter = null;
        images.close();
        images = null;
        tabs = null;
        binding = null;
        super.onDestroyView();
    }
}
