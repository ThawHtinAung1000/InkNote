package wlv.ac.uk.inknote.utils;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;

public final class AttachmentFiles {

    public static final String DIRECTORY_NAME = "post_images";

    public static final class Cleanup {

        private final int removedCount;
        private final boolean pending;

        public Cleanup(
                int removedCount,
                boolean pending
        ) {
            this.removedCount = removedCount;
            this.pending = pending;
        }

        public int getRemovedCount() {
            return removedCount;
        }

        public boolean isPending() {
            return pending;
        }
    }

    private final Context appContext;

    public AttachmentFiles(Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * Removes files that are no longer referenced by SQLite.
     *
     * The post_images directory is reserved for retained images.
     * Future image capture/import staging must use a separate
     * directory.
     *
     * Call through BlogPostService to serialize file and database
     * operations.
     */
    public Cleanup removeUnreferenced(
            Set<String> referencedFileNames
    ) throws IOException {
        AppExecutors.requireWorkerThread();

        Objects.requireNonNull(
                referencedFileNames,
                "referencedFileNames"
        );

        File filesRoot = appContext
                .getFilesDir()
                .getCanonicalFile();

        File imageDirectory = new File(
                filesRoot,
                DIRECTORY_NAME
        );

        if (!imageDirectory.exists()) {
            return new Cleanup(0, false);
        }

        File root = imageDirectory.getCanonicalFile();

        if (!filesRoot.equals(root.getParentFile())
                || !root.isDirectory()) {
            throw new IOException(
                    "Image directory is unavailable."
            );
        }

        File[] files = root.listFiles();

        if (files == null) {
            throw new IOException(
                    "Image directory cannot be read."
            );
        }

        int removedCount = 0;
        boolean pending = false;

        for (File file : files) {
            if (referencedFileNames.contains(file.getName())) {
                continue;
            }

            File canonicalFile = file.getCanonicalFile();

            if (!root.equals(canonicalFile.getParentFile())
                    || file.isDirectory()) {
                pending = true;
                continue;
            }

            if (file.delete()) {
                removedCount++;
            } else if (file.exists()) {
                pending = true;
            }
        }

        return new Cleanup(removedCount, pending);
    }
}