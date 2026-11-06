package org.telegram.ui;

import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Telegram's article editor, kept for everyone: what a writer builds there (headings, bold and italic, links,
 * quotes, lists, code, dividers, photos, videos, emoji) is converted here to a Nilak article instead of being
 * flattened to a plain message when the writer has no Premium. The photos and videos the editor already uploaded
 * are posted again (no new upload) to the writer's media channel, the article keeps their t.me links, and the
 * .nilak file goes to the channel. Nilak readers see the full article; other apps see a file with its title.
 */
public final class TigiRichArticle {

    private static final long LINK_TIMEOUT_MS = 90_000;

    private TigiRichArticle() {
    }

    /* ---------- blocks → Nilak Markdown ---------- */

    public interface MediaRef {
        /** The reference for a photo (video=false) or video id, or null to leave it out. */
        String ref(long id, boolean video);
    }

    public static class Converted {
        public String title = "";
        public String subtitle = "";
        public String cover;
        public final StringBuilder body = new StringBuilder();
    }

    public static Converted convert(ArrayList<TL_iv.PageBlock> blocks, MediaRef media) {
        final Converted out = new Converted();
        for (TL_iv.PageBlock block : blocks) {
            appendBlock(out, block, media, "");
        }
        if (TextUtils.isEmpty(out.title)) {
            // no title block: the first heading or the first words become the title
            final String plain = TigiArticle.plainText(out.body.toString());
            final String first = plain.split("\n", 2)[0].trim();
            out.title = first.length() > 120 ? first.substring(0, 120).trim() + "…" : first;
        }
        return out;
    }

    private static void separate(StringBuilder body, boolean joined) {
        if (body.length() > 0) {
            body.append(joined ? "\n" : "\n\n");
        }
    }

