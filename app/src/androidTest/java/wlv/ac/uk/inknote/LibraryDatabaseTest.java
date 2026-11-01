package wlv.ac.uk.inknote;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
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

/** Real SQLite checks; every test uses a separate database inside a unique cache directory. */
@RunWith(AndroidJUnit4.class)
public final class LibraryDatabaseTest {

    private File testDirectory;
    private IsolatedDatabaseContext databaseContext;
    private InkNoteDbHelper helper;
    private BlogPostRepository repository;
    private BlogPostService service;

    @Before
    public void setUp() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        testDirectory = new File(appContext.getCacheDir(), "library-db-test-" + UUID.randomUUID());
        assertTrue(testDirectory.mkdirs());
        databaseContext = new IsolatedDatabaseContext(appContext, testDirectory);
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
    public void versionOneUpgradePreservesPostsAttachmentsAndDefaults() {
        createVersionOneFixture();

        BlogPost retained = repository.findById(37L);
        assertNotNull(retained);
        assertEquals(37L, retained.getId());
        assertEquals("Before migration", retained.getTitle());
        assertEquals("Retained body", retained.getBody());
        assertEquals(100L, retained.getCreatedAt());
        assertEquals(150L, retained.getUpdatedAt());
        assertFalse(retained.isDraft());
        assertFalse(retained.isFavourite());
        assertNotNull(retained.getAttachment());
        assertEquals(9L, retained.getAttachment().getId());
        assertEquals(37L, retained.getAttachment().getPostId());
        assertEquals("retained.jpg", retained.getAttachment().getRelativePath());
        assertEquals("image/jpeg", retained.getAttachment().getMimeType());
        assertEquals(2, helper.getReadableDatabase().getVersion());
        assertEquals(1, repository.findLibrary("").size());

        assertInvalidFlagRejected(37L, "is_draft");
        assertInvalidFlagRejected(37L, "is_favourite");

        BlogPost next = repository.insert("After migration", "New body", 200L);
        assertTrue(next.getId() > retained.getId());
    }

    @Test
    public void flagChangesPersistAndTextEditsPreserveBothFlags() {
        BlogPost original = repository.insert("Original title", "Original body", 100L);

        ServiceResult<BlogPost> favourite = service.setFavourite(original.getId(), true);
        assertTrue(favourite.isSuccess());
        assertTrue(favourite.getData().isFavourite());
        assertEquals(100L, favourite.getData().getUpdatedAt());

        BlogPost draft = repository.setDraft(original.getId(), true, 200L);
        assertNotNull(draft);
        assertTrue(draft.isDraft());
        assertTrue(draft.isFavourite());
        assertEquals(200L, draft.getUpdatedAt());

        // Reapplying a status does not make a new edit or advance its timestamp.
        BlogPost unchanged = repository.setDraft(original.getId(), true, 500L);
        assertNotNull(unchanged);
        assertEquals(200L, unchanged.getUpdatedAt());

        BlogPost edited = repository.update(
                original.getId(), "Edited title", "Edited body", 300L
        );
        assertNotNull(edited);
        assertTrue(edited.isDraft());
        assertTrue(edited.isFavourite());
        assertEquals(100L, edited.getCreatedAt());
        assertEquals(300L, edited.getUpdatedAt());

        BlogPost persisted = repository.findById(original.getId());
        assertNotNull(persisted);
        assertTrue(persisted.isDraft());
        assertTrue(persisted.isFavourite());
        assertEquals("Edited body", persisted.getBody());

        ServiceResult<BlogPost> unfavourite = service.setFavourite(original.getId(), false);
        assertTrue(unfavourite.isSuccess());
        assertFalse(unfavourite.getData().isFavourite());
        assertTrue(unfavourite.getData().isDraft());
        assertEquals(300L, unfavourite.getData().getUpdatedAt());

        ServiceResult<BlogPost> normalPost = service.setDraft(original.getId(), false);
        assertTrue(normalPost.isSuccess());
        assertFalse(normalPost.getData().isDraft());
        assertFalse(normalPost.getData().isFavourite());
        assertTrue(normalPost.getData().getUpdatedAt() >= 300L);
        assertEquals("Edited title", normalPost.getData().getTitle());
    }

