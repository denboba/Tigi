package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.LruCache;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.Locale;

/**
 * Nilak: PDF pages drawn with Android's own PdfRenderer, always off the main thread on one queue (the renderer is not
 * thread-safe), with bounded bitmap sizes and a small memory cache. Damaged, huge or password-protected files fail
 * quietly to "can't show" instead of crashing.
 */
public final class TigiPdf {

    /** Larger files open in another app: rendering them here would be slow and memory-hungry. */
    public static final long MAX_SIZE = 150L * 1024 * 1024;
    /** No page bitmap taller than this many times its width (very long pages are cut, the reader still shows them). */
    private static final float MAX_ASPECT = 3f;

    static final DispatchQueue queue = new DispatchQueue("NilakPdf");

    private static final LruCache<String, Bitmap> firstPages = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private TigiPdf() {
    }

    public static boolean isPdf(TLRPC.Document document) {
        if (document == null) {
            return false;
        }
        if ("application/pdf".equalsIgnoreCase(document.mime_type)) {
            return true;
        }
        final String name = FileLoader.getDocumentFileName(document);
        return !TextUtils.isEmpty(name) && name.toLowerCase(Locale.US).endsWith(".pdf");
    }

    /** The first page at the given width, from the cache or rendered now; null (on the main thread) when it can't be. */
    public static void firstPage(File file, int width, Utilities.Callback<Bitmap> onDone) {
        final String key = file.getAbsolutePath() + "_" + file.length() + "_" + width;
        final Bitmap cached = firstPages.get(key);
        if (cached != null && !cached.isRecycled()) {
            onDone.run(cached);
            return;
        }
        if (file.length() > MAX_SIZE) {
            onDone.run(null);
            return;
        }
        queue.postRunnable(() -> {
            Bitmap bitmap = null;
            Document document = null;
            try {
                document = Document.open(file);
                if (document != null && document.pageCount() > 0) {
                    bitmap = document.render(0, width);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (document != null) {
                    document.close();
                }
            }
            final Bitmap result = bitmap;
            if (result != null) {
                firstPages.put(key, result);
            }
            AndroidUtilities.runOnUIThread(() -> onDone.run(result));
        });
    }

    /** An open PDF; every call must run on {@link #queue}. */
    static final class Document {
        private final ParcelFileDescriptor descriptor;
        private final PdfRenderer renderer;

        private Document(ParcelFileDescriptor descriptor, PdfRenderer renderer) {
            this.descriptor = descriptor;
            this.renderer = renderer;
        }

        /** Null when the file can't be read; throws SecurityException for password-protected files. */
        static Document open(File file) throws Exception {
            if (file == null || !file.exists() || file.length() == 0 || file.length() > MAX_SIZE) {
                return null;
            }
            final ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                return new Document(descriptor, new PdfRenderer(descriptor));
            } catch (Exception e) {
                descriptor.close();
                throw e;
            }
        }

        int pageCount() {
            return renderer.getPageCount();
        }

        /** Height / width of a page. */
        float aspect(int index) {
            final PdfRenderer.Page page = renderer.openPage(index);
            try {
                return page.getWidth() > 0 ? page.getHeight() / (float) page.getWidth() : 1.414f;
            } finally {
                page.close();
            }
        }

        Bitmap render(int index, int width) {
            final PdfRenderer.Page page = renderer.openPage(index);
            try {
                final int w = Math.max(1, Math.min(width, 2048));
                final float aspect = page.getWidth() > 0 ? page.getHeight() / (float) page.getWidth() : 1.414f;
                final int h = Math.max(1, (int) (w * Math.min(aspect, MAX_ASPECT)));
                final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE); // PDF pages are transparent where nothing is drawn
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } finally {
                page.close();
            }
        }

        void close() {
            try {
                renderer.close();
            } catch (Throwable ignore) {
            }
            try {
                descriptor.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
