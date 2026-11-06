package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;

/**
 * Where the person stopped in each Nilak article, kept on the phone only. Read shows the last unfinished article
 * as "Continue reading", and the reader opens it at the same place.
 */
public final class TigiReadingProgress {

    private static final int KEEP = 40;

    public static final class Entry {
        public long dialogId;
        public int messageId;
        public String title;
        public float fraction;
        public long time;
    }

    private TigiReadingProgress() {
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("nilak_reading_" + account, Context.MODE_PRIVATE);
    }

    private static String key(long dialogId, int messageId) {
        return dialogId + "_" + messageId;
    }

    public static float fraction(int account, MessageObject message) {
        return prefs(account).getFloat("f_" + key(message.getDialogId(), message.getId()), 0f);
    }

    public static void save(int account, MessageObject message, String title, float fraction) {
        final SharedPreferences prefs = prefs(account);
        final String k = key(message.getDialogId(), message.getId());
        final SharedPreferences.Editor editor = prefs.edit()
            .putFloat("f_" + k, fraction)
            .putString("t_" + k, title != null ? title : "")
            .putLong("w_" + k, System.currentTimeMillis());
        if (fraction > 0.03f && fraction < 0.97f) {
            editor.putString("last", k);
        } else if (k.equals(prefs.getString("last", null))) {
            // finished (or barely started): nothing to continue
            editor.remove("last");
        }
        editor.apply();
        trim(prefs);
    }

    /** The last article left unfinished, or null. */
    public static Entry last(int account) {
        final SharedPreferences prefs = prefs(account);
        final String k = prefs.getString("last", null);
        if (TextUtils.isEmpty(k)) {
            return null;
        }
        final int split = k.lastIndexOf('_');
        if (split <= 0) {
            return null;
        }
        final Entry entry = new Entry();
        try {
            entry.dialogId = Long.parseLong(k.substring(0, split));
            entry.messageId = Integer.parseInt(k.substring(split + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        entry.title = prefs.getString("t_" + k, "");
        entry.fraction = prefs.getFloat("f_" + k, 0f);
        entry.time = prefs.getLong("w_" + k, 0);
        return entry;
    }

    /** Keeps the newest few dozen articles; older positions are dropped. */
    private static void trim(SharedPreferences prefs) {
        final java.util.Map<String, ?> all = prefs.getAll();
        int count = 0;
        for (String name : all.keySet()) {
            if (name.startsWith("w_")) {
                count++;
            }
        }
        if (count <= KEEP) {
            return;
        }
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getKey().startsWith("w_") && e.getValue() instanceof Long && (Long) e.getValue() < oldestTime) {
                oldestTime = (Long) e.getValue();
                oldest = e.getKey().substring(2);
            }
        }
        if (oldest != null) {
            prefs.edit().remove("w_" + oldest).remove("f_" + oldest).remove("t_" + oldest).apply();
        }
    }
}
