package wlv.ac.uk.inknote.ui.editor;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import wlv.ac.uk.inknote.InkNoteApplication;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.services.BlogPostService;
import wlv.ac.uk.inknote.ui.library.LibraryContent;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.InputValidator;
import wlv.ac.uk.inknote.utils.ServiceResult;

/** Serializes editor saves while keeping the newest input independent of older I/O results. */
public final class EditorViewModel extends AndroidViewModel {
    public static final String ARG_POST_ID = "post_id";
    public enum Status { EMPTY, LOADING, UNSAVED, SAVING, AUTOSAVED, SAVED, ERROR }
    private enum Goal { AUTO, SAVE, EXIT }

    public static final class State {
        public final String title, body, bodyFormat, photoPath, error;
        public final List<String> tags;
        public final Status status;
        public final long postId, savedAt;
        public final boolean loading, photoBusy, exiting, loadFailed, pendingPhoto;
        public final int revision;

        private State(EditorViewModel model) {
            title = model.title; body = model.body; bodyFormat = model.format;
            photoPath = model.photoPath; error = model.error;
            tags = Collections.unmodifiableList(new ArrayList<>(model.tags));
            status = model.status; postId = model.postId; savedAt = model.savedAt;
            loading = model.loading; photoBusy = model.photoBusy;
            exiting = model.exiting; loadFailed = model.loadFailed; revision = model.revision;
            pendingPhoto = model.pendingPhotoUri != null || model.removePhoto;
        }
    }

    private static final class Snapshot {
        final String title, body, format;
        final List<String> tags;
        final int revision;
        Snapshot(EditorViewModel model) {
            title = model.title; body = model.body; format = model.format;
            tags = new ArrayList<>(model.tags); revision = model.revision;
        }
    }

    private final SavedStateHandle savedState;
    private final BlogPostService service;
    private final AppExecutors executors;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<Boolean> exit = new MutableLiveData<>(false);
    private final String editorKey;
    private String title, body, format, photoPath, error;
    private List<String> tags;
    private long postId, savedAt;
    private int revision;
    private boolean dirty, loading, loadFailed, photoBusy, exiting, inFlight, cleared;
    private boolean removePhoto, photoBlocked, recoveringPhoto, normalSaveRequested;
    private String pendingPhotoUri, originalPhotoPath, photoError;
    private Goal queuedGoal;
    private Runnable debounce;
    private Status status = Status.EMPTY;

    public EditorViewModel(@NonNull Application application, SavedStateHandle savedState) {
        this(application, savedState,
                ((InkNoteApplication) application).getContainer().getBlogPostService(),
                ((InkNoteApplication) application).getContainer().getExecutors());
    }

    EditorViewModel(@NonNull Application application, SavedStateHandle savedState,
                    BlogPostService service, AppExecutors executors) {
        super(application);
        this.savedState = savedState;
        this.service = service;
        this.executors = executors;
        String restoredKey = savedState.get("editor_key");
        editorKey = restoredKey == null ? UUID.randomUUID().toString() : restoredKey;
        savedState.set("editor_key", editorKey);
        Long restoredId = savedState.get("editor_id");
        Long argumentId = savedState.get(ARG_POST_ID);
        postId = restoredId != null ? restoredId : argumentId == null ? 0L : argumentId;
        title = value("editor_title"); body = value("editor_body"); format = value("editor_format");
        ArrayList<String> restoredTags = savedState.get("editor_tags");
        tags = restoredTags == null ? new ArrayList<>() : new ArrayList<>(restoredTags);
        photoPath = savedState.get("editor_photo");
        pendingPhotoUri = savedState.get("editor_pending_photo");
        originalPhotoPath = savedState.get("editor_photo_original");
        removePhoto = Boolean.TRUE.equals(savedState.get("editor_remove_photo"));
        recoveringPhoto = pendingPhotoUri != null;
        dirty = Boolean.TRUE.equals(savedState.get("editor_dirty"));
        if (postId > 0) loadInitial();
        else {
            if (dirty) status = Status.UNSAVED;
            publish();
            if (pendingPhotoUri != null || removePhoto) pump();
            else if (dirty) scheduleAutosave();
        }
    }

    private String value(String key) {
        String value = savedState.get(key);
        return value == null ? "" : value;
    }
    public LiveData<State> getState() { return state; }
    public LiveData<Boolean> getExit() { return exit; }
    public void exitHandled() { exit.setValue(false); }

