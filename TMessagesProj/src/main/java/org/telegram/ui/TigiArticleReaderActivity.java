package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.io.File;
import java.util.ArrayList;

/**
 * Nilak article reader: downloads the article's package (one Telegram file), unpacks it and lays it out natively:
 * cover, title, subtitle, author and reading time, then the body blocks with photos, videos, quotes, callouts, lists,
 * code and link cards. Text size can be changed (kept for all articles), a thin bar shows reading progress, and
 * Comments and Share sit at the end. Also opens a composer draft for preview.
 */
public class TigiArticleReaderActivity extends BaseFragment {

    private static final String KEY_TEXT_SCALE = "nilak_reader_scale";
    private static final float[] SCALES = {0.9f, 1f, 1.15f, 1.3f};

    private MessageObject message;
    private TLRPC.Chat chat;
    private File draftDir;

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout column;
    private View progressBar;
    private TextView statusView;
    private RadialProgressView spinner;
    private File articleDir;
    private TigiArticle.Meta meta;
    private ArrayList<TigiArticle.Block> blocks;
    private boolean destroyed;
    private VideoView playing;
    /** Messages behind the article's t.me media links, loaded once. */
    private final java.util.HashMap<String, MessageObject> linked = new java.util.HashMap<>();
    private TigiInlinePlayer inlinePlayer;

    public static void open(BaseFragment fragment, MessageObject message) {
        if (fragment == null || message == null || !TigiArticle.isArticle(message)) {
            return;
        }
        final TigiArticleReaderActivity reader = new TigiArticleReaderActivity();
        reader.message = message;
        fragment.presentFragment(reader);
    }

    /** Preview of a composer draft folder (manifest.json, article.md, media/). */
    public static void preview(BaseFragment fragment, File dir) {
        final TigiArticleReaderActivity reader = new TigiArticleReaderActivity();
        reader.draftDir = dir;
        fragment.presentFragment(reader);
    }

