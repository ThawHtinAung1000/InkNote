package wlv.ac.uk.inknote.ui.editor;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import wlv.ac.uk.inknote.data.local.InkNoteDbHelper;
import wlv.ac.uk.inknote.data.repository.BlogPostRepository;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.services.BlogPostService;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.AttachmentFiles;
import wlv.ac.uk.inknote.utils.ServiceResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises real serialized saves using only a private temporary database and owned executors. */
@RunWith(AndroidJUnit4.class)
public final class EditorViewModelTest {
    private Application application;
    private File testDirectory;
    private InkNoteDbHelper helper;
    private BlogPostService service;
    private AppExecutors executors;
    private final List<ViewModelStore> stores = new ArrayList<>();
    private final List<CountDownLatch> ioGates = new ArrayList<>();

    @Before public void setUp() {
        application = (Application) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        testDirectory = new File(application.getCacheDir(), "editor-vm-test-" + UUID.randomUUID());
        assertTrue(new File(testDirectory, "files").mkdirs());
        Context isolated = new EditorContext(application, testDirectory);
        helper = new InkNoteDbHelper(isolated);
        service = new BlogPostService(new BlogPostRepository(helper), new AttachmentFiles(isolated));
        executors = new AppExecutors();
    }

    @After public void tearDown() throws Exception {
        for (CountDownLatch gate : ioGates) gate.countDown();
        onMain(() -> {
            for (ViewModelStore store : stores) store.clear();
        });
        if (executors != null) {
            executors.io().shutdown();
            assertTrue("Fixture I/O did not finish.", executors.io().awaitTermination(10, TimeUnit.SECONDS));
        }
        if (helper != null) helper.close();
        if (testDirectory != null) deleteTree(testDirectory);
    }

    @Test
    public void backgroundDraftFlushQueuedDuringManualSaveNeverDemotesSavedPost() throws Exception {
        EditorViewModel model = newModel(new SavedStateHandle());
        CountDownLatch releaseIo = blockIo();
        onMain(() -> {
            model.setDocument("Manual save", "The body must remain saved.", "");
            model.save();
            model.preserveDraft();
        });

        releaseIo.countDown();
        EditorViewModel.State saved = awaitValue(model.getState(),
                state -> state != null && state.status == EditorViewModel.Status.SAVED && state.postId > 0);
        settleQueues();

        List<BlogPost> posts = storedPosts();
        assertEquals(1, posts.size());
        assertEquals(saved.postId, posts.get(0).getId());
        assertFalse("A redundant background flush must not turn Save into a draft.", posts.get(0).isDraft());
        onMain(() -> {
            assertNotNull(model.getState().getValue());
            assertEquals(EditorViewModel.Status.SAVED, model.getState().getValue().status);
        });
    }

    @Test
    public void rapidEditsAndBackSaveNewestContentUnderOneGeneratedId() throws Exception {
        EditorViewModel model = newModel(new SavedStateHandle());
        CountDownLatch releaseIo = blockIo();
        onMain(() -> {
            model.setDocument("First title", "First body", "");
            model.preserveDraft();
            model.setDocument("Latest title", "Most recent body", "");
            assertNull(model.validateAndAddTag("Ideas"));
            model.saveAndExit();
        });

        releaseIo.countDown();
        awaitValue(model.getExit(), Boolean.TRUE::equals);
        settleQueues();

        List<BlogPost> posts = storedPosts();
        assertEquals(1, posts.size());
        BlogPost post = posts.get(0);
        assertTrue(post.getId() > 0);
        assertEquals("Latest title", post.getTitle());
        assertEquals("Most recent body", post.getBody());
        assertEquals(Arrays.asList("Ideas"), post.getTags());
        assertTrue(post.isDraft());
        onMain(() -> {
            EditorViewModel.State current = model.getState().getValue();
            assertNotNull(current);
            assertEquals(post.getId(), current.postId);
            assertEquals("Most recent body", current.body);
        });
    }