    private void loadInitial() {
        loading = true; loadFailed = false; error = null; status = Status.LOADING; publish();
        executors.io().execute(() -> {
            ServiceResult<BlogPost> result = service.getPostById(postId);
            executors.main().execute(() -> {
                if (cleared) return;
                loading = false;
                if (!result.isSuccess()) {
                    loadFailed = true; exiting = false; queuedGoal = null;
                    status = Status.ERROR; error = result.getFailure().getMessage();
                    publish(); return;
                }
                BlogPost post = result.getData();
                if (!Boolean.TRUE.equals(savedState.get("editor_has_input"))) {
                    title = post.getTitle() == null ? "" : post.getTitle();
                    body = post.getBody();
                    format = post.getBodyFormat();
                    tags = new ArrayList<>(post.getTags());
                }
                photoPath = post.hasAttachment() ? post.getAttachment().getRelativePath() : null;
                savedAt = post.getUpdatedAt();
                status = dirty ? Status.UNSAVED : post.isDraft() ? Status.AUTOSAVED : Status.SAVED;
                persistInput();
                publish();
                if (pendingPhotoUri != null || removePhoto || queuedGoal != null) pump();
                else if (dirty) scheduleAutosave();
            });
        });
    }

    public void setDocument(String title, String body, String format) {
        if (loading || loadFailed) return;
        if (Objects.equals(this.title, title) && Objects.equals(this.body, body)
                && Objects.equals(this.format, format)) return;
        this.title = title; this.body = body; this.format = format;
        changed();
    }
    public String validateAndAddTag(String value) {
        if (loading || loadFailed || exiting) return "Wait for the message to finish loading.";
        try {
            List<String> proposed = new ArrayList<>(tags);
            proposed.add(value);
            List<String> validated = InputValidator.editorTags(proposed);
            if (!validated.equals(tags)) {
                tags = new ArrayList<>(validated);
                changed();
            }
            return null;
        } catch (InputValidator.InvalidInputException failure) {
            return failure.getMessage();
        }
    }
    public void removeTag(String tag) {
        if (loading || loadFailed || exiting) return;
        if (tags.remove(tag)) changed();
    }
    private void changed() {
        revision++; dirty = true; error = null;
        status = Status.UNSAVED;
        persistInput(); publish(); scheduleAutosave();
    }
    private boolean hasContent() {
        return postId > 0 || LibraryContent.wordCount(title) > 0 || LibraryContent.wordCount(body) > 0
                || !tags.isEmpty() || photoPath != null || pendingPhotoUri != null;
    }
    private void scheduleAutosave() {
        cancelDebounce();
        if (cleared || loading || loadFailed || exiting || photoBusy || !hasContent()) return;
        debounce = () -> request(Goal.AUTO);
        handler.postDelayed(debounce, 650);
    }
    private void cancelDebounce() {
        if (debounce != null) handler.removeCallbacks(debounce);
        debounce = null;
    }
    public void preserveDraft() {
        if (dirty && !loading && !loadFailed && !photoBlocked) request(Goal.AUTO);
    }
    public void save() {
        if (loadFailed) { loadInitial(); return; }
        photoBlocked = false;
        normalSaveRequested = true;
        request(Goal.SAVE);
    }
    public void saveAndExit() {
        if (loading) { exiting = true; queuedGoal = merge(queuedGoal, Goal.EXIT); publish(); return; }
        if (loadFailed) {
            if (!dirty && pendingPhotoUri == null && !removePhoto) exit.setValue(true);
            else { exiting = false; publish(); }
            return;
        }
        if (!dirty && pendingPhotoUri == null && !removePhoto && !inFlight) {
            exit.setValue(true); return;
        }
        photoBlocked = false;
        exiting = true;
        request(Goal.EXIT);
    }
    public void retry() {
        if (loadFailed) { loadInitial(); return; }
        photoBlocked = false;
        request(Goal.AUTO);
    }
    private static Goal merge(Goal current, Goal next) {
        if (current == Goal.EXIT || next == Goal.EXIT) return Goal.EXIT;
        if (current == Goal.SAVE || next == Goal.SAVE) return Goal.SAVE;
        return next;
    }
    private void request(Goal goal) {
        cancelDebounce();
        if (loading || loadFailed || cleared) return;
        if (goal != Goal.SAVE && !hasContent()) {
            if (goal == Goal.EXIT) exit.setValue(true);
            return;
        }
        queuedGoal = merge(queuedGoal, goal);
        pump();
    }

