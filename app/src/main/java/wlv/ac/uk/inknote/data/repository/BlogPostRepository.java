package wlv.ac.uk.inknote.data.repository;


import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import wlv.ac.uk.inknote.data.local.InkNoteDbHelper;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.model.PostAttachment;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.InputValidator;
import wlv.ac.uk.inknote.utils.SearchUtils;

public final class BlogPostRepository {

    private static final int DELETE_CHUNK_SIZE = 900;

    private static final String SELECT_POST =
            "SELECT "
                    + "p.id AS p_id, "
                    + "p.title AS p_title, "
                    + "p.body AS p_body, "
                    + "p.created_at AS p_created_at, "
                    + "p.updated_at AS p_updated_at, "
                    + "a.id AS a_id, "
                    + "a.post_id AS a_post_id, "
                    + "a.relative_path AS a_relative_path, "
                    + "a.mime_type AS a_mime_type "
                    + "FROM blog_posts p "
                    + "LEFT JOIN post_attachments a "
                    + "ON a.post_id = p.id";

    private static final String NEWEST_FIRST =
            " ORDER BY p.created_at DESC, p.id DESC";

    private static final String SEARCH_SELECTION =
            " WHERE ("
                    + "p.title LIKE ? ESCAPE '\\' "
                    + "OR p.body LIKE ? ESCAPE '\\'"
                    + ")";

    private final InkNoteDbHelper helper;

    public BlogPostRepository(InkNoteDbHelper helper) {
        this.helper = Objects.requireNonNull(
                helper,
                "helper"
        );
    }

