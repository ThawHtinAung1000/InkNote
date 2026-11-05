package wlv.ac.uk.inknote.data.local;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class InkNoteDbHelper extends SQLiteOpenHelper {

    public static final String DATABASE_NAME = "inknest.db";
    public static final int DATABASE_VERSION = 3;

    public static final String POSTS = "blog_posts";
    public static final String ATTACHMENTS = "post_attachments";

    private static final String CREATE_POST_ORDER_INDEX =
            "CREATE INDEX idx_blog_posts_created_id "
                    + "ON blog_posts(created_at DESC, id DESC)";

    private interface Migration {
        void apply(SQLiteDatabase database);
    }

    // Key: source schema version; value: migration to the next version.
    private static final Map<Integer, Migration> MIGRATIONS = migrations();

    public InkNoteDbHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    private static String createPostsTable(String table) {
        return "CREATE TABLE " + table + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT, "
                + "body TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL CHECK(created_at >= 0), "
                + "updated_at INTEGER NOT NULL CHECK(updated_at >= created_at), "
                + "is_draft INTEGER NOT NULL DEFAULT 0 CHECK(is_draft IN (0, 1)), "
                + "is_favourite INTEGER NOT NULL DEFAULT 0 CHECK(is_favourite IN (0, 1)), "
                + "body_format TEXT NOT NULL DEFAULT '', "
                + "tags_json TEXT NOT NULL DEFAULT '[]', "
                + "editor_key TEXT UNIQUE, "
                + "CHECK(is_draft = 1 OR length(body) > 0)"
                + ")";
    }

    private static String createAttachmentsTable(String table, String postsTable) {
        return "CREATE TABLE " + table + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "post_id INTEGER NOT NULL UNIQUE, "
                + "relative_path TEXT NOT NULL UNIQUE CHECK("
                + "length(relative_path) > 0 AND relative_path NOT IN ('.', '..') "
                + "AND instr(relative_path, '/') = 0 "
                + "AND instr(relative_path, char(92)) = 0), "
                + "mime_type TEXT NOT NULL CHECK(mime_type LIKE 'image/%'), "
                + "FOREIGN KEY(post_id) REFERENCES " + postsTable + "(id) ON DELETE CASCADE"
                + ")";
    }

    private static Map<Integer, Migration> migrations() {
        Map<Integer, Migration> migrations = new HashMap<>();
        migrations.put(1, database -> {
            database.execSQL(
                    "ALTER TABLE blog_posts ADD COLUMN "
                            + "is_draft INTEGER NOT NULL DEFAULT 0 CHECK(is_draft IN (0, 1))"
            );
            database.execSQL(
                    "ALTER TABLE blog_posts ADD COLUMN "
                            + "is_favourite INTEGER NOT NULL DEFAULT 0 CHECK(is_favourite IN (0, 1))"
            );
        });
        migrations.put(2, InkNoteDbHelper::migrateToVersionThree);
        return Collections.unmodifiableMap(migrations);
    }

    private static void migrateToVersionThree(SQLiteDatabase database) {
        // Rebuild both sides of the foreign key without disabling enforcement.
        // SQLiteOpenHelper wraps this entire migration in its transaction.
        String newPosts = "blog_posts_v3";
        String newAttachments = "post_attachments_v3";
        database.execSQL(createPostsTable(newPosts));
        database.execSQL(
                "INSERT INTO blog_posts_v3 "
                        + "(id, title, body, created_at, updated_at, is_draft, is_favourite) "
                        + "SELECT id, title, body, created_at, updated_at, is_draft, is_favourite "
                        + "FROM blog_posts"
        );
        database.execSQL(createAttachmentsTable(newAttachments, newPosts));
        database.execSQL(
                "INSERT INTO post_attachments_v3 (id, post_id, relative_path, mime_type) "
                        + "SELECT id, post_id, relative_path, mime_type FROM post_attachments"
        );
        preserveSequence(database, POSTS, newPosts);
        preserveSequence(database, ATTACHMENTS, newAttachments);

        // Drop the old child before its parent so ON DELETE CASCADE cannot touch retained rows.
        database.execSQL("DROP TABLE post_attachments");
        database.execSQL("DROP TABLE blog_posts");
        database.execSQL("ALTER TABLE blog_posts_v3 RENAME TO blog_posts");
        database.execSQL("ALTER TABLE post_attachments_v3 RENAME TO post_attachments");
        database.execSQL(CREATE_POST_ORDER_INDEX);
    }

    private static void preserveSequence(SQLiteDatabase database, String oldTable, String newTable) {
        // A deleted highest ID must not become available again after a migration.
        database.execSQL(
                "INSERT INTO sqlite_sequence (name, seq) "
                        + "SELECT ?, seq FROM sqlite_sequence WHERE name = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = ?)",
                new Object[]{newTable, oldTable, newTable}
        );
        database.execSQL(
                "UPDATE sqlite_sequence SET seq = MAX(seq, "
                        + "COALESCE((SELECT seq FROM sqlite_sequence WHERE name = ?), 0)) "
                        + "WHERE name = ?",
                new Object[]{oldTable, newTable}
        );
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        super.onConfigure(database);
        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(createPostsTable(POSTS));
        database.execSQL(createAttachmentsTable(ATTACHMENTS, POSTS));
        database.execSQL(CREATE_POST_ORDER_INDEX);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        for (int version = oldVersion; version < newVersion; version++) {
            Migration migration = MIGRATIONS.get(version);
            if (migration == null) {
                throw new SQLiteException("Missing migration from schema version " + version);
            }
            migration.apply(database);
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new SQLiteException("Database downgrade is not supported.");
    }
}