    private void pump() {
        if (cleared || loading || loadFailed || inFlight) return;
        if ((pendingPhotoUri != null || removePhoto) && !photoBlocked) {
            runPhotoOperation();
            return;
        }
        Goal goal = queuedGoal;
        if (goal == null) return;
        queuedGoal = null;
        if (goal == Goal.AUTO && !dirty) return;
        if (goal == Goal.EXIT && !dirty && !normalSaveRequested) {
            exiting = false; publish(); exit.setValue(true); return;
        }
        runSave(goal);
    }
    private void runSave(Goal goal) {
        Snapshot snapshot = new Snapshot(this);
        long targetId = postId;
        boolean saveAsNormal = goal == Goal.SAVE || (goal == Goal.EXIT && normalSaveRequested);
        inFlight = true; status = Status.SAVING; error = photoBlocked ? photoError : null; publish();
        executors.io().execute(() -> {
            ServiceResult<BlogPost> result = service.saveEditor(targetId, editorKey, snapshot.title,
                    snapshot.body, snapshot.format, snapshot.tags, !saveAsNormal);
            executors.main().execute(() -> {
                if (cleared) return;
                inFlight = false;
                if (!result.isSuccess()) { fail(result.getFailure().getMessage()); return; }
                if (saveAsNormal) normalSaveRequested = false;
                acceptSaved(result.getData(), snapshot.revision, saveAsNormal);
                if (goal == Goal.EXIT && !dirty && pendingPhotoUri == null && !removePhoto) {
                    exiting = false; publish(); exit.setValue(true); return;
                }
                if (goal == Goal.EXIT) queuedGoal = Goal.EXIT;
                if (dirty && queuedGoal == null) queuedGoal = Goal.AUTO;
                pump();
            });
        });
    }
    public void attachPhoto(Uri uri) {
        if (loading || loadFailed || photoBusy || uri == null) return;
        pendingPhotoUri = uri.toString();
        originalPhotoPath = photoPath;
        recoveringPhoto = false; removePhoto = false; photoBlocked = false; photoError = null;
        revision++; dirty = true;
        persistInput(); pump();
    }
    public void removePhoto() {
        if (loading || loadFailed || photoBusy) return;
        pendingPhotoUri = null; removePhoto = true; photoBlocked = false; photoError = null;
        revision++; dirty = true;
        persistInput(); pump();
    }
    public void cancelPendingPhoto() {
        if (photoBusy || inFlight) return;
        pendingPhotoUri = null; removePhoto = false; originalPhotoPath = null;
        recoveringPhoto = false; photoBlocked = false; photoError = null;
        changed();
    }

    private void runPhotoOperation() {
        cancelDebounce();
        Snapshot snapshot = new Snapshot(this);
        String uri = pendingPhotoUri;
        String original = originalPhotoPath;
        boolean removing = removePhoto;
        boolean recovering = recoveringPhoto;
        long targetId = postId;
        inFlight = true; photoBusy = true; status = Status.SAVING; error = null; publish();
        executors.io().execute(() -> {
            ServiceResult<BlogPost> result = service.saveEditor(targetId, editorKey, snapshot.title,
                    snapshot.body, snapshot.format, snapshot.tags, true);
            if (result.isSuccess()) {
                BlogPost saved = result.getData();
                String actual = saved.hasAttachment() ? saved.getAttachment().getRelativePath() : null;
                if (removing) result = service.removePhoto(saved.getId());
                else if (!recovering || Objects.equals(original, actual)) {
                    result = service.importPhoto(saved.getId(), Uri.parse(uri));
                }
            }
            final ServiceResult<BlogPost> outcome = result;
            executors.main().execute(() -> {
                if (cleared) return;
                inFlight = false; photoBusy = false;
                if (!outcome.isSuccess()) {
                    photoBlocked = true;
                    photoError = outcome.getFailure().getMessage();
                    fail(photoError);
                    return;
                }
                pendingPhotoUri = null; removePhoto = false; originalPhotoPath = null; recoveringPhoto = false; photoError = null;
                BlogPost post = outcome.getData();
                photoPath = post.hasAttachment() ? post.getAttachment().getRelativePath() : null;
                acceptSaved(post, snapshot.revision, false);
                if (dirty && queuedGoal == null) queuedGoal = Goal.AUTO;
                pump();
            });
        });
    }
    private void acceptSaved(BlogPost post, int savedRevision, boolean manual) {
        postId = post.getId(); savedAt = post.getUpdatedAt();
        if (revision == savedRevision) {
            dirty = false; status = manual ? Status.SAVED : Status.AUTOSAVED;
        } else status = Status.UNSAVED;
        if (photoBlocked) { status = Status.ERROR; error = photoError; }
        else error = null;
        persistInput(); publish();
    }
    private void fail(String message) {
        error = message; status = Status.ERROR; exiting = false; queuedGoal = null; normalSaveRequested = false;
        persistInput(); publish();
    }
    private void persistInput() {
        savedState.set("editor_has_input", true);
        savedState.set("editor_id", postId);
        savedState.set("editor_title", title);
        savedState.set("editor_body", body);
        savedState.set("editor_format", format);
        savedState.set("editor_tags", new ArrayList<>(tags));
        savedState.set("editor_photo", photoPath);
        savedState.set("editor_dirty", dirty);
        savedState.set("editor_pending_photo", pendingPhotoUri);
        savedState.set("editor_photo_original", originalPhotoPath);
        savedState.set("editor_remove_photo", removePhoto);
    }
    private void publish() { state.setValue(new State(this)); }

    @Override protected void onCleared() {
        cleared = true; cancelDebounce();
        // Fragment.onStop flushes pending edits; an already-running serialized write finishes.
        super.onCleared();
    }
}
