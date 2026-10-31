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
import java.util.Locale;

/**
 * Nilak article composer. Cover photo, title, subtitle and a body in Nilak Markdown with a toolbar for headings,
 * bold, italic, highlight, quotes, callouts, lists, links, dividers, photos and videos. Photos and videos are
 * copied into the draft folder and referenced from the text, so the draft survives closing the app. Preview opens
 * the real reader on the draft; Publish packs a .nilak file (see {@link TigiArticle}) and posts it to the channel
 * as one document, its caption the title and subtitle.
 */
public class TigiArticleActivity extends BaseFragment {

    private static final String KEY_TITLE = "nilak_article_draft_title";
    private static final String KEY_SUBTITLE = "nilak_article_draft_subtitle";
    private static final String KEY_BODY = "nilak_article_draft_body";
    private static final String KEY_COVER = "nilak_article_draft_cover";
    private static final int REQUEST_IMAGE = 4741;
    private static final int REQUEST_VIDEO = 4742;
    private static final int REQUEST_COVER = 4743;
    private static final int MAX_TITLE = 160;
    private static final int MAX_SUBTITLE = 280;
    /** Telegram takes far bigger files; this keeps articles quick to open on mobile data. */
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
    private String coverPath;

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

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(currentAccount);
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        // bar: close · New article · Preview · Publish
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