    private static void appendBlock(Converted out, TL_iv.PageBlock block, MediaRef media, String quotePrefix) {
        final StringBuilder body = out.body;
        if (block == null) {
            return;
        }
        if (block instanceof TL_iv.pageBlockTitle) {
            if (TextUtils.isEmpty(out.title)) {
                out.title = TigiArticle.plainText(inline(block.text)).trim();
            } else {
                separate(body, false);
                body.append(quotePrefix).append("## ").append(inline(block.text));
            }
        } else if (block instanceof TL_iv.pageBlockSubtitle) {
            if (TextUtils.isEmpty(out.subtitle)) {
                out.subtitle = TigiArticle.plainText(inline(block.text)).trim();
            } else {
                separate(body, false);
                body.append(quotePrefix).append(inline(block.text));
            }
        } else if (block instanceof TL_iv.pageBlockHeader || block instanceof TL_iv.pageBlockHeading1 || block instanceof TL_iv.pageBlockHeading2) {
            separate(body, false);
            body.append(quotePrefix).append("## ").append(inline(block.text));
        } else if (block instanceof TL_iv.pageBlockHeading3 || block instanceof TL_iv.pageBlockHeading4
            || block instanceof TL_iv.pageBlockHeading5 || block instanceof TL_iv.pageBlockHeading6) {
            separate(body, false);
            body.append(quotePrefix).append("### ").append(inline(block.text));
        } else if (block instanceof TL_iv.pageBlockSubheader || block instanceof TL_iv.pageBlockKicker) {
            separate(body, false);
            body.append(quotePrefix).append("### ").append(inline(block.text));
        } else if (block instanceof TL_iv.pageBlockParagraph) {
            final String text = inline(block.text);
            if (!text.trim().isEmpty()) {
                separate(body, false);
                body.append(quotePrefix).append(text.replace("\n", "\n" + quotePrefix));
            }
        } else if (block instanceof TL_iv.pageBlockFooter) {
            final String text = inline(block.text);
            if (!text.trim().isEmpty()) {
                separate(body, false);
                body.append(quotePrefix).append('*').append(text).append('*');
            }
        } else if (block instanceof TL_iv.pageBlockPreformatted) {
            separate(body, false);
            body.append("```\n").append(plain(block.text)).append("\n```");
        } else if (block instanceof TL_iv.pageBlockDivider) {
            separate(body, false);
            body.append("---");
        } else if (block instanceof TL_iv.pageBlockList) {
            separate(body, false);
            final ArrayList<TL_iv.PageListItem> items = ((TL_iv.pageBlockList) block).items;
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    body.append('\n');
                }
                body.append(quotePrefix).append("- ").append(listItemText(items.get(i)));
            }
        } else if (block instanceof TL_iv.pageBlockOrderedList) {
            separate(body, false);
            final ArrayList<TL_iv.PageListOrderedItem> items = ((TL_iv.pageBlockOrderedList) block).items;
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    body.append('\n');
                }
                body.append(quotePrefix).append(i + 1).append(". ").append(orderedItemText(items.get(i)));
            }
        } else if (block instanceof TL_iv.pageBlockBlockquote) {
            separate(body, false);
            body.append("> ").append(inline(block.text).replace("\n", "\n> "));
            final TL_iv.RichText caption = ((TL_iv.pageBlockBlockquote) block).caption;
            final String by = caption != null ? inline(caption) : "";
            if (!by.trim().isEmpty()) {
                body.append("\n> — ").append(by);
            }
        } else if (block instanceof TL_iv.pageBlockPullquote) {
            separate(body, false);
            body.append("> [!IMPORTANT] ").append(inline(block.text).replace("\n", " "));
        } else if (block instanceof TL_iv.pageBlockBlockquoteBlocks) {
            for (TL_iv.PageBlock child : ((TL_iv.pageBlockBlockquoteBlocks) block).blocks) {
                appendBlock(out, child, media, "> ");
            }
        } else if (block instanceof TL_iv.pageBlockPhoto) {
            final String ref = media.ref(((TL_iv.pageBlockPhoto) block).photo_id, false);
            if (ref != null) {
                if (out.cover == null && body.length() == 0) {
                    // a photo before any text is the cover
                    out.cover = ref;
                } else {
                    separate(body, false);
                    body.append("![").append(captionOf(block)).append("](").append(ref).append(')');
                }
            }
        } else if (block instanceof TL_iv.pageBlockVideo) {
            final String ref = media.ref(((TL_iv.pageBlockVideo) block).video_id, true);
            if (ref != null) {
                separate(body, false);
                body.append("![").append(captionOf(block)).append("](").append(ref).append(')');
            }
        } else if (block instanceof TL_iv.pageBlockCover) {
            appendBlock(out, ((TL_iv.pageBlockCover) block).cover, media, quotePrefix);
        } else if (block instanceof TL_iv.pageBlockCollage) {
            for (TL_iv.PageBlock item : ((TL_iv.pageBlockCollage) block).items) {
                appendBlock(out, item, media, quotePrefix);
            }
        } else if (block instanceof TL_iv.pageBlockSlideshow) {
            for (TL_iv.PageBlock item : ((TL_iv.pageBlockSlideshow) block).items) {
                appendBlock(out, item, media, quotePrefix);
            }
        } else if (block instanceof TL_iv.pageBlockDetails) {
            final TL_iv.pageBlockDetails details = (TL_iv.pageBlockDetails) block;
            separate(body, false);
            body.append("### ").append(inline(details.title));
            for (TL_iv.PageBlock child : details.blocks) {
                appendBlock(out, child, media, quotePrefix);
            }
        } else if (block instanceof TL_iv.pageBlockEmbed && !TextUtils.isEmpty(((TL_iv.pageBlockEmbed) block).url)) {
            separate(body, false);
            body.append(((TL_iv.pageBlockEmbed) block).url);
        } else if (block.text != null) {
            // anything else with text keeps its words
            final String text = inline(block.text);
            if (!text.trim().isEmpty()) {
                separate(body, false);
                body.append(quotePrefix).append(text);
            }
        }
    }

    private static String captionOf(TL_iv.PageBlock block) {
        if (block.caption == null || block.caption.text == null) {
            return "";
        }
        return TigiArticle.plainText(inline(block.caption.text)).replace("]", "").replace("\n", " ").trim();
    }

    private static String listItemText(TL_iv.PageListItem item) {
        if (item instanceof TL_iv.TL_pageListItemText) {
            return inline(((TL_iv.TL_pageListItemText) item).text);
        }
        if (item instanceof TL_iv.TL_pageListItemBlocks) {
            return blocksText(((TL_iv.TL_pageListItemBlocks) item).blocks);
        }
        return "";
    }

    private static String orderedItemText(TL_iv.PageListOrderedItem item) {
        if (item instanceof TL_iv.TL_pageListOrderedItemText) {
            return inline(((TL_iv.TL_pageListOrderedItemText) item).text);
        }
        if (item instanceof TL_iv.TL_pageListOrderedItemBlocks) {
            return blocksText(((TL_iv.TL_pageListOrderedItemBlocks) item).blocks);
        }
        return "";
    }

    private static String blocksText(ArrayList<TL_iv.PageBlock> blocks) {
        final StringBuilder out = new StringBuilder();
        for (TL_iv.PageBlock block : blocks) {
            if (block != null && block.text != null) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(inline(block.text));
            }
        }
        return out.toString();
    }

    private static final String ESCAPED = "\\*_~=`[]";

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (ESCAPED.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Rich text as Nilak inline markup. */
    public static String inline(TL_iv.RichText text) {
        if (text == null || text instanceof TL_iv.textEmpty) {
            return "";
        }
        if (text instanceof TL_iv.textPlain) {
            return escape(((TL_iv.textPlain) text).text);
        }
        if (text instanceof TL_iv.textConcat) {
            final StringBuilder out = new StringBuilder();
            for (TL_iv.RichText part : text.texts) {
                out.append(inline(part));
            }
            return out.toString();
        }
        if (text instanceof TL_iv.textCustomEmoji) {
            // Telegram's own emoji, kept with its id; readers show it as Telegram's emoji
            final TL_iv.textCustomEmoji emoji = (TL_iv.textCustomEmoji) text;
            final String alt = TextUtils.isEmpty(emoji.alt) ? "🙂" : emoji.alt;
            return "[" + escape(alt) + "](emoji:" + emoji.document_id + ")";
        }
        final String inner = inline(text.text);
        if (inner.isEmpty()) {
            return "";
        }
        if (text instanceof TL_iv.textBold) {
            return "**" + inner + "**";
        } else if (text instanceof TL_iv.textItalic) {
            return "*" + inner + "*";
        } else if (text instanceof TL_iv.textStrike) {
            return "~~" + inner + "~~";
        } else if (text instanceof TL_iv.textMarked) {
            return "==" + inner + "==";
        } else if (text instanceof TL_iv.textFixed) {
            return "`" + plain(text.text).replace("`", "'") + "`";
        } else if (text instanceof TL_iv.textUrl) {
            final String url = text.url;
            return url != null && (url.startsWith("http://") || url.startsWith("https://")) ? "[" + inner + "](" + url + ")" : inner;
        }
        // underline, spoiler, mention, hashtag, email, phone and the rest keep their words
        return inner;
    }

    /** Rich text without markup. */
    public static String plain(TL_iv.RichText text) {
        if (text == null) {
            return "";
        }
        if (text instanceof TL_iv.textPlain) {
            return ((TL_iv.textPlain) text).text != null ? ((TL_iv.textPlain) text).text : "";
        }
        if (text instanceof TL_iv.textConcat) {
            final StringBuilder out = new StringBuilder();
            for (TL_iv.RichText part : text.texts) {
                out.append(plain(part));
            }
            return out.toString();
        }
        if (text instanceof TL_iv.textCustomEmoji) {
            return ((TL_iv.textCustomEmoji) text).alt != null ? ((TL_iv.textCustomEmoji) text).alt : "";
        }
        return plain(text.text);
    }

    /* ---------- publishing ---------- */

    /**
     * From Telegram's article editor in a Nilak compose screen: links the editor's photos and videos through the
     * media channel, converts, packs and posts the article to the channel.
     */
    /** Choices from Nilak Writer's publish screen. */
    public static final class Options {
        /** Shown on the reading-time line (for example "Life"); null for none. */
        public String topic;
        /** Public articles carry #NilakRead so Read finds them for everyone. */
        public boolean showInRead = true;
        /** A published article to replace in the same post; null publishes a new one. */
        public org.telegram.messenger.MessageObject editing;
    }

    public static void publish(BaseFragment fragment, long dialogId, ArrayList<TL_iv.PageBlock> blocks, ArrayList<TLRPC.Photo> photos, ArrayList<TLRPC.Document> documents, boolean notify, int scheduleDate) {
        publish(fragment, dialogId, blocks, photos, documents, notify, scheduleDate, new Options());
    }

    public static void publish(BaseFragment fragment, long dialogId, ArrayList<TL_iv.PageBlock> blocks, ArrayList<TLRPC.Photo> photos, ArrayList<TLRPC.Document> documents, boolean notify, int scheduleDate, Options options) {
        if (fragment == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat == null || !TigiArticle.canWrite(account, chat)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakArticlesChannelsOnly)).show();
            return;
        }
        // the photos and videos the article actually uses
        final HashMap<Long, TLRPC.Photo> photoById = new HashMap<>();
        final HashMap<Long, TLRPC.Document> videoById = new HashMap<>();
        if (photos != null) {
            for (TLRPC.Photo photo : photos) {
                if (photo != null) {
                    photoById.put(photo.id, photo);
                }
            }
        }
        if (documents != null) {
            for (TLRPC.Document document : documents) {
                if (document != null) {
                    videoById.put(document.id, document);
                }
            }
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.NilakArticlePublishing)).show();
        final HashMap<String, String> links = new HashMap<>();
        if (photoById.isEmpty() && videoById.isEmpty()) {
            finish(fragment, account, chat, blocks, links, notify, scheduleDate, options);
            return;
        }
        TigiArticleMedia.ensureStore(account, store -> {
            if (store == null) {
                // no media channel: the article goes out with its text; the photos are left out
                finish(fragment, account, chat, blocks, links, notify, scheduleDate, options);
                return;
            }
            final int[] left = {photoById.size() + videoById.size()};
            final boolean[] done = {false};
            final Runnable complete = () -> {
                if (!done[0]) {
                    done[0] = true;
                    finish(fragment, account, chat, blocks, links, notify, scheduleDate, options);
                }
            };
            // never wait forever for the server
            AndroidUtilities.runOnUIThread(complete, LINK_TIMEOUT_MS);
            for (TLRPC.Photo photo : photoById.values()) {
                final String token = TigiArticleMedia.newToken();
                TigiArticleMedia.postExisting(account, store, photo, null, token, link -> AndroidUtilities.runOnUIThread(() -> {
                    if (link != null) {
                        links.put("p" + photo.id, link);
                    }
                    if (--left[0] <= 0) {
                        AndroidUtilities.cancelRunOnUIThread(complete);
                        complete.run();
                    }
                }));
            }
            for (TLRPC.Document document : videoById.values()) {
                final String token = TigiArticleMedia.newToken();
                TigiArticleMedia.postExisting(account, store, null, document, token, link -> AndroidUtilities.runOnUIThread(() -> {
                    if (link != null) {
                        links.put("v" + document.id, link);
                    }
                    if (--left[0] <= 0) {
                        AndroidUtilities.cancelRunOnUIThread(complete);
                        complete.run();
                    }
                }));
            }
        });
    }

    private static void finish(BaseFragment fragment, int account, TLRPC.Chat chat, ArrayList<TL_iv.PageBlock> blocks, HashMap<String, String> links, boolean notify, int scheduleDate, Options options) {
        final Converted converted = convert(blocks, (id, video) -> links.get((video ? "v" : "p") + id));
        Utilities.globalQueue.postRunnable(() -> {
            File packed = null;
            try {
                final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_rich_" + System.currentTimeMillis());
                dir.mkdirs();
                final TigiArticle.Meta meta = new TigiArticle.Meta();
                meta.title = converted.title;
                meta.subtitle = converted.subtitle;
                meta.author = chat.title;
                meta.lang = TigiRegion.language();
                meta.cover = converted.cover;
                final String body = converted.body.toString();
                meta.words = TigiArticle.countWords(TigiArticle.plainText(body));
                meta.minutes = TigiArticle.minutesFor(meta.words);
                meta.created = System.currentTimeMillis() / 1000;
                TigiArticle.writeMeta(dir, meta);
                TigiArticle.writeText(new File(dir, TigiArticle.BODY), body);
                final File outDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_publish");
                outDir.mkdirs();
                packed = new File(outDir, TigiArticle.slug(meta.title) + TigiArticle.EXT);
                TigiArticle.pack(dir, packed);
                TigiArticle.deleteRecursive(dir);
            } catch (Exception e) {
                FileLog.e(e);
                packed = null;
            }
            final File file = packed;
            AndroidUtilities.runOnUIThread(() -> {
                if (file == null || !file.exists()) {
                    if (fragment.getParentActivity() != null) {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakArticleFailed)).show();
                    }
                    return;
                }
                String lead = converted.subtitle;
                if (TextUtils.isEmpty(lead)) {
                    final String plain = TigiArticle.plainText(converted.body.toString());
                    lead = plain.length() > 180 ? plain.substring(0, 180).trim() + "…" : plain;
                }
                String caption = converted.title + (TextUtils.isEmpty(lead) ? "" : "\n" + lead);
                // reading time on its own line: Nilak cards show it, and other apps simply see "📖 6 min read"
                final int minutes = TigiArticle.minutesFor(TigiArticle.countWords(TigiArticle.plainText(converted.body.toString())));
                caption += "\n\n" + TigiArticle.MINUTES_MARK + " " + LocaleController.formatString(R.string.NilakReadMinutes, minutes);
                if (!TextUtils.isEmpty(options.topic)) {
                    caption += " · " + options.topic;
                }
                if (options.showInRead && !TextUtils.isEmpty(org.telegram.messenger.ChatObject.getPublicUsername(chat))) {
                    // public articles carry the Read tag: hashtag search finds them for everyone's Read
                    caption += " · #" + TigiTopicFeedActivity.READ_TAG;
                }
                if (options.editing != null) {
                    // editing in place: the post keeps its likes and comments, the file and caption are replaced
                    options.editing.editingMessage = caption;
                }
                SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), file.getAbsolutePath(), file.getAbsolutePath(), null,
                    caption, TigiArticle.MIME, -chat.id, null, null, null, null, options.editing, notify, scheduleDate, null, null, false);
                TigiPosting.setLastChannel(account, chat.id);
                if (fragment.getParentActivity() != null) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.NilakArticlePublished)).show();
                }
            });
        });
    }

    /** The writer's name, for articles without a channel title. */
    static String authorName(int account) {
        final TLRPC.User self = org.telegram.messenger.UserConfig.getInstance(account).getCurrentUser();
        return self != null ? UserObject.getUserName(self) : "";
    }
}
