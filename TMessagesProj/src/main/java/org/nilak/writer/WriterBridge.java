package org.nilak.writer;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.TigiArticleMedia;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.TigiArticle;
import org.telegram.ui.TigiRichArticle;

import java.io.File;
import java.util.ArrayList;
import java.util.TreeSet;

/**
 * The only place Nilak Writer touches Telegram: it turns a WriterDoc into Telegram's article blocks and publishes
 * them (a native rich message when the account may post one, else a Nilak article file), and reads a published
 * article back into a WriterDoc for editing.
 */
public final class WriterBridge {

    private WriterBridge() {
    }

    /** Native articles show in every Telegram app; Telegram decides who may post them (often Premium only). */
    public static boolean nativeArticles(int account) {
        return MessagesController.getInstance(account).richEditorAllowed() || UserConfig.getInstance(account).isPremium();
    }

    /* ---------- publish ---------- */

    public static void publish(BaseFragment fragment, TLRPC.Chat channel, WriterDoc doc, String topic, boolean showInRead, boolean notify, MessageObject editing) {
        final int account = fragment.getCurrentAccount();
        final boolean editNative = editing != null && editing.messageOwner != null && editing.messageOwner.rich_message != null;
        final boolean editFile = editing != null && !editNative;
        if (editNative || !editFile && nativeArticles(account)) {
            final ArrayList<TL_iv.PageBlock> blocks = toBlocks(doc, true);
            if (editNative) {
                SendMessagesHelper.prepareEditingArticle(AccountInstance.getInstance(account), editing, blocks, null, null, null, false, fragment);
            } else {
                SendMessagesHelper.prepareSendingArticle(AccountInstance.getInstance(account), blocks, false, -channel.id, null, null, notify, 0, 0, null, 0, 0, 0);
            }
            return;
        }
        final TigiRichArticle.Options options = new TigiRichArticle.Options();
        options.topic = topic;
        options.showInRead = showInRead;
        options.editing = editing;
        TigiRichArticle.publish(fragment, -channel.id, toBlocks(doc, false), null, null, notify, 0, options);
    }

    /** native: Telegram's editor blocks (Heading 1 for the title); file: the classic title/header blocks. */
    static ArrayList<TL_iv.PageBlock> toBlocks(WriterDoc doc, boolean nativeBlocks) {
        final ArrayList<TL_iv.PageBlock> out = new ArrayList<>();
        if (!TextUtils.isEmpty(doc.title.trim())) {
            final TL_iv.PageBlock title = nativeBlocks ? new TL_iv.pageBlockHeading1() : new TL_iv.pageBlockTitle();
            title.text = plain(doc.title.trim());
            out.add(title);
        }
        TL_iv.pageBlockList bullets = null;
        TL_iv.pageBlockOrderedList numbers = null;
        for (WriterDoc.Block block : doc.blocks) {
            if (block.type == WriterDoc.DIVIDER) {
                bullets = null;
                numbers = null;
                out.add(new TL_iv.pageBlockDivider());
                continue;
            }
            if (block.text.toString().trim().isEmpty()) {
                continue;
            }
            // consecutive list items make one list, as in Telegram's own editor
            if (block.type == WriterDoc.BULLET) {
                numbers = null;
                if (bullets == null) {
                    bullets = new TL_iv.pageBlockList();
                    out.add(bullets);
                }
                final TL_iv.TL_pageListItemText item = new TL_iv.TL_pageListItemText();
                item.text = toRichText(trim(block.text));
                bullets.items.add(item);
                continue;
            }
            if (block.type == WriterDoc.NUMBER) {
                bullets = null;
                if (numbers == null) {
                    numbers = new TL_iv.pageBlockOrderedList();
                    out.add(numbers);
                }
                final TL_iv.TL_pageListOrderedItemText item = new TL_iv.TL_pageListOrderedItemText();
                item.num = (numbers.items.size() + 1) + ".";
                item.text = toRichText(trim(block.text));
                numbers.items.add(item);
                continue;
            }
            bullets = null;
            numbers = null;
            final TL_iv.PageBlock page;
            switch (block.type) {
                case WriterDoc.HEADING:
                    page = nativeBlocks ? new TL_iv.pageBlockHeading2() : new TL_iv.pageBlockHeader();
                    break;
                case WriterDoc.QUOTE: {
                    final TL_iv.pageBlockBlockquote quote = new TL_iv.pageBlockBlockquote();
                    quote.caption = new TL_iv.textEmpty();
                    page = quote;
                    break;
                }
                default:
                    page = new TL_iv.pageBlockParagraph();
            }
            page.text = toRichText(trim(block.text));
            out.add(page);
        }
        return out;
    }

