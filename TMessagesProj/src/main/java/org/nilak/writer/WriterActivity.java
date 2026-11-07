package org.nilak.writer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.TigiLilac;
import org.telegram.ui.TigiStyle;

import java.util.ArrayList;

/**
 * Nilak Writer: a calm page for long posts. A title, then blocks (paragraph, heading, quote) with bold, italic and
 * links; Enter starts a new paragraph, Backspace at the start joins it to the one above. Saved as you type.
 */
public class WriterActivity extends BaseFragment {

    private static final long SAVE_DELAY_MS = 800;

    private WriterDoc doc;
    /** The published post being edited, when known (else it is looked up from the draft before publishing). */
    private MessageObject editing;

    private FrameLayout root;
    private ScrollView scroll;
    private EditText titleView;
    private LinearLayout column;
    private TextView savedView;
    private LinearLayout toolbar;
    private TextView headingButton;
    private TextView quoteButton;
    private TextView boldButton;
    private TextView italicButton;
    private LinearLayout bubble;
    private BlockEdit focused;
    private boolean binding;
    /** Telegram's own emoji, sticker and GIF keyboard, shown in place of the system keyboard. */
    private FrameLayout emojiPanel;
    private org.telegram.ui.Components.EmojiView emojiView;
    private TextView emojiButton;
    /** @mentions: people, channels and groups with a public username, suggested above the toolbar. */
    private android.widget.HorizontalScrollView mentionStrip;
    private LinearLayout mentionRow;
    private BlockEdit mentionTarget;
    private int mentionStart = -1;
    private String mentionQuery;
    private int mentionRequest;
    private Runnable mentionSearch;

    /** A new article. */
    public WriterActivity() {
        super();
    }

    /** A draft, or a published article (doc.editMessageId) to edit. */
    public WriterActivity(WriterDoc doc, MessageObject editing) {
        super();
        this.doc = doc;
        this.editing = editing;
    }

    private final Runnable saver = () -> {
        if (doc == null) {
            return;
        }
        collect();
        WriterDrafts.save(currentAccount, doc);
        if (savedView != null) {
            savedView.setText("●  " + LocaleController.getString(R.string.NilakWriterSaved));
        }
    };

    private boolean dark() {
        return Theme.isCurrentThemeDark();
    }

    private int page() {
        return dark() ? 0xFF141217 : 0xFFFFFFFF;
    }

    private int ink() {
        return dark() ? 0xFFECE7F3 : 0xFF1E1A24;
    }

    private int muted() {
        return dark() ? 0xFF6E657C : 0xFFB3A9C2;
    }

