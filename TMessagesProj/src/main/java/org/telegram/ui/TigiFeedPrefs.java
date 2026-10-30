package org.telegram.ui;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.MessagesController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/**
 * Nilak: what the user told the feeds, kept on the phone only. Saved posts (bookmarks for the profile's Saved tab),
 * channels hidden from Home / Reels / Explore, and single posts marked "Not interested".
 */
public class TigiFeedPrefs {

    private static final String KEY_SAVED = "nilak_saved_posts";
    private static final String KEY_HIDDEN = "nilak_hidden_channels";
    private static final String KEY_NOT_INTERESTED = "nilak_not_interested";
    private static final int MAX_SAVED = 500;
    private static final int MAX_NOT_INTERESTED = 1000;

    private static SharedPreferences prefs(int account) {
        return MessagesController.getMainSettings(account);
    }

    private static String key(long chatId, int messageId) {
        return chatId + ":" + messageId;
    }

    private static ArrayList<String> list(int account, String prefKey) {
        final String raw = prefs(account).getString(prefKey, "");
        final ArrayList<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) {
            Collections.addAll(out, raw.split(","));
        }
        return out;
    }

    private static void store(int account, String prefKey, ArrayList<String> values) {
        prefs(account).edit().putString(prefKey, TextUtils.join(",", values)).apply();
    }

    /* saved */

    public static boolean isSaved(int account, long chatId, int messageId) {
        return list(account, KEY_SAVED).contains(key(chatId, messageId));
    }

    /** Saves or unsaves the post; returns true when it is saved afterwards. Newest first. */
    public static boolean toggleSaved(int account, long chatId, int messageId) {
        final ArrayList<String> saved = list(account, KEY_SAVED);
        final String key = key(chatId, messageId);
        final boolean nowSaved = !saved.remove(key);
        if (nowSaved) {
            saved.add(0, key);
            while (saved.size() > MAX_SAVED) {
                saved.remove(saved.size() - 1);
            }
        }
        store(account, KEY_SAVED, saved);
        return nowSaved;
    }

    /** Saved posts as {chatId, messageId} pairs, newest first. */
    public static ArrayList<long[]> saved(int account) {
        final ArrayList<long[]> out = new ArrayList<>();
        for (String entry : list(account, KEY_SAVED)) {
            final int colon = entry.indexOf(':');
            if (colon > 0) {
                try {
                    out.add(new long[]{Long.parseLong(entry.substring(0, colon)), Long.parseLong(entry.substring(colon + 1))});
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return out;
    }

    /* hidden channels */

    public static boolean isHidden(int account, long chatId) {
        return list(account, KEY_HIDDEN).contains(String.valueOf(chatId));
    }

    public static void setHidden(int account, long chatId, boolean hidden) {
        final ArrayList<String> ids = list(account, KEY_HIDDEN);
        ids.remove(String.valueOf(chatId));
        if (hidden) {
            ids.add(String.valueOf(chatId));
        }
        store(account, KEY_HIDDEN, ids);
    }

    /* not interested */

    public static boolean isNotInterested(int account, long chatId, int messageId) {
        return list(account, KEY_NOT_INTERESTED).contains(key(chatId, messageId));
    }

    public static void setNotInterested(int account, long chatId, int messageId, boolean value) {
        final ArrayList<String> keys = list(account, KEY_NOT_INTERESTED);
        final String key = key(chatId, messageId);
        keys.remove(key);
        if (value) {
            keys.add(0, key);
            while (keys.size() > MAX_NOT_INTERESTED) {
                keys.remove(keys.size() - 1);
            }
        }
        store(account, KEY_NOT_INTERESTED, keys);
    }

    /* affinity: how often the user opens a channel from Explore or Reels, for on-device ordering */

    private static final String KEY_AFFINITY = "nilak_channel_affinity";
    private static final int MAX_AFFINITY = 300;

    private static java.util.LinkedHashMap<String, Integer> affinity(int account) {
        final java.util.LinkedHashMap<String, Integer> map = new java.util.LinkedHashMap<>();
        for (String entry : list(account, KEY_AFFINITY)) {
            final int colon = entry.indexOf(':');
            if (colon > 0) {
                try {
                    map.put(entry.substring(0, colon), Integer.parseInt(entry.substring(colon + 1)));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return map;
    }

    public static void bumpAffinity(int account, long chatId) {
        final java.util.LinkedHashMap<String, Integer> map = affinity(account);
        final String key = String.valueOf(chatId);
        final Integer old = map.remove(key);
        map.put(key, old == null ? 1 : Math.min(old + 1, 1000));
        final ArrayList<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : map.entrySet()) {
            out.add(e.getKey() + ":" + e.getValue());
        }
        // most recently touched last; drop the oldest
        while (out.size() > MAX_AFFINITY) {
            out.remove(0);
        }
        store(account, KEY_AFFINITY, out);
    }

    /** Stable sort: channels opened more often first, the rest keep their order. */
    public static void sortByAffinity(int account, ArrayList<org.telegram.tgnet.TLRPC.Chat> chats) {
        final java.util.LinkedHashMap<String, Integer> map = affinity(account);
        if (map.isEmpty()) {
            return;
        }
        Collections.sort(chats, (a, b) -> {
            final Integer sa = map.get(String.valueOf(a.id));
            final Integer sb = map.get(String.valueOf(b.id));
            return (sb == null ? 0 : sb) - (sa == null ? 0 : sa);
        });
    }

    /** Everything the feeds should skip, read once per load. */
    public static Filter filter(int account) {
        final Filter filter = new Filter();
        filter.hiddenChannels.addAll(list(account, KEY_HIDDEN));
        filter.notInterested.addAll(list(account, KEY_NOT_INTERESTED));
        return filter;
    }

    public static class Filter {
        final HashSet<String> hiddenChannels = new HashSet<>();
        final HashSet<String> notInterested = new HashSet<>();

        public boolean skips(long chatId, int messageId) {
            return hiddenChannels.contains(String.valueOf(chatId)) || notInterested.contains(key(chatId, messageId));
        }

        public boolean hidesChannel(long chatId) {
            return hiddenChannels.contains(String.valueOf(chatId));
        }
    }
}
