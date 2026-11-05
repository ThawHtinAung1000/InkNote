package wlv.ac.uk.inknote.ui.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.AttachmentFiles;

/** Bounded local previews; no network requests and no file access on the UI thread. */
public final class LocalThumbnailLoader {
    public static final class Request {
        volatile boolean cancelled;
        public void cancel() { cancelled = true; }
    }

    private final Context appContext;
    private final AppExecutors executors;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private volatile boolean closed;

    public LocalThumbnailLoader(Context context, AppExecutors executors) {
        this.appContext = context.getApplicationContext();
        this.executors = executors;
    }

    public Request load(ImageView image, String name) {
        Request request = new Request();
        image.setImageResource(R.drawable.ic_welcome_photo);
        Bitmap cached = cache.get(name);
        if (cached != null) {
            image.setImageBitmap(cached);
            return request;
        }
        WeakReference<ImageView> target = new WeakReference<>(image);
        executors.io().execute(() -> {
            if (closed || request.cancelled) return;
            Bitmap bitmap = decode(name);
            if (closed || request.cancelled || bitmap == null) return;
            cache.put(name, bitmap);
            executors.main().execute(() -> {
                ImageView view = target.get();
                if (!closed && !request.cancelled && view != null) view.setImageBitmap(bitmap);
            });
        });
        return request;
    }

    private Bitmap decode(String name) {
        AppExecutors.requireWorkerThread();
        try {
            File filesRoot = appContext.getFilesDir().getCanonicalFile();
            File directory = new File(filesRoot, AttachmentFiles.DIRECTORY_NAME).getCanonicalFile();
            File file = new File(directory, name).getCanonicalFile();
            if (!filesRoot.equals(directory.getParentFile()) || !directory.equals(file.getParentFile())) return null;
            if (!file.isFile() || !file.canRead()) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 1024 || bounds.outHeight / options.inSampleSize > 1024) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (IOException | SecurityException ignored) {
            // Missing/unreadable images keep the local placeholder. Never log private paths.
            return null;
        }
    }

    public void close() {
        closed = true;
        cache.evictAll();
    }
}
