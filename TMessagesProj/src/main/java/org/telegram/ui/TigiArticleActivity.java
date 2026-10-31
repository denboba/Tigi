package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

/**
 * Nilak article composer. What you write is what readers see: bold looks bold, headings are big, quotes and
 * callouts are drawn, photos and videos show as themselves, and no markup appears (see {@link TigiArticleEditor}).
 * Photos and videos upload in the background to the writer's media channel and the article keeps only their links
 * ({@link TigiArticleMedia}); without a media channel they travel inside the article file instead. Preview opens the
 * real reader; Publish posts one small .nilak file to the channel (see {@link TigiArticle}).
 */
public class TigiArticleActivity extends BaseFragment {

    private static final String KEY_TITLE = "nilak_article_draft_title";
    private static final String KEY_SUBTITLE = "nilak_article_draft_subtitle";
    private static final String KEY_BODY = "nilak_article_draft_body";
    private static final String KEY_COVER = "nilak_article_draft_cover";
    private static final String KEY_COVER_LOCAL = "nilak_article_draft_cover_local";
    private static final String KEY_MEDIA = "nilak_article_draft_media";
    private static final int REQUEST_IMAGE = 4741;
    private static final int REQUEST_VIDEO = 4742;
    private static final int REQUEST_COVER = 4743;
    private static final int MAX_TITLE = 160;
    private static final int MAX_SUBTITLE = 280;
    private static final long MAX_ARTICLE_BYTES = 150L * 1024 * 1024;

    private long chatId;
    private EditTextBoldCursor titleInput;
    private EditTextBoldCursor subtitleInput;
    private EditTextBoldCursor bodyInput;
    private ImageView coverView;
    private TextView coverHint;
    private TextView publishButton;
    private TextView countView;
    private boolean publishing;
    private boolean editing;
    private boolean destroyed;
    /** Cover: its link (or pending token, or local path when kept inside) and the local copy. */
    private String coverRef;
    private String coverLocal;
    /** media reference (link or token) → local copy inside the draft folder, for pictures in the editor */
    private final HashMap<String, String> localByRef = new HashMap<>();

    public TigiArticleActivity() {
        super();
    }

    public TigiArticleActivity(Bundle args) {
        super(args);
    }

    /** From Create: write an article for the given channel (articles are for channels only). */
    public static void start(BaseFragment fragment, TLRPC.Chat chat) {
        if (fragment == null || chat == null) {
            return;
        }
        if (!TigiArticle.canWrite(fragment.getCurrentAccount(), chat)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(
                org.telegram.messenger.BuildVars.TIGI_PAID_FEATURES ? R.string.NilakArticlesPremium : R.string.NilakArticlesChannelsOnly)).show();
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        fragment.presentFragment(new TigiArticleActivity(args));
    }

    static File draftDir() {
        final File dir = new File(ApplicationLoader.getFilesDirFixed(), "nilak_article_draft");
        new File(dir, TigiArticle.MEDIA_DIR).mkdirs();
        return dir;
    }

