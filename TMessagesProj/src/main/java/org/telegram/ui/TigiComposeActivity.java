package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Nilak composer: the new post screen. Photos and videos come from Telegram's own gallery (with its editor), the
 * caption is written here, and Share posts straight into the user's profile channel; the channel's chat is never
 * shown. Text posts are written here too.
 */
public class TigiComposeActivity extends BaseFragment {

    public static final int MODE_POST = 1;
    public static final int MODE_REEL = 2;
    public static final int MODE_TEXT = 3;

    private static final int MAX_CAPTION = 1024;
    private static final int MAX_TEXT = 4096;

    private int mode;
    private long chatId;
    private ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
    private EditTextBoldCursor input;
    private TextView shareButton;
    private TextView counter;
    private boolean sent;

    private static final int REQUEST_PICK = 4731;

    /**
     * Starts a post, reel or text post into chat. For media the composer opens Android's photo picker (no storage
     * permission needed) and shows what was picked; nothing is posted before Share.
     */
    public static void start(BaseFragment fragment, TLRPC.Chat chat, int mode) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null) {
            return;
        }
        fragment.presentFragment(create(chat, mode, null));
    }

    /** A text post with text already in it (a repost comment above a link); the cursor goes to the start. */
    public static void startText(BaseFragment fragment, TLRPC.Chat chat, String text) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null) {
            return;
        }
        final TigiComposeActivity activity = create(chat, MODE_TEXT, null);
        activity.arguments.putString("initial_text", text);
        fragment.presentFragment(activity);
    }

    private static TigiComposeActivity create(TLRPC.Chat chat, int mode, ArrayList<SendMessagesHelper.SendingMediaInfo> media) {
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        args.putInt("mode", mode);
        final TigiComposeActivity activity = new TigiComposeActivity(args);
        if (media != null) {
            activity.media = new ArrayList<>(media);
        }
        return activity;
    }

    public TigiComposeActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        mode = arguments.getInt("mode", MODE_POST);
        chatId = arguments.getLong("chat_id");
        return getMessagesController().getChat(chatId) != null && super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, AndroidUtilities.statusBarHeight, 0, AndroidUtilities.navigationBarHeight);
        ViewCompat.setOnApplyWindowInsetsListener(column, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        // bar: close, title, Share
        final FrameLayout bar = new FrameLayout(context);
        final ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setOnClickListener(v -> {
            if (!sent && (!media.isEmpty() || input != null && input.length() > 0)) {
                confirmDiscard();
            } else {
                finishFragment();
            }
        });
        bar.addView(close, LayoutHelper.createFrame(48, 48, Gravity.CENTER_VERTICAL | Gravity.LEFT, 6, 0, 0, 0));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(mode == MODE_REEL ? R.string.NilakNewReel : mode == MODE_TEXT ? R.string.TigiCreateText : R.string.NilakNewPost));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bar.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        shareButton = new TextView(context);
        shareButton.setText(LocaleController.getString(R.string.NilakShare));
        shareButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        shareButton.setTypeface(TigiStyle.displayBold());
        shareButton.setTextColor(0xFFFFFFFF);
        shareButton.setGravity(Gravity.CENTER);
        shareButton.setPadding(dp(18), 0, dp(18), 0);
        shareButton.setBackground(TigiStyle.primaryPill(18));
        shareButton.setOnClickListener(v -> share());
        bar.addView(shareButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 12, 0));
        column.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // who posts: the user's photo and name, the way it will look on the post
        final LinearLayout who = new LinearLayout(context);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.setPadding(dp(16), dp(8), dp(16), dp(8));
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(18));
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        final boolean own = TigiPosting.ownChannel(currentAccount) != null && TigiPosting.ownChannel(currentAccount).id == chatId;
        final TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (own && self != null) {
            avatarDrawable.setInfo(currentAccount, self);
            avatar.setForUserOrChat(self, avatarDrawable);
        } else {
            avatarDrawable.setInfo(currentAccount, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
        }
        who.addView(avatar, LayoutHelper.createLinear(36, 36));
        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(own && self != null ? org.telegram.messenger.UserObject.getUserName(self) : chat.title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        names.addView(name);
        final TextView where = new TextView(context);
        where.setText(LocaleController.getString(own ? R.string.NilakPostingToProfile : R.string.NilakPostingToChannel));
        where.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        where.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        names.addView(where);
        who.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        body.addView(who, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (mode != MODE_TEXT) {
            final HorizontalScrollView strip = new HorizontalScrollView(context);
            strip.setHorizontalScrollBarEnabled(false);
            tiles = new LinearLayout(context);
            tiles.setOrientation(LinearLayout.HORIZONTAL);
            tiles.setPadding(dp(12), dp(4), dp(12), dp(4));
            strip.addView(tiles);
            body.addView(strip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            rebuildTiles();
        }

        input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, mode == MODE_TEXT ? 20 : 16);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setHint(LocaleController.getString(mode == MODE_TEXT ? R.string.NilakTextHint : R.string.NilakCaptionHint));
        input.setCursorColor(TigiStyle.accent());
        input.setCursorWidth(1.5f);
        input.setBackground(null);
        input.setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        input.setMinHeight(dp(mode == MODE_TEXT ? 220 : 96));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(mode == MODE_TEXT ? MAX_TEXT : MAX_CAPTION)});
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateShare();
            }
        });
        body.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final String initial = arguments.getString("initial_text");
        if (!TextUtils.isEmpty(initial) && input.length() == 0) {
            input.setText(initial);
            input.setSelection(0);
        }

        counter = new TextView(context);
        counter.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        counter.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        counter.setGravity(Gravity.RIGHT);
        counter.setPadding(dp(16), 0, dp(16), dp(8));
        body.addView(counter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView tip = new TextView(context);
        tip.setText(LocaleController.getString(R.string.NilakCaptionTip));
        tip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        tip.setPadding(dp(16), dp(4), dp(16), dp(16));
        body.addView(tip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        updateShare();
        if (mode == MODE_TEXT) {
            AndroidUtilities.runOnUIThread(() -> {
                input.requestFocus();
                AndroidUtilities.showKeyboard(input);
            }, 250);
        }
        return fragmentView;
    }

    private LinearLayout tiles;
    private boolean pickerShown;

    private void rebuildTiles() {
        if (tiles == null || getParentActivity() == null) {
            return;
        }
        tiles.removeAllViews();
        final Context context = getParentActivity();
        final boolean single = media.size() == 1;
        for (int i = 0; i < media.size(); i++) {
            tiles.addView(mediaTile(context, media.get(i), i, single), single
                ? LayoutHelper.createLinear((int) (AndroidUtilities.displaySize.x / AndroidUtilities.density) - 24, 340)
                : LayoutHelper.createLinear(150, 190, 4, 0, 4, 0));
        }
        if (mode == MODE_POST && media.size() < 10 || media.isEmpty()) {
            // add more (or pick again)
            final TextView add = new TextView(context);
            add.setText(media.isEmpty() ? LocaleController.getString(R.string.NilakPickMedia) : "+");
            add.setTextSize(TypedValue.COMPLEX_UNIT_DIP, media.isEmpty() ? 15 : 28);
            add.setTypeface(TigiStyle.displayBold());
            add.setTextColor(TigiStyle.accent());
            add.setGravity(Gravity.CENTER);
            add.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.multAlpha(TigiStyle.accent(), 0.08f)));
            add.setContentDescription(LocaleController.getString(R.string.NilakAddMedia));
            add.setOnClickListener(v -> openPicker());
            tiles.addView(add, media.isEmpty()
                ? LayoutHelper.createLinear((int) (AndroidUtilities.displaySize.x / AndroidUtilities.density) - 24, 200)
                : LayoutHelper.createLinear(96, 190, 4, 0, 4, 0));
        }
        updateShare();
    }

    private void openPicker() {
        final android.content.Intent intent;
        final int max = mode == MODE_REEL ? 1 : 10 - media.size();
        if (Build.VERSION.SDK_INT >= 33) {
            intent = new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            if (mode == MODE_REEL) {
                intent.setType("video/*");
            }
            if (max > 1) {
                intent.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(max, android.provider.MediaStore.getPickImagesMaxLimit()));
            }
        } else {
            intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            if (mode == MODE_REEL) {
                intent.setType("video/*");
            } else {
                intent.setType("*/*");
                intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                intent.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, max > 1);
            }
        }
        try {
            startActivityForResult(intent, REQUEST_PICK);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiPostErrorGeneric)).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mode != MODE_TEXT && media.isEmpty() && !pickerShown) {
            pickerShown = true;
            AndroidUtilities.runOnUIThread(this::openPicker, 150);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK) {
            return;
        }
        final ArrayList<android.net.Uri> uris = new ArrayList<>();
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (uris.isEmpty()) {
            if (media.isEmpty() && input != null && input.length() == 0) {
                // nothing picked on the first open: leave without a trace
                finishFragment();
            }
            return;
        }
        final android.content.ContentResolver resolver = getParentActivity() != null ? getParentActivity().getContentResolver() : null;
        for (int i = 0; i < uris.size() && media.size() < 10; i++) {
            final android.net.Uri uri = uris.get(i);
            final String type = resolver != null ? resolver.getType(uri) : null;
            final boolean video = type != null && type.startsWith("video/");
            if (mode == MODE_REEL && !video) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakReelNeedsVideo)).show();
                continue;
            }
            final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.uri = uri;
            info.isVideo = video;
            media.add(info);
        }
        rebuildTiles();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!sent && (!media.isEmpty() || input != null && input.length() > 0)) {
            if (invoked) {
                confirmDiscard();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void confirmDiscard() {
        if (getParentActivity() == null) {
            return;
        }
        new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(LocaleController.getString(R.string.NilakDiscardTitle))
            .setMessage(LocaleController.getString(R.string.NilakDiscardText))
            .setPositiveButton(LocaleController.getString(R.string.NilakDiscard), (d, w) -> {
                sent = true;
                finishFragment();
            })
            .setNegativeButton(LocaleController.getString(R.string.NilakKeepEditing), null)
            .makeRed(org.telegram.ui.ActionBar.AlertDialog.BUTTON_POSITIVE)
            .show();
    }

    private View mediaTile(Context context, SendMessagesHelper.SendingMediaInfo info, int index, boolean single) {
        final FrameLayout tile = new FrameLayout(context);
        final ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundGray)));
        image.setClipToOutline(true);
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        loadPreview(image, info, single ? 900 : 400);
        tile.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final ImageView remove = new ImageView(context);
        remove.setScaleType(ImageView.ScaleType.CENTER);
        remove.setImageResource(R.drawable.ic_close_white);
        remove.setBackground(Theme.createRoundRectDrawable(dp(14), 0x99000000));
        remove.setContentDescription(LocaleController.getString(R.string.Delete));
        remove.setOnClickListener(v -> {
            media.remove(info);
            rebuildTiles();
        });
        tile.addView(remove, LayoutHelper.createFrame(28, 28, Gravity.TOP | Gravity.LEFT, 8, 8, 0, 0));
        if (info.isVideo) {
            final ImageView play = new ImageView(context);
            play.setImageResource(R.drawable.tigi_play_24);
            play.setScaleType(ImageView.ScaleType.CENTER);
            play.setBackground(Theme.createRoundRectDrawable(dp(20), 0x66000000));
            tile.addView(play, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        }
        if (!single) {
            final TextView number = new TextView(context);
            number.setText(String.valueOf(index + 1));
            number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            number.setTypeface(TigiStyle.displayBold());
            number.setTextColor(0xFFFFFFFF);
            number.setGravity(Gravity.CENTER);
            number.setBackground(Theme.createRoundRectDrawable(dp(11), TigiStyle.PRIMARY));
            tile.addView(number, LayoutHelper.createFrame(22, 22, Gravity.TOP | Gravity.RIGHT, 0, 8, 8, 0));
        }
        tile.setContentDescription(LocaleController.getString(info.isVideo ? R.string.AttachVideo : R.string.AttachPhoto) + " " + (index + 1));
        return tile;
    }

    /** A preview bitmap off the main thread (photos and video frames, from the picker's content Uri). */
    private void loadPreview(ImageView image, SendMessagesHelper.SendingMediaInfo info, int size) {
        final Activity activity = getParentActivity();
        if (activity == null || info.uri == null) {
            return;
        }
        final android.content.ContentResolver resolver = activity.getContentResolver();
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            android.graphics.Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    bitmap = resolver.loadThumbnail(info.uri, new android.util.Size(size, size), null);
                } else if (info.isVideo) {
                    final android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                    retriever.setDataSource(activity, info.uri);
                    bitmap = retriever.getFrameAtTime(0);
                    retriever.release();
                } else {
                    final android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                    options.inSampleSize = 4;
                    try (java.io.InputStream stream = resolver.openInputStream(info.uri)) {
                        bitmap = android.graphics.BitmapFactory.decodeStream(stream, null, options);
                    }
                }
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
            }
            final android.graphics.Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    image.setImageBitmap(result);
                }
            });
        });
    }

    private void updateShare() {
        final boolean ready = mode == MODE_TEXT ? input != null && input.getText().toString().trim().length() > 0 : !media.isEmpty();
        if (shareButton != null) {
            shareButton.setEnabled(ready);
            shareButton.setAlpha(ready ? 1f : 0.5f);
        }
        if (counter != null) {
            final int max = mode == MODE_TEXT ? MAX_TEXT : MAX_CAPTION;
            final int length = input != null ? input.length() : 0;
            counter.setText(length > max * 0.8f ? (max - length) + "" : "");
        }
    }

    private void share() {
        if (sent) {
            return;
        }
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        if (!TigiPosting.canPostTo(chat)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiPostNoRights)).show();
            return;
        }
        final String text = input.getText().toString().trim();
        if (media.isEmpty() && text.isEmpty()) {
            return;
        }
        sent = true;
        AndroidUtilities.hideKeyboard(input);
        TigiPosting.setLastChannel(currentAccount, chatId);
        final ArrayList<TLRPC.MessageEntity> entities = text.isEmpty() ? null
            : MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);
        if (media.isEmpty()) {
            final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, -chatId, null, null, null, true, entities, null, null, true, 0, 0, null, false);
            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
        } else {
            // the caption goes on the first photo, like an album caption
            for (int i = 0; i < media.size(); i++) {
                media.get(i).caption = i == 0 && !text.isEmpty() ? text : null;
                media.get(i).entities = i == 0 ? entities : null;
            }
            SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(currentAccount), media, -chatId, null, null, null, null,
                false, media.size() > 1, null, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
        }
        // back to Home, where the new post shows at the top with its upload progress
        final MainTabsActivity tabs = TigiNavigation.findMainTabs(this);
        if (tabs != null) {
            tabs.openHomeTab();
            BulletinFactory.of(tabs).createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.NilakPosting)).show();
        }
        finishFragment();
    }
}
