package org.nilak.writer;

import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.URLSpan;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.TigiArticle;
import org.telegram.ui.TigiRegion;
import org.telegram.ui.TigiTopicFeedActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.TreeSet;

/**
 * A Writer document becomes one self-contained .nilak file (the text in Nilak Markdown, the photos inside it),
 * posted as a file to the person's posts. Readers download the file and read it offline, in any channel.
 */
public final class WriterExport {

    public interface Result {
        /** packed and handed to Telegram for sending (true), or packing failed (false). */
        void run(boolean handedOver);
    }

    private WriterExport() {
    }

    public static void publish(int account, TLRPC.Chat channel, WriterDoc doc, String topic, boolean showInRead, boolean notify,
                               MessageObject editing, Result result) {
        final String body = markdown(doc);
        final String title = doc.title.trim().isEmpty() ? LocaleController.getString(R.string.NilakWriterUntitled) : doc.title.trim();
        final TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        final String author = me != null ? UserObject.getUserName(me) : channel.title;
        final String cover = firstPhoto(doc);
        final int words = doc.wordCount();
        final int minutes = doc.minutes();
        Utilities.globalQueue.postRunnable(() -> {
            File packed = null;
            try {
                final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_writer_out_" + System.currentTimeMillis());
                dir.mkdirs();
                final File media = new File(dir, "media");
                for (WriterDoc.Block block : doc.blocks) {
                    if (block.type == WriterDoc.PHOTO && !TextUtils.isEmpty(block.path)) {
                        media.mkdirs();
                        final File source = new File(block.path);
                        if (source.exists()) {
                            copy(source, new File(media, source.getName()));
                        }
                    }
                }
                final TigiArticle.Meta meta = new TigiArticle.Meta();
                meta.title = title;
                meta.subtitle = "";
                meta.author = author;
                meta.lang = TigiRegion.language();
                meta.cover = cover != null ? "media/" + new File(cover).getName() : null;
                meta.words = words;
                meta.minutes = minutes;
                meta.created = System.currentTimeMillis() / 1000;
                TigiArticle.writeMeta(dir, meta);
                TigiArticle.writeText(new File(dir, TigiArticle.BODY), body);
                final File outDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_publish");
                outDir.mkdirs();
                packed = new File(outDir, TigiArticle.slug(title) + TigiArticle.EXT);
                TigiArticle.pack(dir, packed);
                TigiArticle.deleteRecursive(dir);
            } catch (Exception e) {
                FileLog.e(e);
                packed = null;
            }
            final File file = packed;
            AndroidUtilities.runOnUIThread(() -> {
                if (file == null || !file.exists()) {
                    result.run(false);
                    return;
                }
                String caption = title;
                final String lead = lead(doc);
                if (!lead.isEmpty()) {
                    caption += "\n" + lead;
                }
                caption += "\n\n" + TigiArticle.MINUTES_MARK + " " + LocaleController.formatString(R.string.NilakReadMinutes, minutes);
                if (!TextUtils.isEmpty(topic)) {
                    caption += " · " + topic;
                }
                if (showInRead && !TextUtils.isEmpty(ChatObject.getPublicUsername(channel))) {
                    caption += " · #" + TigiTopicFeedActivity.READ_TAG;
                }
                if (editing != null) {
                    // in place: the same post keeps its likes and comments
                    editing.editingMessage = caption;
                }
                SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), file.getAbsolutePath(), file.getAbsolutePath(), null,
                    caption, TigiArticle.MIME, -channel.id, null, null, null, null, editing, notify, 0, null, null, false);
                result.run(true);
            });
        });
    }

    private static String firstPhoto(WriterDoc doc) {
        for (WriterDoc.Block block : doc.blocks) {
            if (block.type == WriterDoc.PHOTO && !TextUtils.isEmpty(block.path) && new File(block.path).exists()) {
                return block.path;
            }
        }
        return null;
    }

    /** The first paragraph, short: it shows under the title in feeds before the file is downloaded. */
    private static String lead(WriterDoc doc) {
        for (WriterDoc.Block block : doc.blocks) {
            if (block.type == WriterDoc.PARAGRAPH) {
                final String text = block.text.toString().trim().replace('\n', ' ');
                if (!text.isEmpty()) {
                    return text.length() > 180 ? text.substring(0, 180).trim() + "…" : text;
                }
            }
        }
        return "";
    }

    /* ---------- Nilak Markdown ---------- */

    static String markdown(WriterDoc doc) {
        final StringBuilder out = new StringBuilder();
        int previous = -1;
        int number = 0;
        for (WriterDoc.Block block : doc.blocks) {
            final String text = block.type == WriterDoc.DIVIDER || block.type == WriterDoc.PHOTO ? "" : inline(block.text).trim();
            if (text.isEmpty() && block.type != WriterDoc.DIVIDER && block.type != WriterDoc.PHOTO) {
                continue;
            }
            // list items stay together; everything else is separated by an empty line
            final boolean sameList = WriterDoc.isList(block.type) && block.type == previous;
            if (out.length() > 0) {
                out.append(sameList ? "\n" : "\n\n");
            }
            number = block.type == WriterDoc.NUMBER ? (sameList ? number + 1 : 1) : 0;
            switch (block.type) {
                case WriterDoc.HEADING:
                    out.append("## ").append(text.replace('\n', ' '));
                    break;
                case WriterDoc.QUOTE:
                    out.append("> ").append(text.replace("\n", "\n> "));
                    break;
                case WriterDoc.BULLET:
                    out.append("- ").append(text.replace('\n', ' '));
                    break;
                case WriterDoc.NUMBER:
                    out.append(number).append(". ").append(text.replace('\n', ' '));
                    break;
                case WriterDoc.DIVIDER:
                    out.append("---");
                    break;
                case WriterDoc.PHOTO: {
                    if (TextUtils.isEmpty(block.path)) {
                        continue;
                    }
                    final String caption = block.text.toString().trim().replace("]", "").replace('\n', ' ');
                    out.append("![").append(caption).append("](media/").append(new File(block.path).getName()).append(")");
                    break;
                }
                default:
                    out.append(text);
            }
            previous = block.type;
        }
        return out.toString();
    }

    /** Bold, italic and links as **x**, *x* and [x](url); spaces stay outside the marks. */
    static String inline(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return text.toString();
        }
        final Spanned spanned = (Spanned) text;
        final TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        cuts.add(spanned.length());
        for (Object span : spanned.getSpans(0, spanned.length(), Object.class)) {
            if (WriterDoc.kindOf(span) != null) {
                cuts.add(spanned.getSpanStart(span));
                cuts.add(spanned.getSpanEnd(span));
            }
        }
        final StringBuilder out = new StringBuilder();
        Integer from = null;
        for (Integer to : cuts) {
            if (from != null && to > from) {
                String piece = spanned.subSequence(from, to).toString();
                boolean bold = false;
                boolean italic = false;
                String url = null;
                for (Object span : spanned.getSpans(from, to, Object.class)) {
                    if (spanned.getSpanStart(span) > from || spanned.getSpanEnd(span) < to) {
                        continue;
                    }
                    final String kind = WriterDoc.kindOf(span);
                    if ("b".equals(kind)) {
                        bold = true;
                    } else if ("i".equals(kind)) {
                        italic = true;
                    } else if ("l".equals(kind)) {
                        url = ((URLSpan) span).getURL();
                    }
                }
                final String core = piece.trim();
                if (core.isEmpty() || !bold && !italic && url == null) {
                    out.append(piece);
                } else {
                    final int lead = piece.indexOf(core);
                    String marked = core;
                    if (italic) {
                        marked = "*" + marked + "*";
                    }
                    if (bold) {
                        marked = "**" + marked + "**";
                    }
                    if (url != null) {
                        marked = "[" + marked.replace("]", "") + "](" + url.replace(")", "%29") + ")";
                    }
                    out.append(piece, 0, lead).append(marked).append(piece.substring(lead + core.length()));
                }
            }
            from = to;
        }
        return out.toString();
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            final byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }
}