        // cover
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
            if (coverPath != null) {
                coverPath = null;
                prefs().edit().remove(KEY_COVER).apply();
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
        bodyInput.setText(prefs().getString(KEY_BODY, ""));
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

        final TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateState();
            }
        };
        titleInput.addTextChangedListener(watcher);
        bodyInput.addTextChangedListener(watcher);
        coverPath = prefs().getString(KEY_COVER, null);
        if (coverPath != null && !new File(draftDir(), coverPath).exists()) {
            coverPath = null;
        }
        bindCover();
        updateState();
        fragmentView = root;
        return root;
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

    private void bindCover() {
        if (coverPath == null) {
            coverView.setImageDrawable(null);
            coverHint.setVisibility(View.VISIBLE);
            return;
        }
        coverHint.setVisibility(View.GONE);
        final File file = new File(draftDir(), coverPath);
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

    private void updateState() {
        final int words = TigiArticle.countWords(bodyInput.getText().toString());
        countView.setText(LocaleController.formatString(R.string.NilakArticleCount, words, TigiArticle.minutesFor(words)));
        final boolean ready = !publishing && titleInput.length() > 0 && bodyInput.getText().toString().trim().length() > 0 && chatId != 0;
        publishButton.setEnabled(ready);
        publishButton.setAlpha(ready ? 1f : 0.5f);
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
        prefs().edit()
            .putString(KEY_TITLE, titleInput.getText().toString())
            .putString(KEY_SUBTITLE, subtitleInput.getText().toString())
            .putString(KEY_BODY, bodyInput.getText().toString())
            .apply();
    }

    private void clearDraft() {
        prefs().edit().remove(KEY_TITLE).remove(KEY_SUBTITLE).remove(KEY_BODY).remove(KEY_COVER).apply();
        TigiArticle.deleteRecursive(draftDir());
    }

    /* toolbar */

    private View toolbar(Context context) {
        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        addTool(row, "H", R.string.NilakFormatHeading, () -> linePrefix("## "));
        addTool(row, "B", R.string.NilakFormatBold, () -> wrap("**"));
        addTool(row, "I", R.string.NilakFormatItalic, () -> wrap("_"));
        addTool(row, "≡", R.string.NilakFormatHighlight, () -> wrap("=="));
        addTool(row, "❝", R.string.NilakFormatQuote, () -> linePrefix("> "));
        addTool(row, "ⓘ", R.string.NilakFormatCallout, () -> linePrefix("> [!NOTE] "));
        addTool(row, "•", R.string.NilakFormatList, () -> linePrefix("- "));
        addTool(row, "1.", R.string.NilakFormatNumbered, () -> linePrefix("1. "));
        addTool(row, "🔗", R.string.NilakFormatLink, this::insertLink);
        addTool(row, "🖼", R.string.NilakFormatImage, () -> pick("image/*", REQUEST_IMAGE));
        addTool(row, "▶", R.string.NilakFormatVideo, () -> pick("video/*", REQUEST_VIDEO));
        addTool(row, "—", R.string.NilakFormatDivider, () -> insertBlock("---"));
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
        button.setOnClickListener(v -> action.run());
        row.addView(button, LayoutHelper.createLinear(44, 40, 0, 0, 8, 0));
    }

    private void wrap(String marker) {
        final Editable text = bodyInput.getText();
        final int start = Math.max(0, bodyInput.getSelectionStart());
        final int end = Math.max(start, bodyInput.getSelectionEnd());
        text.insert(end, marker);
        text.insert(start, marker);
        bodyInput.setSelection(start == end ? start + marker.length() : end + marker.length() * 2);
        bodyInput.requestFocus();
    }

    private void linePrefix(String prefix) {
        final Editable text = bodyInput.getText();
        final int start = Math.max(0, bodyInput.getSelectionStart());
        int lineStart = start;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        text.insert(lineStart, prefix);
        bodyInput.setSelection(Math.min(text.length(), start + prefix.length()));
        bodyInput.requestFocus();
    }

    /** Puts a block on its own line (empty lines around it) at the cursor. */
    private void insertBlock(String block) {
        final Editable text = bodyInput.getText();
        final int at = Math.max(0, bodyInput.getSelectionStart());
        final boolean needBefore = at > 0 && text.charAt(at - 1) != '\n';
        final String insert = (needBefore ? "\n\n" : (at > 0 ? "\n" : "")) + block + "\n\n";
        text.insert(at, insert);
        bodyInput.setSelection(Math.min(text.length(), at + insert.length()));
        bodyInput.requestFocus();
    }

    private void insertLink() {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
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
            final int start = Math.max(0, bodyInput.getSelectionStart());
            final int end = Math.max(start, bodyInput.getSelectionEnd());
            if (start == end) {
                // no selected words: a link card on its own line
                insertBlock(value);
            } else {
                text.insert(end, "](" + value + ")");
                text.insert(start, "[");
                bodyInput.setSelection(Math.min(text.length(), end + value.length() + 4));
            }
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
            AndroidUtilities.runOnUIThread(() -> {
                if (bodyInput == null) {
                    return;
                }
                if (path == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiPostErrorGeneric)).show();
                    return;
                }
                if (requestCode == REQUEST_COVER) {
                    coverPath = path;
                    prefs().edit().putString(KEY_COVER, path).apply();
                    bindCover();
                } else {
                    // the caption goes between the brackets; empty means none
                    insertBlock("![](" + path + ")");
                    saveDraft();
                }
            });
        });
    }

    /** Copies the picked file into the draft's media folder; returns its path relative to the draft. */
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

    /** Writes manifest.json and article.md into the draft folder from the fields. */
    private TigiArticle.Meta writeDraftFiles() throws Exception {
        final File dir = draftDir();
        final String body = bodyInput.getText().toString();
        final TigiArticle.Meta meta = new TigiArticle.Meta();
        meta.title = titleInput.getText().toString().trim();
        meta.subtitle = subtitleInput.getText().toString().trim();
        final TLRPC.User me = getUserConfig().getCurrentUser();
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        meta.author = chat != null ? chat.title : me != null ? UserObject.getUserName(me) : "";
        meta.lang = TigiRegion.language();
        meta.cover = coverPath;
        meta.words = TigiArticle.countWords(body);
        meta.minutes = TigiArticle.minutesFor(meta.words);
        meta.created = System.currentTimeMillis() / 1000;
        TigiArticle.writeMeta(dir, meta);
        TigiArticle.writeText(new File(dir, TigiArticle.BODY), body);
        return meta;
    }

    private void openPreview() {
        saveDraft();
        try {
            writeDraftFiles();
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
        try {
            meta = writeDraftFiles();
        } catch (Exception e) {
            FileLog.e(e);
            progress.dismiss();
            publishing = false;
            updateState();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File packed = null;
            try {
                final File outDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_publish");
                outDir.mkdirs();
                packed = new File(outDir, TigiArticle.slug(meta.title) + TigiArticle.EXT);
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
                // the caption: title, then the subtitle (or the start of the text) for feeds and search
                String lead = meta.subtitle;
                if (TextUtils.isEmpty(lead)) {
                    final String plain = TigiArticle.plainText(bodyInput.getText().toString());
                    lead = plain.length() > 180 ? plain.substring(0, 180).trim() + "…" : plain;
                }
                final String caption = meta.title + (TextUtils.isEmpty(lead) ? "" : "\n" + lead);
                SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(currentAccount), file.getAbsolutePath(), file.getAbsolutePath(), null,
                    caption, TigiArticle.MIME, -chat.id, null, null, null, null, null, true, 0, null, null, false);
                TigiPosting.setLastChannel(currentAccount, chat.id);
                clearDraft();
                titleInput.setText("");
                subtitleInput.setText("");
                bodyInput.setText("");
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