    @Override
    public boolean onFragmentCreate() {
        if (doc == null) {
            doc = new WriterDoc();
        }
        if (doc.blocks.isEmpty()) {
            doc.blocks.add(new WriterDoc.Block(WriterDoc.PARAGRAPH, ""));
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        root = new FrameLayout(context);
        root.setBackgroundColor(page());
        final LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        // top: close · Saved · Preview · Publish
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(12), 0);
        final TextView close = new TextView(context);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        close.setTextColor(ink());
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setOnClickListener(v -> finishFragment());
        bar.addView(close, LayoutHelper.createLinear(44, 44));
        savedView = new TextView(context);
        savedView.setText("●  " + LocaleController.getString(R.string.NilakWriterSaved));
        savedView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        savedView.setTextColor(0xFF8C819E);
        bar.addView(savedView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 2, 0, 8, 0));
        final TextView preview = pill(context, R.string.NilakWriterPreview, false);
        preview.setOnClickListener(v -> openPublish(true));
        bar.addView(preview, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
        final TextView publish = pill(context, R.string.NilakWriterPublish, true);
        publish.setOnClickListener(v -> openPublish(false));
        bar.addView(publish, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        page.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54));

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(120));
        titleView = new EditText(context);
        titleView.setBackground(null);
        titleView.setHint(LocaleController.getString(R.string.NilakWriterTitleHint));
        titleView.setHintTextColor(muted());
        titleView.setTextColor(ink());
        titleView.setTypeface(TigiStyle.displayExtraBold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        titleView.setLineSpacing(dp(2), 1f);
        titleView.setPadding(dp(22), dp(8), dp(22), dp(8));
        titleView.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        titleView.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        titleView.setText(doc.title);
        titleView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // the title is one line of thought: Enter moves on to the text
                final int newline = TextUtils.indexOf(s, '\n');
                if (newline >= 0) {
                    s.delete(newline, newline + 1);
                    if (column.getChildCount() > 0) {
                        focusBlock((BlockEdit) column.getChildAt(0), 0);
                    }
                }
                changed();
            }
        });
        titleView.setOnFocusChangeListener((v, has) -> {
            if (has) {
                focused = null;
                updateToolbar();
                hideBubble();
            }
        });
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        content.addView(column, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // tapping the empty space under the text continues at the end
        final View tail = new View(context);
        tail.setOnClickListener(v -> {
            if (column.getChildCount() > 0) {
                final BlockEdit last = (BlockEdit) column.getChildAt(column.getChildCount() - 1);
                focusBlock(last, last.length());
            }
        });
        content.addView(tail, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        mentionStrip = new android.widget.HorizontalScrollView(context);
        mentionStrip.setHorizontalScrollBarEnabled(false);
        mentionStrip.setVisibility(View.GONE);
        mentionStrip.setBackgroundColor(dark() ? 0xFF1B1820 : 0xFFFAF7FE);
        mentionRow = new LinearLayout(context);
        mentionRow.setOrientation(LinearLayout.HORIZONTAL);
        mentionRow.setGravity(Gravity.CENTER_VERTICAL);
        mentionRow.setPadding(dp(10), 0, dp(10), 0);
        mentionStrip.addView(mentionRow, new android.widget.FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        page.addView(mentionStrip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        toolbar = createToolbar(context);
        page.addView(toolbar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));
        emojiPanel = new FrameLayout(context);
        emojiPanel.setVisibility(View.GONE);
        final int panelHeight = Math.max(dp(240), org.telegram.messenger.MessagesController.getGlobalEmojiSettings().getInt("kbd_height", dp(260)));
        page.addView(emojiPanel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, panelHeight));
        root.addView(page, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        bubble = createBubble(context);
        bubble.setVisibility(View.GONE);
        root.addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.TOP | Gravity.LEFT));

        binding = true;
        for (WriterDoc.Block block : doc.blocks) {
            column.addView(newBlockView(context, block.type, block.text, block.path));
        }
        binding = false;
        fragmentView = root;
        updateToolbar();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        if (titleView != null && doc.isEmpty()) {
            titleView.requestFocus();
            AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(titleView), 250);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(saver);
        saver.run();
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(saver);
        if (emojiView != null) {
            emojiView.onDestroy();
        }
        super.onFragmentDestroy();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (emojiPanel != null && emojiPanel.getVisibility() == View.VISIBLE) {
            if (invoked) {
                hideEmojiPanel();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    /** Called by the publish screen once the article went out: the draft is done. */
    void published() {
        AndroidUtilities.cancelRunOnUIThread(saver);
        if (doc != null) {
            WriterDrafts.delete(currentAccount, doc.id);
        }
        doc = null;
        // the publish screen is on top and closes itself
        removeSelfFromStack();
    }

    private void changed() {
        if (binding || doc == null) {
            return;
        }
        if (column != null) {
            // list numbers follow the items above them
            for (int i = 0; i < column.getChildCount(); i++) {
                column.getChildAt(i).invalidate();
            }
        }
        if (savedView != null) {
            savedView.setText("○  " + LocaleController.getString(R.string.NilakWriterSaving));
        }
        AndroidUtilities.cancelRunOnUIThread(saver);
        AndroidUtilities.runOnUIThread(saver, SAVE_DELAY_MS);
    }

    /** The screen's state into the document. */
    private void collect() {
        if (doc == null || titleView == null) {
            return;
        }
        doc.title = titleView.getText().toString();
        doc.blocks.clear();
        for (int i = 0; i < column.getChildCount(); i++) {
            final BlockEdit edit = (BlockEdit) column.getChildAt(i);
            doc.blocks.add(new WriterDoc.Block(edit.type, edit.type == WriterDoc.DIVIDER ? "" : new SpannableStringBuilder(edit.getText()), edit.path));
        }
    }

    private void openPublish(boolean previewOnly) {
        collect();
        if (doc == null || doc.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.NilakWriterEmpty)).show();
            return;
        }
        WriterDrafts.save(currentAccount, doc);
        AndroidUtilities.hideKeyboard(root);
        if (previewOnly) {
            WriterPreview.open(this, doc);
        } else {
            presentFragment(new WriterPublishActivity(this, doc, editing));
        }
    }

    /* ---------- blocks ---------- */

    private BlockEdit newBlockView(Context context, int type, CharSequence text) {
        return newBlockView(context, type, text, null);
    }

    private BlockEdit newBlockView(Context context, int type, CharSequence text, String path) {
        final BlockEdit edit = new BlockEdit(context);
        edit.path = path;
        edit.setText(text);
        edit.setType(type);
        return edit;
    }

    /** Insert sheet: "Emoji" opens the Telegram keyboard on its emoji page. */
    private static final int EMOJI = 100;
    private static final int MENTION = 101;
    private static final int PICK_VIDEO = 7302;
    private static final long MAX_VIDEO_BYTES = 200L * 1024 * 1024;

    private void pickVideo() {
        final android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(android.content.Intent.createChooser(intent, LocaleController.getString(R.string.NilakWriterVideo)), PICK_VIDEO);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /** The picked video is copied into the writer's folder (it goes into the .nilak file when published). */
    private void onVideoPicked(android.net.Uri uri) {
        final int account = currentAccount;
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            String name = "video_" + System.currentTimeMillis();
            final android.content.ContentResolver resolver = org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver();
            String type = resolver.getType(uri);
            name += type != null && type.contains("webm") ? ".webm" : type != null && type.contains("3gp") ? ".3gp" : ".mp4";
            final java.io.File out = new java.io.File(WriterDrafts.mediaDir(account), name);
            int failure = 0;
            try (java.io.InputStream in = resolver.openInputStream(uri); java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                final byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while (in != null && (n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_VIDEO_BYTES) {
                        failure = R.string.NilakWriterVideoTooBig;
                        break;
                    }
                    os.write(buffer, 0, n);
                }
                if (total == 0 && failure == 0) {
                    failure = R.string.NilakWriterVideoFailed;
                }
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
                failure = R.string.NilakWriterVideoFailed;
            }
            if (failure != 0) {
                out.delete();
            }
            final int error = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (error != 0) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(error)).show();
                    return;
                }
                insertMedia(WriterDoc.VIDEO, out.getAbsolutePath());
            });
        });
    }

    /** A frame of the video with a play mark, as wide as the text, for the editor. */
    private android.graphics.drawable.Drawable videoDrawable(String path) {
        if (path == null) {
            return null;
        }
        try {
            final android.graphics.Bitmap frame = android.media.ThumbnailUtils.createVideoThumbnail(path, android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
            final int width = AndroidUtilities.displaySize.x - dp(44);
            final int height = frame != null ? Math.min(dp(360), Math.round(width * (frame.getHeight() / (float) Math.max(1, frame.getWidth())))) : dp(200);
            final android.graphics.Bitmap canvasBitmap = android.graphics.Bitmap.createBitmap(width, Math.max(dp(120), height), android.graphics.Bitmap.Config.ARGB_8888);
            final android.graphics.Canvas canvas = new android.graphics.Canvas(canvasBitmap);
            canvas.drawColor(0xFF1E1A24);
            if (frame != null) {
                canvas.drawBitmap(frame, null, new android.graphics.Rect(0, 0, canvasBitmap.getWidth(), canvasBitmap.getHeight()), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                frame.recycle();
            }
            final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0x99000000);
            final float cx = canvasBitmap.getWidth() / 2f;
            final float cy = canvasBitmap.getHeight() / 2f;
            canvas.drawCircle(cx, cy, dp(28), paint);
            paint.setColor(0xFFFFFFFF);
            final android.graphics.Path play = new android.graphics.Path();
            play.moveTo(cx - dp(8), cy - dp(12));
            play.lineTo(cx + dp(13), cy);
            play.lineTo(cx - dp(8), cy + dp(12));
            play.close();
            canvas.drawPath(play, paint);
            final androidx.core.graphics.drawable.RoundedBitmapDrawable drawable =
                androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(getParentActivity().getResources(), canvasBitmap);
            drawable.setCornerRadius(dp(12));
            drawable.setBounds(0, 0, canvasBitmap.getWidth(), canvasBitmap.getHeight());
            return drawable;
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    /* ---------- @mentions ---------- */

    /** Finds an "@name" being typed right before the cursor and offers matching people, channels and groups. */
    private void checkMention(BlockEdit edit) {
        if (edit == null || edit.type == WriterDoc.CODE || edit.type == WriterDoc.TABLE || edit.type == WriterDoc.EMBED
            || edit.type == WriterDoc.DIVIDER || edit.type == WriterDoc.STICKER) {
            hideMentions();
            return;
        }
        final int cursor = edit.getSelectionEnd();
        if (cursor < 0 || edit.getSelectionStart() != cursor) {
            hideMentions();
            return;
        }
        final CharSequence text = edit.getText();
        int i = cursor - 1;
        while (i >= 0 && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i--;
        }
        if (i < 0 || text.charAt(i) != '@' || (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) || cursor - i - 1 > 32) {
            hideMentions();
            return;
        }
        if (edit.getText().getSpans(i, cursor, URLSpan.class).length > 0) {
            hideMentions(); // already a mention
            return;
        }
        final String query = text.subSequence(i + 1, cursor).toString().toLowerCase(java.util.Locale.ROOT);
        mentionTarget = edit;
        mentionStart = i;
        if (query.equals(mentionQuery) && mentionStrip.getVisibility() == View.VISIBLE) {
            return;
        }
        mentionQuery = query;
        showMentions(localMentions(query), query);
        if (mentionSearch != null) {
            AndroidUtilities.cancelRunOnUIThread(mentionSearch);
        }
        if (query.length() >= 3) {
            // public people, channels and groups beyond your chats, found by Telegram's search
            mentionSearch = () -> searchMentions(query);
            AndroidUtilities.runOnUIThread(mentionSearch, 350);
        }
    }

    /** Your chats with a public username (so readers can open them), matching the name or the username. */
    private ArrayList<org.telegram.tgnet.TLObject> localMentions(String query) {
        final ArrayList<org.telegram.tgnet.TLObject> found = new ArrayList<>();
        final org.telegram.messenger.MessagesController controller = getMessagesController();
        final ArrayList<org.telegram.tgnet.TLRPC.Dialog> dialogs = controller.getAllDialogs();
        for (int d = 0; d < dialogs.size() && d < 400 && found.size() < 15; d++) {
            final long id = dialogs.get(d).id;
            org.telegram.tgnet.TLObject object = null;
            String username = null;
            String name = null;
            if (id > 0) {
                final org.telegram.tgnet.TLRPC.User user = controller.getUser(id);
                if (user != null && !user.deleted) {
                    object = user;
                    username = org.telegram.messenger.UserObject.getPublicUsername(user);
                    name = org.telegram.messenger.UserObject.getUserName(user);
                }
            } else {
                final org.telegram.tgnet.TLRPC.Chat chat = controller.getChat(-id);
                if (chat != null) {
                    object = chat;
                    username = org.telegram.messenger.ChatObject.getPublicUsername(chat);
                    name = chat.title;
                }
            }
            if (object == null || TextUtils.isEmpty(username)) {
                continue;
            }
            if (query.isEmpty() || username.toLowerCase(java.util.Locale.ROOT).startsWith(query)
                || (name != null && name.toLowerCase(java.util.Locale.ROOT).contains(query))) {
                found.add(object);
            }
        }
        return found;
    }

    private void searchMentions(String query) {
        final org.telegram.tgnet.TLRPC.TL_contacts_search request = new org.telegram.tgnet.TLRPC.TL_contacts_search();
        request.q = query;
        request.limit = 15;
        final int token = ++mentionRequest;
        getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (token != mentionRequest || !query.equals(mentionQuery) || !(response instanceof org.telegram.tgnet.TLRPC.TL_contacts_found)) {
                return;
            }
            final org.telegram.tgnet.TLRPC.TL_contacts_found result = (org.telegram.tgnet.TLRPC.TL_contacts_found) response;
            getMessagesController().putUsers(result.users, false);
            getMessagesController().putChats(result.chats, false);
            final ArrayList<org.telegram.tgnet.TLObject> list = localMentions(query);
            for (org.telegram.tgnet.TLRPC.User user : result.users) {
                if (!TextUtils.isEmpty(org.telegram.messenger.UserObject.getPublicUsername(user)) && !list.contains(user) && list.size() < 25) {
                    list.add(user);
                }
            }
            for (org.telegram.tgnet.TLRPC.Chat chat : result.chats) {
                if (!TextUtils.isEmpty(org.telegram.messenger.ChatObject.getPublicUsername(chat)) && !list.contains(chat) && list.size() < 25) {
                    list.add(chat);
                }
            }
            showMentions(list, query);
        }));
    }

    private void showMentions(ArrayList<org.telegram.tgnet.TLObject> list, String query) {
        if (mentionRow == null) {
            return;
        }
        mentionRow.removeAllViews();
        if (list.isEmpty()) {
            mentionStrip.setVisibility(View.GONE);
            return;
        }
        final Context context = getParentActivity();
        for (org.telegram.tgnet.TLObject object : list) {
            final boolean user = object instanceof org.telegram.tgnet.TLRPC.User;
            final String name = user ? org.telegram.messenger.UserObject.getUserName((org.telegram.tgnet.TLRPC.User) object) : ((org.telegram.tgnet.TLRPC.Chat) object).title;
            final String username = user ? org.telegram.messenger.UserObject.getPublicUsername((org.telegram.tgnet.TLRPC.User) object)
                : org.telegram.messenger.ChatObject.getPublicUsername((org.telegram.tgnet.TLRPC.Chat) object);
            final LinearLayout chip = new LinearLayout(context);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(4), 0, dp(14), 0);
            final GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(20));
            shape.setColor(dark() ? 0xFF2A2533 : 0xFFFFFFFF);
            shape.setStroke(dp(1), dark() ? 0xFF3A3346 : 0xFFE3D9F2);
            chip.setBackground(shape);
            final org.telegram.ui.Components.BackupImageView avatar = new org.telegram.ui.Components.BackupImageView(context);
            avatar.setRoundRadius(dp(16));
            final org.telegram.ui.Components.AvatarDrawable placeholder = new org.telegram.ui.Components.AvatarDrawable();
            placeholder.setInfo(currentAccount, object);
            avatar.setForUserOrChat(object, placeholder);
            chip.addView(avatar, LayoutHelper.createLinear(32, 32));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = new TextView(context);
            title.setText(org.telegram.messenger.Emoji.replaceEmoji(name, title.getPaint().getFontMetricsInt(), false));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            title.setTypeface(TigiStyle.displayBold());
            title.setTextColor(ink());
            title.setSingleLine(true);
            title.setMaxWidth(dp(160));
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            final TextView handle = new TextView(context);
            handle.setText("@" + username + (user ? "" : org.telegram.messenger.ChatObject.isChannelAndNotMegaGroup((org.telegram.tgnet.TLRPC.Chat) object)
                ? " · " + LocaleController.getString(R.string.NilakMentionChannel) : " · " + LocaleController.getString(R.string.NilakMentionGroup)));
            handle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            handle.setTextColor(TigiStyle.accent());
            handle.setSingleLine(true);
            texts.addView(handle);
            chip.addView(texts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
            chip.setContentDescription(name + ", @" + username);
            chip.setOnClickListener(v -> insertMention(username));
            mentionRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 42, 0, 0, 8, 0));
        }
        mentionStrip.setVisibility(View.VISIBLE);
        mentionStrip.scrollTo(0, 0);
    }

    /** "@que" becomes "@username " linked to the profile (t.me), so readers can open it. */
    private void insertMention(String username) {
        final BlockEdit edit = mentionTarget;
        if (edit == null || mentionStart < 0 || column == null || column.indexOfChild(edit) < 0) {
            hideMentions();
            return;
        }
        final int end = Math.max(mentionStart, edit.getSelectionEnd());
        if (mentionStart >= edit.length() || edit.getText().charAt(mentionStart) != '@') {
            hideMentions();
            return;
        }
        final String mention = "@" + username;
        final int start = mentionStart;
        final Editable text = edit.getText();
        final boolean wasBinding = binding;
        binding = true;
        text.replace(start, Math.min(end, text.length()), mention + " ");
        text.setSpan(new URLSpan("https://t.me/" + username), start, start + mention.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        edit.setSelection(Math.min(text.length(), start + mention.length() + 1));
        binding = wasBinding;
        hideMentions();
        changed();
    }

    private void hideMentions() {
        mentionTarget = null;
        mentionStart = -1;
        mentionQuery = null;
        mentionRequest++;
        if (mentionSearch != null) {
            AndroidUtilities.cancelRunOnUIThread(mentionSearch);
            mentionSearch = null;
        }
        if (mentionStrip != null && mentionStrip.getVisibility() != View.GONE) {
            mentionStrip.setVisibility(View.GONE);
            mentionRow.removeAllViews();
        }
    }

    /* ---------- Telegram keyboard: emoji, animated emoji, stickers, GIFs ---------- */

    private void createEmojiView() {
        if (emojiView != null || getParentActivity() == null) {
            return;
        }
        emojiView = new org.telegram.ui.Components.EmojiView(this, true, true, true, getParentActivity(), true, null, null, true, null, false);
        emojiView.allowEmojisForNonPremium(true);
        emojiView.setDelegate(new org.telegram.ui.Components.EmojiView.EmojiViewDelegate() {
            @Override
            public boolean onBackspace() {
                final BlockEdit target = textTarget();
                if (target == null || target.length() == 0) {
                    return false;
                }
                target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                return true;
            }

            @Override
            public void onEmojiSelected(String emoji) {
                final BlockEdit target = textTarget();
                if (target == null) {
                    return;
                }
                insertText(target, org.telegram.messenger.Emoji.replaceEmoji(emoji, target.getPaint().getFontMetricsInt(), false));
            }

            @Override
            public void onCustomEmojiSelected(long documentId, org.telegram.tgnet.TLRPC.Document document, String emoticon, boolean isRecent) {
                final BlockEdit target = textTarget();
                if (target == null) {
                    return;
                }
                final android.text.SpannableString emoji = new android.text.SpannableString(TextUtils.isEmpty(emoticon) ? "😀" : emoticon);
                final org.telegram.ui.Components.AnimatedEmojiSpan span = document != null
                    ? new org.telegram.ui.Components.AnimatedEmojiSpan(document, target.getPaint().getFontMetricsInt())
                    : new org.telegram.ui.Components.AnimatedEmojiSpan(documentId, target.getPaint().getFontMetricsInt());
                emoji.setSpan(span, 0, emoji.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                insertText(target, emoji);
            }

            @Override
            public void onStickerSelected(View view, org.telegram.tgnet.TLRPC.Document sticker, String query, Object parent, MessageObject.SendAnimationData sendAnimationData, boolean notify, int scheduleDate, int scheduleRepeatPeriod) {
                final String ref = WriterMedia.encode(sticker);
                if (ref != null) {
                    hideEmojiPanel();
                    insertMedia(WriterDoc.STICKER, ref);
                }
            }

            @Override
            public void onGifSelected(View view, Object gif, String query, Object parent, boolean notify, int scheduleDate, int scheduleRepeatPeriod) {
                org.telegram.tgnet.TLRPC.Document document = null;
                if (gif instanceof org.telegram.tgnet.TLRPC.Document) {
                    document = (org.telegram.tgnet.TLRPC.Document) gif;
                } else if (gif instanceof org.telegram.tgnet.TLRPC.BotInlineResult) {
                    document = ((org.telegram.tgnet.TLRPC.BotInlineResult) gif).document;
                }
                final String ref = WriterMedia.encode(document);
                if (ref == null) {
                    BulletinFactory.of(WriterActivity.this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakWriterGifFailed)).show();
                    return;
                }
                hideEmojiPanel();
                insertMedia(WriterDoc.GIF, ref);
            }

            @Override
            public void onClearEmojiRecent() {
                if (emojiView != null) {
                    emojiView.clearRecentEmoji();
                }
            }
        });
        emojiPanel.addView(emojiView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    /** Where typed emoji go: the block being written (not a divider or a sticker), else a new line at the end. */
    private BlockEdit textTarget() {
        if (focused != null && focused.type != WriterDoc.DIVIDER && focused.type != WriterDoc.STICKER) {
            return focused;
        }
        if (column == null) {
            return null;
        }
        final BlockEdit edit = newBlockView(getParentActivity(), WriterDoc.PARAGRAPH, "");
        column.addView(edit);
        focused = edit;
        edit.requestFocus();
        return edit;
    }

    private void insertText(BlockEdit target, CharSequence text) {
        int at = target.getSelectionEnd();
        if (at < 0) {
            at = target.length();
        }
        target.getText().replace(Math.min(at, target.getSelectionStart() < 0 ? at : target.getSelectionStart()), at, text);
        target.updateAnimatedEmoji(true);
        changed();
    }

    private void showEmojiPanel(boolean gifs) {
        createEmojiView();
        if (emojiView == null) {
            return;
        }
        hideBubble();
        if (focused != null) {
            AndroidUtilities.hideKeyboard(focused);
        } else if (root != null) {
            AndroidUtilities.hideKeyboard(root);
        }
        // the panel replaces the keyboard once it has gone
        AndroidUtilities.runOnUIThread(() -> {
            if (emojiPanel == null || emojiView == null) {
                return;
            }
            emojiPanel.setVisibility(View.VISIBLE);
            emojiView.setVisibility(View.VISIBLE);
            emojiView.onOpen(false, false);
            if (gifs) {
                emojiView.switchToGifRecent();
            }
            emojiView.setShowing(true);
            light(emojiButton, true);
            if (focused != null) {
                focused.requestFocus();
            }
        }, 120);
    }

    private void hideEmojiPanel() {
        if (emojiPanel != null && emojiPanel.getVisibility() == View.VISIBLE) {
            emojiPanel.setVisibility(View.GONE);
            if (emojiView != null) {
                emojiView.setShowing(false);
            }
            light(emojiButton, false);
        }
    }

    private void toggleEmojiPanel() {
        if (emojiPanel != null && emojiPanel.getVisibility() == View.VISIBLE) {
            hideEmojiPanel();
            final BlockEdit target = textTarget();
            if (target != null) {
                focusBlock(target, target.getSelectionEnd() >= 0 ? target.getSelectionEnd() : target.length());
            }
        } else {
            showEmojiPanel(false);
        }
    }

    /* ---------- photos ---------- */

    private static final int PICK_PHOTO = 7301;

    private void pickPhoto() {
        final android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(android.content.Intent.createChooser(intent, LocaleController.getString(R.string.NilakWriterPhoto)), PICK_PHOTO);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == PICK_VIDEO && resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            onVideoPicked(data.getData());
            return;
        }
        if (requestCode != PICK_PHOTO || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        final int account = currentAccount;
        // resized off the main thread: at most 1600 px on the long side, JPEG, so articles stay light to download
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final java.io.File out = new java.io.File(WriterDrafts.mediaDir(account), "photo_" + System.currentTimeMillis() + ".jpg");
            boolean ok = false;
            try {
                final android.content.ContentResolver resolver = org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver();
                final android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (java.io.InputStream in = resolver.openInputStream(uri)) {
                    android.graphics.BitmapFactory.decodeStream(in, null, bounds);
                }
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) {
                    sample *= 2;
                }
                final android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inSampleSize = sample;
                android.graphics.Bitmap bitmap;
                try (java.io.InputStream in = resolver.openInputStream(uri)) {
                    bitmap = android.graphics.BitmapFactory.decodeStream(in, null, options);
                }
                if (bitmap != null) {
                    final float scale = 1600f / Math.max(bitmap.getWidth(), bitmap.getHeight());
                    if (scale < 1f) {
                        final android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bitmap,
                            Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
                        if (scaled != bitmap) {
                            bitmap.recycle();
                            bitmap = scaled;
                        }
                    }
                    try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                        ok = bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, os);
                    }
                    bitmap.recycle();
                }
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
            }
            final boolean saved = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (!saved || column == null || getParentActivity() == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakWriterPhotoFailed)).show();
                    return;
                }
                insertPhoto(out.getAbsolutePath());
            });
        });
    }

    private void insertPhoto(String path) {
        insertMedia(WriterDoc.PHOTO, path);
    }

    private void insertMedia(int type, String path) {
        if (column == null || getParentActivity() == null) {
            return;
        }
        int index = focused != null ? column.indexOfChild(focused) + 1 : column.getChildCount();
        if (focused != null && focused.length() == 0 && focused.type == WriterDoc.PARAGRAPH) {
            // an empty line is replaced by the photo
            index = column.indexOfChild(focused);
            column.removeView(focused);
            focused = null;
        }
        final BlockEdit photo = newBlockView(getParentActivity(), type, "", path);
        column.addView(photo, index);
        // writing goes on under the photo; the caption is a tap away
        if (index + 1 > column.getChildCount()) {
            index = column.getChildCount() - 1;
        }
        BlockEdit after = index + 1 < column.getChildCount() ? (BlockEdit) column.getChildAt(index + 1) : null;
        if (after == null || after.type != WriterDoc.PARAGRAPH || after.length() > 0) {
            after = newBlockView(getParentActivity(), WriterDoc.PARAGRAPH, "");
            column.addView(after, index + 1);
        }
        focusBlock(after, 0);
        changed();
    }

    private void confirmRemovePhoto(BlockEdit photo) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(photo.type == WriterDoc.PHOTO ? R.string.NilakWriterRemovePhoto : R.string.NilakWriterRemoveMedia));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            column.removeView(photo);
            changed();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /** The photo drawn above its caption, as wide as the text and at most about half a screen tall. */
    private android.graphics.drawable.Drawable photoDrawable(String path) {
        if (path == null) {
            return null;
        }
        try {
            final int width = AndroidUtilities.displaySize.x - dp(44);
            final android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0) {
                return null;
            }
            final android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = Math.max(1, bounds.outWidth / Math.max(1, width));
            final android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(path, options);
            if (bitmap == null) {
                return null;
            }
            final int height = Math.min(dp(420), Math.round(width * (bitmap.getHeight() / (float) bitmap.getWidth())));
            final androidx.core.graphics.drawable.RoundedBitmapDrawable drawable =
                androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(getParentActivity().getResources(), bitmap);
            drawable.setCornerRadius(dp(12));
            drawable.setBounds(0, 0, width, height);
            return drawable;
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    private void focusBlock(BlockEdit edit, int position) {
        hideEmojiPanel();
        edit.requestFocus();
        edit.setSelection(Math.max(0, Math.min(position, edit.length())));
        AndroidUtilities.showKeyboard(edit);
    }

    /** Enter in the middle of a block: the rest goes to a new paragraph below. */
    private void split(BlockEdit edit, int at) {
        if (WriterDoc.isList(edit.type) && edit.length() == 0) {
            // Enter on an empty list item ends the list
            edit.setType(WriterDoc.PARAGRAPH);
            updateToolbar();
            changed();
            return;
        }
        final Editable text = edit.getText();
        final CharSequence rest = new SpannableStringBuilder(text.subSequence(at, text.length()));
        binding = true;
        text.delete(at, text.length());
        binding = false;
        final BlockEdit next = newBlockView(edit.getContext(), WriterDoc.isList(edit.type) ? edit.type : WriterDoc.PARAGRAPH, rest);
        column.addView(next, column.indexOfChild(edit) + 1);
        focusBlock(next, 0);
        changed();
    }

    /** Backspace at the start: a heading or quote becomes a paragraph; a paragraph joins the block above. */
    private void joinUp(BlockEdit edit) {
        if (WriterDoc.isMedia(edit.type)) {
            // Backspace in an empty caption asks to remove the photo
            if (edit.length() == 0) {
                confirmRemovePhoto(edit);
            }
            return;
        }
        if (edit.type != WriterDoc.PARAGRAPH) {
            edit.setType(WriterDoc.PARAGRAPH);
            updateToolbar();
            changed();
            return;
        }
        final int index = column.indexOfChild(edit);
        if (index <= 0) {
            if (edit.length() == 0) {
                titleView.requestFocus();
                titleView.setSelection(titleView.length());
            }
            return;
        }
        final BlockEdit above = (BlockEdit) column.getChildAt(index - 1);
        if (above.type == WriterDoc.DIVIDER) {
            column.removeView(above);
            changed();
            return;
        }
        if (above.type == WriterDoc.TABLE) {
            // Backspace under a table: an empty line goes away, otherwise the table opens to edit
            if (edit.length() == 0) {
                column.removeView(edit);
                changed();
            } else {
                openTable(above);
            }
            return;
        }
        if (WriterDoc.isMedia(above.type) || WriterDoc.isMultiline(above.type) || above.type == WriterDoc.EMBED) {
            // text never joins a photo, code, a table or a link card: an empty line goes away, otherwise the cursor moves to the caption
            if (edit.length() == 0) {
                column.removeView(edit);
            }
            focusBlock(above, above.length());
            changed();
            return;
        }
        final int join = above.length();
        above.getText().append(edit.getText());
        column.removeView(edit);
        focusBlock(above, join);
        changed();
    }

    private void setFocusedType(int type) {
        if (focused == null) {
            if (column.getChildCount() == 0) {
                return;
            }
            focused = (BlockEdit) column.getChildAt(column.getChildCount() - 1);
        }
        if (WriterDoc.isMedia(focused.type) || focused.type == WriterDoc.DIVIDER) {
            return; // a caption stays a caption
        }
        focused.setType(focused.type == type ? WriterDoc.PARAGRAPH : type);
        updateToolbar();
        changed();
    }

    /** Insert sheet: a new heading or quote under the current block. */
    private void insertBlock(int type) {
        int index = focused != null ? column.indexOfChild(focused) + 1 : column.getChildCount();
        if (focused != null && focused.length() == 0 && type != WriterDoc.DIVIDER && type != WriterDoc.TABLE && focused.type != WriterDoc.DIVIDER && !WriterDoc.isMedia(focused.type)) {
            // an empty line simply becomes the new kind of block
            focused.setType(type);
            focusBlock(focused, 0);
            updateToolbar();
            changed();
            return;
        }
        final BlockEdit edit = newBlockView(getParentActivity(), type, type == WriterDoc.TABLE ? WriterTable.starter() : "");
        column.addView(edit, index++);
        if (type == WriterDoc.TABLE) {
            final BlockEdit after = newBlockView(getParentActivity(), WriterDoc.PARAGRAPH, "");
            column.addView(after, index);
            focused = after;
            changed();
            openTable(edit);
            return;
        }
        if (type == WriterDoc.DIVIDER) {
            // writing goes on under the divider
            final BlockEdit after = newBlockView(getParentActivity(), WriterDoc.PARAGRAPH, "");
            column.addView(after, index);
            focusBlock(after, 0);
        } else {
            focusBlock(edit, 0);
        }
        changed();
    }

    /** The number shown before a numbered item: its place among the numbered items right above it. */
    private int numberOf(BlockEdit edit) {
        int number = 1;
        for (int i = column.indexOfChild(edit) - 1; i >= 0; i--) {
            if (((BlockEdit) column.getChildAt(i)).type != WriterDoc.NUMBER) {
                break;
            }
            number++;
        }
        return number;
    }

    private void confirmRemoveDivider(BlockEdit divider) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NilakWriterRemoveDivider));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            column.removeView(divider);
            changed();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /* ---------- inline marks ---------- */

    private void toggleStyle(int style) {
        if (focused == null || focused.getSelectionEnd() <= focused.getSelectionStart()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.NilakWriterSelectFirst)).show();
            return;
        }
        final Editable text = focused.getText();
        final int start = focused.getSelectionStart();
        final int end = focused.getSelectionEnd();
        boolean covered = false;
        for (StyleSpan span : text.getSpans(start, end, StyleSpan.class)) {
            if (span.getStyle() == style && text.getSpanStart(span) <= start && text.getSpanEnd(span) >= end) {
                covered = true;
            }
        }
        // take the style off the selection (keeping it on the parts outside), or put it on
        for (StyleSpan span : text.getSpans(start, end, StyleSpan.class)) {
            if (span.getStyle() != style) {
                continue;
            }
            final int s = text.getSpanStart(span);
            final int e = text.getSpanEnd(span);
            text.removeSpan(span);
            if (s < start) {
                text.setSpan(new StyleSpan(style), s, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (e > end) {
                text.setSpan(new StyleSpan(style), end, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (!covered) {
            text.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        updateToolbar();
        changed();
    }

    private void editLink() {
        if (focused == null || focused.getSelectionEnd() <= focused.getSelectionStart()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.NilakWriterSelectFirst)).show();
            return;
        }
        final BlockEdit target = focused;
        final int start = target.getSelectionStart();
        final int end = target.getSelectionEnd();
        final URLSpan[] existing = target.getText().getSpans(start, end, URLSpan.class);
        final Context context = getParentActivity();
        final EditText input = new EditText(context);
        input.setHint(LocaleController.getString(R.string.NilakWriterLinkHint));
        input.setSingleLine(true);
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI);
        input.setText(existing.length > 0 ? existing[0].getURL() : "");
        final FrameLayout box = new FrameLayout(context);
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 22, 4, 22, 0));
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.NilakWriterLink));
        builder.setView(box);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            String url = input.getText().toString().trim();
            for (URLSpan span : target.getText().getSpans(start, end, URLSpan.class)) {
                target.getText().removeSpan(span);
            }
            if (!url.isEmpty()) {
                if (!url.contains("://") && !url.startsWith("tg:") && !url.startsWith("mailto:")) {
                    url = "https://" + url;
                }
                target.getText().setSpan(new URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            changed();
        });
        if (existing.length > 0) {
            builder.setNeutralButton(LocaleController.getString(R.string.NilakWriterRemoveLink), (d, w) -> {
                for (URLSpan span : target.getText().getSpans(start, end, URLSpan.class)) {
                    target.getText().removeSpan(span);
                }
                changed();
            });
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }

    private boolean hasStyle(int style) {
        if (focused == null) {
            return false;
        }
        final int start = focused.getSelectionStart();
        final int end = Math.max(start, focused.getSelectionEnd());
        for (StyleSpan span : focused.getText().getSpans(start, end, StyleSpan.class)) {
            if (span.getStyle() == style && focused.getText().getSpanStart(span) <= start && focused.getText().getSpanEnd(span) >= end) {
                return true;
            }
        }
        return false;
    }

    /* ---------- toolbar, bubble, insert sheet ---------- */

    private LinearLayout createToolbar(Context context) {
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), 0);
        final GradientDrawable line = new GradientDrawable();
        line.setColor(dark() ? 0xFF1B1820 : 0xFFFFFFFF);
        line.setStroke(1, dark() ? 0xFF2B2632 : 0xFFEFE9F6);
        bar.setBackground(line);
        headingButton = tool(context, "H", R.string.NilakWriterHeading, v -> setFocusedType(WriterDoc.HEADING));
        headingButton.setTypeface(TigiStyle.displayExtraBold());
        quoteButton = tool(context, "❝", R.string.NilakWriterQuote, v -> setFocusedType(WriterDoc.QUOTE));
        boldButton = tool(context, "B", R.string.NilakWriterBold, v -> toggleStyle(Typeface.BOLD));
        boldButton.setTypeface(Typeface.DEFAULT_BOLD);
        italicButton = tool(context, "I", R.string.NilakWriterItalic, v -> toggleStyle(Typeface.ITALIC));
        italicButton.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        final TextView link = tool(context, "🔗", R.string.NilakWriterLink, v -> editLink());
        emojiButton = tool(context, "☺", R.string.NilakWriterEmoji, v -> toggleEmojiPanel());
        final TextView add = tool(context, "＋", R.string.NilakWriterAdd, v -> openInsertSheet());
        add.setTextColor(TigiStyle.accent());
        for (TextView view : new TextView[]{headingButton, quoteButton, boldButton, italicButton, link, emojiButton, add}) {
            bar.addView(view, LayoutHelper.createLinear(0, 40, 1f, 2, 0, 2, 0));
        }
        return bar;
    }

    private TextView tool(Context context, String label, int description, View.OnClickListener onClick) {
        final TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        view.setTextColor(dark() ? 0xFFD6CEE3 : 0xFF3A3046);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(LocaleController.getString(description));
        view.setOnClickListener(onClick);
        return view;
    }

    private void updateToolbar() {
        if (toolbar == null) {
            return;
        }
        light(headingButton, focused != null && focused.type == WriterDoc.HEADING);
        light(quoteButton, focused != null && focused.type == WriterDoc.QUOTE);
        light(boldButton, hasStyle(Typeface.BOLD));
        light(italicButton, hasStyle(Typeface.ITALIC));
    }

    private void light(TextView view, boolean on) {
        view.setSelected(on);
        view.setBackground(on ? Theme.createRoundRectDrawable(dp(10), dark() ? 0xFF33284A : 0xFFEFE6FC) : null);
        view.setTextColor(on ? TigiStyle.accent() : (dark() ? 0xFFD6CEE3 : 0xFF3A3046));
    }

    private LinearLayout createBubble(Context context) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackground(Theme.createRoundRectDrawable(dp(12), 0xFF1E1A24));
        box.setElevation(dp(6));
        final TextView bold = bubbleItem(context, "B", v -> toggleStyle(Typeface.BOLD));
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        final TextView italic = bubbleItem(context, "I", v -> toggleStyle(Typeface.ITALIC));
        italic.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        final TextView link = bubbleItem(context, "🔗 " + LocaleController.getString(R.string.NilakWriterLink), v -> editLink());
        box.addView(bold, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        box.addView(italic, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        box.addView(link, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        return box;
    }

    private TextView bubbleItem(Context context, String label, View.OnClickListener onClick) {
        final TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        view.setOnClickListener(onClick);
        return view;
    }

    /** The bubble sits just under the selected words, inside the screen. */
    private void showBubble(BlockEdit edit) {
        final Layout layout = edit.getLayout();
        if (layout == null || bubble == null) {
            return;
        }
        final int end = edit.getSelectionEnd();
        final int line = layout.getLineForOffset(end);
        final int[] at = new int[2];
        edit.getLocationInWindow(at);
        final int[] rootAt = new int[2];
        root.getLocationInWindow(rootAt);
        final float x = at[0] - rootAt[0] + edit.getTotalPaddingLeft() + layout.getPrimaryHorizontal(edit.getSelectionStart());
        final float y = at[1] - rootAt[1] + edit.getTotalPaddingTop() + layout.getLineBottom(line) + dp(10);
        bubble.setVisibility(View.VISIBLE);
        bubble.post(() -> {
            final float maxX = root.getWidth() - bubble.getWidth() - dp(12);
            bubble.setTranslationX(Math.max(dp(12), Math.min(x - dp(12), maxX)));
            bubble.setTranslationY(Math.min(y, root.getHeight() - bubble.getHeight() - dp(64)));
        });
    }

    private void hideBubble() {
        if (bubble != null) {
            bubble.setVisibility(View.GONE);
        }
    }

    private void openInsertSheet() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        collect();
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(4), dp(12), dp(16));
        final TextView header = new TextView(context);
        header.setText(LocaleController.getString(R.string.NilakWriterAdd));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        header.setTypeface(TigiStyle.displayExtraBold());
        header.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        header.setPadding(dp(8), dp(8), dp(8), dp(12));
        column.addView(header);
        final BottomSheet[] sheet = new BottomSheet[1];
        // {label, block type to insert (-1 = coming soon)}
        final int[][] items = {
            {R.string.NilakWriterHeading, WriterDoc.HEADING}, {R.string.NilakWriterQuote, WriterDoc.QUOTE}, {R.string.NilakWriterList, WriterDoc.BULLET}, {R.string.NilakWriterNumbers, WriterDoc.NUMBER},
            {R.string.NilakWriterPhoto, WriterDoc.PHOTO}, {R.string.NilakWriterVideo, WriterDoc.VIDEO}, {R.string.NilakWriterStickers, WriterDoc.STICKER}, {R.string.NilakWriterGif, WriterDoc.GIF},
            {R.string.NilakWriterCode, WriterDoc.CODE}, {R.string.NilakWriterTable, WriterDoc.TABLE}, {R.string.NilakWriterEmbed, WriterDoc.EMBED}, {R.string.NilakWriterNote, WriterDoc.CALLOUT},
            {R.string.NilakWriterDivider, WriterDoc.DIVIDER}, {R.string.NilakWriterEmoji, EMOJI}, {R.string.NilakWriterMention, MENTION}
        };
        final String[] icons = {"H", "❝", "•", "1.", "🖼", "▶", "✨", "GIF", "{ }", "▦", "↗", "💡", "✣", "☺", "@"};
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
            }
            final boolean live = items[i][1] >= 0;
            final int blockType = items[i][1];
            row.addView(insertItem(context, icons[i], items[i][0], live, v -> {
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
                if (!live) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.NilakWriterSoonToast)).show();
                } else if (blockType == WriterDoc.PHOTO) {
                    pickPhoto();
                } else if (blockType == WriterDoc.VIDEO) {
                    pickVideo();
                } else if (blockType == WriterDoc.STICKER || blockType == EMOJI) {
                    showEmojiPanel(false);
                } else if (blockType == WriterDoc.GIF) {
                    showEmojiPanel(true);
                } else if (blockType == MENTION) {
                    final BlockEdit target = textTarget();
                    if (target != null) {
                        final int at = target.getSelectionEnd() >= 0 ? target.getSelectionEnd() : target.length();
                        final boolean space = at > 0 && !Character.isWhitespace(target.getText().charAt(at - 1));
                        focusBlock(target, at);
                        insertText(target, space ? " @" : "@");
                        checkMention(target);
                    }
                } else {
                    insertBlock(blockType);
                }
            }), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        while (row != null && row.getChildCount() < 4) {
            row.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
        }
        final TextView words = new TextView(context);
        words.setText(LocaleController.formatString(R.string.NilakWriterWords, doc.wordCount(), doc.minutes()));
        words.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        words.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        words.setGravity(Gravity.END);
        words.setPadding(dp(8), dp(6), dp(8), 0);
        column.addView(words, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setCustomView(column);
        sheet[0] = builder.create();
        showDialog(sheet[0]);
    }

    private View insertItem(Context context, String icon, int label, boolean live, View.OnClickListener onClick) {
        final LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        final FrameLayout tile = new FrameLayout(context);
        final TextView glyph = new TextView(context);
        glyph.setText(icon);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_DIP, icon.length() > 2 ? 15 : 21);
        glyph.setTypeface(TigiStyle.displayExtraBold());
        glyph.setTextColor(live ? TigiStyle.accent() : 0xFFB9B0C7);
        glyph.setBackground(Theme.createRoundRectDrawable(dp(16), live ? Theme.multAlpha(TigiStyle.accent(), 0.10f) : Theme.multAlpha(0xFF8C819E, 0.10f)));
        tile.addView(glyph, LayoutHelper.createFrame(52, 52, Gravity.CENTER));
        if (!live) {
            final TextView soon = new TextView(context);
            soon.setText(LocaleController.getString(R.string.NilakWriterSoon));
            soon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f);
            soon.setTypeface(TigiStyle.displayBold());
            soon.setTextColor(TigiStyle.accent());
            soon.setPadding(dp(6), dp(1), dp(6), dp(1));
            soon.setBackground(Theme.createRoundRectDrawable(dp(8), Theme.multAlpha(TigiStyle.accent(), 0.14f)));
            tile.addView(soon, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 0, 2, 0));
        }
        item.addView(tile, LayoutHelper.createLinear(64, 58));
        final TextView name = new TextView(context);
        name.setText(LocaleController.getString(label));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        name.setTextColor(live ? Theme.getColor(Theme.key_dialogTextBlack) : 0xFFA79EB6);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        item.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        item.setContentDescription(LocaleController.getString(label) + (live ? "" : ", " + LocaleController.getString(R.string.NilakWriterSoon)));
        item.setOnClickListener(onClick);
        return item;
    }

    private TextView pill(Context context, int text, boolean primary) {
        final TextView view = new TextView(context);
        view.setText(LocaleController.getString(text));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(TigiStyle.displayBold());
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(15), 0, dp(15), 0);
        view.setTextColor(primary ? 0xFFFFFFFF : (dark() ? 0xFFE2DAEE : 0xFF4A3A66));
        view.setBackground(primary ? TigiStyle.primaryPill(17)
            : Theme.createSimpleSelectorRoundRectDrawable(dp(17), dark() ? 0xFF2A2533 : 0xFFF2EDF9, Theme.multAlpha(TigiStyle.accent(), 0.2f)));
        return view;
    }

    /** The table editor: cells, rows and columns; the block shows the result as a grid. */
    private void openTable(BlockEdit edit) {
        hideEmojiPanel();
        hideMentions();
        AndroidUtilities.hideKeyboard(root);
        WriterTable.edit(this, edit.getText().toString(), text -> {
            if (column == null || column.indexOfChild(edit) < 0) {
                return;
            }
            final boolean wasBinding = binding;
            binding = true;
            edit.setText(text);
            binding = wasBinding;
            edit.requestLayout();
            edit.invalidate();
            changed();
        }, () -> {
            if (column != null && column.indexOfChild(edit) >= 0) {
                column.removeView(edit);
                changed();
            }
        });
    }

    /** Lines the table up in columns (the editor font is monospaced), so it reads as a grid while writing. */
    private void alignTable(BlockEdit edit) {
        final String text = edit.getText().toString();
        if (text.trim().isEmpty() || !text.contains("|")) {
            return;
        }
        final ArrayList<String[]> rows = new ArrayList<>();
        int columns = 0;
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
        final int[] widths = new int[columns];
        for (String[] cells : rows) {
            for (int c = 0; c < cells.length; c++) {
                widths[c] = Math.max(widths[c], cells[c].codePointCount(0, cells[c].length()));
            }
        }
        final StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                out.append('\n');
            }
            final String[] cells = rows.get(r);
            for (int c = 0; c < columns; c++) {
                final String cell = c < cells.length ? cells[c] : "";
                out.append(cell);
                if (c < columns - 1) {
                    for (int pad = cell.codePointCount(0, cell.length()); pad < widths[c]; pad++) {
                        out.append(' ');
                    }
                    out.append(" | ");
                }
            }
        }
        final String aligned = out.toString();
        if (!aligned.equals(text)) {
            final boolean wasBinding = binding;
            binding = true;
            edit.setText(aligned);
            binding = wasBinding;
            if (!wasBinding) {
                changed();
            }
        }
    }

    /* ---------- one block of text ---------- */

    private class BlockEdit extends org.telegram.ui.Components.EditTextEffects {
        int type = WriterDoc.PARAGRAPH;
        String path;
        /** STICKER / GIF: the Telegram sticker or GIF drawn above the caption. */
        private org.telegram.messenger.ImageReceiver media;
        private int mediaWidth;
        private int mediaHeight;
        private boolean attached;

        BlockEdit(Context context) {
            super(context);
            setBackground(null);
            setTextColor(ink());
            setHintTextColor(muted());
            setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
            setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
            setLinkTextColor(TigiStyle.accent());
            addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (binding) {
                        return;
                    }
                    final int newline = TextUtils.indexOf(s, '\n');
                    if (newline >= 0 && WriterDoc.isMultiline(type)) {
                        // code and tables keep their lines; Enter on an empty last line leaves the block
                        final int last = s.length() - 1;
                        if (s.charAt(last) == '\n' && last > 0 && s.charAt(last - 1) == '\n' && getSelectionEnd() == s.length()) {
                            binding = true;
                            s.delete(last - 1, last + 1);
                            binding = false;
                            split(BlockEdit.this, s.length());
                            return;
                        }
                        changed();
                        return;
                    }
                    if (newline >= 0) {
                        binding = true;
                        s.delete(newline, newline + 1);
                        binding = false;
                        split(BlockEdit.this, newline);
                        return;
                    }
                    changed();
                    checkMention(BlockEdit.this);
                }
            });
            setOnFocusChangeListener((v, has) -> {
                if (has) {
                    focused = BlockEdit.this;
                    updateToolbar();
                }
            });
            setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN
                    && getSelectionStart() == 0 && getSelectionEnd() == 0) {
                    joinUp(BlockEdit.this);
                    return true;
                }
                return false;
            });
        }

        void setType(int type) {
            this.type = type;
            final int index = column != null ? column.indexOfChild(this) : -1;
            setCompoundDrawables(null, null, null, null);
            setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
            setHorizontallyScrolling(false);
            setMedia(null);
            setInputType(type == WriterDoc.EMBED ? EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                : WriterDoc.isMultiline(type) ? EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                : EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
            switch (type) {
                case WriterDoc.CODE:
                    setTypeface(Typeface.MONOSPACE);
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
                    setLineSpacing(0, 1.3f);
                    setGravity(Gravity.TOP | Gravity.LEFT);
                    setPadding(dp(36), dp(40), dp(36), dp(18));
                    setBackground(inset(Theme.createRoundRectDrawable(dp(14), 0xFF1E1A26)));
                    setHint(LocaleController.getString(R.string.NilakWriterCodeHint));
                    break;
                case WriterDoc.TABLE:
                    // drawn as a grid; a tap opens the table editor (cells, rows, columns)
                    setHint(null);
                    setFocusable(false);
                    setFocusableInTouchMode(false);
                    setCursorVisible(false);
                    setLongClickable(false);
                    setBackground(null);
                    setPadding(dp(18), dp(10), dp(18), dp(10));
                    setContentDescription(LocaleController.getString(R.string.NilakWriterTable));
                    setOnClickListener(v -> openTable(BlockEdit.this));
                    break;
                case WriterDoc.CALLOUT:
                    setTypeface(Typeface.DEFAULT);
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f);
                    setLineSpacing(0, 1.45f);
                    setPadding(dp(40), dp(40), dp(36), dp(18));
                    setBackground(inset(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(TigiStyle.accent(), dark() ? 0.18f : 0.09f))));
                    setHint(LocaleController.getString(R.string.NilakWriterNoteHint));
                    break;
                case WriterDoc.EMBED:
                    setTypeface(TigiStyle.displayBold());
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    setLineSpacing(0, 1.2f);
                    setPadding(dp(80), dp(20), dp(36), dp(20));
                    final GradientDrawable card = new GradientDrawable();
                    card.setCornerRadius(dp(16));
                    card.setColor(dark() ? 0xFF1B1820 : 0xFFFFFFFF);
                    card.setStroke(dp(1), dark() ? 0xFF3A3346 : 0xFFE3D9F2);
                    setBackground(inset(card));
                    setHint(LocaleController.getString(R.string.NilakWriterEmbedHint));
                    break;
                case WriterDoc.VIDEO:
                    setTypeface(Typeface.DEFAULT);
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    setLineSpacing(0, 1.3f);
                    setGravity(Gravity.CENTER_HORIZONTAL);
                    setPadding(dp(22), dp(10), dp(22), dp(12));
                    setBackground(null);
                    setHint(LocaleController.getString(R.string.NilakWriterCaptionHint));
                    setCompoundDrawablePadding(dp(8));
                    setCompoundDrawables(null, videoDrawable(path), null, null);
                    break;
                case WriterDoc.STICKER:
                case WriterDoc.GIF: {
                    final org.telegram.tgnet.TLRPC.Document document = WriterMedia.decode(path);
                    final int width = AndroidUtilities.displaySize.x - dp(44);
                    if (type == WriterDoc.STICKER) {
                        mediaWidth = mediaHeight = dp(160);
                    } else {
                        mediaWidth = width;
                        mediaHeight = Math.max(dp(120), Math.min(dp(400), Math.round(width / WriterMedia.aspect(document))));
                    }
                    setTypeface(Typeface.DEFAULT);
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    setLineSpacing(0, 1.3f);
                    setGravity(Gravity.CENTER_HORIZONTAL);
                    setPadding(dp(22), dp(10) + mediaHeight + dp(8), dp(22), dp(12));
                    setBackground(null);
                    // a sticker needs no caption; a GIF can have one
                    setHint(type == WriterDoc.GIF ? LocaleController.getString(R.string.NilakWriterCaptionHint) : null);
                    setContentDescription(LocaleController.getString(type == WriterDoc.GIF ? R.string.AttachGif : R.string.AttachSticker));
                    setMedia(document);
                    break;
                }
                case WriterDoc.HEADING:
                    setTypeface(TigiStyle.displayExtraBold());
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
                    setLineSpacing(dp(1), 1f);
                    setPadding(dp(22), dp(14), dp(22), dp(4));
                    setBackground(null);
                    setHint(LocaleController.getString(R.string.NilakWriterHeading));
                    break;
                case WriterDoc.QUOTE: {
                    setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17.5f);
                    setLineSpacing(0, 1.45f);
                    setPadding(dp(40), dp(6), dp(22), dp(10));
                    // the lilac bar on the left of a quote
                    final GradientDrawable bar = new GradientDrawable();
                    bar.setColor(TigiStyle.accent());
                    final LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{bar});
                    if (LocaleController.isRTL) {
                        layers.setLayerInset(0, 0, dp(6), dp(22), dp(10));
                        layers.setLayerGravity(0, Gravity.RIGHT);
                    } else {
                        layers.setLayerInset(0, dp(22), dp(6), 0, dp(10));
                        layers.setLayerGravity(0, Gravity.LEFT);
                    }
                    layers.setLayerWidth(0, dp(3));
                    setBackground(layers);
                    setHint(LocaleController.getString(R.string.NilakWriterQuote));
                    break;
                }
                case WriterDoc.BULLET:
                case WriterDoc.NUMBER:
                    setTypeface(Typeface.DEFAULT);
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
                    setLineSpacing(0, 1.45f);
                    setPadding(LocaleController.isRTL ? dp(22) : dp(48), dp(2), LocaleController.isRTL ? dp(48) : dp(22), dp(4));
                    setBackground(null);
                    setHint(LocaleController.getString(R.string.NilakWriterListItem));
                    break;
                case WriterDoc.PHOTO:
                    // the photo above, its caption as the text (small, centred, optional)
                    setTypeface(Typeface.DEFAULT);
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    setLineSpacing(0, 1.3f);
                    setGravity(Gravity.CENTER_HORIZONTAL);
                    setPadding(dp(22), dp(10), dp(22), dp(12));
                    setBackground(null);
                    setHint(LocaleController.getString(R.string.NilakWriterCaptionHint));
                    setCompoundDrawablePadding(dp(8));
                    setCompoundDrawables(null, photoDrawable(path), null, null);
                    break;
                case WriterDoc.DIVIDER:
                    // a quiet break between parts: not text, removed with a tap or Backspace from below
                    setText("");
                    setHint(null);
                    setFocusable(false);
                    setFocusableInTouchMode(false);
                    setCursorVisible(false);
                    setLongClickable(false);
                    setMinHeight(dp(44));
                    setPadding(dp(22), dp(8), dp(22), dp(8));
                    setBackground(null);
                    setContentDescription(LocaleController.getString(R.string.NilakWriterDivider));
                    setOnClickListener(v -> confirmRemoveDivider(BlockEdit.this));
                    break;
                default:
                    setTypeface(Typeface.DEFAULT);
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
                    setLineSpacing(0, 1.5f);
                    setPadding(dp(22), dp(4), dp(22), dp(8));
                    setBackground(null);
                    setHint(column != null && (column.getChildCount() == 0 || index == 0) ? LocaleController.getString(R.string.NilakWriterStart) : null);
            }
            setTextColor(type == WriterDoc.QUOTE ? (dark() ? 0xFFCDBEEA : 0xFF4A3A66) : WriterDoc.isMedia(type) ? muted() | 0xFF000000
                : type == WriterDoc.EMBED ? TigiStyle.accent() : type == WriterDoc.CODE ? 0xFFE9E3F5 : ink());
            setHintTextColor(type == WriterDoc.CODE ? 0x80E9E3F5 : muted());
            requestLayout();
        }

        private final android.graphics.Paint markPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        /**
         * A box background (an inset drawable) reports its insets as padding, and Android would replace the text
         * padding with them: the text then runs under the labels and to the edge. Keep the padding we set.
         */
        @Override
        public void setBackground(android.graphics.drawable.Drawable background) {
            final int left = getPaddingLeft();
            final int top = getPaddingTop();
            final int right = getPaddingRight();
            final int bottom = getPaddingBottom();
            super.setBackground(background);
            setPadding(left, top, right, bottom);
        }

        /** Boxes (code, table, note, link) sit inside the page margins. */
        private android.graphics.drawable.Drawable inset(android.graphics.drawable.Drawable box) {
            return new android.graphics.drawable.InsetDrawable(box, dp(18), dp(6), dp(18), dp(6));
        }

        private WriterTable.Grid tableGrid;

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (type != WriterDoc.TABLE) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            final int width = View.MeasureSpec.getSize(widthMeasureSpec);
            if (tableGrid == null) {
                tableGrid = new WriterTable.Grid();
            }
            tableGrid.layout(getText().toString(), width - dp(44), ink());
            setMeasuredDimension(width, dp(10) + tableGrid.height + dp(28));
        }

        private void setMedia(org.telegram.tgnet.TLRPC.Document document) {
            if (document == null) {
                if (media != null) {
                    media.onDetachedFromWindow();
                    media = null;
                }
                return;
            }
            if (media == null) {
                media = new org.telegram.messenger.ImageReceiver(this);
                media.setRoundRadius(type == WriterDoc.GIF ? dp(12) : 0);
                if (attached) {
                    media.onAttachedToWindow();
                }
            }
            if (type == WriterDoc.STICKER) {
                media.setAspectFit(true);
            }
            WriterMedia.bind(media, document);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            if (media != null) {
                media.onAttachedToWindow();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            attached = false;
            if (media != null) {
                media.onDetachedFromWindow();
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN && emojiPanel != null && emojiPanel.getVisibility() == View.VISIBLE) {
                // touching the text brings the keyboard back in place of the emoji panel
                hideEmojiPanel();
                AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(this), 50);
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (type == WriterDoc.TABLE) {
                if (tableGrid != null) {
                    tableGrid.draw(canvas, dp(22), dp(10), getWidth() - dp(44), dark());
                    // a small hint under the table: tap to edit
                    markPaint.setTextAlign(android.graphics.Paint.Align.RIGHT);
                    markPaint.setTypeface(Typeface.DEFAULT);
                    markPaint.setTextSize(dp(11.5f));
                    markPaint.setColor(muted() | 0xFF000000);
                    canvas.drawText(LocaleController.getString(R.string.NilakTableTapToEdit), getWidth() - dp(24), dp(10) + tableGrid.height + dp(17), markPaint);
                }
                return;
            }
            super.onDraw(canvas);
            if (type == WriterDoc.CODE || type == WriterDoc.CALLOUT) {
                // the label at the top of the box
                markPaint.setTextAlign(android.graphics.Paint.Align.LEFT);
                markPaint.setTypeface(TigiStyle.displayBold());
                markPaint.setTextSize(dp(12.5f));
                markPaint.setColor(type == WriterDoc.CODE ? 0xFFB9A6E0 : TigiStyle.accent());
                final String label = type == WriterDoc.CODE ? "</>  " + LocaleController.getString(R.string.NilakWriterCode)
                    : "💡  " + LocaleController.getString(R.string.NilakWriterNote);
                canvas.drawText(label, type == WriterDoc.CODE ? dp(36) : dp(40), dp(29), markPaint);
                if (type == WriterDoc.CALLOUT) {
                    markPaint.setColor(TigiStyle.accent());
                    canvas.drawRoundRect(new android.graphics.RectF(dp(26), dp(18), dp(30), getHeight() - dp(18)), dp(2), dp(2), markPaint);
                }
            } else if (type == WriterDoc.EMBED) {
                // a round badge with an arrow, like the link card readers see
                markPaint.setColor(TigiStyle.accent());
                final float cy = getHeight() / 2f;
                canvas.drawCircle(dp(55), cy, dp(19), markPaint);
                markPaint.setColor(0xFFFFFFFF);
                markPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
                markPaint.setTypeface(TigiStyle.displayExtraBold());
                markPaint.setTextSize(dp(17));
                canvas.drawText("↗", dp(55), cy + dp(6), markPaint);
            }
            if (media != null) {
                final float left = (getWidth() - mediaWidth) / 2f;
                media.setImageCoords(left, dp(10) + getScrollY(), mediaWidth, mediaHeight);
                media.draw(canvas);
            }
            if (type == WriterDoc.DIVIDER) {
                markPaint.setColor(Theme.multAlpha(TigiStyle.accent(), 0.55f));
                markPaint.setTextSize(dp(15));
                markPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
                canvas.drawText("✣     ✣     ✣", getWidth() / 2f, getHeight() / 2f + dp(5), markPaint);
                return;
            }
            if (!WriterDoc.isList(type) || getLayout() == null) {
                return;
            }
            // the bullet or number sits on the first line, in the margin
            final float baseline = getTotalPaddingTop() + getLayout().getLineBaseline(0) - getScrollY();
            markPaint.setColor(type == WriterDoc.BULLET ? TigiStyle.accent() : getCurrentTextColor());
            markPaint.setTextSize(getTextSize());
            markPaint.setTypeface(type == WriterDoc.NUMBER ? TigiStyle.displayBold() : Typeface.DEFAULT);
            final String mark = type == WriterDoc.BULLET ? "•" : numberOf(this) + ".";
            if (LocaleController.isRTL) {
                markPaint.setTextAlign(android.graphics.Paint.Align.LEFT);
                canvas.drawText(mark, getWidth() - dp(44), baseline, markPaint);
            } else {
                markPaint.setTextAlign(android.graphics.Paint.Align.RIGHT);
                canvas.drawText(mark, dp(40), baseline, markPaint);
            }
        }

        @Override
        protected void onSelectionChanged(int selStart, int selEnd) {
            super.onSelectionChanged(selStart, selEnd);
            if (focused == this) {
                updateToolbar();
                if (mentionStrip != null && mentionStrip.getVisibility() == View.VISIBLE) {
                    checkMention(this);
                }
                if (selEnd > selStart) {
                    showBubble(this);
                } else {
                    hideBubble();
                }
            }
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            final InputConnection base = super.onCreateInputConnection(outAttrs);
            if (base == null) {
                return null;
            }
            // keyboards delete with deleteSurroundingText: at the very start that means "join with the block above"
            return new InputConnectionWrapper(base, true) {
                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    if (beforeLength > 0 && afterLength == 0 && getSelectionStart() == 0 && getSelectionEnd() == 0) {
                        joinUp(BlockEdit.this);
                        return true;
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength);
                }
            };
        }
    }
}