    @Test
    public void librarySearchTreatsLikeWildcardsAndEscapeAsLiteralText() {
        BlogPost percent = repository.insert("100% ready", "Plain text", 100L);
        BlogPost underscore = repository.insert("under_score", "Plain text", 200L);
        BlogPost slash = repository.insert("A path", "C:\\notes", 300L);
        repository.insert("100 percent ready", "under score", 400L);

        assertOnlyMatch("%", percent.getId());
        assertOnlyMatch("_", underscore.getId());
        assertOnlyMatch("\\", slash.getId());
        assertOnlyMatch("100%", percent.getId());
        assertOnlyMatch("under_", underscore.getId());
    }

    @Test
    public void libraryReturnsAllPostsWithStableOrderingAndFreshDefaults() {
        long firstId = 0L;
        long lastId = 0L;
        for (int index = 0; index < 55; index++) {
            // Equal times exercise the ID tie breaker rather than clock timing.
            BlogPost post = repository.insert("Post " + index, "Body", 100L);
            if (index == 0) {
                firstId = post.getId();
            }
            lastId = post.getId();
        }

        ServiceResult<List<BlogPost>> result = service.getLibraryPosts("");
        assertTrue(result.isSuccess());
        assertEquals(55, result.getData().size());
        assertEquals(lastId, result.getData().get(0).getId());
        assertEquals(firstId, result.getData().get(54).getId());
        for (BlogPost post : result.getData()) {
            assertFalse(post.isDraft());
            assertFalse(post.isFavourite());
        }

        assertInvalidFlagRejected(firstId, "is_draft");
        assertInvalidFlagRejected(firstId, "is_favourite");
    }

    private void assertOnlyMatch(String query, long expectedId) {
        ServiceResult<List<BlogPost>> result = service.getLibraryPosts(query);
        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().size());
        assertEquals(expectedId, result.getData().get(0).getId());
    }

    private void assertInvalidFlagRejected(long postId, String column) {
        ContentValues invalid = new ContentValues();
        invalid.put(column, 2);
        try {
            helper.getWritableDatabase().update(
                    InkNoteDbHelper.POSTS,
                    invalid,
                    "id = ?",
                    new String[]{Long.toString(postId)}
            );
            fail("Boolean metadata must reject values other than zero or one.");
        } catch (SQLiteConstraintException expected) {
            // Both freshly created and migrated databases must enforce the constraint.
        }
    }

    private void createVersionOneFixture() {
        try (SQLiteDatabase database = databaseContext.openOrCreateDatabase(
                InkNoteDbHelper.DATABASE_NAME, Context.MODE_PRIVATE, null
        )) {
            // Historical v1 schema: the fixture must not contain v2 flags.
            database.execSQL(
                    "CREATE TABLE blog_posts ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "title TEXT, "
                            + "body TEXT NOT NULL CHECK(length(body) > 0), "
                            + "created_at INTEGER NOT NULL CHECK(created_at >= 0), "
                            + "updated_at INTEGER NOT NULL CHECK(updated_at >= created_at))"
            );
            database.execSQL(
                    "CREATE TABLE post_attachments ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "post_id INTEGER NOT NULL UNIQUE, "
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
                    "INSERT INTO blog_posts (id, title, body, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    new Object[]{37L, "Before migration", "Retained body", 100L, 150L}
            );
            database.execSQL(
                    "INSERT INTO post_attachments (id, post_id, relative_path, mime_type) "
                            + "VALUES (?, ?, ?, ?)",
                    new Object[]{9L, 37L, "retained.jpg", "image/jpeg"}
            );
            database.setVersion(1);
        }
    }

    /** Prevents SQLiteOpenHelper from falling through to the application's real database. */
    private static final class IsolatedDatabaseContext extends ContextWrapper {
        private final File directory;

        IsolatedDatabaseContext(Context base, File directory) {
            super(base);
            this.directory = directory;
        }

        @Override
        public Context getApplicationContext() {
            return this;
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