    private static BlogPost mapPost(Cursor cursor) {
        int attachmentIdColumn =
                cursor.getColumnIndexOrThrow("a_id");

        PostAttachment attachment = null;

        if (!cursor.isNull(attachmentIdColumn)) {
            attachment = new PostAttachment(
                    cursor.getLong(attachmentIdColumn),

                    cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                    "a_post_id"
                            )
                    ),

                    cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                    "a_relative_path"
                            )
                    ),

                    cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                    "a_mime_type"
                            )
                    )
            );
        }

        return new BlogPost(
                cursor.getLong(
                        cursor.getColumnIndexOrThrow("p_id")
                ),

                cursor.getString(
                        cursor.getColumnIndexOrThrow("p_title")
                ),

                cursor.getString(
                        cursor.getColumnIndexOrThrow("p_body")
                ),

                cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                                "p_created_at"
                        )
                ),

                cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                                "p_updated_at"
                        )
                ),

                attachment
        );
    }

    private static ContentValues textValues(
            String title,
            String body
    ) {
        ContentValues values = new ContentValues();

        if (title == null) {
            values.putNull("title");
        } else {
            values.put("title", title);
        }

        values.put("body", body);

        return values;
    }

    /**
     * Complete metadata mapping for the later image stage.
     * Generated attachment IDs are excluded from insert values.
     */
    static ContentValues attachmentValues(
            PostAttachment attachment
    ) {
        ContentValues values = new ContentValues();

        values.put(
                "post_id",
                attachment.getPostId()
        );

        values.put(
                "relative_path",
                attachment.getRelativePath()
        );

        values.put(
                "mime_type",
                attachment.getMimeType()
        );

        return values;
    }

    public BlogPost insert(
            String title,
            String body,
            long timestamp
    ) {
        SQLiteDatabase database = writableDatabase();

        ContentValues values = textValues(title, body);

        values.put("created_at", timestamp);
        values.put("updated_at", timestamp);

        long id = database.insertOrThrow(
                InkNoteDbHelper.POSTS,
                null,
                values
        );

        if (id <= 0) {
            throw new SQLiteException(
                    "Message insertion returned an invalid ID."
            );
        }

        return new BlogPost(
                id,
                title,
                body,
                timestamp,
                timestamp,
                null
        );
    }

    /**
     * Returns null if the post does not exist.
     */
    public BlogPost findById(long id) {
        return findById(readableDatabase(), id);
    }

    /**
     * Stable newest-first ordering:
     * created_at DESC, id DESC.
     */
    public List<BlogPost> findPage(
            int limit,
            int offset
    ) {
        InputValidator.requireValidPage(limit, offset);

        String sql = SELECT_POST
                + NEWEST_FIRST
                + " LIMIT ? OFFSET ?";

        String[] arguments = {
                Integer.toString(limit),
                Integer.toString(offset)
        };

        return queryPosts(
                readableDatabase(),
                sql,
                arguments
        );
    }

    /**
     * Empty query returns the normal paginated list.
     * <p>
     * Default SQLite LIKE matching is case-insensitive for ASCII
     * letters. It does not provide full Unicode case folding.
     * <p>
     * Myanmar text is stored and matched without normalization.
     */
    public List<BlogPost> search(
            String query,
            int limit,
            int offset
    ) {
        Objects.requireNonNull(query, "query");

        InputValidator.requireValidPage(limit, offset);

        if (query.isEmpty()) {
            return findPage(limit, offset);
        }

        String pattern =
                SearchUtils.literalContainsPattern(query);

        String sql = SELECT_POST
                + SEARCH_SELECTION
                + NEWEST_FIRST
                + " LIMIT ? OFFSET ?";

        String[] arguments = {
                pattern,
                pattern,
                Integer.toString(limit),
                Integer.toString(offset)
        };

        return queryPosts(
                readableDatabase(),
                sql,
                arguments
        );
    }

    /**
     * Returns null if the post does not exist.
     * <p>
     * Preserves created_at and attachment metadata.
     */
    public BlogPost update(
            long id,
            String title,
            String body,
            long timestamp
    ) {
        SQLiteDatabase database = writableDatabase();

        database.beginTransaction();

        try {
            BlogPost existing = findById(database, id);

            if (existing == null) {
                database.setTransactionSuccessful();
                return null;
            }

            long updatedAt = Math.max(
                    timestamp,
                    existing.getUpdatedAt()
            );

            ContentValues values = textValues(title, body);
            values.put("updated_at", updatedAt);

            int affectedRows = database.update(
                    InkNoteDbHelper.POSTS,
                    values,
                    "id = ?",
                    new String[]{
                            Long.toString(id)
                    }
            );

            if (affectedRows != 1) {
                throw new SQLiteException(
                        "Unexpected message update row count."
                );
            }

            BlogPost updated = new BlogPost(
                    id,
                    title,
                    body,
                    existing.getCreatedAt(),
                    updatedAt,
                    existing.getAttachment()
            );

            database.setTransactionSuccessful();

            return updated;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Deletes only the supplied IDs.
     * <p>
     * IDs are validated and deduplicated.
     * Empty selection returns zero.
     * Missing IDs are ignored.
     * All chunks share one transaction.
     */
    public int deletePosts(List<Long> selectedIds) {
        AppExecutors.requireWorkerThread();

        List<Long> ids =
                InputValidator.uniqueIds(selectedIds);

        if (ids.isEmpty()) {
            return 0;
        }

        SQLiteDatabase database = writableDatabase();

        int deletedCount = 0;

        database.beginTransaction();

        try {
            for (int start = 0;
                 start < ids.size();
                 start += DELETE_CHUNK_SIZE) {

                int end = Math.min(
                        start + DELETE_CHUNK_SIZE,
                        ids.size()
                );

                int size = end - start;

                StringBuilder placeholders =
                        new StringBuilder();

                String[] arguments = new String[size];

                for (int index = 0; index < size; index++) {
                    if (index > 0) {
                        placeholders.append(",");
                    }

                    placeholders.append("?");

                    arguments[index] = Long.toString(
                            ids.get(start + index)
                    );
                }

                String selection =
                        "id IN (" + placeholders + ")";

                int affectedRows = database.delete(
                        InkNoteDbHelper.POSTS,
                        selection,
                        arguments
                );

                if (affectedRows < 0 || affectedRows > size) {
                    throw new SQLiteException(
                            "Unexpected message deletion row count."
                    );
                }

                deletedCount += affectedRows;
            }

            database.setTransactionSuccessful();

            return deletedCount;
        } finally {
            database.endTransaction();
        }
    }

    public Set<String> getReferencedAttachmentPaths() {
        SQLiteDatabase database = readableDatabase();

        Set<String> paths = new HashSet<>();

        try (Cursor cursor = database.query(
                InkNoteDbHelper.ATTACHMENTS,
                new String[]{
                        "relative_path"
                },
                null,
                null,
                null,
                null,
                null
        )) {
            int pathColumn = cursor.getColumnIndexOrThrow(
                    "relative_path"
            );

            while (cursor.moveToNext()) {
                paths.add(cursor.getString(pathColumn));
            }
        }

        return Collections.unmodifiableSet(paths);
    }

    private BlogPost findById(
            SQLiteDatabase database,
            long id
    ) {
        String sql = SELECT_POST + " WHERE p.id = ?";

        String[] arguments = {
                Long.toString(id)
        };

        try (Cursor cursor = database.rawQuery(
                sql,
                arguments
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }

            return mapPost(cursor);
        }
    }

    private List<BlogPost> queryPosts(
            SQLiteDatabase database,
            String sql,
            String[] arguments
    ) {
        List<BlogPost> posts = new ArrayList<>();

        try (Cursor cursor = database.rawQuery(
                sql,
                arguments
        )) {
            while (cursor.moveToNext()) {
                posts.add(mapPost(cursor));
            }
        }

        return Collections.unmodifiableList(posts);
    }

    private SQLiteDatabase readableDatabase() {
        AppExecutors.requireWorkerThread();

        return helper.getReadableDatabase();
    }

    private SQLiteDatabase writableDatabase() {
        AppExecutors.requireWorkerThread();

        return helper.getWritableDatabase();
    }
}