    private static CharSequence trim(CharSequence text) {
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.subSequence(start, end);
    }

    private static TL_iv.RichText plain(String text) {
        final TL_iv.textPlain plain = new TL_iv.textPlain();
        plain.text = text;
        return plain;
    }

    /** Spans to Telegram rich text: the text is cut where any mark starts or ends, each piece wrapped in its marks. */
    static TL_iv.RichText toRichText(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return plain(text.toString());
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
        final ArrayList<TL_iv.RichText> parts = new ArrayList<>();
        Integer from = null;
        for (Integer to : cuts) {
            if (from != null && to > from) {
                TL_iv.RichText piece = plain(spanned.subSequence(from, to).toString());
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
                if (italic) {
                    final TL_iv.textItalic wrap = new TL_iv.textItalic();
                    wrap.text = piece;
                    piece = wrap;
                }
                if (bold) {
                    final TL_iv.textBold wrap = new TL_iv.textBold();
                    wrap.text = piece;
                    piece = wrap;
                }
                if (url != null) {
                    final TL_iv.textUrl wrap = new TL_iv.textUrl();
                    wrap.text = piece;
                    wrap.url = url;
                    piece = wrap;
                }
                parts.add(piece);
            }
            from = to;
        }
        if (parts.isEmpty()) {
            return plain("");
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        final TL_iv.textConcat concat = new TL_iv.textConcat();
        concat.texts.addAll(parts);
        return concat;
    }

    /* ---------- edit a published article ---------- */

    /** A Nilak article file or a native article in a post. */
    public static boolean isArticle(MessageObject message) {
        return message != null && message.messageOwner != null
            && (message.messageOwner.rich_message != null || TigiArticle.isArticle(message));
    }

    /**
     * The published article as a draft to edit, or null when its file is not on the phone yet (the caller starts
     * the download and tries again).
     */
    public static WriterDoc importArticle(MessageObject message) {
        final WriterDoc doc = new WriterDoc();
        doc.editDialogId = message.getDialogId();
        doc.editMessageId = message.getId();
        if (message.messageOwner.rich_message != null) {
            boolean titleTaken = false;
            for (TL_iv.PageBlock block : message.messageOwner.rich_message.blocks) {
                final CharSequence text = block.text != null ? toSpans(block.text) : "";
                if (!titleTaken && (block instanceof TL_iv.pageBlockHeading1 || block instanceof TL_iv.pageBlockTitle)) {
                    doc.title = text.toString();
                    titleTaken = true;
                } else if (block instanceof TL_iv.pageBlockHeading1 || block instanceof TL_iv.pageBlockHeading2 || block instanceof TL_iv.pageBlockHeading3
                    || block instanceof TL_iv.pageBlockHeader || block instanceof TL_iv.pageBlockSubheader) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.HEADING, text));
                } else if (block instanceof TL_iv.pageBlockBlockquote || block instanceof TL_iv.pageBlockPullquote) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.QUOTE, text));
                } else if (block instanceof TL_iv.pageBlockDivider) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.DIVIDER, ""));
                } else if (block instanceof TL_iv.pageBlockList) {
                    for (TL_iv.PageListItem item : ((TL_iv.pageBlockList) block).items) {
                        if (item instanceof TL_iv.TL_pageListItemText) {
                            doc.blocks.add(new WriterDoc.Block(WriterDoc.BULLET, toSpans(((TL_iv.TL_pageListItemText) item).text)));
                        }
                    }
                } else if (block instanceof TL_iv.pageBlockOrderedList) {
                    for (TL_iv.PageListOrderedItem item : ((TL_iv.pageBlockOrderedList) block).items) {
                        if (item instanceof TL_iv.TL_pageListOrderedItemText) {
                            doc.blocks.add(new WriterDoc.Block(WriterDoc.NUMBER, toSpans(((TL_iv.TL_pageListOrderedItemText) item).text)));
                        }
                    }
                } else if (block.text != null) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.PARAGRAPH, text));
                }
            }
            return doc;
        }
        try {
            final File file = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
            if (file == null || !file.exists()) {
                return null;
            }
            final File dir = new File(file.getParentFile(), "nilak_edit_" + message.getId());
            TigiArticle.deleteRecursive(dir);
            dir.mkdirs();
            TigiArticle.unpack(file, dir);
            final TigiArticle.Meta meta = TigiArticle.readMeta(dir);
            doc.title = meta != null ? meta.title : TigiArticle.titleOf(message);
            final String body = TigiArticle.readText(new File(dir, TigiArticle.BODY));
            for (TigiArticle.Block block : TigiArticle.parse(body)) {
                if (block.type == TigiArticle.BLOCK_HEADING) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.HEADING, block.text));
                } else if (block.type == TigiArticle.BLOCK_QUOTE) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.QUOTE, block.text));
                } else if (block.type == TigiArticle.BLOCK_CALLOUT) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.CALLOUT, block.text));
                } else if (block.type == TigiArticle.BLOCK_CODE) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.CODE, block.text != null ? block.text.toString() : ""));
                } else if (block.type == TigiArticle.BLOCK_LINK && block.url != null) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.EMBED, block.url));
                } else if (block.type == TigiArticle.BLOCK_TABLE) {
                    final StringBuilder rows = new StringBuilder();
                    for (java.util.ArrayList<CharSequence> row : block.rows) {
                        if (rows.length() > 0) {
                            rows.append('\n');
                        }
                        for (int c = 0; c < row.size(); c++) {
                            rows.append(c > 0 ? " | " : "").append(row.get(c));
                        }
                    }
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.TABLE, rows.toString()));
                } else if (block.type == TigiArticle.BLOCK_IMAGE && WriterMedia.isDocRef(block.path)) {
                    final TLRPC.Document document = WriterMedia.decode(block.path);
                    if (document != null) {
                        doc.blocks.add(new WriterDoc.Block(WriterMedia.isSticker(document) ? WriterDoc.STICKER : WriterDoc.GIF,
                            block.caption != null ? block.caption : "", block.path));
                    }
                } else if (block.type == TigiArticle.BLOCK_VIDEO && block.path != null && !TigiArticleMedia.isTelegramRef(block.path)) {
                    final File source = new File(dir, block.path);
                    if (source.exists() && source.getCanonicalPath().startsWith(dir.getCanonicalPath())) {
                        final File copy = new File(WriterDrafts.mediaDir(message.currentAccount), System.currentTimeMillis() + "_" + source.getName());
                        copyFile(source, copy);
                        doc.blocks.add(new WriterDoc.Block(WriterDoc.VIDEO, block.caption != null ? block.caption : "", copy.getAbsolutePath()));
                    }
                } else if (block.type == TigiArticle.BLOCK_IMAGE && block.path != null) {
                    // the photo is copied out of the file so the draft keeps it after the folder is cleaned up
                    final File source = new File(dir, block.path);
                    if (source.exists()) {
                        final File copy = new File(WriterDrafts.mediaDir(message.currentAccount), System.currentTimeMillis() + "_" + source.getName());
                        copyFile(source, copy);
                        doc.blocks.add(new WriterDoc.Block(WriterDoc.PHOTO, block.caption != null ? block.caption : "", copy.getAbsolutePath()));
                    }
                } else if (block.type == TigiArticle.BLOCK_DIVIDER) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.DIVIDER, ""));
                } else if (block.type == TigiArticle.BLOCK_BULLETS || block.type == TigiArticle.BLOCK_NUMBERS) {
                    for (CharSequence item : block.items) {
                        doc.blocks.add(new WriterDoc.Block(block.type == TigiArticle.BLOCK_BULLETS ? WriterDoc.BULLET : WriterDoc.NUMBER, item));
                    }
                } else if (block.text != null && block.text.length() > 0) {
                    doc.blocks.add(new WriterDoc.Block(WriterDoc.PARAGRAPH, block.text));
                }
            }
            TigiArticle.deleteRecursive(dir);
            return doc;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(from); java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            final byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }

    /** Telegram rich text back to spans (bold, italic, links; other marks keep their text). */
    static CharSequence toSpans(TL_iv.RichText text) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        append(out, text);
        return out;
    }

    private static void append(SpannableStringBuilder out, TL_iv.RichText text) {
        if (text == null || text instanceof TL_iv.textEmpty) {
            return;
        }
        if (text instanceof TL_iv.textPlain) {
            out.append(((TL_iv.textPlain) text).text);
            return;
        }
        if (text instanceof TL_iv.textConcat) {
            for (TL_iv.RichText part : text.texts) {
                append(out, part);
            }
            return;
        }
        final int start = out.length();
        append(out, text.text);
        final int end = out.length();
        if (end <= start) {
            return;
        }
        Object span = null;
        if (text instanceof TL_iv.textBold) {
            span = new StyleSpan(Typeface.BOLD);
        } else if (text instanceof TL_iv.textItalic) {
            span = new StyleSpan(Typeface.ITALIC);
        } else if (text instanceof TL_iv.textUrl && !TextUtils.isEmpty(text.url)) {
            span = new URLSpan(text.url);
        }
        if (span != null) {
            out.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
