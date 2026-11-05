package wlv.ac.uk.inknote.utils;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Imports from an in-process provider into a unique test directory, never the user's photos. */
@RunWith(AndroidJUnit4.class)
public final class AttachmentFilesTest {
    private static final Uri PHOTO_URI = Uri.parse("content://inknest-photo-test/selected");
    private File testDirectory;
    private File filesRoot;
    private Context targetContext;

    @Before public void setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        testDirectory = new File(targetContext.getCacheDir(), "photo-test-" + UUID.randomUUID());
        filesRoot = new File(testDirectory, "files");
        assertTrue(filesRoot.mkdirs());
    }

    @After public void tearDown() {
        deleteTree(testDirectory);
    }

    @Test
    public void contentImportNormalizesAlphaAndBoundsSizeBeforeLoadingPreview() throws IOException {
        File source = new File(testDirectory, "selected.png");
        Bitmap input = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(source)) {
            input.eraseColor(Color.TRANSPARENT);
            assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            input.recycle();
        }
        AttachmentFiles storage = storageFor(source);

        AttachmentFiles.ImportedPhoto imported = storage.importPhoto(PHOTO_URI);

        assertEquals("image/jpeg", imported.getMimeType());
        assertTrue(imported.getRelativePath().endsWith(".jpg"));
        assertFalse(imported.getRelativePath().contains("/"));
        File retained = new File(new File(filesRoot, AttachmentFiles.DIRECTORY_NAME),
                imported.getRelativePath());
        assertTrue(retained.isFile());
        assertTrue(source.isFile());
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(retained.getPath(), bounds);
        assertEquals("image/jpeg", bounds.outMimeType);
        assertEquals(2048, bounds.outWidth);
        assertEquals(1024, bounds.outHeight);
        assertDirectoryEmpty(new File(filesRoot, "photo_import_staging"));

        Bitmap preview = storage.loadPreview(imported.getRelativePath(), 512);
        assertNotNull(preview);
        try {
            assertEquals(512, preview.getWidth());
            assertEquals(256, preview.getHeight());
            int pixel = preview.getPixel(0, 0);
            assertTrue(Color.red(pixel) >= 250);
            assertTrue(Color.green(pixel) >= 250);
            assertTrue(Color.blue(pixel) >= 250);
        } finally {
            preview.recycle();
        }
        storage.deleteImportedPhoto(imported.getRelativePath());
        assertFalse(retained.exists());
        assertTrue(source.exists());
    }

    @Test
    public void contentImportAppliesExifRotationBeforeSavingNormalizedJpeg() throws IOException {
        File source = new File(testDirectory, "rotated.jpg");
        Bitmap input = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        byte[] original;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.eraseColor(Color.BLUE);
            assertTrue(input.compress(Bitmap.CompressFormat.JPEG, 95, output));
            original = output.toByteArray();
        } finally {
            input.recycle();
        }
        // A minimal standard EXIF orientation IFD: orientation 6 means rotate 90 degrees.
        byte[] exif = {
                0x45, 0x78, 0x69, 0x66, 0, 0,
                0x49, 0x49, 0x2a, 0, 8, 0, 0, 0,
                1, 0,
                0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0,
                0, 0, 0, 0
        };
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(original, 0, 2);
            output.write(0xff);
            output.write(0xe1);
            output.write(0);
            output.write(exif.length + 2);
            output.write(exif);
            output.write(original, 2, original.length - 2);
        }
        AttachmentFiles storage = storageFor(source);

        AttachmentFiles.ImportedPhoto imported = storage.importPhoto(PHOTO_URI);
        Bitmap preview = storage.loadPreview(imported.getRelativePath(), 200);

        assertNotNull(preview);
        try {
            assertEquals(40, preview.getWidth());
            assertEquals(80, preview.getHeight());
        } finally {
            preview.recycle();
        }
    }

    @Test
    public void invalidAndOversizedContentLeaveNoRetainedOrStagedFiles() throws IOException {
        File invalid = new File(testDirectory, "not-a-photo");
        try (FileOutputStream output = new FileOutputStream(invalid)) {
            output.write("This is text, not encoded image data.".getBytes(StandardCharsets.UTF_8));
        }
        assertImportRejected(storageFor(invalid), PHOTO_URI);
        assertDirectoryEmpty(new File(filesRoot, AttachmentFiles.DIRECTORY_NAME));
        assertDirectoryEmpty(new File(filesRoot, "photo_import_staging"));

        File oversized = new File(testDirectory, "too-large");
        try (RandomAccessFile file = new RandomAccessFile(oversized, "rw")) {
            file.setLength(20L * 1024 * 1024 + 1);
        }
        assertImportRejected(storageFor(oversized), PHOTO_URI);
        assertDirectoryEmpty(new File(filesRoot, AttachmentFiles.DIRECTORY_NAME));
        assertDirectoryEmpty(new File(filesRoot, "photo_import_staging"));
    }

    @Test
    public void pathTraversalAndFileUrisAreRejectedWithoutDeletingOutsideFiles() throws IOException {
        File outside = new File(filesRoot, "outside.jpg");
        try (FileOutputStream output = new FileOutputStream(outside)) {
            output.write(1);
        }
        AttachmentFiles storage = storageFor(outside);

        assertImportRejected(storage, Uri.fromFile(outside));
        assertInvalidRetainedName(storage, "../outside.jpg");
        assertInvalidRetainedName(storage, "..\\outside.jpg");
        assertTrue(outside.exists());
        assertNull(storage.loadPreview("missing.jpg", 200));
    }

    @Test
    public void orphanCleanupNeverDeletesSeparateImportStaging() throws IOException {
        File retainedDirectory = new File(filesRoot, AttachmentFiles.DIRECTORY_NAME);
        File stagingDirectory = new File(filesRoot, "photo_import_staging");
        assertTrue(retainedDirectory.mkdir());
        assertTrue(stagingDirectory.mkdir());
        File orphan = new File(retainedDirectory, "orphan.jpg");
        File staged = new File(stagingDirectory, "active.tmp");
        assertTrue(orphan.createNewFile());
        assertTrue(staged.createNewFile());

        AttachmentFiles.Cleanup cleanup = storageFor(staged).removeUnreferenced(Collections.emptySet());

        assertEquals(1, cleanup.getRemovedCount());
        assertFalse(cleanup.isPending());
        assertFalse(orphan.exists());
        assertTrue(staged.exists());
    }

    private AttachmentFiles storageFor(File selected) {
        ContentProvider provider = new FixtureProvider(selected);
        ProviderInfo info = new ProviderInfo();
        info.authority = "inknest-photo-test";
        provider.attachInfo(targetContext, info);
        ContentResolver resolver = ContentResolver.wrap(provider);
        Context isolated = new ContextWrapper(targetContext) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesRoot; }
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
        return new AttachmentFiles(isolated);
    }

    private static void assertImportRejected(AttachmentFiles storage, Uri uri) {
        try {
            storage.importPhoto(uri);
            fail("Unsupported or invalid photo data must fail.");
        } catch (IOException expected) {
            // The importer must return a checked failure with no completed retained photo.
        }
    }

    private static void assertInvalidRetainedName(AttachmentFiles storage, String name) {
        try {
            storage.loadPreview(name, 200);
            fail("A preview name must stay inside private photo storage.");
        } catch (IOException expected) {
            // No path supplied through metadata may escape the retained directory.
        }
        try {
            storage.deleteImportedPhoto(name);
            fail("Rollback deletion must reject a name outside private photo storage.");
        } catch (IOException expected) {
            // Never remove an arbitrary file from the files root.
        }
    }

    private static void assertDirectoryEmpty(File directory) {
        if (!directory.exists()) return;
        File[] children = directory.listFiles();
        assertNotNull(children);
        assertEquals(0, children.length);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        assertTrue(file.delete());
    }

    private static final class FixtureProvider extends ContentProvider {
        private final File selected;
        FixtureProvider(File selected) { this.selected = selected; }
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) {
            // Deliberately untrusted provider metadata; validation must inspect encoded bytes.
            return "image/jpeg";
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (!"r".equals(mode)) throw new FileNotFoundException("Read-only test provider.");
            return ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                                      String[] arguments, String sortOrder) {
            throw new UnsupportedOperationException("This provider only supplies photo streams.");
        }
        @Override public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException("Read-only test provider.");
        }
        @Override public int delete(Uri uri, String selection, String[] arguments) {
            throw new UnsupportedOperationException("Read-only test provider.");
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
            throw new UnsupportedOperationException("Read-only test provider.");
        }
    }
}