    @Override
    public boolean onFragmentCreate() {
        if (message != null) {
            chat = getMessagesController().getChat(-message.getDialogId());
        }
        return (message != null || draftDir != null) && super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(poller);
        if (playing != null) {
            playing.stopPlayback();
        }
        if (inlinePlayer != null) {
            inlinePlayer.stop();
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (playing != null && playing.isPlaying()) {
            playing.pause();
        }
        if (inlinePlayer != null) {
            inlinePlayer.pause();
        }
    }

    private float scale() {
        final int index = MessagesController.getGlobalMainSettings().getInt(KEY_TEXT_SCALE, 1);
        return SCALES[Math.max(0, Math.min(SCALES.length - 1, index))];
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout outer = new LinearLayout(context);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(barIcon(context, R.drawable.ic_ab_back, R.string.AccDescrGoBack, v -> finishFragment(), LocaleController.isRTL), LayoutHelper.createLinear(48, 52));
        final TextView barTitle = new TextView(context);
        barTitle.setText(LocaleController.getString(draftDir != null ? R.string.NilakArticlePreview : R.string.NilakArticleLabel));
        barTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        barTitle.setTypeface(TigiStyle.displayBold());
        barTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        bar.addView(barTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 0, 0));
        final TextView textSize = new TextView(context);
        textSize.setText("Aa");
        textSize.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textSize.setTypeface(TigiStyle.displayExtraBold());
        textSize.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textSize.setGravity(Gravity.CENTER);
        textSize.setContentDescription(LocaleController.getString(R.string.NilakTextSize));
        textSize.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        textSize.setOnClickListener(v -> {
            final int index = (MessagesController.getGlobalMainSettings().getInt(KEY_TEXT_SCALE, 1) + 1) % SCALES.length;
            MessagesController.getGlobalMainSettings().edit().putInt(KEY_TEXT_SCALE, index).apply();
            render();
        });
        bar.addView(textSize, LayoutHelper.createLinear(48, 48));
        if (message != null) {
            bar.addView(barIcon(context, R.drawable.tigi_share_24, R.string.ShareFile, v -> share(), false), LayoutHelper.createLinear(48, 48, 0, 0, 6, 0));
        }
        outer.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        final FrameLayout track = new FrameLayout(context);
        track.setBackgroundColor(Theme.getColor(Theme.key_divider));
        progressBar = new View(context);
        progressBar.setBackgroundColor(TigiStyle.accent());
        progressBar.setPivotX(LocaleController.isRTL ? AndroidUtilities.displaySize.x : 0);
        progressBar.setScaleX(0f);
        track.addView(progressBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        outer.addView(track, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 2));

        scroll = new ScrollView(context);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateProgress());
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(8), dp(20), dp(40) + AndroidUtilities.navigationBarHeight);
        scroll.addView(column);
        outer.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(outer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        spinner = new RadialProgressView(context);
        spinner.setProgressColor(TigiStyle.accent());
        root.addView(spinner, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(32), 0, dp(32), 0);
        root.addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 64, 0, 0));

        fragmentView = root;
        start();
        return root;
    }

    private View barIcon(Context context, int icon, int description, View.OnClickListener onClick, boolean mirror) {
        final ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setContentDescription(LocaleController.getString(description));
        view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        view.setOnClickListener(onClick);
        if (mirror) {
            view.setScaleX(-1f);
        }
        return view;
    }

    /* loading */

    private final Runnable poller = this::poll;

    private void start() {
        if (draftDir != null) {
            articleDir = draftDir;
            loadParsed();
            return;
        }
        final File file = FileLoader.getInstance(currentAccount).getPathToMessage(message.messageOwner);
        if (file != null && file.exists() && file.length() > 0 && !isLoading()) {
            unpack(file);
            return;
        }
        FileLoader.getInstance(currentAccount).loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
        poll();
    }

    private boolean isLoading() {
        return FileLoader.getInstance(currentAccount).isLoadingFile(FileLoader.getAttachFileName(message.getDocument()));
    }

    /** Waits for the download, showing its progress; a stalled or failed download offers a retry. */
    private void poll() {
        if (destroyed) {
            return;
        }
        final File file = FileLoader.getInstance(currentAccount).getPathToMessage(message.messageOwner);
        if (file != null && file.exists() && file.length() > 0 && !isLoading()) {
            unpack(file);
            return;
        }
        if (!isLoading()) {
            spinner.setVisibility(View.GONE);
            statusView.setText(LocaleController.getString(R.string.NilakArticleLoadFailed));
            statusView.setOnClickListener(v -> {
                statusView.setOnClickListener(null);
                spinner.setVisibility(View.VISIBLE);
                FileLoader.getInstance(currentAccount).loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
                AndroidUtilities.runOnUIThread(poller, 500);
            });
            return;
        }
        final Float progress = ImageLoader.getInstance().getFileProgress(FileLoader.getAttachFileName(message.getDocument()));
        final int percent = progress != null ? Math.round(progress * 100) : 0;
        statusView.setText(LocaleController.formatString(R.string.NilakArticleLoading, percent, AndroidUtilities.formatFileSize(message.getDocument().size)));
        AndroidUtilities.runOnUIThread(poller, 400);
    }

    private void unpack(File file) {
        statusView.setText(LocaleController.getString(R.string.NilakArticleOpening));
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_articles/" + message.getDocument().id);
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok;
            try {
                // unpacked once per article; a stale folder (no body) is unpacked again
                if (!new File(dir, TigiArticle.BODY).exists()) {
                    TigiArticle.unpack(file, dir);
                }
                ok = new File(dir, TigiArticle.BODY).exists();
            } catch (Exception e) {
                FileLog.e(e);
                ok = false;
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) {
                    return;
                }
                if (!success) {
                    spinner.setVisibility(View.GONE);
                    statusView.setText(LocaleController.getString(R.string.NilakArticleBroken));
                    return;
                }
                articleDir = dir;
                loadParsed();
            });
        });
    }

    private void loadParsed() {
        Utilities.globalQueue.postRunnable(() -> {
            final TigiArticle.Meta loadedMeta = TigiArticle.readMeta(articleDir);
            final ArrayList<TigiArticle.Block> parsed = TigiArticle.parse(TigiArticle.readBody(articleDir));
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) {
                    return;
                }
                meta = loadedMeta;
                if (TextUtils.isEmpty(meta.title) && message != null) {
                    meta.title = TigiArticle.titleOf(message);
                }
                blocks = parsed;
                // photos and videos kept as Telegram links: fetch those messages (small) before laying out
                final ArrayList<String> refs = new ArrayList<>();
                if (TigiArticleMedia.isTelegramRef(meta.cover)) {
                    refs.add(meta.cover);
                }
                for (TigiArticle.Block block : parsed) {
                    if ((block.type == TigiArticle.BLOCK_IMAGE || block.type == TigiArticle.BLOCK_VIDEO) && TigiArticleMedia.isTelegramRef(block.path)) {
                        refs.add(block.path);
                    }
                }
                if (refs.isEmpty()) {
                    spinner.setVisibility(View.GONE);
                    statusView.setVisibility(View.GONE);
                    render();
                    return;
                }
                TigiArticleMedia.loadMessages(currentAccount, refs, found -> {
                    if (destroyed) {
                        return;
                    }
                    linked.putAll(found);
                    spinner.setVisibility(View.GONE);
                    statusView.setVisibility(View.GONE);
                    render();
                });
            });
        });
    }

    /* layout */

    private void render() {
        if (column == null || meta == null || blocks == null) {
            return;
        }
        if (playing != null) {
            playing.stopPlayback();
            playing = null;
        }
        column.removeAllViews();
        final Context context = getParentActivity();
        final float s = scale();
        final int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        if (!TextUtils.isEmpty(meta.cover)) {
            final View cover = mediaView(context, meta.cover, false);
            if (cover != null) {
                column.addView(cover, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 16));
            }
        }
        final TextView title = new TextView(context);
        title.setText(meta.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28 * s);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(ink);
        title.setLineSpacing(dp(2), 1f);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        if (!TextUtils.isEmpty(meta.subtitle)) {
            final TextView subtitle = new TextView(context);
            subtitle.setText(meta.subtitle);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18 * s);
            subtitle.setTextColor(gray);
            subtitle.setLineSpacing(dp(2), 1f);
            column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }
        final TextView byline = new TextView(context);
        final StringBuilder by = new StringBuilder();
        final String author = chat != null ? chat.title : meta.author;
        if (!TextUtils.isEmpty(author)) {
            by.append(author);
        }
        if (message != null) {
            by.append(by.length() > 0 ? " · " : "").append(LocaleController.formatDateAudio(message.messageOwner.date, true));
        }
        final int minutes = meta.minutes > 0 ? meta.minutes : TigiArticle.minutesFor(meta.words);
        by.append(by.length() > 0 ? " · " : "").append(LocaleController.formatString(R.string.NilakReadMinutes, minutes));
        byline.setText(by);
        byline.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        byline.setTypeface(TigiStyle.displayBold());
        byline.setTextColor(gray);
        if (chat != null) {
            byline.setOnClickListener(v -> TigiNavigation.openProfile(this, -chat.id));
        }
        column.addView(byline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 18));
        column.addView(divider(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 18));

        for (TigiArticle.Block block : blocks) {
            final View view = blockView(context, block, s, ink, gray);
            if (view != null) {
                column.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));
            }
        }

        if (message != null) {
            column.addView(divider(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 12, 0, 16));
            final LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            if (chat != null) {
                actions.addView(actionButton(context, R.string.TigiComments, v -> showDialog(new TigiCommentsSheet(this, chat, message))), LayoutHelper.createLinear(0, 44, 1f, 0, 0, 6, 0));
            }
            actions.addView(actionButton(context, R.string.ShareFile, v -> share()), LayoutHelper.createLinear(0, 44, 1f, 6, 0, 0, 0));
            column.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        scroll.post(this::updateProgress);
    }

    private View blockView(Context context, TigiArticle.Block block, float s, int ink, int gray) {
        switch (block.type) {
            case TigiArticle.BLOCK_HEADING: {
                final TextView view = text(context, block.text, (block.level == 1 ? 24 : block.level == 2 ? 21 : 18) * s, ink);
                view.setTypeface(TigiStyle.displayExtraBold());
                androidx.core.view.ViewCompat.setAccessibilityHeading(view, true);
                view.setPadding(0, dp(8), 0, 0);
                return view;
            }
            case TigiArticle.BLOCK_PARAGRAPH:
                return text(context, block.text, 17 * s, ink);
            case TigiArticle.BLOCK_QUOTE: {
                final LinearLayout box = new LinearLayout(context);
                box.setOrientation(LinearLayout.HORIZONTAL);
                final View bar = new View(context);
                bar.setBackgroundColor(TigiStyle.accent());
                box.addView(bar, LayoutHelper.createLinear(3, LayoutHelper.MATCH_PARENT));
                final TextView view = text(context, block.text, 19 * s, ink);
                view.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
                box.addView(view, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 2, 0, 2));
                return box;
            }
            case TigiArticle.BLOCK_CALLOUT: {
                final LinearLayout box = new LinearLayout(context);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(14), dp(12), dp(14), dp(12));
                final int color = "WARNING".equals(block.kind) ? 0xFFE0A030 : "IMPORTANT".equals(block.kind) ? 0xFFF0446B : "TIP".equals(block.kind) ? 0xFF22C55E : TigiStyle.accent();
                box.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.multAlpha(color, 0.14f)));
                final TextView label = new TextView(context);
                label.setText(LocaleController.getString("WARNING".equals(block.kind) ? R.string.NilakCalloutWarning
                    : "IMPORTANT".equals(block.kind) ? R.string.NilakCalloutImportant : "TIP".equals(block.kind) ? R.string.NilakCalloutTip : R.string.NilakCalloutNote));
                label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                label.setTypeface(TigiStyle.displayExtraBold());
                label.setTextColor(color);
                box.addView(label);
                box.addView(text(context, block.text, 16 * s, ink), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
                return box;
            }
            case TigiArticle.BLOCK_BULLETS:
            case TigiArticle.BLOCK_NUMBERS: {
                final LinearLayout list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < block.items.size(); i++) {
                    final LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    final TextView marker = text(context, block.type == TigiArticle.BLOCK_BULLETS ? "•" : (i + 1) + ".", 17 * s, TigiStyle.accent());
                    marker.setTypeface(TigiStyle.displayBold());
                    row.addView(marker, LayoutHelper.createLinear(28, LayoutHelper.WRAP_CONTENT));
                    row.addView(text(context, block.items.get(i), 17 * s, ink), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                    list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 0 : 6, 0, 0));
                }
                return list;
            }
            case TigiArticle.BLOCK_DIVIDER: {
                final TextView view = text(context, "•   •   •", 16, gray);
                view.setGravity(Gravity.CENTER);
                view.setPadding(0, dp(6), 0, dp(6));
                return view;
            }
            case TigiArticle.BLOCK_CODE: {
                final HorizontalScrollView box = new HorizontalScrollView(context);
                box.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
                final TextView view = text(context, block.text, 14 * s, ink);
                view.setTypeface(Typeface.MONOSPACE);
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                view.setMovementMethod(null);
                box.addView(view);
                return box;
            }
            case TigiArticle.BLOCK_IMAGE:
            case TigiArticle.BLOCK_VIDEO: {
                final View media = mediaView(context, block.path, block.type == TigiArticle.BLOCK_VIDEO);
                if (media == null) {
                    return null;
                }
                final LinearLayout box = new LinearLayout(context);
                box.setOrientation(LinearLayout.VERTICAL);
                box.addView(media, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                addCaption(context, box, block.caption, gray);
                return box;
            }
            case TigiArticle.BLOCK_LINK: {
                final LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(14), dp(12), dp(14), dp(12));
                card.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundGray)));
                String host = block.url;
                try {
                    host = Uri.parse(block.url).getHost();
                } catch (Exception ignore) {
                }
                final TextView name = text(context, host, 15, ink);
                name.setTypeface(TigiStyle.displayBold());
                card.addView(name);
                final TextView url = text(context, block.url, 13, TigiStyle.accent());
                url.setSingleLine(true);
                url.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(url);
                card.setOnClickListener(v -> Browser.openUrl(getParentActivity(), block.url));
                return card;
            }
        }
        return null;
    }

    /**
     * A photo or video from its reference: a Telegram post link (loaded and cached by Telegram, photos sharpen as
     * they load, videos stream), a web image link, or a file inside the package (older articles, previews).
     */
    private View mediaView(Context context, String ref, boolean videoHint) {
        if (TextUtils.isEmpty(ref)) {
            return null;
        }
        if (TigiArticleMedia.isTelegramRef(ref)) {
            final MessageObject media = linked.get(TigiArticleMedia.key(ref));
            if (media == null) {
                return null;
            }
            return telegramMediaView(context, media);
        }
        if (TigiArticleMedia.isWebImage(ref)) {
            final org.telegram.ui.Components.BackupImageView image = new org.telegram.ui.Components.BackupImageView(context);
            image.setRoundRadius(dp(12));
            image.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
            image.setImage(ref, null, null);
            final FrameLayout box = new FrameLayout(context);
            box.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 220));
            return box;
        }
        final File file = new File(articleDir, ref);
        if (!file.getAbsolutePath().startsWith(articleDir.getAbsolutePath()) || !file.exists()) {
            return null;
        }
        return videoHint || TigiArticle.isVideoPath(ref) ? videoView(context, file) : imageView(context, file, false);
    }

    private View telegramMediaView(Context context, MessageObject media) {
        final int width = AndroidUtilities.displaySize.x - dp(40);
        int w = 0, h = 0;
        if (media.photoThumbs != null && !media.photoThumbs.isEmpty()) {
            final TLRPC.PhotoSize biggest = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(media.photoThumbs, AndroidUtilities.getPhotoSize());
            if (biggest != null) {
                w = biggest.w;
                h = biggest.h;
            }
        }
        final int height = w > 0 && h > 0 ? Math.max(dp(120), Math.min(dp(520), Math.round(width * h / (float) w))) : dp(220);
        final FrameLayout box = new FrameLayout(context);
        box.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
        box.setClipToOutline(true);
        box.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        final org.telegram.ui.Components.BackupImageView image = new org.telegram.ui.Components.BackupImageView(context);
        final TLRPC.PhotoSize size = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(media.photoThumbs, AndroidUtilities.getPhotoSize());
        final TLRPC.PhotoSize thumb = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(media.photoThumbs, 40);
        image.getImageReceiver().setImage(
            org.telegram.messenger.ImageLocation.getForObject(size, media.photoThumbsObject), "800_800",
            org.telegram.messenger.ImageLocation.getForObject(thumb, media.photoThumbsObject), "40_40_b",
            0, null, media, 0);
        box.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (media.isVideo()) {
            final ImageView play = new ImageView(context);
            play.setImageResource(R.drawable.tigi_play_big);
            play.setScaleType(ImageView.ScaleType.CENTER);
            box.addView(play, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            box.setContentDescription(LocaleController.getString(R.string.AttachVideo));
            box.setOnClickListener(v -> {
                if (inlinePlayer == null) {
                    inlinePlayer = new TigiInlinePlayer(currentAccount);
                    inlinePlayer.setLooping(false);
                }
                play.setVisibility(View.GONE);
                inlinePlayer.play(box, media, (msg, rendering, failed) -> {
                    if (failed) {
                        play.setVisibility(View.VISIBLE);
                    }
                });
            });
        } else {
            box.setContentDescription(LocaleController.getString(R.string.AttachPhoto));
        }
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
        box.setLayoutParams(params);
        final LinearLayout wrapper = new LinearLayout(context);
        wrapper.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
        return wrapper;
    }

    private TextView text(Context context, CharSequence value, float sizeDp, int color) {
        final TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTextColor(color);
        view.setLinkTextColor(TigiStyle.accent());
        view.setLineSpacing(0, 1.5f);
        view.setTextIsSelectable(true);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        return view;
    }

    private void addCaption(Context context, LinearLayout box, String caption, int gray) {
        if (TextUtils.isEmpty(caption)) {
            return;
        }
        final TextView view = text(context, TigiArticle.inline(caption), 13, gray);
        view.setGravity(Gravity.CENTER);
        box.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
    }

    private View divider(Context context) {
        final View view = new View(context);
        view.setBackgroundColor(Theme.getColor(Theme.key_divider));
        return view;
    }

    private View actionButton(Context context, int label, View.OnClickListener onClick) {
        final TextView view = new TextView(context);
        view.setText(LocaleController.getString(label));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(TigiStyle.displayBold());
        view.setTextColor(TigiStyle.accent());
        view.setGravity(Gravity.CENTER);
        view.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
        view.setOnClickListener(onClick);
        return view;
    }

    /** A photo scaled to the column width, decoded off the main thread. */
    private ImageView imageView(Context context, File file, boolean cover) {
        final ImageView image = new ImageView(context);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
        image.setMinimumHeight(dp(cover ? 180 : 120));
        image.setClipToOutline(true);
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        final int target = AndroidUtilities.displaySize.x;
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = null;
            try {
                final BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= target) {
                    sample *= 2;
                }
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && result != null) {
                    image.setMinimumHeight(0);
                    image.setImageBitmap(result);
                }
            });
        });
        return image;
    }

    /** A video: its first frame with a play mark; tapping plays it in place with the system controls. */
    private View videoView(Context context, File file) {
        final FrameLayout box = new FrameLayout(context);
        box.setBackground(Theme.createRoundRectDrawable(dp(12), 0xFF000000));
        final ImageView poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(poster, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 220));
        final ImageView play = new ImageView(context);
        play.setImageResource(R.drawable.tigi_play_big);
        play.setScaleType(ImageView.ScaleType.CENTER);
        box.addView(play, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 220));
        box.setContentDescription(LocaleController.getString(R.string.AttachVideo));
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap frame = null;
            try {
                frame = ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(), MediaStore.Images.Thumbnails.MINI_KIND);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Bitmap result = frame;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && result != null) {
                    poster.setImageBitmap(result);
                }
            });
        });
        box.setOnClickListener(v -> {
            if (playing != null) {
                playing.stopPlayback();
            }
            box.removeAllViews();
            box.setOnClickListener(null);
            final VideoView video = new VideoView(context);
            final MediaController controls = new MediaController(context);
            controls.setAnchorView(video);
            video.setMediaController(controls);
            video.setVideoPath(file.getAbsolutePath());
            video.setOnPreparedListener(mp -> video.start());
            box.addView(video, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 220, Gravity.CENTER));
            playing = video;
        });
        return box;
    }

    private void updateProgress() {
        if (scroll == null || column == null || progressBar == null) {
            return;
        }
        final int range = column.getHeight() - scroll.getHeight();
        progressBar.setScaleX(range <= 0 ? 1f : Math.max(0f, Math.min(1f, scroll.getScrollY() / (float) range)));
    }

    private void share() {
        if (message == null || chat == null) {
            return;
        }
        TigiShare.show(this, chat, message, message, meta != null ? meta.title + (TextUtils.isEmpty(meta.subtitle) ? "" : "\n" + meta.subtitle) : TigiArticle.titleOf(message));
    }
}
