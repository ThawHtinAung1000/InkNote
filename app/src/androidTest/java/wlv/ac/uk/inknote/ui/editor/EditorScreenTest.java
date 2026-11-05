package wlv.ac.uk.inknote.ui.editor;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.data.local.InkNoteDbHelper;
import wlv.ac.uk.inknote.data.repository.BlogPostRepository;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.services.BlogPostService;
import wlv.ac.uk.inknote.ui.MainActivity;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.AttachmentFiles;
import wlv.ac.uk.inknote.utils.InputValidator;
import wlv.ac.uk.inknote.utils.ServiceResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Uses the actual editor view with an injected fixture ViewModel and isolated SQLite/files. */
@RunWith(AndroidJUnit4.class)
public final class EditorScreenTest {
    private Application application;
    private File testDirectory;
    private InkNoteDbHelper helper;
    private BlogPostRepository repository;
    private BlogPostService service;
    private AppExecutors executors;
    private ActivityScenario<MainActivity> scenario;

    @Before
    public void setUp() {
        application = (Application) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        testDirectory = new File(application.getCacheDir(), "editor-screen-test-" + UUID.randomUUID());
        assertTrue(new File(testDirectory, "files").mkdirs());
        Context isolated = new ScreenDatabaseContext(application, testDirectory);
        helper = new InkNoteDbHelper(isolated);
        repository = new BlogPostRepository(helper);
        service = new BlogPostService(repository, new AttachmentFiles(isolated));
        executors = new AppExecutors();
    }

