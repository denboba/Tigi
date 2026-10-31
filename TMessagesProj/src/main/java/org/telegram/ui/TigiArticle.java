package org.telegram.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import org.json.JSONObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Nilak Articles: long-form posts for news desks and writers, published in channels.
 *
 * <h3>Package</h3>
 * One file per article, sent to the channel as a document named {@code <slug>.nilak} with the MIME type
 * {@link #MIME}. It is a zip:
 * <pre>
 *   manifest.json   {"format":"nilak-article","version":1,"title","subtitle","author","lang","cover","words","minutes","created"}
 *   article.md      the body in Nilak Markdown (below)
 *   media/...       the article's photos and videos, referenced from article.md by relative path
 * </pre>
 * Everything an article needs travels in that one message: it is stored and delivered by Telegram like any file,
 * reads offline once downloaded, and the reader never fetches anything from the web. The message caption carries
 * the title and the subtitle, so feeds and search show them before the file is downloaded.
 *
 * <h3>Nilak Markdown</h3>
 * A small, predictable subset of CommonMark, plus a few blocks writers ask for:
 * <ul>
 * <li>{@code # }, {@code ## }, {@code ### } headings; paragraphs separated by an empty line</li>
 * <li>inline {@code **bold**}, {@code *italic*} or {@code _italic_}, {@code ~~struck~~}, {@code `code`},
 * {@code ==highlight==}, {@code [text](https://…)} and bare links</li>
 * <li>{@code > } quote; {@code > [!NOTE]}, {@code > [!TIP]}, {@code > [!WARNING]} callouts</li>
 * <li>{@code - } bullet lists, {@code 1. } numbered lists, {@code ---} divider, fenced code blocks</li>
 * <li>{@code ![caption](media/photo.jpg)} on its own line: a photo; the same with .mp4/.webm/.mov: a video</li>
 * <li>a link alone on a line: a link card</li>
 * </ul>
 * Anything else is shown as plain text, so an article never fails to open because of its markup.
 *
 * <h3>Who can write</h3>
 * Channels only (the author posts to a channel they can post in). With {@code BuildVars.TIGI_PAID_FEATURES} on,
 * writing becomes a premium feature ({@link #canWrite}); reading stays free for everyone.
 */
public final class TigiArticle {

    public static final String EXT = ".nilak";
    public static final String MIME = "application/vnd.nilak.article+zip";
    public static final String FORMAT = "nilak-article";
    public static final int VERSION = 1;
    public static final String MANIFEST = "manifest.json";
    public static final String BODY = "article.md";
    public static final String MEDIA_DIR = "media";

    /** Largest package a reader unpacks, and the largest single entry; guards against broken or hostile files. */
    private static final long MAX_PACKAGE = 400L * 1024 * 1024;
    private static final int WORDS_PER_MINUTE = 200;

    private TigiArticle() {
    }

    /* ---------- messages ---------- */

    public static boolean isArticle(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        final TLRPC.Document document = message.getDocument();
        if (document == null) {
            return false;
        }
        if (MIME.equals(document.mime_type)) {
            return true;
        }
        final String name = FileLoader.getDocumentFileName(document);
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(EXT);
    }

    /** The title from the caption's first line, else the file name without ".nilak". */
    public static String titleOf(MessageObject message) {
        final String caption = message.messageOwner.message;
        if (!TextUtils.isEmpty(caption)) {
            final String first = caption.trim().split("\n", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        final String name = message.getDocument() != null ? FileLoader.getDocumentFileName(message.getDocument()) : null;
        if (name == null) {
            return "";
        }
        final String base = name.toLowerCase(Locale.ROOT).endsWith(EXT) ? name.substring(0, name.length() - EXT.length()) : name;
        return base.replace('-', ' ').replace('_', ' ');
    }

    /** The caption after the title line (the subtitle), or empty. */
    public static String subtitleOf(MessageObject message) {
        final String caption = message.messageOwner.message;
        if (TextUtils.isEmpty(caption)) {
            return "";
        }
        final String[] parts = caption.trim().split("\n", 2);
        return parts.length > 1 ? parts[1].trim() : "";
    }

    /** Channels only; premium once paid features are on. */
    public static boolean canWrite(int account, TLRPC.Chat chat) {
        if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || !TigiPosting.canPostTo(chat)) {
            return false;
        }
        return !BuildVars.TIGI_PAID_FEATURES || UserConfig.getInstance(account).isPremium();
    }

    /** A file name from the title: lower case, words joined by "-", at most 60 characters, never empty. */
    public static String slug(String title) {
        String slug = title == null ? "" : title.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug.isEmpty() ? "article" : slug;
    }

    /* ---------- manifest ---------- */

    public static class Meta {
        public String title = "";
        public String subtitle = "";
        public String author = "";
        public String lang = "";
        public String cover;
        public int words;
        public int minutes;
        public long created;

        JSONObject toJson() throws Exception {
            final JSONObject json = new JSONObject();
            json.put("format", FORMAT);
            json.put("version", VERSION);
            json.put("title", title);
            json.put("subtitle", subtitle);
            json.put("author", author);
            json.put("lang", lang);
            if (cover != null) {
                json.put("cover", cover);
            }
            json.put("words", words);
            json.put("minutes", minutes);
            json.put("created", created);
            return json;
        }

        static Meta fromJson(JSONObject json) {
            final Meta meta = new Meta();
            meta.title = json.optString("title", "");
            meta.subtitle = json.optString("subtitle", "");
            meta.author = json.optString("author", "");
            meta.lang = json.optString("lang", "");
            meta.cover = json.has("cover") ? json.optString("cover", null) : null;
            meta.words = json.optInt("words", 0);
            meta.minutes = json.optInt("minutes", 0);
            meta.created = json.optLong("created", 0);
            return meta;
        }
    }

    public static int countWords(String body) {
        if (TextUtils.isEmpty(body)) {
            return 0;
        }
        // markup and media lines do not count as words
        final String text = body.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ").replaceAll("[#>*_`~=\\[\\]()-]", " ").trim();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }

    public static int minutesFor(int words) {
        return Math.max(1, Math.round(words / (float) WORDS_PER_MINUTE));
    }

    /* ---------- package I/O ---------- */

    public static void writeText(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    public static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        }
    }

    public static void writeMeta(File dir, Meta meta) throws Exception {
        writeText(new File(dir, MANIFEST), meta.toJson().toString(2));
    }

    public static Meta readMeta(File dir) {
        try {
            final JSONObject json = new JSONObject(readText(new File(dir, MANIFEST)));
            return Meta.fromJson(json);
        } catch (Exception e) {
            return new Meta();
        }
    }

    public static String readBody(File dir) {
        try {
            return readText(new File(dir, BODY));
        } catch (Exception e) {
            return "";
        }
    }

    /** Zips manifest.json, article.md and the media the body (or the cover) uses into out. Unused media stay out. */
    public static void pack(File dir, File out) throws IOException {
        final String body = readBody(dir);
        final Meta meta = readMeta(dir);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            addEntry(zip, new File(dir, MANIFEST), MANIFEST);
            addEntry(zip, new File(dir, BODY), BODY);
            final File media = new File(dir, MEDIA_DIR);
            final File[] files = media.listFiles();
            if (files != null) {
                for (File file : files) {
                    final String path = MEDIA_DIR + "/" + file.getName();
                    if (file.isFile() && (body.contains(path) || path.equals(meta.cover))) {
                        addEntry(zip, file, path);
                    }
                }
            }
        }
    }

    private static void addEntry(ZipOutputStream zip, File file, String name) throws IOException {
        if (!file.exists()) {
            return;
        }
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            final byte[] chunk = new byte[16384];
            int read;
            while ((read = in.read(chunk)) > 0) {
                zip.write(chunk, 0, read);
            }
        }
        zip.closeEntry();
    }

    /**
     * Unpacks a package into dir (emptied first). Only manifest.json, article.md and files under media/ are
     * written, names cannot leave dir, and the total size is capped.
     */
    public static void unpack(File zipFile, File dir) throws IOException {
        deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        final String root = dir.getCanonicalPath() + File.separator;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            final byte[] chunk = new byte[16384];
            while ((entry = zip.getNextEntry()) != null) {
                final String name = entry.getName();
                if (entry.isDirectory() || !(MANIFEST.equals(name) || BODY.equals(name) || name.startsWith(MEDIA_DIR + "/"))) {
                    continue;
                }
                final File target = new File(dir, name);
                if (!target.getCanonicalPath().startsWith(root)) {
                    continue;
                }
                final File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    int read;
                    while ((read = zip.read(chunk)) > 0) {
                        total += read;
                        if (total > MAX_PACKAGE) {
                            throw new IOException("article too large");
                        }
                        out.write(chunk, 0, read);
                    }
                }
            }
        }
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    /* ---------- Nilak Markdown ---------- */

    public static final int BLOCK_HEADING = 0;
    public static final int BLOCK_PARAGRAPH = 1;
    public static final int BLOCK_QUOTE = 2;
    public static final int BLOCK_CALLOUT = 3;
    public static final int BLOCK_BULLETS = 4;
    public static final int BLOCK_NUMBERS = 5;
    public static final int BLOCK_DIVIDER = 6;
    public static final int BLOCK_CODE = 7;
    public static final int BLOCK_IMAGE = 8;
    public static final int BLOCK_VIDEO = 9;
    public static final int BLOCK_LINK = 10;

    public static class Block {
        public final int type;
        public int level;
        public CharSequence text;
        public String path;
        public String caption;
        public String url;
        public String kind;
        public final ArrayList<CharSequence> items = new ArrayList<>();

        Block(int type) {
            this.type = type;
        }
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\d{1,3}[.)]\\s+(.+)$");
    private static final Pattern MEDIA = Pattern.compile("^!\\[([^\\]]*)]\\(\\s*([^)\\s]+)\\s*\\)$");
    private static final Pattern BARE_URL = Pattern.compile("^https?://\\S+$");
    private static final Pattern CALLOUT = Pattern.compile("^>\\s*\\[!(NOTE|TIP|WARNING|IMPORTANT)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    public static boolean isVideoPath(String path) {
        final String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".3gp") || lower.endsWith(".m4v");
    }

    public static ArrayList<Block> parse(String markdown) {
        final ArrayList<Block> blocks = new ArrayList<>();
        if (markdown == null) {
            return blocks;
        }
        final String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder paragraph = null;
        Block list = null;
        Block quote = null;
        int i = 0;
        while (i < lines.length) {
            final String raw = lines[i];
            final String line = raw.trim();
            // fenced code keeps its lines exactly
            if (line.startsWith("```")) {
                paragraph = flush(blocks, paragraph);
                list = null;
                quote = null;
                final StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (code.length() > 0) {
                        code.append('\n');
                    }
                    code.append(lines[i]);
                    i++;
                }
                final Block block = new Block(BLOCK_CODE);
                block.text = code.toString();
                blocks.add(block);
                i++;
                continue;
            }
            if (line.isEmpty()) {
                paragraph = flush(blocks, paragraph);
                list = null;
                quote = null;
                i++;
                continue;
            }
            Matcher m;
            if (line.equals("---") || line.equals("***") || line.equals("___")) {
                paragraph = flush(blocks, paragraph);
                list = null;
                quote = null;
                blocks.add(new Block(BLOCK_DIVIDER));
            } else if ((m = HEADING.matcher(line)).matches()) {
                paragraph = flush(blocks, paragraph);
                list = null;
                quote = null;
                final Block block = new Block(BLOCK_HEADING);
                block.level = m.group(1).length();
                block.text = inline(m.group(2));
                blocks.add(block);
            } else if ((m = CALLOUT.matcher(line)).matches()) {
                paragraph = flush(blocks, paragraph);
                list = null;
                final Block block = new Block(BLOCK_CALLOUT);
                block.kind = m.group(1).toUpperCase(Locale.ROOT);
                final StringBuilder text = new StringBuilder(m.group(2));
                while (i + 1 < lines.length && lines[i + 1].trim().startsWith(">")) {
                    i++;
                    final String next = lines[i].trim().substring(1).trim();
                    text.append(text.length() > 0 ? "\n" : "").append(next);
                }
                block.text = inline(text.toString());
                blocks.add(block);
                quote = null;
            } else if (line.startsWith(">")) {
                paragraph = flush(blocks, paragraph);
                list = null;
                final String text = line.substring(1).trim();
                if (quote == null) {
                    quote = new Block(BLOCK_QUOTE);
                    quote.text = inline(text);
                    blocks.add(quote);
                } else {
                    final SpannableStringBuilder joined = new SpannableStringBuilder(quote.text);
                    joined.append('\n').append(inline(text));
                    quote.text = joined;
                }
            } else if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
                paragraph = flush(blocks, paragraph);
                quote = null;
                if (list == null || list.type != BLOCK_BULLETS) {
                    list = new Block(BLOCK_BULLETS);
                    blocks.add(list);
                }
                list.items.add(inline(line.substring(2).trim()));
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                paragraph = flush(blocks, paragraph);
                quote = null;
                if (list == null || list.type != BLOCK_NUMBERS) {
                    list = new Block(BLOCK_NUMBERS);
                    blocks.add(list);
                }
                list.items.add(inline(m.group(1)));
            } else if ((m = MEDIA.matcher(line)).matches()) {
                paragraph = flush(blocks, paragraph);
                list = null;
                quote = null;
                final String path = m.group(2);
                final Block block = new Block(isVideoPath(path) ? BLOCK_VIDEO : BLOCK_IMAGE);
                block.path = path;
                block.caption = m.group(1).trim();
                blocks.add(block);
            } else if (BARE_URL.matcher(line).matches() && paragraph == null) {
                list = null;
                quote = null;
                final Block block = new Block(BLOCK_LINK);
                block.url = line;
                blocks.add(block);
            } else {
                list = null;
                quote = null;
                if (paragraph == null) {
                    paragraph = new StringBuilder(line);
                } else {
                    paragraph.append('\n').append(line);
                }
            }
            i++;
        }
        flush(blocks, paragraph);
        return blocks;
    }

    private static StringBuilder flush(ArrayList<Block> blocks, StringBuilder paragraph) {
        if (paragraph != null && paragraph.length() > 0) {
            final Block block = new Block(BLOCK_PARAGRAPH);
            block.text = inline(paragraph.toString());
            blocks.add(block);
        }
        return null;
    }

    private static final Pattern INLINE = Pattern.compile(
        "`([^`]+)`"                                   // 1 code
            + "|\\*\\*(.+?)\\*\\*"                    // 2 bold
            + "|~~(.+?)~~"                            // 3 struck
            + "|==(.+?)=="                            // 4 highlight
            + "|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)" // 5,6 link
            + "|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)" // 7 italic *
            + "|(?<![\\w])_(?!\\s)(.+?)(?<!\\s)_(?![\\w])"    // 8 italic _
            + "|(https?://[^\\s<>()]+[^\\s<>().,;:!?'\"])"   // 9 bare link
    );

    /** Inline markup as spans; nested markup inside bold/italic/links is parsed too. */
    public static CharSequence inline(String text) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        if (TextUtils.isEmpty(text)) {
            return out;
        }
        final Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.append(text, last, m.start());
            }
            final int start = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(0x22888888), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                out.append(inline(m.group(2)));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                out.append(inline(m.group(3)));
                out.setSpan(new StrikethroughSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(4) != null) {
                out.append(inline(m.group(4)));
                out.setSpan(new BackgroundColorSpan(0x55B996F0), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(5) != null) {
                out.append(inline(m.group(5)));
                out.setSpan(new URLSpan(m.group(6)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(7) != null || m.group(8) != null) {
                out.append(inline(m.group(7) != null ? m.group(7) : m.group(8)));
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(9) != null) {
                out.append(m.group(9));
                out.setSpan(new URLSpan(m.group(9)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        if (last < text.length()) {
            out.append(text, last, text.length());
        }
        return out;
    }

    /** Plain text of the body for excerpts: markup removed, media lines dropped. */
    public static String plainText(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown.replaceAll("(?m)^!\\[[^\\]]*]\\([^)]*\\)\\s*$", "")
            .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
            .replaceAll("(?m)^(#{1,3}|>|-|\\*|•|\\d+[.)])\\s+", "")
            .replaceAll("\\[!(NOTE|TIP|WARNING|IMPORTANT)]", "")
            .replace("**", "").replace("~~", "").replace("==", "").replace("`", "")
            .replaceAll("\\n{3,}", "\n\n").trim();
    }
}
