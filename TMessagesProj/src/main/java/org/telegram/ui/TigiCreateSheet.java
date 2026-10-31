package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.util.ArrayList;

/**
 * Tigi "+": what the user can make (post, reel, story, live, text) and the channel it goes to.
 * Posting itself is Telegram's own composer in that channel (gallery, editor, caption, album order, schedule).
 */
public class TigiCreateSheet {

    private static final int ACTION_POST = 0;
    private static final int ACTION_REEL = 1;
    private static final int ACTION_STORY = 2;
    private static final int ACTION_LIVE = 3;
    private static final int ACTION_TEXT = 4;
    private static final int ACTION_OTHER_CHANNEL = 5;
    private static final int ACTION_ARTICLE = 6;

    /** Live uses Telegram's channel video chat / live stream, see TigiLive. */
    static boolean liveAvailable = true;

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        if (MessagesController.getInstance(account).isFrozen()) {
            AccountFrozenAlert.show(account);
            return;
        }
        // Post, Reel, Story and Live as large tiles, Text post as a wide row, and the channel posts go to at the bottom
        final ArrayList<Integer> tiles = new ArrayList<>();
        if (BuildVars.TIGI_POST) {
            tiles.add(ACTION_POST);
            tiles.add(ACTION_REEL);
        }
        if (TigiPosting.storiesOffered(account)) {
            tiles.add(ACTION_STORY);
        }
        if (BuildVars.TIGI_LIVE && liveAvailable) {
            tiles.add(ACTION_LIVE);
        }
        if (tiles.isEmpty() && !BuildVars.TIGI_POST) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final Theme.ResourcesProvider resources = fragment.getResourceProvider();
        final BottomSheet sheet = new BottomSheet(context, false, resources);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resources);
        final int grayColor = Theme.getColor(Theme.key_dialogTextGray3, resources);
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.TigiCreate));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(textColor);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        final TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.TigiCreateWhat));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(grayColor);
        content.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 2, 4, 14));

        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                content.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
            }
            final int action = tiles.get(i);
            row.addView(tile(context, resources, action, false, () -> {
                sheet.dismiss();
                run(fragment, action);
            }), LayoutHelper.createLinear(0, 108, 1f, i % 2 == 0 ? 0 : 5, 0, i % 2 == 0 ? 5 : 0, 0));
        }
        if (row != null && tiles.size() % 2 == 1) {
            // keep the last tile half width
            row.addView(new View(context), LayoutHelper.createLinear(0, 108, 1f, 5, 0, 0, 0));
        }
        if (BuildVars.TIGI_POST) {
            content.addView(tile(context, resources, ACTION_TEXT, true, () -> {
                sheet.dismiss();
                run(fragment, ACTION_TEXT);
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 0, 0, 0, 4));
            content.addView(tile(context, resources, ACTION_ARTICLE, true, () -> {
                sheet.dismiss();
                run(fragment, ACTION_ARTICLE);
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 0, 6, 0, 4));

            // "Posting to <channel> · Change" when the user runs other channels too
            final TLRPC.Chat target = TigiPosting.defaultChannel(account);
            final ArrayList<TLRPC.Chat> postable = TigiPosting.postableChannels(account);
            if (target != null && postable.size() > (postable.contains(target) ? 1 : 0)) {
                final TextView footer = new TextView(context);
                final String change = LocaleController.getString(R.string.TigiChange);
                final String text = LocaleController.formatString(R.string.TigiPostingTo, target.title) + " · " + change;
                final android.text.SpannableString span = new android.text.SpannableString(text);
                span.setSpan(new android.text.style.ForegroundColorSpan(TigiStyle.accent()), text.length() - change.length(), text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                footer.setText(span);
                footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                footer.setTextColor(grayColor);
                footer.setGravity(Gravity.CENTER);
                footer.setMinHeight(AndroidUtilities.dp(44));
                footer.setOnClickListener(v -> {
                    sheet.dismiss();
                    pickFrom(fragment, TigiPosting.postableChannels(account), chat -> compose(fragment, chat, ChatActivity.TIGI_COMPOSE_MEDIA));
                });
                content.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            }
        }
        sheet.setCustomView(content);
        fragment.showDialog(sheet);
    }

    private static void run(BaseFragment fragment, int action) {
        final int account = fragment.getCurrentAccount();
        switch (action) {
            case ACTION_POST:
                pickChannel(fragment, ChatActivity.TIGI_COMPOSE_MEDIA);
                break;
            case ACTION_REEL:
                if (BuildVars.TIGI_SILENT_POST) {
                    pickChannel(fragment, ChatActivity.TIGI_COMPOSE_MEDIA, chat -> TigiComposeActivity.start(fragment, chat, TigiComposeActivity.MODE_REEL));
                } else {
                    pickChannel(fragment, ChatActivity.TIGI_COMPOSE_MEDIA);
                }
                break;
            case ACTION_TEXT:
                pickChannel(fragment, ChatActivity.TIGI_COMPOSE_TEXT);
                break;
            case ACTION_ARTICLE:
                pickChannel(fragment, ChatActivity.TIGI_COMPOSE_TEXT, chat -> TigiArticleActivity.start(fragment, chat));
                break;
            case ACTION_OTHER_CHANNEL:
                pickFrom(fragment, TigiPosting.postableChannels(account), chat -> compose(fragment, chat, ChatActivity.TIGI_COMPOSE_MEDIA));
                break;
            case ACTION_STORY:
                TigiPosting.openStoryRecorder(fragment);
                break;
            case ACTION_LIVE:
                TigiLive.start(fragment);
                break;
        }
    }

    private static View tile(Context context, Theme.ResourcesProvider resources, int action, boolean wide, Runnable onClick) {
        final int icon;
        final int title;
        final int hint;
        switch (action) {
            case ACTION_REEL: icon = R.drawable.tigi_reels_24; title = R.string.TigiCreateReel; hint = R.string.TigiCreateReelHint; break;
            case ACTION_STORY: icon = R.drawable.tigi_clock_24; title = R.string.TigiCreateStory; hint = R.string.TigiCreateStoryHint; break;
            case ACTION_LIVE: icon = R.drawable.tigi_live_24; title = R.string.TigiCreateLive; hint = R.string.TigiCreateLiveHint; break;
            case ACTION_TEXT: icon = R.drawable.tigi_text_24; title = R.string.TigiCreateText; hint = R.string.TigiCreateTextHint; break;
            case ACTION_ARTICLE: icon = R.drawable.msg_edit; title = R.string.NilakCreateArticle; hint = R.string.NilakCreateArticleHint; break;
            default: icon = R.drawable.tigi_image_24; title = R.string.TigiCreatePost; hint = R.string.TigiCreatePostHint; break;
        }
        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resources);
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        box.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.LEFT | Gravity.CENTER_VERTICAL);
        box.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        box.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), Theme.multAlpha(textColor, 0.05f), Theme.multAlpha(textColor, 0.11f)));
        final ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
        box.addView(image, LayoutHelper.createLinear(26, 26));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(LocaleController.getString(title));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(textColor);
        texts.addView(name);
        final TextView sub = new TextView(context);
        sub.setText(LocaleController.getString(hint));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        sub.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resources));
        sub.setMaxLines(2);
        texts.addView(sub);
        if (wide) {
            box.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
        } else {
            box.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        }
        box.setContentDescription(LocaleController.getString(title) + ", " + LocaleController.getString(hint));
        box.setOnClickListener(v -> onClick.run());
        return box;
    }

    /* the channel */

    /** One channel: straight to it. Several: "Post as" with the last used first. None: offer a personal channel. */
    static void pickChannel(BaseFragment fragment, int composeMode) {
        pickChannel(fragment, composeMode, chat -> compose(fragment, chat, composeMode));
    }

    /**
     * One channel for everything, without asking: the user's Tigi channel or the last one used. On the very first
     * post a private personal channel is made quietly; only if that fails does the user see a choice or an error.
     */
    static void pickChannel(BaseFragment fragment, int composeMode, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onPicked) {
        final int account = fragment.getCurrentAccount();
        final TLRPC.Chat target = TigiPosting.defaultChannel(account);
        if (target != null) {
            onPicked.run(target);
            return;
        }
        createQuietly(fragment, onPicked);
    }

    private static void createQuietly(BaseFragment fragment, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onPicked) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        if (TigiPosting.isCreating()) {
            return; // a second tap while the channel is being made
        }
        final AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.showDelayed(300);
        TigiPosting.createPersonalChannel(account, TigiPosting.suggestedTitle(account), null, chat -> {
            progress.dismiss();
            if (fragment.getParentActivity() != null) {
                onPicked.run(chat);
            }
        }, message -> {
            progress.dismiss();
            if (fragment.getParentActivity() == null) {
                return;
            }
            final ArrayList<TLRPC.Chat> channels = TigiPosting.postableChannels(account);
            if (!channels.isEmpty()) {
                // could not make one (too many channels, limits): post in one the user already runs
                pickFrom(fragment, channels, onPicked);
            } else {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, message).show();
            }
        });
    }

    /** The "Post as" picker over the given channels (also used to choose where to go live). */
    static void pickFrom(BaseFragment fragment, ArrayList<TLRPC.Chat> channels, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onPicked) {
        final int account = fragment.getCurrentAccount();
        if (channels.isEmpty()) {
            askCreatePersonalChannel(fragment, onPicked);
            return;
        }
        if (channels.size() == 1) {
            onPicked.run(channels.get(0));
            return;
        }
        final Context context = fragment.getParentActivity();
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
        final BottomSheet[] sheet = new BottomSheet[1];
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        final long last = TigiPosting.lastChannel(account);
        for (int i = 0; i < channels.size(); i++) {
            final TLRPC.Chat chat = channels.get(i);
            final String subtitle = chat.id == last ? LocaleController.getString(R.string.TigiPostLastUsed)
                : (chat.creator ? LocaleController.getString(R.string.ChannelCreator) : LocaleController.getString(R.string.ChannelAdmin));
            list.addView(channelRow(context, account, chat, subtitle, v -> {
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
                onPicked.run(chat);
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));
        }
        final TLRPC.Chat own = MessagesController.getInstance(account).getChat(MessagesController.getMainSettings(account).getLong("tigi_own_channel", 0));
        if (!TigiPosting.canPostTo(own)) {
            list.addView(actionRow(context, LocaleController.getString(R.string.TigiPostNewChannel), v -> {
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
                askCreatePersonalChannel(fragment, onPicked);
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        }
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        builder.setTitle(LocaleController.getString(R.string.TigiPostAs), true);
        builder.setCustomView(scroll);
        sheet[0] = builder.create();
        fragment.showDialog(sheet[0]);
    }

    private static View channelRow(Context context, int account, TLRPC.Chat chat, String subtitle, View.OnClickListener onClick) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(onClick);
        row.setContentDescription(chat.title + ", " + subtitle);
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(20));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(account, chat);
        avatar.setForUserOrChat(chat, avatarDrawable);
        row.addView(avatar, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 0, 16, 0));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(chat.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        final TextView sub = new TextView(context);
        sub.setText(subtitle);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        sub.setSingleLine(true);
        sub.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        texts.addView(title);
        texts.addView(sub);
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 16 : 72, 0, LocaleController.isRTL ? 72 : 16, 0));
        return row;
    }

    private static View actionRow(Context context, String text, View.OnClickListener onClick) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(onClick);
        final ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.msg_channel_create);
        icon.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2), android.graphics.PorterDuff.Mode.SRC_IN));
        row.addView(icon, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 0, 16, 0));
        final TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
        label.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        row.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 16 : 72, 0, LocaleController.isRTL ? 72 : 16, 0));
        return row;
    }

    /** First post without a channel: explain, let the user name it, private unless they tick "public link". */
    static void askCreatePersonalChannel(BaseFragment fragment, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onCreated) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        final TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.TigiOwnChannelInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));

        final EditTextBoldCursor titleField = new EditTextBoldCursor(context);
        titleField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleField.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleField.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        titleField.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        titleField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleField.setBackground(null);
        titleField.setSingleLine(true);
        titleField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleField.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        titleField.setHint(LocaleController.getString(R.string.EnterChannelName));
        titleField.setText(TigiPosting.suggestedTitle(account));
        titleField.setPadding(0, dp(8), 0, dp(8));
        content.addView(titleField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));

        final String username = TigiPosting.suggestedUsername(account);
        final CheckBoxCell publicCell;
        if (username != null) {
            publicCell = new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, fragment.getResourceProvider());
            publicCell.setBackground(Theme.getSelectorDrawable(false));
            publicCell.setText(LocaleController.formatString(R.string.TigiOwnChannelPublic, username), "", false, false);
            publicCell.setOnClickListener(v -> publicCell.setChecked(!publicCell.isChecked(), true));
            content.addView(publicCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 0));
        } else {
            publicCell = null;
        }

        new AlertDialog.Builder(context, fragment.getResourceProvider())
            .setTitle(LocaleController.getString(R.string.TigiOwnChannelTitleDialog))
            .setView(content)
            .setPositiveButton(LocaleController.getString(R.string.Create), (dialog, which) -> {
                final String title = titleField.getText() != null ? titleField.getText().toString().trim() : "";
                final String link = publicCell != null && publicCell.isChecked() ? username : null;
                final AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
                progress.setCanCancel(false);
                progress.show();
                TigiPosting.createPersonalChannel(account, title, link, chat -> {
                    progress.dismiss();
                    if (fragment.getParentActivity() != null) {
                        onCreated.run(chat);
                    }
                }, message -> {
                    progress.dismiss();
                    if (fragment.getParentActivity() != null) {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, message).show();
                    }
                });
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show();
    }

    /** Opens Telegram's composer in the channel; it goes back by itself once the post is sent. */
    static void compose(BaseFragment fragment, TLRPC.Chat chat, int composeMode) {
        if (fragment == null || fragment.getParentActivity() == null || !TigiPosting.canPostTo(chat)) {
            if (fragment != null && fragment.getParentActivity() != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiPostNoRights)).show();
            }
            return;
        }
        if (BuildVars.TIGI_SILENT_POST) {
            // Nilak's own composer: gallery, caption, Share; the channel's chat is never shown
            TigiComposeActivity.start(fragment, chat, composeMode == ChatActivity.TIGI_COMPOSE_TEXT ? TigiComposeActivity.MODE_TEXT : TigiComposeActivity.MODE_POST);
            return;
        }
        TigiPosting.setLastChannel(fragment.getCurrentAccount(), chat.id);
        if (fragment instanceof MainTabsActivity) {
            // the new post shows up at the top of Home when the composer closes
            ((MainTabsActivity) fragment).openHomeTab();
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        args.putInt("tigi_compose", composeMode);
        fragment.presentFragment(new ChatActivity(args));
    }
}