    @Override
    public boolean onFragmentCreate() {
        chatId = arguments != null ? arguments.getLong("chat_id", 0) : 0;
        if (chatId == 0) {
            final TLRPC.Chat target = TigiPosting.defaultChannel(currentAccount);
            chatId = target != null ? target.id : 0;
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(currentAccount);
    }

    private int editorWidth() {
        return Math.max(dp(200), AndroidUtilities.displaySize.x - dp(36));
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setOnClickListener(v -> finishFragment());
        bar.addView(close, LayoutHelper.createLinear(48, 56));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakNewArticle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bar.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));
        final TextView preview = new TextView(context);
        preview.setText(LocaleController.getString(R.string.NilakArticlePreview));
        preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        preview.setTypeface(TigiStyle.displayBold());
        preview.setTextColor(TigiStyle.accent());
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(12), 0, dp(12), 0);
        preview.setOnClickListener(v -> openPreview());
        bar.addView(preview, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40));
        publishButton = new TextView(context);
        publishButton.setText(LocaleController.getString(R.string.NilakPublish));
        publishButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        publishButton.setTypeface(TigiStyle.displayBold());
        publishButton.setTextColor(0xFFFFFFFF);
        publishButton.setGravity(Gravity.CENTER);
        publishButton.setPadding(dp(16), 0, dp(16), 0);
        publishButton.setBackground(TigiStyle.primaryPill(18));
        publishButton.setOnClickListener(v -> confirmPublish());
        bar.addView(publishButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 12, 0));
        column.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), dp(4), dp(18), dp(24));

        final FrameLayout cover = new FrameLayout(context);
        cover.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.multAlpha(TigiStyle.accent(), 0.10f)));
        coverView = new ImageView(context);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setClipToOutline(true);
        coverView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        cover.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        coverHint = new TextView(context);
        coverHint.setText(LocaleController.getString(R.string.NilakAddCover));
        coverHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        coverHint.setTypeface(TigiStyle.displayBold());
        coverHint.setTextColor(TigiStyle.accent());
        coverHint.setGravity(Gravity.CENTER);
        cover.addView(coverHint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        cover.setContentDescription(LocaleController.getString(R.string.NilakAddCover));
        cover.setOnClickListener(v -> pick("image/*", REQUEST_COVER));
        cover.setOnLongClickListener(v -> {
            if (coverRef != null) {
                coverRef = null;
                coverLocal = null;
                saveDraft();
                bindCover();
            }
            return true;
        });
        fields.addView(cover, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160, 0, 0, 0, 12));

        titleInput = field(context, R.string.NilakArticleTitleHint, 26, true, MAX_TITLE);
        titleInput.setText(prefs().getString(KEY_TITLE, ""));
        fields.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subtitleInput = field(context, R.string.NilakArticleSubtitleHint, 17, false, MAX_SUBTITLE);
        subtitleInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitleInput.setText(prefs().getString(KEY_SUBTITLE, ""));
        fields.addView(subtitleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 6));

        bodyInput = field(context, R.string.NilakArticleBodyHint, 17, false, 0);
        bodyInput.setLineSpacing(dp(4), 1f);
        bodyInput.setMinLines(10);
        bodyInput.setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        fields.addView(bodyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        countView = new TextView(context);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        countView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        fields.addView(countView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        final TextView note = new TextView(context);
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        note.setText(chat != null ? LocaleController.formatString(R.string.NilakArticleNote, chat.title) : LocaleController.getString(R.string.TigiPostNoRights));
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        fields.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        scroll.addView(fields);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        final View line = new View(context);
        line.setBackgroundColor(Theme.getColor(Theme.key_divider));
        column.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        column.addView(toolbar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        loadDraft();
        titleInput.addTextChangedListener(new SimpleWatcher(this::updateState));
        bodyInput.addTextChangedListener(new TextWatcher() {
            private int newlineAt = -1;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                newlineAt = !editing && count == 1 && before == 0 && start < s.length() && s.charAt(start) == '\n' ? start : -1;
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (editing) {
                    return;
                }
                editing = true;
                try {
                    // Enter on an empty list item or quote ends the list, like every editor
                    if (newlineAt > 0) {
                        final int lineStart = TigiArticleEditor.paragraphStart(s, newlineAt);
                        final TigiArticleEditor.Block block = TigiArticleEditor.blockAt(s, lineStart);
                        if ((block instanceof TigiArticleEditor.Bullet || block instanceof TigiArticleEditor.Numbered || block instanceof TigiArticleEditor.Quote)
                            && TigiArticleEditor.isEmptyLine(s, lineStart, newlineAt)) {
                            s.delete(newlineAt, newlineAt + 1);
                            TigiArticleEditor.removeBlocks(s, lineStart);
                        } else if (block instanceof TigiArticleEditor.Media || block instanceof TigiArticleEditor.Divider) {
                            // a new line after a photo starts plain text
                            final int next = newlineAt + 1;
                            for (TigiArticleEditor.Block b : s.getSpans(next, next, TigiArticleEditor.Block.class)) {
                                if (s.getSpanStart(b) >= next) {
                                    s.removeSpan(b);
                                }
                            }
                        }
                    }
                    TigiArticleEditor.normalize(s);
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    editing = false;
                }
                updateState();
            }
        });
        bindCover();
        updateState();
        resolvePendingUploads();
        fragmentView = root;
        return root;
    }

    private static class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            onChange.run();
        }
    }

    private EditTextBoldCursor field(Context context, int hint, int sizeDp, boolean bold, int maxLength) {
        final EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setHint(LocaleController.getString(hint));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        if (bold) {
            input.setTypeface(TigiStyle.displayExtraBold());
        }
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setCursorColor(TigiStyle.accent());
        input.setBackground(null);
        input.setPadding(0, dp(6), 0, dp(6));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (maxLength > 0) {
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
        }
        return input;
    }

    /* draft */

    private void loadDraft() {
        localByRef.clear();
        try {
            final JSONObject map = new JSONObject(prefs().getString(KEY_MEDIA, "{}"));
            final Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                localByRef.put(key, map.getString(key));
            }
        } catch (Exception ignore) {
        }
        coverRef = prefs().getString(KEY_COVER, null);
        coverLocal = prefs().getString(KEY_COVER_LOCAL, null);
        editing = true;
        bodyInput.setText(TigiArticleEditor.fromMarkdown(prefs().getString(KEY_BODY, ""), draftDir(), localByRef, editorWidth()));
        editing = false;
    }

    @Override
    public void onPause() {
        super.onPause();
        saveDraft();
    }

    private void saveDraft() {
        if (titleInput == null || publishing) {
            return;
        }
        final JSONObject map = new JSONObject();
        try {
            for (TigiArticleEditor.Media media : TigiArticleEditor.media(bodyInput.getText())) {
                if (media.ref != null && media.localPath != null) {
                    map.put(media.ref, media.localPath);
                }
            }
        } catch (Exception ignore) {
        }
        final SharedPreferences.Editor editor = prefs().edit()
            .putString(KEY_TITLE, titleInput.getText().toString())
            .putString(KEY_SUBTITLE, subtitleInput.getText().toString())
            .putString(KEY_BODY, TigiArticleEditor.toMarkdown(bodyInput.getText(), false))
            .putString(KEY_MEDIA, map.toString());
        if (coverRef != null) {
            editor.putString(KEY_COVER, coverRef).putString(KEY_COVER_LOCAL, coverLocal);
        } else {
            editor.remove(KEY_COVER).remove(KEY_COVER_LOCAL);
        }
        editor.apply();
    }

    private void clearDraft() {
        prefs().edit().remove(KEY_TITLE).remove(KEY_SUBTITLE).remove(KEY_BODY).remove(KEY_COVER).remove(KEY_COVER_LOCAL).remove(KEY_MEDIA).apply();
        TigiArticle.deleteRecursive(draftDir());
    }

    /** Uploads that finished while the composer was closed: found by their tokens and linked. */
    private void resolvePendingUploads() {
        for (TigiArticleEditor.Media media : TigiArticleEditor.media(bodyInput.getText())) {
            if (TigiArticleMedia.isPending(media.ref)) {
                final String token = media.ref;
                TigiArticleMedia.resolvePending(currentAccount, token, link -> {
                    if (link != null) {
                        setLink(token, link);
                    }
                });
            }
        }
        if (TigiArticleMedia.isPending(coverRef)) {
            final String token = coverRef;
            TigiArticleMedia.resolvePending(currentAccount, token, link -> {
                if (link != null && token.equals(coverRef)) {
                    coverRef = link;
                    saveDraft();
                }
            });
        }
    }

    private void setLink(String token, String link) {
        if (destroyed || bodyInput == null) {
            return;
        }
        for (TigiArticleEditor.Media media : TigiArticleEditor.media(bodyInput.getText())) {
            if (token.equals(media.ref)) {
                media.ref = link;
                // redraw without the upload mark
                final Editable text = bodyInput.getText();
                final int start = text.getSpanStart(media);
                final int end = text.getSpanEnd(media);
                if (start >= 0) {
                    final TigiArticleEditor.Media done = new TigiArticleEditor.Media(
                        TigiArticleEditor.mediaDrawable(draftDir(), media.localPath, media.video, editorWidth(), false), link, media.localPath, media.video, media.caption);
                    editing = true;
                    text.removeSpan(media);
                    text.setSpan(done, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    editing = false;
                }
            }
        }
        saveDraft();
        updateState();
    }

    private void bindCover() {
        if (coverLocal == null) {
            coverView.setImageDrawable(null);
            coverHint.setVisibility(View.VISIBLE);
            return;
        }
        coverHint.setVisibility(View.GONE);
        final File file = new File(draftDir(), coverLocal);
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = null;
            try {
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4;
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                if (coverView != null && result != null) {
                    coverView.setImageBitmap(result);
                }
            });
        });
    }

    private int pendingCount() {
        int count = TigiArticleMedia.isPending(coverRef) ? 1 : 0;
        for (TigiArticleEditor.Media media : TigiArticleEditor.media(bodyInput.getText())) {
            if (TigiArticleMedia.isPending(media.ref)) {
                count++;
            }
        }
        return count;
    }

    private void updateState() {
        if (bodyInput == null) {
            return;
        }
        final int words = TigiArticle.countWords(bodyInput.getText().toString().replace(String.valueOf(TigiArticleEditor.OBJECT), " "));
        final int uploading = pendingCount();
        final String count = LocaleController.formatString(R.string.NilakArticleCount, words, TigiArticle.minutesFor(words));
        countView.setText(uploading > 0 ? count + " · " + LocaleController.formatPluralString("NilakUploadingMedia", uploading) : count);
        final boolean ready = !publishing && titleInput.length() > 0 && TigiArticleEditor.hasText(bodyInput.getText()) && chatId != 0;
        publishButton.setEnabled(ready);
        publishButton.setAlpha(ready ? 1f : 0.5f);
    }

    /* toolbar */

    private View toolbar(Context context) {
        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        addTool(row, "H", R.string.NilakFormatHeading, this::toggleHeading);
        addTool(row, "B", R.string.NilakFormatBold, () -> inline(TigiArticleEditor.Bold.class));
        addTool(row, "I", R.string.NilakFormatItalic, () -> inline(TigiArticleEditor.Italic.class));
        addTool(row, "S", R.string.NilakFormatStrike, () -> inline(TigiArticleEditor.Strike.class));
        addTool(row, "≡", R.string.NilakFormatHighlight, () -> inline(TigiArticleEditor.Mark.class));
        addTool(row, "❝", R.string.NilakFormatQuote, () -> toggleBlock(TigiArticleEditor.Quote.class));
        addTool(row, "ⓘ", R.string.NilakFormatCallout, () -> toggleBlock(TigiArticleEditor.Callout.class));
        addTool(row, "•", R.string.NilakFormatList, () -> toggleBlock(TigiArticleEditor.Bullet.class));
        addTool(row, "1.", R.string.NilakFormatNumbered, () -> toggleBlock(TigiArticleEditor.Numbered.class));
        addTool(row, "{ }", R.string.NilakFormatCode, () -> toggleBlock(TigiArticleEditor.Code.class));
        addTool(row, "🔗", R.string.NilakFormatLink, this::insertLink);
        addTool(row, "🖼", R.string.NilakFormatImage, () -> pick("image/*", REQUEST_IMAGE));
        addTool(row, "▶", R.string.NilakFormatVideo, () -> pick("video/*", REQUEST_VIDEO));
        addTool(row, "—", R.string.NilakFormatDivider, this::insertDivider);
        scroll.addView(row);
        return scroll;
    }

    private void addTool(LinearLayout row, String label, int description, Runnable action) {
        final TextView button = new TextView(row.getContext());
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(TigiStyle.displayExtraBold());
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(LocaleController.getString(description));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), Theme.getColor(Theme.key_windowBackgroundGray), Theme.multAlpha(TigiStyle.accent(), 0.2f)));
        button.setOnClickListener(v -> {
            action.run();
            updateState();
        });
        row.addView(button, LayoutHelper.createLinear(44, 40, 0, 0, 8, 0));
    }

    /** The selection, or the word at the cursor when nothing is selected. */
    private int[] range() {
        final Editable text = bodyInput.getText();
        int start = Math.max(0, bodyInput.getSelectionStart());
        int end = Math.max(start, bodyInput.getSelectionEnd());
        if (start == end) {
            while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
                start--;
            }
            while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
                end++;
            }
        }
        return new int[]{start, end};
    }

    private void inline(Class<?> type) {
        final int[] r = range();
        if (r[0] >= r[1]) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.NilakSelectTextFirst)).show();
            return;
        }
        editing = true;
        TigiArticleEditor.toggleInline(bodyInput.getText(), r[0], r[1], type);
        editing = false;
        bodyInput.requestFocus();
    }

    private int currentParagraph() {
        return TigiArticleEditor.paragraphStart(bodyInput.getText(), Math.max(0, bodyInput.getSelectionStart()));
    }

    /** None → heading → subheading → none. */
    private void toggleHeading() {
        final Editable text = bodyInput.getText();
        final int start = currentParagraph();
        final TigiArticleEditor.Block block = TigiArticleEditor.blockAt(text, start);
        if (block instanceof TigiArticleEditor.Media || block instanceof TigiArticleEditor.Divider) {
            return;
        }
        editing = true;
        if (block instanceof TigiArticleEditor.Heading) {
            TigiArticleEditor.setBlock(text, start, ((TigiArticleEditor.Heading) block).level <= 2 ? new TigiArticleEditor.Heading(3) : null);
        } else {
            TigiArticleEditor.setBlock(text, start, new TigiArticleEditor.Heading(2));
        }
        editing = false;
        bodyInput.requestFocus();
    }

    private void toggleBlock(Class<?> type) {
        final Editable text = bodyInput.getText();
        final int start = currentParagraph();
        final TigiArticleEditor.Block block = TigiArticleEditor.blockAt(text, start);
        if (block instanceof TigiArticleEditor.Media || block instanceof TigiArticleEditor.Divider) {
            return;
        }
        editing = true;
        if (block != null && block.getClass() == type) {
            TigiArticleEditor.setBlock(text, start, null);
        } else {
            final TigiArticleEditor.Block created = type == TigiArticleEditor.Quote.class ? new TigiArticleEditor.Quote()
                : type == TigiArticleEditor.Callout.class ? new TigiArticleEditor.Callout("NOTE")
                : type == TigiArticleEditor.Bullet.class ? new TigiArticleEditor.Bullet()
                : type == TigiArticleEditor.Numbered.class ? new TigiArticleEditor.Numbered()
                : new TigiArticleEditor.Code();
            TigiArticleEditor.setBlock(text, start, created);
        }
        TigiArticleEditor.renumber(text);
        editing = false;
        bodyInput.invalidate();
        bodyInput.requestFocus();
    }

    /** Puts an object (photo, video, divider) on its own line at the cursor. */
    private void insertObject(TigiArticleEditor.Block span) {
        final Editable text = bodyInput.getText();
        int at = Math.max(0, bodyInput.getSelectionEnd());
        editing = true;
        if (at > 0 && text.charAt(at - 1) != '\n') {
            text.insert(at, "\n");
            at++;
        }
        text.insert(at, String.valueOf(TigiArticleEditor.OBJECT));
        text.setSpan(span, at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.insert(at + 1, "\n");
        editing = false;
        bodyInput.setSelection(Math.min(text.length(), at + 2));
        bodyInput.requestFocus();
        saveDraft();
    }

    private void insertDivider() {
        insertObject(new TigiArticleEditor.Divider(TigiArticleEditor.dividerDrawable(editorWidth())));
    }

    private void insertLink() {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final int[] selection = {Math.max(0, bodyInput.getSelectionStart()), Math.max(0, bodyInput.getSelectionEnd())};
        final EditTextBoldCursor url = new EditTextBoldCursor(context);
        url.setHint("https://");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        url.setCursorColor(TigiStyle.accent());
        url.setPadding(dp(24), dp(8), dp(24), dp(8));
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.NilakFormatLink));
        builder.setView(url);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            String value = url.getText().toString().trim();
            if (value.isEmpty()) {
                return;
            }
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "https://" + value;
            }
            final Editable text = bodyInput.getText();
            final int start = Math.min(selection[0], selection[1]);
            final int end = Math.max(selection[0], selection[1]);
            editing = true;
            if (start == end) {
                // no selected words: the link on its own line, shown to readers as a link card
                int at = start;
                if (at > 0 && text.charAt(at - 1) != '\n') {
                    text.insert(at, "\n");
                    at++;
                }
                text.insert(at, value + "\n");
                text.setSpan(new TigiArticleEditor.Link(value), at, at + value.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                text.setSpan(new TigiArticleEditor.Link(value), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            editing = false;
            updateState();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /* media */

    private void pick(String type, int request) {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        try {
            startActivityForResult(intent, request);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiPostErrorGeneric)).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode != REQUEST_IMAGE && requestCode != REQUEST_VIDEO && requestCode != REQUEST_COVER) {
            return;
        }
        final Uri uri = data.getData();
        final boolean video = requestCode == REQUEST_VIDEO;
        Utilities.globalQueue.postRunnable(() -> {
            final String path = copyIntoDraft(uri, video, requestCode == REQUEST_COVER);
            final android.graphics.drawable.Drawable picture = path != null && requestCode != REQUEST_COVER
                ? TigiArticleEditor.mediaDrawable(draftDir(), path, video, editorWidth(), true) : null;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || bodyInput == null) {
                    return;
                }
                if (path == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiPostErrorGeneric)).show();
                    return;
                }
                final String token = TigiArticleMedia.newToken();
                if (requestCode == REQUEST_COVER) {
                    coverLocal = path;
                    coverRef = token;
                    bindCover();
                } else {
                    insertObject(new TigiArticleEditor.Media(picture, token, path, video, null));
                }
                saveDraft();
                updateState();
                upload(token, path, video, requestCode == REQUEST_COVER);
            });
        });
    }

    /** Sends the file to the media channel; without one, the article keeps the file inside (its local path). */
    private void upload(String token, String path, boolean video, boolean cover) {
        TigiArticleMedia.ensureStore(currentAccount, store -> {
            if (destroyed) {
                return;
            }
            if (store == null) {
                applyLink(token, path, cover);
                return;
            }
            TigiArticleMedia.upload(currentAccount, store, new File(draftDir(), path).getAbsolutePath(), video, token,
                link -> AndroidUtilities.runOnUIThread(() -> applyLink(token, link != null ? link : path, cover)));
        });
    }

    private void applyLink(String token, String link, boolean cover) {
        if (cover) {
            if (token.equals(coverRef)) {
                coverRef = link;
                saveDraft();
                updateState();
            }
        } else {
            setLink(token, link);
        }
    }

    private String copyIntoDraft(Uri uri, boolean video, boolean cover) {
        try {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(ApplicationLoader.applicationContext.getContentResolver().getType(uri));
            if (TextUtils.isEmpty(extension)) {
                extension = video ? "mp4" : "jpg";
            }
            extension = extension.toLowerCase(Locale.ROOT);
            final String name = (cover ? "cover_" : video ? "video_" : "photo_") + System.currentTimeMillis() + "." + extension;
            final File target = new File(new File(draftDir(), TigiArticle.MEDIA_DIR), name);
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    return null;
                }
                final byte[] chunk = new byte[16384];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                }
            }
            return TigiArticle.MEDIA_DIR + "/" + name;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /* preview and publish */

    /** manifest.json and article.md in the draft folder; localMedia writes local paths (for the preview). */
    private TigiArticle.Meta writeDraftFiles(boolean localMedia) throws Exception {
        final File dir = draftDir();
        final String body = TigiArticleEditor.toMarkdown(bodyInput.getText(), localMedia);
        final TigiArticle.Meta meta = new TigiArticle.Meta();
        meta.title = titleInput.getText().toString().trim();
        meta.subtitle = subtitleInput.getText().toString().trim();
        final TLRPC.User me = getUserConfig().getCurrentUser();
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        meta.author = chat != null ? chat.title : me != null ? UserObject.getUserName(me) : "";
        meta.lang = TigiRegion.language();
        meta.cover = localMedia ? coverLocal : (TigiArticleMedia.isPending(coverRef) ? coverLocal : coverRef);
        meta.words = TigiArticle.countWords(TigiArticle.plainText(body));
        meta.minutes = TigiArticle.minutesFor(meta.words);
        meta.created = System.currentTimeMillis() / 1000;
        TigiArticle.writeMeta(dir, meta);
        TigiArticle.writeText(new File(dir, TigiArticle.BODY), body);
        return meta;
    }

    private void openPreview() {
        saveDraft();
        try {
            writeDraftFiles(true);
            TigiArticleReaderActivity.preview(this, draftDir());
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiPostErrorGeneric)).show();
        }
    }

    private void confirmPublish() {
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        if (chat == null || !TigiArticle.canWrite(currentAccount, chat)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakArticlesChannelsOnly)).show();
            return;
        }
        final int uploading = pendingCount();
        if (uploading > 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.formatPluralString("NilakWaitUploads", uploading)).show();
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NilakPublish));
        builder.setMessage(LocaleController.formatString(R.string.NilakArticleConfirm, chat.title));
        builder.setPositiveButton(LocaleController.getString(R.string.NilakPublish), (d, w) -> publish(chat));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void publish(TLRPC.Chat chat) {
        if (publishing) {
            return;
        }
        saveDraft();
        publishing = true;
        updateState();
        AndroidUtilities.hideKeyboard(bodyInput);
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);
        final TigiArticle.Meta meta;
        final String plainLead;
        try {
            meta = writeDraftFiles(false);
            plainLead = TigiArticle.plainText(TigiArticleEditor.toMarkdown(bodyInput.getText(), false));
        } catch (Exception e) {
            FileLog.e(e);
            progress.dismiss();
            publishing = false;
            updateState();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File packed;
            try {
                final File outDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_publish");
                outDir.mkdirs();
                packed = new File(outDir, TigiArticle.slug(meta.title) + TigiArticle.EXT);
                // only media kept inside (no media channel) are packed; linked media stay links
                TigiArticle.pack(draftDir(), packed);
            } catch (Exception e) {
                FileLog.e(e);
                packed = null;
            }
            final File file = packed;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                publishing = false;
                if (isFinishing() || getParentActivity() == null) {
                    return;
                }
                if (file == null || !file.exists()) {
                    updateState();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakArticleFailed)).show();
                    return;
                }
                if (file.length() > MAX_ARTICLE_BYTES) {
                    updateState();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.NilakArticleTooBig, AndroidUtilities.formatFileSize(MAX_ARTICLE_BYTES))).show();
                    return;
                }
                String lead = meta.subtitle;
                if (TextUtils.isEmpty(lead)) {
                    lead = plainLead.length() > 180 ? plainLead.substring(0, 180).trim() + "…" : plainLead;
                }
                final String caption = meta.title + (TextUtils.isEmpty(lead) ? "" : "\n" + lead);
                SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(currentAccount), file.getAbsolutePath(), file.getAbsolutePath(), null,
                    caption, TigiArticle.MIME, -chat.id, null, null, null, null, null, true, 0, null, null, false);
                TigiPosting.setLastChannel(currentAccount, chat.id);
                clearDraft();
                editing = true;
                titleInput.setText("");
                subtitleInput.setText("");
                bodyInput.setText("");
                editing = false;
                final MainTabsActivity tabs = TigiNavigation.findMainTabs(this);
                if (tabs != null) {
                    tabs.openHomeTab();
                    BulletinFactory.of(tabs).createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.NilakArticlePublished)).show();
                }
                finishFragment();
            });
        });
    }
}
