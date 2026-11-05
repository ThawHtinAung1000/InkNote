package wlv.ac.uk.inknote;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import wlv.ac.uk.inknote.data.local.InkNoteDbHelper;
import wlv.ac.uk.inknote.data.repository.BlogPostRepository;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.services.BlogPostService;
import wlv.ac.uk.inknote.utils.AttachmentFiles;
import wlv.ac.uk.inknote.utils.ServiceResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises real migration and editor persistence using only isolated cache databases. */
@RunWith(AndroidJUnit4.class)
public final class EditorDatabaseTest {

    private File testDirectory;
    private EditorDatabaseContext databaseContext;
    private InkNoteDbHelper helper;
    private BlogPostRepository repository;
    private BlogPostService service;

    @Before
    public void setUp() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        testDirectory = new File(appContext.getCacheDir(), "editor-db-test-" + UUID.randomUUID());
        assertTrue(testDirectory.mkdirs());
        databaseContext = new EditorDatabaseContext(appContext, testDirectory);
        helper = new InkNoteDbHelper(databaseContext);
        repository = new BlogPostRepository(helper);
        service = new BlogPostService(repository, new AttachmentFiles(databaseContext));
    }

    @After
    public void tearDown() {
        if (helper != null) {
            helper.close();
        }
        if (databaseContext != null) {
            SQLiteDatabase.deleteDatabase(
                    databaseContext.getDatabasePath(InkNoteDbHelper.DATABASE_NAME)
            );
        }
        if (testDirectory != null) {
            assertTrue(testDirectory.delete() || !testDirectory.exists());
        }
    }

    @Test
    public void versionTwoMigrationPreservesFlagsPhotosAndDeletedHighIdSequences() {
        createVersionTwoFixture();

        BlogPost retained = repository.findById(37L);
        assertNotNull(retained);
        assertEquals("Retained title", retained.getTitle());
        assertEquals("Retained body", retained.getBody());
        assertEquals(100L, retained.getCreatedAt());
        assertEquals(150L, retained.getUpdatedAt());
        assertTrue(retained.isDraft());
        assertTrue(retained.isFavourite());
        assertEquals("", retained.getBodyFormat());
        assertTrue(retained.getTags().isEmpty());
        assertNotNull(retained.getAttachment());
        assertEquals(9L, retained.getAttachment().getId());
        assertEquals(37L, retained.getAttachment().getPostId());
        assertEquals("retained.jpg", retained.getAttachment().getRelativePath());
        assertEquals("image/jpeg", retained.getAttachment().getMimeType());
        assertEquals(3, helper.getReadableDatabase().getVersion());

        BlogPost next = repository.insert("New title", "New body", 300L);
        assertTrue(next.getId() > 100L);
        BlogPost replaced = repository.replaceAttachment(
                retained.getId(), "replacement.jpg", "image/jpeg", 400L
        );
        assertNotNull(replaced);
        assertNotNull(replaced.getAttachment());
        assertTrue(replaced.getAttachment().getId() > 200L);
        assertForeignKeysValid();

        // Foreign-key cascade must still target the renamed final parent table.
        assertEquals(1, repository.deletePosts(Collections.singletonList(retained.getId())));
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT id FROM post_attachments WHERE post_id = ?",
                new String[]{Long.toString(retained.getId())}
        )) {
            assertEquals(0, cursor.getCount());
        }
        assertForeignKeysValid();
    }

    @Test
    public void repeatingAnInsertAfterLosingItsCallbackUsesTheSameEditorKey() {
        String key = UUID.randomUUID().toString();
        BlogPost first = requireSuccess(service.saveEditor(
                0L, key, "First title", "First body", "", Collections.emptyList(), true
        ));
        BlogPost retried = requireSuccess(service.saveEditor(
                0L, key, "Recovered title", "Recovered body", "", Collections.emptyList(), true
        ));

        assertEquals(first.getId(), retried.getId());
        assertEquals(first.getCreatedAt(), retried.getCreatedAt());
        assertEquals("Recovered title", retried.getTitle());
        assertEquals("Recovered body", retried.getBody());
        assertTrue(retried.isDraft());
        assertEquals(1, requireLibrary().size());

        // Normal Save may also be retried before the UI has received a generated ID.
        BlogPost saved = requireSuccess(service.saveEditor(
                0L, key, "Saved title", "Saved body", "", Collections.emptyList(), false
        ));
        assertEquals(first.getId(), saved.getId());
        assertFalse(saved.isDraft());
        assertEquals(first.getCreatedAt(), saved.getCreatedAt());
        assertEquals(1, requireLibrary().size());
    }

    @Test
    public void titleOnlyDraftIsAllowedButBlankNormalSaveAndStatusChangeAreRejected() {
        String key = UUID.randomUUID().toString();
        BlogPost draft = requireSuccess(service.saveEditor(
                0L, key, "A title without a body", "", "", Collections.emptyList(), true
        ));
        assertTrue(draft.isDraft());
        assertEquals("", draft.getBody());

        assertValidationFailure(service.saveEditor(
                draft.getId(), key, "Should not replace the draft", "", "",
                Collections.emptyList(), false
        ));
        assertValidationFailure(service.setDraft(draft.getId(), false));

        BlogPost unchanged = repository.findById(draft.getId());
        assertNotNull(unchanged);
        assertTrue(unchanged.isDraft());
        assertEquals("A title without a body", unchanged.getTitle());
        assertEquals("", unchanged.getBody());

        ContentValues invalid = new ContentValues();
        invalid.put("is_draft", 0);
        try {
            helper.getWritableDatabase().update(
                    InkNoteDbHelper.POSTS, invalid, "id = ?",
                    new String[]{Long.toString(draft.getId())}
            );
            fail("The schema must reject a normal post with an empty body.");
        } catch (SQLiteConstraintException expected) {
            // The database protects the invariant even if a caller bypasses the service.
        }
    }

    @Test
    public void normalSavePersistsFormattingAndTagsWhilePreservingPhotoFavouriteAndCreation()
            throws Exception {
        String key = UUID.randomUUID().toString();
        BlogPost draft = repository.saveEditor(
                0L, key, "Draft", "Hello world", "", Collections.emptyList(), true, 100L
        );
        assertNotNull(draft);
        repository.setFavourite(draft.getId(), true);
        BlogPost withPhoto = repository.replaceAttachment(
                draft.getId(), "retained.jpg", "image/jpeg", 150L
        );
        assertNotNull(withPhoto);
        assertNotNull(withPhoto.getAttachment());
        long attachmentId = withPhoto.getAttachment().getId();

        JSONArray spans = new JSONArray();
        for (String type : Arrays.asList("bold", "italic", "bullet", "quote")) {
            spans.put(new JSONObject().put("type", type).put("start", 0).put("end", 11));
        }
        String format = new JSONObject().put("version", 1).put("spans", spans).toString();
        BlogPost saved = requireSuccess(service.saveEditor(
                draft.getId(), key, "Saved message", "Hello world", format,
                Arrays.asList(" Work ", "work", "Ideas", " IDEAS "), false
        ));

        assertFalse(saved.isDraft());
        assertTrue(saved.isFavourite());
        assertEquals(100L, saved.getCreatedAt());
        assertNotNull(saved.getAttachment());
        assertEquals(attachmentId, saved.getAttachment().getId());
        assertEquals("retained.jpg", saved.getAttachment().getRelativePath());
        assertEquals(Arrays.asList("Work", "Ideas"), saved.getTags());
        JSONObject storedFormat = new JSONObject(saved.getBodyFormat());
        assertEquals(1, storedFormat.getInt("version"));
        assertEquals(4, storedFormat.getJSONArray("spans").length());

        BlogPost reloaded = requireSuccess(service.getPostById(saved.getId()));
        assertEquals(saved.getBodyFormat(), reloaded.getBodyFormat());
        assertEquals(saved.getTags(), reloaded.getTags());
        assertTrue(reloaded.isFavourite());
        assertFalse(reloaded.isDraft());
        assertEquals(attachmentId, reloaded.getAttachment().getId());
        assertEquals(100L, reloaded.getCreatedAt());
    }

    @Test
    public void invalidFormatRangesTypesAndControlTagsDoNotMutateSavedContent() throws Exception {
        String key = UUID.randomUUID().toString();
        BlogPost original = requireSuccess(service.saveEditor(
                0L, key, "Original", "Hello", "", Collections.singletonList("Work"), true
        ));

        assertValidationFailure(service.saveEditor(
                original.getId(), key, "Invalid range", "Hello", format("bold", 0, 6),
                Collections.singletonList("Work"), true
        ));
        assertValidationFailure(service.saveEditor(
                original.getId(), key, "Unsupported type", "Hello", format("underline", 0, 5),
                Collections.singletonList("Work"), true
        ));
        assertValidationFailure(service.saveEditor(
                original.getId(), key, "Invalid tag", "Hello", "",
                Collections.singletonList("bad" + (char) 0), true
        ));

        BlogPost unchanged = repository.findById(original.getId());
        assertNotNull(unchanged);
        assertEquals("Original", unchanged.getTitle());
        assertEquals("Hello", unchanged.getBody());
        assertEquals("", unchanged.getBodyFormat());
        assertEquals(Collections.singletonList("Work"), unchanged.getTags());
        assertEquals(original.getUpdatedAt(), unchanged.getUpdatedAt());
    }

    private static String format(String type, int start, int end) throws Exception {
        JSONObject span = new JSONObject()
                .put("type", type).put("start", start).put("end", end);
        return new JSONObject().put("version", 1)
                .put("spans", new JSONArray().put(span)).toString();
    }

    private static BlogPost requireSuccess(ServiceResult<BlogPost> result) {
        assertTrue(result.isSuccess());
        return result.getData();
    }

    private List<BlogPost> requireLibrary() {
        ServiceResult<List<BlogPost>> result = service.getLibraryPosts("");
        assertTrue(result.isSuccess());
        return result.getData();
    }

    private static void assertValidationFailure(ServiceResult<BlogPost> result) {
        assertFalse(result.isSuccess());
        assertEquals(ServiceResult.ErrorCode.VALIDATION, result.getFailure().getCode());
    }

    private void assertForeignKeysValid() {
        try (Cursor cursor = helper.getReadableDatabase().rawQuery("PRAGMA foreign_key_check", null)) {
            assertEquals(0, cursor.getCount());
        }
    }

    private void createVersionTwoFixture() {
        try (SQLiteDatabase database = databaseContext.openOrCreateDatabase(
                InkNoteDbHelper.DATABASE_NAME, Context.MODE_PRIVATE, null
        )) {
            database.setForeignKeyConstraintsEnabled(true);
            database.execSQL(
                    "CREATE TABLE blog_posts ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, "
                            + "body TEXT NOT NULL CHECK(length(body) > 0), "
                            + "created_at INTEGER NOT NULL CHECK(created_at >= 0), "
                            + "updated_at INTEGER NOT NULL CHECK(updated_at >= created_at), "
                            + "is_draft INTEGER NOT NULL DEFAULT 0 CHECK(is_draft IN (0, 1)), "
                            + "is_favourite INTEGER NOT NULL DEFAULT 0 CHECK(is_favourite IN (0, 1)))"
            );
            database.execSQL(
                    "CREATE TABLE post_attachments ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, post_id INTEGER NOT NULL UNIQUE, "
                            + "relative_path TEXT NOT NULL UNIQUE CHECK("
                            + "length(relative_path) > 0 AND relative_path NOT IN ('.', '..') "
                            + "AND instr(relative_path, '/') = 0 "
                            + "AND instr(relative_path, char(92)) = 0), "
                            + "mime_type TEXT NOT NULL CHECK(mime_type LIKE 'image/%'), "
                            + "FOREIGN KEY(post_id) REFERENCES blog_posts(id) ON DELETE CASCADE)"
            );
            database.execSQL(
                    "CREATE INDEX idx_blog_posts_created_id "
                            + "ON blog_posts(created_at DESC, id DESC)"
            );
            database.execSQL(
                    "INSERT INTO blog_posts "
                            + "(id, title, body, created_at, updated_at, is_draft, is_favourite) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new Object[]{37L, "Retained title", "Retained body", 100L, 150L, 1, 1}
            );
            database.execSQL(
                    "INSERT INTO post_attachments (id, post_id, relative_path, mime_type) "
                            + "VALUES (?, ?, ?, ?)",
                    new Object[]{9L, 37L, "retained.jpg", "image/jpeg"}
            );
            database.execSQL(
                    "INSERT INTO blog_posts "
                            + "(id, title, body, created_at, updated_at, is_draft, is_favourite) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new Object[]{100L, "Deleted", "Deleted body", 200L, 200L, 0, 0}
            );
            database.execSQL(
                    "INSERT INTO post_attachments (id, post_id, relative_path, mime_type) "
                            + "VALUES (?, ?, ?, ?)",
                    new Object[]{200L, 100L, "deleted.jpg", "image/jpeg"}
            );
            database.delete("blog_posts", "id = ?", new String[]{"100"});
            database.setVersion(2);
        }
    }

    private static final class EditorDatabaseContext extends ContextWrapper {
        private final File directory;

        EditorDatabaseContext(Context base, File directory) {
            super(base);
            this.directory = directory;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public File getFilesDir() {
            return new File(directory, "files");
        }

        @Override
        public File getDatabasePath(String name) {
            return new File(directory, name);
        }

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
                    getDatabasePath(name).getAbsolutePath(),
                    factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY,
                    errorHandler
            );
        }
    }
}
