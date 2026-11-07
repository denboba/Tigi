package org.nilak.writer;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

/**
 * The Nilak Writer document: a title and a list of blocks, each holding text with inline marks. Plain data, no
 * Telegram types, saved as JSON. Bold, italic and links live in the text as Android spans while editing.
 */
public final class WriterDoc {

    public static final int PARAGRAPH = 0;
    public static final int HEADING = 1;
    public static final int QUOTE = 2;
    public static final int BULLET = 3;
    public static final int NUMBER = 4;
    public static final int DIVIDER = 5;
    /** A photo (path to the copy in the writer's folder) with its caption as the text. */
    public static final int PHOTO = 6;
    /** Code, kept exactly as typed (monospace, its own lines). */
    public static final int CODE = 7;
    /** A note box (a callout). */
    public static final int CALLOUT = 8;
    /** A video from the gallery (path) with its caption. */
    public static final int VIDEO = 9;
    /** A link shown as a card; the text is the address. */
    public static final int EMBED = 10;
    /** A table: one row per line, cells split by |, the first row is the header. */
    public static final int TABLE = 11;
    /** A Telegram sticker (path = WriterMedia "tgdoc:" reference). */
    public static final int STICKER = 12;
    /** A Telegram GIF (path = WriterMedia reference) with its caption. */
    public static final int GIF = 13;

    public static boolean isList(int type) {
        return type == BULLET || type == NUMBER;
    }

    /** Blocks that are a picture or a clip, with an optional caption as their text. */
    public static boolean isMedia(int type) {
        return type == PHOTO || type == VIDEO || type == STICKER || type == GIF;
    }

    /** Blocks where Enter adds a line instead of starting a new block. */
    public static boolean isMultiline(int type) {
        return type == CODE || type == TABLE;
    }

    public static final class Block {
        public int type;
        public CharSequence text;
        /** PHOTO: the image file. */
        public String path;

        public Block(int type, CharSequence text) {
            this.type = type;
            this.text = text != null ? text : "";
        }

        public Block(int type, CharSequence text, String path) {
            this(type, text);
            this.path = path;
        }
    }

    public String id = UUID.randomUUID().toString();
    public String title = "";
    public final ArrayList<Block> blocks = new ArrayList<>();
    public long updated;
    /** The published post this draft edits (0 = a new article). */
    public long editDialogId;
    public int editMessageId;
    /** Where the Barruu goes when published: the chat it was started from (0 = the person's own channel). */
    public long targetDialogId;

    public boolean isEmpty() {
        if (title.trim().length() > 0) {
            return false;
        }
        for (Block block : blocks) {
            if (block.type == DIVIDER || isMedia(block.type) || block.text.toString().trim().length() > 0) {
                return false;
            }
        }
        return true;
    }

    public int wordCount() {
        final StringBuilder all = new StringBuilder(title).append(' ');
        for (Block block : blocks) {
            all.append(block.text).append(' ');
        }
        final String text = all.toString().trim();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }

    public int minutes() {
        return Math.max(1, Math.round(wordCount() / 200f));
    }

    /* ---------- JSON ---------- */

    public JSONObject toJson() throws Exception {
        final JSONObject json = new JSONObject();
        json.put("v", 1);
        json.put("id", id);
        json.put("title", title);
        json.put("updated", updated);
        json.put("editDialog", editDialogId);
        json.put("editMessage", editMessageId);
        json.put("target", targetDialogId);
        final JSONArray list = new JSONArray();
        for (Block block : blocks) {
            final JSONObject item = new JSONObject();
            item.put("type", block.type);
            item.put("text", block.text.toString());
            item.put("marks", marksToJson(block.text));
            if (block.path != null) {
                item.put("path", block.path);
            }
            list.put(item);
        }
        json.put("blocks", list);
        return json;
    }

    public static WriterDoc fromJson(JSONObject json) throws Exception {
        final WriterDoc doc = new WriterDoc();
        doc.id = json.optString("id", doc.id);
        doc.title = json.optString("title", "");
        doc.updated = json.optLong("updated", 0);
        doc.editDialogId = json.optLong("editDialog", 0);
        doc.editMessageId = json.optInt("editMessage", 0);
        doc.targetDialogId = json.optLong("target", 0);
        final JSONArray list = json.optJSONArray("blocks");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                final JSONObject item = list.getJSONObject(i);
                final SpannableStringBuilder text = new SpannableStringBuilder(item.optString("text", ""));
                applyMarks(text, item.optJSONArray("marks"));
                doc.blocks.add(new Block(item.optInt("type", PARAGRAPH), text, item.optString("path", null)));
            }
        }
        return doc;
    }

    /** b = bold, i = italic, l = link (with url). */
    private static JSONArray marksToJson(CharSequence text) throws Exception {
        final JSONArray marks = new JSONArray();
        if (!(text instanceof Spanned)) {
            return marks;
        }
        final Spanned spanned = (Spanned) text;
        for (Object span : spanned.getSpans(0, spanned.length(), Object.class)) {
            final String kind = kindOf(span);
            if (kind == null) {
                continue;
            }
            final int start = spanned.getSpanStart(span);
            final int end = spanned.getSpanEnd(span);
            if (start < 0 || end <= start) {
                continue;
            }
            final JSONObject mark = new JSONObject();
            mark.put("k", kind);
            mark.put("s", start);
            mark.put("e", end);
            if (span instanceof URLSpan) {
                mark.put("u", ((URLSpan) span).getURL());
            } else if (span instanceof org.telegram.ui.Components.AnimatedEmojiSpan) {
                mark.put("u", String.valueOf(((org.telegram.ui.Components.AnimatedEmojiSpan) span).getDocumentId()));
            }
            marks.put(mark);
        }
        return marks;
    }

    private static void applyMarks(Spannable text, JSONArray marks) {
        if (marks == null) {
            return;
        }
        for (int i = 0; i < marks.length(); i++) {
            final JSONObject mark = marks.optJSONObject(i);
            if (mark == null) {
                continue;
            }
            final int start = Math.max(0, mark.optInt("s"));
            final int end = Math.min(text.length(), mark.optInt("e"));
            if (end <= start) {
                continue;
            }
            final Object span = spanFor(mark.optString("k"), mark.optString("u"));
            if (span != null) {
                text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    static String kindOf(Object span) {
        if (span instanceof StyleSpan) {
            final int style = ((StyleSpan) span).getStyle();
            return style == Typeface.BOLD ? "b" : style == Typeface.ITALIC ? "i" : null;
        }
        if (span instanceof URLSpan) {
            return "l";
        }
        if (span instanceof org.telegram.ui.Components.AnimatedEmojiSpan) {
            return "e";
        }
        return null;
    }

    static Object spanFor(String kind, String url) {
        switch (kind) {
            case "b": return new StyleSpan(Typeface.BOLD);
            case "i": return new StyleSpan(Typeface.ITALIC);
            case "l": return url != null && !url.isEmpty() ? new URLSpan(url) : null;
            case "e":
                try {
                    return new org.telegram.ui.Components.AnimatedEmojiSpan(Long.parseLong(url), null);
                } catch (Exception e) {
                    return null;
                }
            default: return null;
        }
    }
}
