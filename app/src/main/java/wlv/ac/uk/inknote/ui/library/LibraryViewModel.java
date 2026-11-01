package wlv.ac.uk.inknote.ui.library;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import wlv.ac.uk.inknote.AppContainer;
import wlv.ac.uk.inknote.InkNoteApplication;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.model.DeleteSummary;
import wlv.ac.uk.inknote.services.BlogPostService;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.ServiceResult;

public final class LibraryViewModel extends AndroidViewModel {
    public enum Notice { FAVOURITE_ADDED, FAVOURITE_REMOVED, DRAFT_SAVED, MARKED_SAVED, DELETED }

    public static final class State {
        public final boolean loading;
        public final List<BlogPost> posts;
        public final int allCount;
        public final int draftCount;
        public final int favouriteCount;
        public final String error;
        public final LibraryContent.Filter filter;
        public final LibraryContent.Sort sort;

        private State(boolean loading, List<BlogPost> source, LibraryContent.Filter filter,
                      LibraryContent.Sort sort, String error) {
            this.loading = loading;
            this.filter = filter;
            this.sort = sort;
            this.error = error;
            this.allCount = source.size();
            int drafts = 0;
            int favourites = 0;
            for (BlogPost post : source) {
                if (post.isDraft()) drafts++;
                if (post.isFavourite()) favourites++;
            }
            this.draftCount = drafts;
            this.favouriteCount = favourites;
            this.posts = Collections.unmodifiableList(LibraryContent.select(source, filter, sort));
        }
    }

    private final SavedStateHandle savedState;
    private final BlogPostService service;
    private final AppExecutors executors;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<String> actionError = new MutableLiveData<>();
    private final MutableLiveData<Notice> notice = new MutableLiveData<>();
    private final MutableLiveData<BlogPost> share = new MutableLiveData<>();
    private final Set<Long> busyPosts = new HashSet<>();
    private List<BlogPost> source = Collections.emptyList();
    private Runnable pendingSearch;
    private long loadGeneration;
    private long shareGeneration;
    private boolean cleared;

    public LibraryViewModel(@NonNull Application application, SavedStateHandle savedState) {
        super(application);
        this.savedState = savedState;
        AppContainer container = ((InkNoteApplication) application).getContainer();
        service = container.getBlogPostService();
        executors = container.getExecutors();
        refresh();
    }

    public LiveData<State> getState() { return state; }
    public LiveData<String> getActionError() { return actionError; }
    public LiveData<Notice> getNotice() { return notice; }
    public LiveData<BlogPost> getShare() { return share; }
    public String getQuery() {
        String value = savedState.get("library_query");
        return value == null ? "" : value;
    }
    public boolean isBannerVisible() { return !Boolean.TRUE.equals(savedState.get("library_banner_dismissed")); }
    public void dismissBanner() { savedState.set("library_banner_dismissed", true); }

    public LibraryContent.Filter getFilter() {
        String value = savedState.get("library_filter");
        try { return value == null ? LibraryContent.Filter.ALL : LibraryContent.Filter.valueOf(value); }
        catch (IllegalArgumentException ignored) { return LibraryContent.Filter.ALL; }
    }
    public LibraryContent.Sort getSort() {
        String value = savedState.get("library_sort");
        try { return value == null ? LibraryContent.Sort.NEWEST : LibraryContent.Sort.valueOf(value); }
        catch (IllegalArgumentException ignored) { return LibraryContent.Sort.NEWEST; }
    }
    public void setQuery(String query) {
        if (getQuery().equals(query)) return;
        savedState.set("library_query", query);
        cancelPendingSearch();
        final long generation = ++loadGeneration; // Invalidate results before the debounce expires.
        state.setValue(new State(true, source, getFilter(), getSort(), null));
        pendingSearch = () -> load(generation, query);
        handler.postDelayed(pendingSearch, 250);
    }
    public void setFilter(LibraryContent.Filter filter) {
        savedState.set("library_filter", filter.name());
        State current = state.getValue();
        state.setValue(new State(current != null && current.loading, source, filter, getSort(),
                current == null ? null : current.error));
    }
    public void setSort(LibraryContent.Sort sort) {
        savedState.set("library_sort", sort.name());
        State current = state.getValue();
        state.setValue(new State(current != null && current.loading, source, getFilter(), sort,
                current == null ? null : current.error));
    }
    public void refresh() {
        cancelPendingSearch();
        long generation = ++loadGeneration;
        state.setValue(new State(true, source, getFilter(), getSort(), null));
        load(generation, getQuery());
    }
    private void cancelPendingSearch() {
        if (pendingSearch != null) {
            handler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
    }
    private void load(long generation, String query) {
        executors.io().execute(() -> {
            ServiceResult<List<BlogPost>> result = service.getLibraryPosts(query);
            executors.main().execute(() -> {
                if (cleared || generation != loadGeneration) return;
                if (result.isSuccess()) {
                    source = new ArrayList<>(result.getData());
                    state.setValue(new State(false, source, getFilter(), getSort(), null));
                } else {
                    state.setValue(new State(false, source, getFilter(), getSort(), result.getFailure().getMessage()));
                }
            });
        });
    }

    public void setFavourite(BlogPost post) {
        if (!busyPosts.add(post.getId())) return;
        boolean favourite = !post.isFavourite();
        executors.io().execute(() -> {
            ServiceResult<BlogPost> result = service.setFavourite(post.getId(), favourite);
            finishMutation(post.getId(), result.isSuccess(),
                    result.isSuccess() ? null : result.getFailure().getMessage(),
                    favourite ? Notice.FAVOURITE_ADDED : Notice.FAVOURITE_REMOVED);
        });
    }
    public void setDraft(BlogPost post) {
        if (!busyPosts.add(post.getId())) return;
        boolean draft = !post.isDraft();
        executors.io().execute(() -> {
            ServiceResult<BlogPost> result = service.setDraft(post.getId(), draft);
            finishMutation(post.getId(), result.isSuccess(),
                    result.isSuccess() ? null : result.getFailure().getMessage(),
                    draft ? Notice.DRAFT_SAVED : Notice.MARKED_SAVED);
        });
    }
    public void delete(long id) {
        if (!busyPosts.add(id)) return;
        executors.io().execute(() -> {
            ServiceResult<DeleteSummary> result = service.deletePost(id);
            finishMutation(id, result.isSuccess(), result.isSuccess() ? null : result.getFailure().getMessage(), Notice.DELETED);
        });
    }
    private void finishMutation(long id, boolean success, String error, Notice successNotice) {
        executors.main().execute(() -> {
            if (cleared) return;
            busyPosts.remove(id);
            if (success) {
                // Invalidate any list query started before this mutation completed.
                refresh();
                notice.setValue(successNotice);
            } else {
                actionError.setValue(error);
            }
        });
    }
    public void shareText(long id) {
        long generation = ++shareGeneration;
        executors.io().execute(() -> {
            ServiceResult<BlogPost> result = service.getPostById(id);
            executors.main().execute(() -> {
                if (cleared || generation != shareGeneration) return;
                if (result.isSuccess()) share.setValue(result.getData());
                else actionError.setValue(result.getFailure().getMessage());
            });
        });
    }
    public void noticeHandled() { notice.setValue(null); }
    public void errorHandled() { actionError.setValue(null); }
    public void shareHandled() { share.setValue(null); }

    @Override protected void onCleared() {
        cleared = true;
        ++loadGeneration;
        ++shareGeneration;
        cancelPendingSearch();
        super.onCleared();
    }
}
