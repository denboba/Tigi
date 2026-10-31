package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * The article editor's model: the text the writer sees is styled (bold looks bold, a heading is big, a quote has its
 * bar, a photo is the photo) and no markup shows. Underneath, the styles are spans of the classes below, and
 * {@link #toMarkdown} / {@link #fromMarkdown} convert to and from Nilak Markdown, which is what is saved and
 * published. One paragraph = one line of the EditText; block styles (heading, quote, list, callout, code, media,
 * divider) belong to a whole paragraph and are kept that way by {@link #normalize}.
 */
public final class TigiArticleEditor {

    /** Stands for a photo, a video or a divider in the text. */
    public static final char OBJECT = '￼';

    private TigiArticleEditor() {
    }

    /* ---------- spans ---------- */

    public static class Bold extends StyleSpan {
        public Bold() {
            super(Typeface.BOLD);
        }
    }

    public static class Italic extends StyleSpan {
        public Italic() {
            super(Typeface.ITALIC);
        }
    }

    public static class Strike extends StrikethroughSpan {
    }

    public static class Mark extends BackgroundColorSpan {
        public Mark() {
            super(0x55B996F0);
        }
    }

    public static class Link extends URLSpan {
        public Link(String url) {
            super(url);
        }
    }

    /** A paragraph-level style. */
    public interface Block {
    }

    public static class Heading extends MetricAffectingSpan implements Block {
        public final int level;

        public Heading(int level) {
            this.level = level;
        }

        private void apply(TextPaint paint) {
            paint.setTextSize(paint.getTextSize() * (level <= 2 ? 1.32f : 1.15f));
            paint.setTypeface(TigiStyle.displayExtraBold());
        }

        @Override
        public void updateMeasureState(TextPaint paint) {
            apply(paint);
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            apply(paint);
        }
    }

    public static class Quote implements LeadingMarginSpan, Block {
        @Override
        public int getLeadingMargin(boolean first) {
            return dp(16);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            final Paint.Style style = p.getStyle();
            final int color = p.getColor();
            p.setStyle(Paint.Style.FILL);
            p.setColor(TigiStyle.accent());
            c.drawRect(x, top, x + dir * dp(3), bottom, p);
            p.setStyle(style);
            p.setColor(color);
        }
    }

    public static class Callout implements LeadingMarginSpan, LineBackgroundSpan, Block {
        public final String kind;

        public Callout(String kind) {
            this.kind = kind == null ? "NOTE" : kind;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return dp(12);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
        }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom, CharSequence text, int start, int end, int lineNumber) {
            final int color = p.getColor();
            p.setColor(Theme.multAlpha(TigiStyle.accent(), 0.14f));
            c.drawRect(left, top, right, bottom, p);
            p.setColor(color);
        }
    }

    public static class Bullet implements LeadingMarginSpan, Block {
        @Override
        public int getLeadingMargin(boolean first) {
            return dp(22);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!first || !(text instanceof Spanned) || ((Spanned) text).getSpanStart(this) != start) {
                return;
            }
            final int color = p.getColor();
            p.setColor(TigiStyle.accent());
            c.drawCircle(x + dir * dp(8), (top + bottom) / 2f, dp(3), p);
            p.setColor(color);
        }
    }

    public static class Numbered implements LeadingMarginSpan, Block {
        public int index = 1;

        @Override
        public int getLeadingMargin(boolean first) {
            return dp(26);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!first || !(text instanceof Spanned) || ((Spanned) text).getSpanStart(this) != start) {
                return;
            }
            final int color = p.getColor();
            final Typeface typeface = p.getTypeface();
            p.setColor(TigiStyle.accent());
            p.setTypeface(TigiStyle.displayBold());
            final String label = index + ".";
            c.drawText(label, dir > 0 ? x : x - p.measureText(label), baseline, p);
            p.setColor(color);
            p.setTypeface(typeface);
        }
    }

    public static class Code extends MetricAffectingSpan implements LineBackgroundSpan, Block {
        @Override
        public void updateMeasureState(TextPaint paint) {
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setTextSize(paint.getTextSize() * 0.88f);
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            updateMeasureState(paint);
        }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom, CharSequence text, int start, int end, int lineNumber) {
            final int color = p.getColor();
            p.setColor(0x22888888);
            c.drawRect(left, top, right, bottom, p);
            p.setColor(color);
        }
    }

    /** A photo or video: shown as its picture; ref is the link (or a pending token), path the local copy. */
    public static class Media extends ImageSpan implements Block {
        public String ref;
        public final String localPath;
        public final boolean video;
        public String caption;

        public Media(Drawable drawable, String ref, String localPath, boolean video, String caption) {
            super(drawable, ImageSpan.ALIGN_BOTTOM);
            this.ref = ref;
            this.localPath = localPath;
            this.video = video;
            this.caption = caption;
        }
    }

    public static class Divider extends ImageSpan implements Block {
        public Divider(Drawable drawable) {
            super(drawable, ImageSpan.ALIGN_BOTTOM);
        }
    }

    /* ---------- drawables ---------- */

    /** The picture for a media span: the local photo or video frame scaled to width, else a labelled box. */
    public static Drawable mediaDrawable(File base, String localPath, boolean video, int width, boolean uploading) {
        Bitmap bitmap = null;
        if (localPath != null) {
            final File file = new File(base, localPath);
            try {
                if (video) {
                    bitmap = android.media.ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(), android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
                } else {
                    final BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                    int sample = 1;
                    while (bounds.outWidth / (sample * 2) >= width) {
                        sample *= 2;
                    }
                    final BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        final int height = bitmap != null ? Math.max(dp(80), Math.min(dp(420), Math.round(width * bitmap.getHeight() / (float) bitmap.getWidth()))) : dp(160);
        final Bitmap out = Bitmap.createBitmap(Math.max(1, width), height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(out);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF rect = new RectF(0, 0, width, height);
        if (bitmap != null) {
            final android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(rect, dp(12), dp(12), android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawBitmap(bitmap, null, rect, paint);
        } else {
            paint.setColor(Theme.multAlpha(TigiStyle.accent(), 0.14f));
            canvas.drawRoundRect(rect, dp(12), dp(12), paint);
        }
        if (video || bitmap == null || uploading) {
            paint.setColor(0x88000000);
            canvas.drawCircle(width / 2f, height / 2f, dp(24), paint);
            paint.setColor(0xFFFFFFFF);
            if (video) {
                final android.graphics.Path play = new android.graphics.Path();
                play.moveTo(width / 2f - dp(7), height / 2f - dp(10));
                play.lineTo(width / 2f + dp(11), height / 2f);
                play.lineTo(width / 2f - dp(7), height / 2f + dp(10));
                play.close();
                canvas.drawPath(play, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                canvas.drawArc(new RectF(width / 2f - dp(10), height / 2f - dp(10), width / 2f + dp(10), height / 2f + dp(10)), -90, 270, false, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        final BitmapDrawable drawable = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), out);
        drawable.setBounds(0, 0, width, height);
        return drawable;
    }

    public static Drawable dividerDrawable(int width) {
        final int height = dp(28);
        final Bitmap out = Bitmap.createBitmap(Math.max(1, width), height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(out);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        for (int i = -1; i <= 1; i++) {
            canvas.drawCircle(width / 2f + i * dp(16), height / 2f, dp(2.5f), paint);
        }
        final BitmapDrawable drawable = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), out);
        drawable.setBounds(0, 0, width, height);
        return drawable;
    }

    /* ---------- paragraphs ---------- */

    public static int paragraphStart(CharSequence text, int position) {
        int start = Math.max(0, Math.min(position, text.length()));
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }

    public static int paragraphEnd(CharSequence text, int position) {
        int end = Math.max(0, Math.min(position, text.length()));
        while (end < text.length() && text.charAt(end) != '\n') {
            end++;
        }
        return end;
    }

    public static Block blockAt(Spanned text, int paragraphStart) {
        final int end = paragraphEnd(text, paragraphStart);
        final Block[] blocks = text.getSpans(paragraphStart, Math.max(paragraphStart, end), Block.class);
        for (Block block : blocks) {
            final int start = text.getSpanStart(block);
            if (start <= paragraphStart && text.getSpanEnd(block) >= Math.min(end, paragraphStart + 1) || start == paragraphStart) {
                return block;
            }
        }
        return null;
    }

    public static void removeBlocks(Editable text, int paragraphStart) {
        final int end = paragraphEnd(text, paragraphStart);
        for (Block block : text.getSpans(paragraphStart, Math.max(paragraphStart, end), Block.class)) {
            if (!(block instanceof Media) && !(block instanceof Divider)) {
                text.removeSpan(block);
            }
        }
    }

    public static void setBlock(Editable text, int paragraphStart, Block block) {
        removeBlocks(text, paragraphStart);
        if (block != null) {
            text.setSpan(block, paragraphStart, paragraphEnd(text, paragraphStart), Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
    }

    /**
     * Keeps block styles on whole paragraphs after an edit: a list or quote continues onto a new line made by
     * Enter; a heading, callout or code style stays on its first line; numbers are counted again.
     */
    public static void normalize(Editable text) {
        final Block[] blocks = text.getSpans(0, text.length(), Block.class);
        for (Block block : blocks) {
            if (block instanceof Media || block instanceof Divider) {
                continue;
            }
            final int start = text.getSpanStart(block);
            final int end = text.getSpanEnd(block);
            if (start < 0) {
                continue;
            }
            final int pStart = paragraphStart(text, start);
            final int pEnd = paragraphEnd(text, pStart);
            if (pStart != start || end != pEnd) {
                text.removeSpan(block);
                text.setSpan(block, pStart, pEnd, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            }
            if (end > pEnd + 1 && (block instanceof Bullet || block instanceof Numbered || block instanceof Quote)) {
                // the following lines inside the old range get their own copy
                int next = pEnd + 1;
                while (next <= Math.min(end, text.length())) {
                    final int nextEnd = paragraphEnd(text, next);
                    if (blockAt(text, next) == null) {
                        final Block copy = block instanceof Bullet ? new Bullet() : block instanceof Numbered ? new Numbered() : new Quote();
                        text.setSpan(copy, next, nextEnd, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                    if (nextEnd >= text.length()) {
                        break;
                    }
                    next = nextEnd + 1;
                }
            }
        }
        renumber(text);
    }

    public static void renumber(Spanned text) {
        int index = 0;
        int position = 0;
        while (position <= text.length()) {
            final Block block = blockAt(text, position);
            index = block instanceof Numbered ? index + 1 : 0;
            if (block instanceof Numbered) {
                ((Numbered) block).index = index;
            }
            final int end = paragraphEnd(text, position);
            if (end >= text.length()) {
                break;
            }
            position = end + 1;
        }
    }

    /* ---------- inline styles ---------- */

    /** Adds the style over the range, or removes it when the whole range already has it. */
    public static void toggleInline(Editable text, int start, int end, Class<?> type) {
        if (start >= end) {
            return;
        }
        final Object[] existing = text.getSpans(start, end, type);
        boolean covered = false;
        for (Object span : existing) {
            if (text.getSpanStart(span) <= start && text.getSpanEnd(span) >= end) {
                covered = true;
            }
        }
        if (covered) {
            for (Object span : existing) {
                final int s = text.getSpanStart(span);
                final int e = text.getSpanEnd(span);
                text.removeSpan(span);
                if (s < start) {
                    text.setSpan(newInline(type, span), s, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (e > end) {
                    text.setSpan(newInline(type, span), end, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        } else {
            text.setSpan(newInline(type, null), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static Object newInline(Class<?> type, Object like) {
        if (type == Bold.class) {
            return new Bold();
        } else if (type == Italic.class) {
            return new Italic();
        } else if (type == Strike.class) {
            return new Strike();
        } else if (type == Mark.class) {
            return new Mark();
        }
        return new Link(like instanceof Link ? ((Link) like).getURL() : "");
    }

    /* ---------- to Markdown ---------- */

    /**
     * Nilak Markdown for the editor text. localMedia: write media as their local paths (for the preview) instead of
     * their links.
     */
    public static String toMarkdown(Spanned text, boolean localMedia) {
        final StringBuilder out = new StringBuilder();
        int position = 0;
        Class<?> previous = null;
        while (position <= text.length()) {
            final int end = paragraphEnd(text, position);
            final Block block = blockAt(text, position);
            final String line = paragraph(text, position, end, block, localMedia);
            if (line != null) {
                final Class<?> kind = block != null ? block.getClass() : null;
                if (out.length() > 0) {
                    final boolean joined = kind != null && kind == previous && (block instanceof Bullet || block instanceof Numbered || block instanceof Quote || block instanceof Code);
                    out.append(joined ? "\n" : "\n\n");
                }
                if (block instanceof Code) {
                    final boolean continues = previous == Code.class && out.length() > 0;
                    if (continues) {
                        // inside the same fence: drop the closing fence written by the previous line
                        final int fence = out.lastIndexOf("\n```");
                        if (fence >= 0) {
                            out.setLength(fence);
                            out.append('\n');
                        }
                        out.append(line).append("\n```");
                    } else {
                        out.append("```\n").append(line).append("\n```");
                    }
                } else {
                    out.append(line);
                }
                previous = kind;
            } else {
                previous = null;
            }
            if (end >= text.length()) {
                break;
            }
            position = end + 1;
        }
        return out.toString();
    }

    private static String paragraph(Spanned text, int start, int end, Block block, boolean localMedia) {
        if (block instanceof Media) {
            final Media media = (Media) block;
            final String ref = localMedia && media.localPath != null ? media.localPath : media.ref;
            final String caption = media.caption != null ? media.caption.replace("]", "").replace("\n", " ") : "";
            return ref == null ? null : "![" + caption + "](" + ref + ")";
        }
        if (block instanceof Divider) {
            return "---";
        }
        if (block instanceof Code) {
            return text.subSequence(start, end).toString();
        }
        final String inline = inline(text, start, end);
        if (inline.trim().isEmpty()) {
            return null;
        }
        if (block instanceof Heading) {
            return (((Heading) block).level <= 2 ? "## " : "### ") + inline;
        } else if (block instanceof Quote) {
            return "> " + inline;
        } else if (block instanceof Callout) {
            return "> [!" + ((Callout) block).kind + "] " + inline;
        } else if (block instanceof Bullet) {
            return "- " + inline;
        } else if (block instanceof Numbered) {
            return ((Numbered) block).index + ". " + inline;
        }
        // a plain line that would read as markup gets an escape
        if (inline.matches("^(#{1,3} |> |[-*•] |\\d{1,3}[.)] |---$|```|!\\[).*")) {
            return "\\" + inline;
        }
        return inline;
    }

    private static final String ESCAPED = "\\*_~=`[]";

    private static String inline(Spanned text, int start, int end) {
        final Object[] spans = text.getSpans(start, end, Object.class);
        final ArrayList<Object> styles = new ArrayList<>();
        for (Object span : spans) {
            if ((span instanceof Bold || span instanceof Italic || span instanceof Strike || span instanceof Mark || span instanceof Link)
                && text.getSpanEnd(span) > text.getSpanStart(span)) {
                styles.add(span);
            }
        }
        final StringBuilder out = new StringBuilder();
        final ArrayList<Object> open = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            // close what ends here (innermost first), then open what starts here
            for (int k = open.size() - 1; k >= 0; k--) {
                final Object span = open.get(k);
                if (Math.min(text.getSpanEnd(span), end) == i) {
                    out.append(closing(span));
                    open.remove(k);
                }
            }
            if (i == end) {
                break;
            }
            final ArrayList<Object> starting = new ArrayList<>();
            for (Object span : styles) {
                if (Math.max(text.getSpanStart(span), start) == i) {
                    starting.add(span);
                }
            }
            // the longest opens first, so it closes last
            java.util.Collections.sort(starting, (a, b) -> text.getSpanEnd(b) - text.getSpanEnd(a));
            for (Object span : starting) {
                out.append(opening(span));
                open.add(span);
            }
            final char c = text.charAt(i);
            if (c == OBJECT) {
                continue;
            }
            if (ESCAPED.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String opening(Object span) {
        if (span instanceof Bold) {
            return "**";
        } else if (span instanceof Italic) {
            return "*";
        } else if (span instanceof Strike) {
            return "~~";
        } else if (span instanceof Mark) {
            return "==";
        }
        return "[";
    }

    private static String closing(Object span) {
        if (span instanceof Link) {
            return "](" + ((Link) span).getURL() + ")";
        }
        return opening(span);
    }

    /* ---------- from Markdown ---------- */

    /** The editor text for saved Markdown; media get their pictures from base/localPath when known. */
    public static SpannableStringBuilder fromMarkdown(String markdown, File base, java.util.Map<String, String> localByRef, int width) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        final ArrayList<TigiArticle.Block> blocks = TigiArticle.parse(markdown);
        for (TigiArticle.Block block : blocks) {
            switch (block.type) {
                case TigiArticle.BLOCK_BULLETS:
                case TigiArticle.BLOCK_NUMBERS:
                    for (CharSequence item : block.items) {
                        appendParagraph(out, item, block.type == TigiArticle.BLOCK_BULLETS ? new Bullet() : new Numbered());
                    }
                    break;
                case TigiArticle.BLOCK_QUOTE:
                    for (CharSequence line : split(block.text)) {
                        appendParagraph(out, line, new Quote());
                    }
                    break;
                case TigiArticle.BLOCK_HEADING:
                    appendParagraph(out, block.text, new Heading(Math.max(2, block.level)));
                    break;
                case TigiArticle.BLOCK_CALLOUT:
                    appendParagraph(out, flatten(block.text), new Callout(block.kind));
                    break;
                case TigiArticle.BLOCK_CODE:
                    for (CharSequence line : split(block.text)) {
                        appendParagraph(out, line.toString(), new Code());
                    }
                    break;
                case TigiArticle.BLOCK_DIVIDER:
                    appendParagraph(out, String.valueOf(OBJECT), new Divider(dividerDrawable(width)));
                    break;
                case TigiArticle.BLOCK_IMAGE:
                case TigiArticle.BLOCK_VIDEO: {
                    final String local = localByRef != null ? localByRef.get(block.path) : null;
                    final String localPath = local != null ? local : (!block.path.startsWith("http") && !TigiArticleMedia.isPending(block.path) ? block.path : null);
                    final boolean video = block.type == TigiArticle.BLOCK_VIDEO;
                    appendParagraph(out, String.valueOf(OBJECT), new Media(mediaDrawable(base, localPath, video, width, TigiArticleMedia.isPending(block.path)), block.path, localPath, video, block.caption));
                    break;
                }
                case TigiArticle.BLOCK_LINK:
                    appendParagraph(out, block.url, null);
                    break;
                default:
                    for (CharSequence line : split(block.text)) {
                        appendParagraph(out, line, null);
                    }
                    break;
            }
        }
        renumber(out);
        return out;
    }

    private static void appendParagraph(SpannableStringBuilder out, CharSequence content, Block block) {
        if (out.length() > 0) {
            out.append('\n');
        }
        final int start = out.length();
        final SpannableStringBuilder converted = convertInline(content);
        out.append(converted);
        if (block != null) {
            // an object (photo, video, divider) covers exactly its character, so typing next to it stays text
            final boolean object = block instanceof Media || block instanceof Divider;
            out.setSpan(block, start, out.length(), object ? Spanned.SPAN_EXCLUSIVE_EXCLUSIVE : Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
    }

    /** Maps the parser's spans to the editor's. */
    private static SpannableStringBuilder convertInline(CharSequence content) {
        final SpannableStringBuilder out = new SpannableStringBuilder(content == null ? "" : content);
        for (Object span : out.getSpans(0, out.length(), Object.class)) {
            final int s = out.getSpanStart(span);
            final int e = out.getSpanEnd(span);
            Object replacement = null;
            if (span instanceof StyleSpan) {
                replacement = ((StyleSpan) span).getStyle() == Typeface.BOLD ? new Bold() : ((StyleSpan) span).getStyle() == Typeface.ITALIC ? new Italic() : null;
            } else if (span instanceof StrikethroughSpan) {
                replacement = new Strike();
            } else if (span instanceof BackgroundColorSpan) {
                replacement = ((BackgroundColorSpan) span).getBackgroundColor() == 0x55B996F0 ? new Mark() : null;
            } else if (span instanceof URLSpan) {
                replacement = new Link(((URLSpan) span).getURL());
            }
            out.removeSpan(span);
            if (replacement != null && e > s) {
                out.setSpan(replacement, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    private static ArrayList<CharSequence> split(CharSequence text) {
        final ArrayList<CharSequence> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                lines.add(text.subSequence(start, i));
                start = i + 1;
            }
        }
        return lines;
    }

    private static CharSequence flatten(CharSequence text) {
        if (text == null) {
            return "";
        }
        final SpannableStringBuilder out = new SpannableStringBuilder(text);
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\n') {
                out.replace(i, i + 1, " ");
            }
        }
        return out;
    }

    /** Every media span in the text, in order. */
    public static Media[] media(Spanned text) {
        final Media[] media = text.getSpans(0, text.length(), Media.class);
        Arrays.sort(media, (a, b) -> text.getSpanStart(a) - text.getSpanStart(b));
        return media;
    }

    public static boolean hasText(Spanned text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c != OBJECT && !Character.isWhitespace(c)) {
                return true;
            }
        }
        return text.getSpans(0, text.length(), Media.class).length > 0;
    }

    static boolean isEmptyLine(CharSequence text, int start, int end) {
        return TextUtils.isEmpty(text.subSequence(start, end).toString().trim());
    }
}
