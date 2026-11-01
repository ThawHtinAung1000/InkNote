package wlv.ac.uk.inknote.ui.library;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.databinding.ItemLibraryPostBinding;
import wlv.ac.uk.inknote.databinding.ItemLibrarySectionBinding;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.model.PostAttachment;

final class LibraryAdapter extends ListAdapter<LibraryAdapter.Row, RecyclerView.ViewHolder> {
    interface Actions {
        void open(BlogPost post);
        void favourite(BlogPost post);
        void more(View anchor, BlogPost post);
        void seeAllFavourites();
        void sort();
    }

    static final class Row {
        static final int POST = 0;
        static final int FAVOURITES = 1;
        static final int RECENT = 2;
        final int type;
        final BlogPost post;
        Row(int type, BlogPost post) { this.type = type; this.post = post; }
        long id() { return type == POST ? post.getId() : -type; }
    }

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<Row>() {
        @Override public boolean areItemsTheSame(@NonNull Row old, @NonNull Row next) {
            return old.type == next.type && old.id() == next.id();
        }
        @Override public boolean areContentsTheSame(@NonNull Row old, @NonNull Row next) {
            if (old.type != Row.POST) return true;
            BlogPost a = old.post;
            BlogPost b = next.post;
            PostAttachment aa = a.getAttachment();
            PostAttachment ba = b.getAttachment();
            return a.isFavourite() == b.isFavourite() && a.isDraft() == b.isDraft()
                    && a.getUpdatedAt() == b.getUpdatedAt() && Objects.equals(a.getTitle(), b.getTitle())
                    && Objects.equals(a.getBody(), b.getBody())
                    && Objects.equals(aa == null ? null : aa.getRelativePath(), ba == null ? null : ba.getRelativePath());
        }
    };

    private final Actions actions;
    private final LocalThumbnailLoader images;
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

    LibraryAdapter(Actions actions, LocalThumbnailLoader images) {
        super(DIFF);
        this.actions = actions;
        this.images = images;
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    void show(List<BlogPost> posts, LibraryContent.Filter filter) {
        List<Row> rows = new ArrayList<>();
        Set<Long> previewIds = new HashSet<>();
        if (filter == LibraryContent.Filter.ALL) {
            for (BlogPost post : posts) {
                if (post.isFavourite() && previewIds.size() < 2) {
                    if (previewIds.isEmpty()) rows.add(new Row(Row.FAVOURITES, null));
                    rows.add(new Row(Row.POST, post));
                    previewIds.add(post.getId());
                }
            }
            if (posts.size() > previewIds.size()) rows.add(new Row(Row.RECENT, null));
        }
        for (BlogPost post : posts) {
            if (!previewIds.contains(post.getId())) rows.add(new Row(Row.POST, post));
        }
        submitList(rows);
    }

    @Override public long getItemId(int position) { return getItem(position).id(); }
    @Override public int getItemViewType(int position) { return getItem(position).type; }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (type == Row.POST) return new PostHolder(ItemLibraryPostBinding.inflate(inflater, parent, false));
        return new SectionHolder(ItemLibrarySectionBinding.inflate(inflater, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = getItem(position);
        if (holder instanceof SectionHolder) {
            ItemLibrarySectionBinding binding = ((SectionHolder) holder).binding;
            boolean favouriteSection = row.type == Row.FAVOURITES;
            binding.sectionTitle.setText(favouriteSection ? R.string.library_favourites : R.string.library_recent);
            binding.sectionAction.setText(favouriteSection ? R.string.library_see_all : R.string.library_sort);
            binding.sectionAction.setOnClickListener(view -> {
                if (favouriteSection) actions.seeAllFavourites(); else actions.sort();
            });
            return;
        }
        PostHolder postHolder = (PostHolder) holder;
        if (postHolder.request != null) postHolder.request.cancel();
        ItemLibraryPostBinding binding = postHolder.binding;
        BlogPost post = row.post;
        Context context = binding.getRoot().getContext();
        String title = LibraryContent.wordCount(post.getTitle()) == 0
                ? context.getString(R.string.library_untitled) : post.getTitle();
        binding.postTitle.setText(title);
        binding.postExcerpt.setText(post.getBody());
        binding.postDate.setText(dateFormat.format(post.getUpdatedAt()));
        binding.draftBadge.setVisibility(post.isDraft() ? View.VISIBLE : View.GONE);
        int words = LibraryContent.wordCount(post.getBody());
        binding.wordCount.setText(context.getResources().getQuantityString(R.plurals.library_words, words, words));
        binding.attachmentOrReadTime.setText(post.hasAttachment() ? context.getString(R.string.library_one_photo)
                : context.getString(R.string.library_read_time, Math.max(1, (words + 199) / 200)));
        binding.favourite.setImageResource(post.isFavourite() ? R.drawable.ic_library_star_filled : R.drawable.ic_library_star);
        binding.favourite.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context,
                post.isFavourite() ? R.color.inknest_library_star : R.color.inknest_muted_foreground)));
        binding.favourite.setContentDescription(context.getString(post.isFavourite()
                ? R.string.library_remove_favourite : R.string.library_add_favourite, title));
        binding.favourite.setSelected(post.isFavourite());
        binding.more.setContentDescription(context.getString(R.string.library_more, title));
        // The screen-reader group reads the actual date, title, and excerpt together.
        binding.postContent.setOnClickListener(view -> actions.open(post));
        binding.getRoot().setOnClickListener(view -> actions.open(post));
        binding.getRoot().setFocusable(false);
        binding.getRoot().setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        binding.favourite.setOnClickListener(view -> actions.favourite(post));
        binding.more.setOnClickListener(view -> actions.more(view, post));

        String relativePath = post.hasAttachment() ? post.getAttachment().getRelativePath() : null;
        if (relativePath == null || relativePath.isEmpty()) {
            binding.hero.setVisibility(View.GONE);
            binding.hero.setImageDrawable(null);
        } else {
            binding.hero.setVisibility(View.VISIBLE);
            postHolder.request = images.load(binding.hero, relativePath);
        }
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof PostHolder) {
            PostHolder postHolder = (PostHolder) holder;
            if (postHolder.request != null) postHolder.request.cancel();
            postHolder.binding.hero.setImageDrawable(null);
        }
        super.onViewRecycled(holder);
    }

    private static final class PostHolder extends RecyclerView.ViewHolder {
        final ItemLibraryPostBinding binding;
        LocalThumbnailLoader.Request request;
        PostHolder(ItemLibraryPostBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }

    private static final class SectionHolder extends RecyclerView.ViewHolder {
        final ItemLibrarySectionBinding binding;
        SectionHolder(ItemLibrarySectionBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
