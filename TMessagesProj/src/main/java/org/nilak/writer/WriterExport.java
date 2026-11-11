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
        publish(account, -channel.id, doc, topic, showInRead, notify, editing, result);
    }

    /** Publishes into any chat: a channel, a group or a private chat (dialogId as Telegram counts it). */
    public static void publish(int account, long dialogId, WriterDoc doc, String topic, boolean showInRead, boolean notify,
                               MessageObject editing, Result result) {
        publishPacked(account, dialogId, doc, topic, showInRead, notify, editing, result);
    }

    private static void publishPacked(int account, long dialogId, WriterDoc doc, String topic, boolean showInRead, boolean notify,
                                      MessageObject editing, Result result) {
        final TLRPC.Chat channel = dialogId < 0 ? org.telegram.messenger.MessagesController.getInstance(account).getChat(-dialogId) : null;
        final String body = markdown(doc);
        final String title = doc.title.trim().isEmpty() ? LocaleController.getString(R.string.NilakWriterUntitled) : doc.title.trim();
        final TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        final String author = me != null ? UserObject.getUserName(me) : channel != null ? channel.title : "";
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
                    if ((block.type == WriterDoc.PHOTO || block.type == WriterDoc.VIDEO) && !TextUtils.isEmpty(block.path)) {
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
                final String coverName = cover != null ? new File(cover).getName() : makeCover(account, doc, media);
                meta.cover = coverName != null ? "media/" + coverName : null;
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
                if (showInRead && channel != null && ChatObject.isChannelAndNotMegaGroup(channel) && !TextUtils.isEmpty(ChatObject.getPublicUsername(channel))) {
                    caption += " · #" + TigiTopicFeedActivity.READ_TAG;
                }
                if (editing != null) {
                    // in place: the same post keeps its likes and comments
                    editing.editingMessage = caption;
                }
                SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), file.getAbsolutePath(), file.getAbsolutePath(), null,
                    caption, TigiArticle.MIME, dialogId, null, null, null, null, editing, notify, 0, null, null, false);
                result.run(true);
            });
        });
    }

    /**
     * When there is no photo, the cover is made from what the article has: a frame of the first video, else the first
     * sticker or GIF on a soft lilac page. Saved in media/ and returned by name (null when nothing fits).
     */
    static String makeCover(int account, WriterDoc doc, File media) {
        for (WriterDoc.Block block : doc.blocks) {
            android.graphics.Bitmap bitmap = null;
            boolean sticker = false;
            try {
                if (block.type == WriterDoc.VIDEO && !TextUtils.isEmpty(block.path) && new File(block.path).exists()) {
                    bitmap = android.media.ThumbnailUtils.createVideoThumbnail(block.path, android.provider.MediaStore.Images.Thumbnails.FULL_SCREEN_KIND);
                } else if (block.type == WriterDoc.STICKER || block.type == WriterDoc.GIF) {
                    final TLRPC.Document document = WriterMedia.decode(block.path);
                    if (document == null) {
                        continue;
                    }
                    sticker = WriterMedia.isSticker(document);
                    final File file = org.telegram.messenger.FileLoader.getInstance(account).getPathToAttach(document, true);
                    if (file != null && file.exists()) {
                        if (org.telegram.messenger.MessageObject.isVideoStickerDocument(document) || !sticker) {
                            bitmap = android.media.ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(), android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
                        } else if (!org.telegram.messenger.MessageObject.isAnimatedStickerDocument(document, true)) {
                            bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                        }
                    }
                    if (bitmap == null) {
                        final TLRPC.PhotoSize thumb = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
                        final File thumbFile = thumb != null ? org.telegram.messenger.FileLoader.getInstance(account).getPathToAttach(thumb, true) : null;
                        if (thumbFile != null && thumbFile.exists()) {
                            bitmap = android.graphics.BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
                        }
                    }
                } else {
                    continue;
                }
                if (bitmap == null) {
                    continue;
                }
                if (sticker) {
                    // the sticker in the middle of a wide lilac page
                    final android.graphics.Bitmap page = android.graphics.Bitmap.createBitmap(1280, 720, android.graphics.Bitmap.Config.ARGB_8888);
                    final android.graphics.Canvas canvas = new android.graphics.Canvas(page);
                    final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
                    paint.setShader(new android.graphics.LinearGradient(0, 0, 1280, 720, 0xFFEADCFB, 0xFFF8F1FD, android.graphics.Shader.TileMode.CLAMP));
                    canvas.drawRect(0, 0, 1280, 720, paint);
                    paint.setShader(null);
                    final float side = 500f;
                    final float scale = side / Math.max(bitmap.getWidth(), bitmap.getHeight());
                    final float w = bitmap.getWidth() * scale;
                    final float h = bitmap.getHeight() * scale;
                    canvas.drawBitmap(bitmap, null, new android.graphics.RectF((1280 - w) / 2f, (720 - h) / 2f, (1280 + w) / 2f, (720 + h) / 2f), paint);
                    bitmap.recycle();
                    bitmap = page;
                }
                media.mkdirs();
                final File out = new File(media, "cover.jpg");
                try (OutputStream os = new FileOutputStream(out)) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, os);
                }
                bitmap.recycle();
                return out.getName();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return null;
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
            final boolean noText = block.type == WriterDoc.DIVIDER || block.type == WriterDoc.POLL || WriterDoc.isMedia(block.type);
            final String text = noText ? "" : block.type == WriterDoc.CODE || block.type == WriterDoc.TABLE || block.type == WriterDoc.EMBED
                ? block.text.toString().replaceAll("\\s+$", "") : inline(block.text).trim();
            if (text.trim().isEmpty() && !noText) {
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
                case WriterDoc.PHOTO:
                case WriterDoc.VIDEO: {
                    if (TextUtils.isEmpty(block.path)) {
                        continue;
                    }
                    final String caption = block.text.toString().trim().replace("]", "").replace('\n', ' ');
                    out.append("![").append(caption).append("](media/").append(new File(block.path).getName()).append(")");
                    break;
                }
                case WriterDoc.STICKER:
                case WriterDoc.GIF: {
                    if (!WriterMedia.isDocRef(block.path)) {
                        continue;
                    }
                    final String caption = block.type == WriterDoc.GIF ? block.text.toString().trim().replace("]", "").replace('\n', ' ') : "";
                    out.append("![").append(caption).append("](").append(block.path).append(")");
                    break;
                }
                case WriterDoc.CODE:
                    out.append("```\n").append(text.replace("```", "``\u200b`")).append("\n```");
                    break;
                case WriterDoc.CALLOUT:
                    // the kind (NOTE, TIP, WARNING, IMPORTANT) is kept from a Barruu opened for editing
                    out.append("> [!").append(block.path != null && block.path.matches("NOTE|TIP|WARNING|IMPORTANT") ? block.path : "NOTE").append("] ")
                        .append(text.replace("\n", "\n> "));
                    break;
                case WriterDoc.EMBED: {
                    String url = text.trim().replace('\n', ' ');
                    if (url.contains(" ")) {
                        url = url.substring(0, url.indexOf(' '));
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    out.append(url);
                    break;
                }
                case WriterDoc.TABLE:
                    out.append(table(text));
                    break;
                case WriterDoc.POLL: {
                    // a quiz, kept in the Barruu itself: question, "+ right", "- other", "> explanation"
                    final WriterQuiz quiz = WriterQuiz.parse(block.text.toString());
                    if (!quiz.isComplete()) {
                        continue;
                    }
                    out.append("```quiz\n").append(quiz.format()).append("\n```");
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
                boolean struck = false;
                boolean highlight = false;
                boolean code = false;
                String url = null;
                long emoji = 0;
                for (Object span : spanned.getSpans(from, to, Object.class)) {
                    if (spanned.getSpanStart(span) > from || spanned.getSpanEnd(span) < to) {
                        continue;
                    }
                    final String kind = WriterDoc.kindOf(span);
                    if ("b".equals(kind)) {
                        bold = true;
                    } else if ("i".equals(kind)) {
                        italic = true;
                    } else if ("s".equals(kind)) {
                        struck = true;
                    } else if ("h".equals(kind)) {
                        highlight = true;
                    } else if ("c".equals(kind)) {
                        code = true;
                    } else if ("l".equals(kind)) {
                        url = ((URLSpan) span).getURL();
                    } else if ("e".equals(kind)) {
                        emoji = ((org.telegram.ui.Components.AnimatedEmojiSpan) span).getDocumentId();
                    }
                }
                if (emoji != 0) {
                    // a Telegram animated emoji: [alt](emoji:id), the alt is the plain emoji it stands for
                    out.append("[").append(piece.replace("]", "").replace("[", "")).append("](emoji:").append(emoji).append(")");
                    from = to;
                    continue;
                }
                final String core = piece.trim();
                if (url != null && core.startsWith("@") && url.startsWith("https://t.me/")) {
                    // a mention stays [@name](https://t.me/name): readers on both apps show it bold and open the profile
                    bold = false;
                }
                if (core.isEmpty() || !bold && !italic && !struck && !highlight && !code && url == null) {
                    out.append(piece);
                } else {
                    final int lead = piece.indexOf(core);
                    String marked = code ? "`" + core.replace("`", "") + "`" : core;
                    if (highlight) {
                        marked = "==" + marked + "==";
                    }
                    if (struck) {
                        marked = "~~" + marked + "~~";
                    }
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

    /** Rows typed as "a | b" become a Markdown table; the first row is the header. */
    static String table(String text) {
        final StringBuilder out = new StringBuilder();
        int columns = 0;
        final java.util.ArrayList<String[]> rows = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            String row = line.trim();
            if (row.isEmpty()) {
                continue;
            }
            if (row.startsWith("|")) {
                row = row.substring(1);
            }
            if (row.endsWith("|")) {
                row = row.substring(0, row.length() - 1);
            }
            final String[] cells = row.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].trim();
            }
            columns = Math.max(columns, cells.length);
            rows.add(cells);
        }
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                out.append('\n');
            }
            out.append('|');
            for (int c = 0; c < columns; c++) {
                final String[] cells = rows.get(r);
                out.append(' ').append(c < cells.length ? cells[c] : "").append(" |");
            }
            if (r == 0) {
                out.append("\n|");
                for (int c = 0; c < columns; c++) {
                    out.append(" --- |");
                }
            }
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