    @Test
    public void newSavedStateHandleRestoresUnsavedUnicodeTextFormattingAndTags() {
        SavedStateHandle firstHandle = new SavedStateHandle();
        EditorViewModel first = newModel(firstHandle);
        String title = "Unsent idea";
        String body = "Hello 🪶\nမြန်မာ";
        String format = "{\"version\":1,\"spans\":[{\"type\":\"bold\",\"start\":0,\"end\":5}]}";
        Map<String, Object> snapshot = new HashMap<>();
        onMain(() -> {
            first.setDocument(title, body, format);
            assertNull(first.validateAndAddTag("Ideas"));
            assertNull(first.validateAndAddTag("Study"));
            for (String key : firstHandle.keys()) snapshot.put(key, firstHandle.get(key));
            stores.get(0).clear(); // Cancel debounce before reconstructing a process-death-style handle.
        });

        SavedStateHandle recreatedHandle = new SavedStateHandle(snapshot);
        EditorViewModel recreated = newModel(recreatedHandle);
        onMain(() -> {
            EditorViewModel.State restored = recreated.getState().getValue();
            assertNotNull(restored);
            assertEquals(title, restored.title);
            assertEquals(body, restored.body);
            assertEquals(format, restored.bodyFormat);
            assertEquals(Arrays.asList("Ideas", "Study"), restored.tags);
            String originalKey = firstHandle.get("editor_key");
            String restoredKey = recreatedHandle.get("editor_key");
            assertEquals(originalKey, restoredKey);
            assertEquals(0L, restored.postId);
            stores.get(stores.size() - 1).clear();
        });
    }

    @Test
    public void manualSaveQueuedBehindAutosaveKeepsNormalSaveIntentWhenBackFollows() throws Exception {
        EditorViewModel model = newModel(new SavedStateHandle());
        CountDownLatch releaseIo = blockIo();
        onMain(() -> {
            model.setDocument("Autosave snapshot", "Earlier body", "");
            model.preserveDraft();
            model.setDocument("Final saved title", "Latest body requested for normal Save", "");
            model.save();
            model.saveAndExit();
            assertFalse(Boolean.TRUE.equals(model.getExit().getValue()));
        });

        releaseIo.countDown();
        awaitValue(model.getExit(), Boolean.TRUE::equals);
        settleQueues();

        List<BlogPost> posts = storedPosts();
        assertEquals(1, posts.size());
        assertEquals("Final saved title", posts.get(0).getTitle());
        assertEquals("Latest body requested for normal Save", posts.get(0).getBody());
        assertFalse("Back must preserve the explicit Save queued behind an autosave.", posts.get(0).isDraft());
        onMain(() -> {
            EditorViewModel.State current = model.getState().getValue();
            assertNotNull(current);
            assertEquals(posts.get(0).getId(), current.postId);
            assertEquals(EditorViewModel.Status.SAVED, current.status);
        });
    }

    @Test
    public void failedPhotoCanBeCancelledBeforeBackWithoutLosingTextOrCreatingDuplicates() throws Exception {
        EditorViewModel model = newModel(new SavedStateHandle());
        onMain(() -> {
            model.setDocument("Keep this text", "Photo failure must not discard this body.", "");
            model.attachPhoto(Uri.parse("content://inknest-nonexistent-photo-test/missing"));
        });
        awaitValue(model.getState(), state -> state != null
                && state.status == EditorViewModel.Status.ERROR && state.pendingPhoto && !state.photoBusy);

        onMain(() -> {
            model.cancelPendingPhoto();
            model.saveAndExit();
        });
        awaitValue(model.getExit(), Boolean.TRUE::equals);
        settleQueues();

        List<BlogPost> posts = storedPosts();
        assertEquals(1, posts.size());
        assertEquals("Keep this text", posts.get(0).getTitle());
        assertEquals("Photo failure must not discard this body.", posts.get(0).getBody());
        assertFalse(posts.get(0).hasAttachment());
        onMain(() -> {
            EditorViewModel.State current = model.getState().getValue();
            assertNotNull(current);
            assertFalse(current.pendingPhoto);
            assertFalse(current.photoBusy);
            assertNull(current.error);
        });
    }

