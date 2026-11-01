package wlv.ac.uk.inknote.data.local;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.Collections;
import java.util.Map;

public final class InkNoteDbHelper extends SQLiteOpenHelper {

    public static final String DATABASE_NAME = "inknest.db";
    public static final int DATABASE_VERSION = 2;

    public static final String POSTS = "blog_posts";
    public static final String ATTACHMENTS = "post_attachments";

    private static final String CREATE_POSTS =
            "CREATE TABLE blog_posts ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "title TEXT, "
                    + "body TEXT NOT NULL "
                    + "CHECK(length(body) > 0), "
                    + "created_at INTEGER NOT NULL "
                    + "CHECK(created_at >= 0), "
                    + "updated_at INTEGER NOT NULL "
                    + "CHECK(updated_at >= created_at), "
                    + "is_draft INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK(is_draft IN (0, 1)), "
                    + "is_favourite INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK(is_favourite IN (0, 1))"
                    + ")";

    private static final String CREATE_ATTACHMENTS =
            "CREATE TABLE post_attachments ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "post_id INTEGER NOT NULL UNIQUE, "
                    + "relative_path TEXT NOT NULL UNIQUE "
                    + "CHECK("
                    + "length(relative_path) > 0 "
                    + "AND relative_path NOT IN ('.', '..') "
                    + "AND instr(relative_path, '/') = 0 "
                    + "AND instr(relative_path, char(92)) = 0"
                    + "), "
                    + "mime_type TEXT NOT NULL "
                    + "CHECK(mime_type LIKE 'image/%'), "
                    + "FOREIGN KEY(post_id) "
                    + "REFERENCES blog_posts(id) "
                    + "ON DELETE CASCADE"
                    + ")";

    private static final String CREATE_POST_ORDER_INDEX =
            "CREATE INDEX idx_blog_posts_created_id "
                    + "ON blog_posts(created_at DESC, id DESC)";

    private interface Migration {
        void apply(SQLiteDatabase database);
    }

    /*
     * Key: source schema version.
     * Value: migration from that version to the next version.
     *
     * Existing posts remain normal, non-favourite posts when
     * upgrading the initial schema. No content or attachments
     * are recreated or discarded.
     */
    private static final Map<Integer, Migration> MIGRATIONS =
            Collections.singletonMap(1, database -> {
                database.execSQL(
                        "ALTER TABLE blog_posts ADD COLUMN "
                                + "is_draft INTEGER NOT NULL DEFAULT 0 "
                                + "CHECK(is_draft IN (0, 1))"
                );
                database.execSQL(
                        "ALTER TABLE blog_posts ADD COLUMN "
                                + "is_favourite INTEGER NOT NULL DEFAULT 0 "
                                + "CHECK(is_favourite IN (0, 1))"
                );
            });

    public InkNoteDbHelper(Context context) {
        super(
                context.getApplicationContext(),
                DATABASE_NAME,
                null,
                DATABASE_VERSION
        );
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        super.onConfigure(database);

        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(CREATE_POSTS);
        database.execSQL(CREATE_ATTACHMENTS);
        database.execSQL(CREATE_POST_ORDER_INDEX);
    }

    @Override
    public void onUpgrade(
            SQLiteDatabase database,
            int oldVersion,
            int newVersion
    ) {
        /*
         * SQLiteOpenHelper already wraps this callback
         * in a transaction.
         */
        for (int version = oldVersion;
             version < newVersion;
             version++) {

            Migration migration = MIGRATIONS.get(version);

            if (migration == null) {
                throw new SQLiteException(
                        "Missing migration from schema version "
                                + version
                );
            }

            migration.apply(database);
        }
    }

    @Override
    public void onDowngrade(
            SQLiteDatabase database,
            int oldVersion,
            int newVersion
    ) {
        throw new SQLiteException(
                "Database downgrade is not supported."
        );
    }
}