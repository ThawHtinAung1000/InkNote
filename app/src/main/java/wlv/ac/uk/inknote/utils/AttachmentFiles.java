package wlv.ac.uk.inknote.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AttachmentFiles {

    public static final String DIRECTORY_NAME = "post_images";

    private static final String STAGING_DIRECTORY_NAME = "photo_import_staging";
    private static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 100_000_000L;
    private static final int MAX_IMAGE_EDGE = 2048;

    public static final class ImportedPhoto {
        private final String relativePath;
        private final String mimeType;

        private ImportedPhoto(String relativePath, String mimeType) {
            this.relativePath = relativePath;
            this.mimeType = mimeType;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getMimeType() {
            return mimeType;
        }
    }

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
        appContext = Objects.requireNonNull(context, "context").getApplicationContext();
    }

    /**
     * Imports a user-selected content URI into private storage. The input is bounded
     * before decoding, validated from its actual encoded bytes, then normalized to
     * a JPEG with its orientation applied and its longest edge at most 2048 pixels.
     *
     * Call through BlogPostService so a retained import and its database reference
     * cannot race file cleanup. A failed database write must call deleteImportedPhoto.
     */
    public ImportedPhoto importPhoto(Uri uri) throws IOException {
        AppExecutors.requireWorkerThread();
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Choose a photo using the device photo picker.");
        }

        File source = null;
        File encoded = null;
        File retained = null;
        Bitmap decoded = null;
        Bitmap flattened = null;
        boolean completed = false;

        try {
            checkInterrupted();
            File filesRoot = filesRoot();
            File stagingDirectory = privateDirectory(filesRoot, STAGING_DIRECTORY_NAME, true);
            File imageDirectory = privateDirectory(filesRoot, DIRECTORY_NAME, true);
            source = File.createTempFile("source-", ".tmp", stagingDirectory);

            ContentResolver resolver = appContext.getContentResolver();
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some picker/provider grants are transient but remain valid for this copy.
            }

            copyBounded(resolver, uri, source);
            validateImage(source);
            checkInterrupted();
            decoded = decodeBounded(source, MAX_IMAGE_EDGE);
            if (decoded == null) {
                throw new IOException("The selected file is not a readable photo.");
            }

            Bitmap toEncode = decoded;
            if (decoded.hasAlpha()) {
                flattened = Bitmap.createBitmap(
                        decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(flattened);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(decoded, 0f, 0f, null);
                toEncode = flattened;
            }

            checkInterrupted();
            encoded = File.createTempFile("normalized-", ".jpg", stagingDirectory);
            try (FileOutputStream output = new FileOutputStream(encoded)) {
                if (!toEncode.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw new IOException("The selected photo could not be saved.");
                }
                output.flush();
                output.getFD().sync();
            }

            checkInterrupted();
            String name = UUID.randomUUID() + ".jpg";
            File destination = new File(imageDirectory, name);
            if (!imageDirectory.equals(destination.getCanonicalFile().getParentFile())
                    || destination.exists() || !encoded.renameTo(destination)) {
                throw new IOException("The selected photo could not be saved.");
            }
            retained = destination;

            completed = true;
            return new ImportedPhoto(name, "image/jpeg");
        } catch (RuntimeException failure) {
            // Provider errors can contain private URIs; callers receive a safe message.
            throw new IOException("The selected photo could not be imported.", failure);
        } finally {
            if (flattened != null) flattened.recycle();
            if (decoded != null) decoded.recycle();
            if (source != null) source.delete();
            if (encoded != null) encoded.delete();
            if (!completed && retained != null) retained.delete();
        }
    }

    /** Removes only the named retained import when a database write is rolled back. */
    public void deleteImportedPhoto(String name) throws IOException {
        AppExecutors.requireWorkerThread();
        File file = retainedFile(name);
        if (file.exists() && !file.delete()) {
            throw new IOException("The unused photo could not be removed.");
        }
    }

    /**
     * Returns null for a missing photo. The caller owns and must eventually recycle
     * the returned bitmap. File access and decoding remain on the shared I/O executor.
     */
    public Bitmap loadPreview(String name, int maxEdge) throws IOException {
        AppExecutors.requireWorkerThread();
        if (maxEdge < 1 || maxEdge > MAX_IMAGE_EDGE) {
            throw new IOException("The photo preview size is invalid.");
        }
        File file = retainedFile(name);
        if (!file.exists()) return null;
        if (!file.isFile() || !file.canRead()) {
            throw new IOException("The saved photo is currently unavailable.");
        }
        validateImage(file);
        try {
            return decodeBounded(file, maxEdge);
        } catch (RuntimeException failure) {
            throw new IOException("The saved photo could not be displayed.", failure);
        }
    }

    private static void copyBounded(ContentResolver resolver, Uri uri, File destination)
            throws IOException {
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("The selected photo could not be opened.");
            }
            byte[] buffer = new byte[16 * 1024];
            long count = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkInterrupted();
                count += read;
                if (count > MAX_IMPORT_BYTES) {
                    throw new IOException("Choose a photo smaller than 20 MB.");
                }
                output.write(buffer, 0, read);
            }
            if (count == 0L) {
                throw new IOException("The selected photo is empty.");
            }
        }
    }

    private static void validateImage(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outMimeType == null || !bounds.outMimeType.startsWith("image/")
                || (long) bounds.outWidth * bounds.outHeight > MAX_IMAGE_PIXELS) {
            throw new IOException("Choose a readable photo smaller than 100 megapixels.");
        }
    }

    private static Bitmap decodeBounded(File file, int maxEdge) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                throw new IllegalArgumentException("Invalid image dimensions.");
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            decoder.setOnPartialImageListener(failure -> false);
            int longest = Math.max(width, height);
            if (longest > maxEdge) {
                double scale = (double) maxEdge / longest;
                decoder.setTargetSize(
                        Math.max(1, (int) Math.round(width * scale)),
                        Math.max(1, (int) Math.round(height * scale)));
            }
        });
    }

    private File filesRoot() throws IOException {
        File root = appContext.getFilesDir().getCanonicalFile();
        if (!root.isDirectory()) {
            throw new IOException("Photo storage is currently unavailable.");
        }
        return root;
    }

    private static File privateDirectory(File filesRoot, String name, boolean create)
            throws IOException {
        File directory = new File(filesRoot, name).getCanonicalFile();
        if (!filesRoot.equals(directory.getParentFile())) {
            throw new IOException("Photo storage is currently unavailable.");
        }
        if (create && !directory.isDirectory() && !directory.mkdir()) {
            throw new IOException("Photo storage could not be created.");
        }
        if (directory.exists() && !directory.isDirectory()) {
            throw new IOException("Photo storage is currently unavailable.");
        }
        return directory;
    }

    private File retainedFile(String name) throws IOException {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("The saved photo name is invalid.");
        }
        File directory = privateDirectory(filesRoot(), DIRECTORY_NAME, false);
        File file = new File(directory, name);
        if (!directory.equals(file.getCanonicalFile().getParentFile()) || file.isDirectory()) {
            throw new IOException("The saved photo is currently unavailable.");
        }
        return file;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Photo import was interrupted.");
        }
    }

    /**
     * Removes files that are no longer referenced by SQLite.
     *
     * The post_images directory is reserved for retained images.
     * Photo imports use a separate staging directory and move
     * into this directory only after normalization completes.
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