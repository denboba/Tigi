package org.nilak.writer;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Drafts on the phone: one JSON file per draft under files/nilak_writer/&lt;account&gt;/, written atomically
 * (temp file, then rename) so a crash never leaves half a draft.
 */
public final class WriterDrafts {

    private WriterDrafts() {
    }

    private static File dir(int account) {
        final File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "nilak_writer/" + account);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Where the writer keeps its photos (copied and resized when added). */
    public static File mediaDir(int account) {
        final File dir = new File(dir(account), "media");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Saves on a background thread; empty documents are deleted instead of saved. */
    public static void save(int account, WriterDoc doc) {
        if (doc.isEmpty()) {
            delete(account, doc.id);
            return;
        }
        doc.updated = System.currentTimeMillis();
        final String data;
        try {
            data = doc.toJson().toString();
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        final String id = doc.id;
        Utilities.globalQueue.postRunnable(() -> {
            final File target = new File(dir(account), id + ".json");
            final File temp = new File(dir(account), id + ".tmp");
            try (OutputStream out = new FileOutputStream(temp)) {
                out.write(data.getBytes("UTF-8"));
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
            if (!temp.renameTo(target)) {
                FileLog.e("nilak writer: could not save draft " + id);
            }
        });
    }

    public static void delete(int account, String id) {
        Utilities.globalQueue.postRunnable(() -> {
            new File(dir(account), id + ".json").delete();
            new File(dir(account), id + ".tmp").delete();
        });
    }

    public static WriterDoc load(int account, String id) {
        return read(new File(dir(account), id + ".json"));
    }

    /** Every draft, last edited first. Reads files: call off the UI thread for many drafts. */
    public static ArrayList<WriterDoc> list(int account) {
        final ArrayList<WriterDoc> docs = new ArrayList<>();
        final File[] files = dir(account).listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                final WriterDoc doc = read(file);
                if (doc != null) {
                    docs.add(doc);
                }
            }
        }
        Collections.sort(docs, (a, b) -> Long.compare(b.updated, a.updated));
        return docs;
    }

    private static WriterDoc read(File file) {
        if (!file.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            final byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                final int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return WriterDoc.fromJson(new JSONObject(new String(data, 0, read, "UTF-8")));
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