    @After
    public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        if (executors != null) {
            executors.io().shutdown();
            assertTrue(executors.io().awaitTermination(10, TimeUnit.SECONDS));
        }
        if (helper != null) helper.close();
        deleteTree(testDirectory);
    }

    @Test
    public void legacyDocumentAndUnsavedEditSurviveRecreationWithoutTruncation() throws Exception {
        String legacyTitle = repeat('T', InputValidator.MAX_TITLE_LENGTH + 25);
        String legacyBody = repeat('w', InputValidator.MAX_BODY_LENGTH + 50);
        BlogPost fixture = executors.io().submit(
                () -> repository.insert(legacyTitle, legacyBody, 100L)
        ).get(10, TimeUnit.SECONDS);
        AtomicReference<EditorViewModel> injected = new AtomicReference<>();

        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            FragmentManager manager = activity.getSupportFragmentManager();
            manager.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentPreCreated(@NonNull FragmentManager fragmentManager,
                                                 @NonNull Fragment fragment,
                                                 @Nullable Bundle savedInstanceState) {
                    if (!(fragment instanceof EditorFragment)) return;
                    Map<String, Object> initial = new HashMap<>();
                    initial.put(EditorViewModel.ARG_POST_ID, fixture.getId());
                    ViewModelProvider.Factory factory = new ViewModelProvider.Factory() {
                        @NonNull
                        @Override
                        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                            EditorViewModel model = new EditorViewModel(
                                    application, new SavedStateHandle(initial), service, executors
                            );
                            injected.set(model);
                            return modelClass.cast(model);
                        }
                    };
                    // onViewCreated's normal provider reuses this stored fixture model.
                    new ViewModelProvider(fragment, factory).get(EditorViewModel.class);
                }
            }, false);
            manager.beginTransaction()
                    .replace(R.id.fragment_container, EditorFragment.newInstance(fixture.getId()))
                    .commitNow();
        });

        EditorViewModel model = injected.get();
        assertNotNull("The editor fixture ViewModel was not injected.", model);
        awaitValue(model.getState(), state -> state != null
                && !state.loading && !state.loadFailed && legacyBody.equals(state.body));

        String editedBody = legacyBody.substring(1);
        scenario.onActivity(activity -> {
            EditText title = activity.findViewById(R.id.title_input);
            EditText body = activity.findViewById(R.id.body_input);
            assertNotNull(title);
            assertNotNull(body);
            assertEquals(legacyTitle, title.getText().toString());
            assertEquals(legacyBody, body.getText().toString());

            // Deletion remains possible in legacy content above today's input limit.
            // The remaining oversized draft cannot be saved, so recreation must retain it.
            body.getText().delete(0, 1);
            assertEquals(editedBody, body.getText().toString());
        });

        scenario.recreate();
        scenario.onActivity(activity -> {
            Fragment current = activity.getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_container);
            assertTrue(current instanceof EditorFragment);
            EditText title = activity.findViewById(R.id.title_input);
            EditText body = activity.findViewById(R.id.body_input);
            assertEquals(legacyTitle, title.getText().toString());
            assertEquals(editedBody, body.getText().toString());
            assertEquals(editedBody, model.getState().getValue().body);
        });

        // Background validation failure must never replace retained data with truncated text.
        ServiceResult<BlogPost> stored = executors.io().submit(
                () -> service.getPostById(fixture.getId())
        ).get(10, TimeUnit.SECONDS);
        assertTrue(stored.isSuccess());
        assertEquals(legacyTitle, stored.getData().getTitle());
        assertEquals(legacyBody, stored.getData().getBody());
        assertEquals(100L, stored.getData().getUpdatedAt());
    }

    @Test
    public void addTagDialogValidatesBlankInputAndPersistsAValidRenderedChip() throws Exception {
        AtomicReference<EditorViewModel> injected = new AtomicReference<>();
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            FragmentManager manager = activity.getSupportFragmentManager();
            manager.executePendingTransactions();
            manager.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
                @Override public void onFragmentPreCreated(
                        @NonNull FragmentManager fragmentManager, @NonNull Fragment fragment,
                        @Nullable Bundle savedInstanceState) {
                    if (!(fragment instanceof EditorFragment)) return;
                    ViewModelProvider.Factory factory = new ViewModelProvider.Factory() {
                        @NonNull @Override
                        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                            EditorViewModel model = new EditorViewModel(
                                    application, new SavedStateHandle(), service, executors);
                            injected.set(model);
                            return modelClass.cast(model);
                        }
                    };
                    new ViewModelProvider(fragment, factory).get(EditorViewModel.class);
                }
            }, false);
            manager.beginTransaction()
                    .replace(R.id.fragment_container, EditorFragment.newInstance(0L))
                    .commitNow();
            EditText title = activity.findViewById(R.id.title_input);
            EditText body = activity.findViewById(R.id.body_input);
            title.setText("Tag dialog fixture");
            body.setText("Body retained while adding a tag.");
            // This actual button callback previously crashed while constructing TextInputLayout.
            assertTrue(activity.findViewById(R.id.add_tag).performClick());
        });

        EditorViewModel model = injected.get();
        assertNotNull(model);
        scenario.onActivity(activity -> {
            View dialogRoot = null;
            for (View window : WindowInspector.getGlobalWindowViews()) {
                if (window.findViewById(android.R.id.button1) != null
                        && findInTree(window, TextInputLayout.class) != null) {
                    dialogRoot = window;
                    break;
                }
            }
            assertNotNull("The Add tag dialog window must be present.", dialogRoot);
            View add = dialogRoot.findViewById(android.R.id.button1);
            TextInputLayout field = findInTree(dialogRoot, TextInputLayout.class);
            TextInputEditText input = findInTree(dialogRoot, TextInputEditText.class);
            assertNotNull(add);
            assertNotNull(field);
            assertNotNull(input);

            assertTrue(add.performClick());
            CharSequence error = field.getError();
            assertNotNull("Blank input should keep the dialog open with a validation error.", error);
            assertTrue(error.length() > 0);
            assertTrue("Blank validation must leave the dialog attached.", dialogRoot.isAttachedToWindow());

            input.setText("Design");
            assertTrue(add.performClick());
            ChipGroup group = activity.findViewById(R.id.tags_chip_group);
            assertNotNull(group);
            assertEquals(1, group.getChildCount());
            assertTrue(group.getChildAt(0) instanceof Chip);
            assertEquals("Design", ((Chip) group.getChildAt(0)).getText().toString());
            assertEquals(Collections.singletonList("Design"), model.getState().getValue().tags);
            model.preserveDraft();
        });
        awaitValue(model.getState(), state -> state != null
                && state.status == EditorViewModel.Status.AUTOSAVED
                && state.tags.equals(Collections.singletonList("Design")));

        ServiceResult<List<BlogPost>> stored = executors.io().submit(
                () -> service.getLibraryPosts("")).get(10, TimeUnit.SECONDS);
        assertTrue(stored.isSuccess());
        assertEquals(1, stored.getData().size());
        assertEquals(Collections.singletonList("Design"), stored.getData().get(0).getTags());
        assertEquals("Body retained while adding a tag.", stored.getData().get(0).getBody());
        assertTrue(stored.getData().get(0).isDraft());
    }

    private static <T extends View> T findInTree(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = findInTree(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static <T> void awaitValue(LiveData<T> data, Predicate<T> ready) throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        Observer<T> observer = value -> {
            if (ready.test(value)) delivered.countDown();
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> data.observeForever(observer)
        );
        try {
            assertTrue("The editor did not finish loading the fixture.",
                    delivered.await(10, TimeUnit.SECONDS));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> data.removeObserver(observer)
            );
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        assertTrue(file.delete());
    }

    private static final class ScreenDatabaseContext extends ContextWrapper {
        private final File directory;

        ScreenDatabaseContext(Context base, File directory) {
            super(base);
            this.directory = directory;
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return new File(directory, "files"); }
        @Override public File getDatabasePath(String name) { return new File(directory, name); }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
                String name, int mode, SQLiteDatabase.CursorFactory factory
        ) {
            return openOrCreateDatabase(name, mode, factory, null);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
                String name, int mode, SQLiteDatabase.CursorFactory factory,
                DatabaseErrorHandler errorHandler
        ) {
            return SQLiteDatabase.openDatabase(
                    getDatabasePath(name).getAbsolutePath(), factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler
            );
        }
    }
}