    @Test
    public void backDuringInitialLoadWaitsThenSavesNewerRestoredInputBeforeExit() throws Exception {
        String key = UUID.randomUUID().toString();
        ServiceResult<BlogPost> seed = executors.io().submit(() -> service.saveEditor(
                0L, key, "Older database title", "Older database body", "",
                Collections.emptyList(), true)).get(10, TimeUnit.SECONDS);
        assertTrue(seed.isSuccess());
        long existingId = seed.getData().getId();
        CountDownLatch releaseIo = blockIo();
        Map<String, Object> restored = new HashMap<>();
        restored.put("editor_id", existingId);
        restored.put("editor_key", key);
        restored.put("editor_has_input", true);
        restored.put("editor_dirty", true);
        restored.put("editor_title", "Newer restored title");
        restored.put("editor_body", "Newer restored body");
        restored.put("editor_format", "");
        restored.put("editor_tags", new ArrayList<>(Collections.singletonList("Restored")));
        EditorViewModel model = newModel(new SavedStateHandle(restored));
        onMain(() -> {
            assertNotNull(model.getState().getValue());
            assertTrue(model.getState().getValue().loading);
            model.saveAndExit();
            assertFalse("Back must wait until restored input can be persisted.",
                    Boolean.TRUE.equals(model.getExit().getValue()));
        });

        releaseIo.countDown();
        awaitValue(model.getExit(), Boolean.TRUE::equals);
        settleQueues();

        List<BlogPost> posts = storedPosts();
        assertEquals(1, posts.size());
        assertEquals(existingId, posts.get(0).getId());
        assertEquals("Newer restored title", posts.get(0).getTitle());
        assertEquals("Newer restored body", posts.get(0).getBody());
        assertEquals(Collections.singletonList("Restored"), posts.get(0).getTags());
        onMain(() -> {
            EditorViewModel.State current = model.getState().getValue();
            assertNotNull(current);
            assertFalse(current.loading);
            assertFalse(current.loadFailed);
            assertEquals("Newer restored body", current.body);
        });
    }

    private EditorViewModel newModel(SavedStateHandle handle) {
        AtomicReference<EditorViewModel> result = new AtomicReference<>();
        onMain(() -> {
            EditorViewModel model = new EditorViewModel(application, handle, service, executors);
            ViewModelStore store = new ViewModelStore();
            store.put("editor", model);
            stores.add(store);
            result.set(model);
        });
        return result.get();
    }

    private CountDownLatch blockIo() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ioGates.add(release);
        executors.io().execute(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue("I/O blocker was not reached.", entered.await(5, TimeUnit.SECONDS));
        return release;
    }

    private static <T> T awaitValue(LiveData<T> data, Predicate<T> ready) throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        Observer<T> observer = value -> {
            if (ready.test(value)) {
                result.set(value);
                delivered.countDown();
            }
        };
        onMain(() -> data.observeForever(observer));
        try {
            assertTrue("Expected editor result was not delivered.", delivered.await(10, TimeUnit.SECONDS));
            return result.get();
        } finally {
            onMain(() -> data.removeObserver(observer));
        }
    }

    private void settleQueues() throws Exception {
        // A state observer can fire before its callback pumps the next queued operation.
        onMain(() -> { });
        executors.io().submit(() -> { }).get(10, TimeUnit.SECONDS);
        onMain(() -> { });
    }

    private List<BlogPost> storedPosts() throws Exception {
        ServiceResult<List<BlogPost>> result = executors.io()
                .submit(() -> service.getLibraryPosts("")).get(10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        return result.getData();
    }

    private static void onMain(Runnable work) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(work);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        assertTrue(file.delete());
    }

    private static final class EditorContext extends ContextWrapper {
        private final File directory;
        EditorContext(Context base, File directory) { super(base); this.directory = directory; }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return new File(directory, "files"); }
        @Override public File getDatabasePath(String name) { return new File(directory, name); }
        @Override public SQLiteDatabase openOrCreateDatabase(
                String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return openOrCreateDatabase(name, mode, factory, null);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(
                String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
            return SQLiteDatabase.openDatabase(getDatabasePath(name).getAbsolutePath(),
                    factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
        }
    }
}
