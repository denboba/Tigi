package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
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

    /** Set by the Live phase: until then the sheet does not offer it. */
    static boolean liveAvailable = false;

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        if (MessagesController.getInstance(account).isFrozen()) {
            AccountFrozenAlert.show(account);
            return;
        }
        final ArrayList<CharSequence> items = new ArrayList<>();
        final ArrayList<Integer> icons = new ArrayList<>();
        final ArrayList<Integer> actions = new ArrayList<>();
        if (BuildVars.TIGI_POST) {
            items.add(LocaleController.getString(R.string.TigiCreatePost));
            icons.add(R.drawable.msg_media);
            actions.add(ACTION_POST);
            items.add(LocaleController.getString(R.string.TigiCreateReel));
            icons.add(R.drawable.msg_video);
            actions.add(ACTION_REEL);
        }
        if (MessagesController.getInstance(account).storiesEnabled()) {
            items.add(LocaleController.getString(R.string.TigiCreateStory));
            icons.add(R.drawable.msg_camera);
            actions.add(ACTION_STORY);
        }
        if (BuildVars.TIGI_LIVE && liveAvailable) {
            items.add(LocaleController.getString(R.string.TigiCreateLive));
            icons.add(R.drawable.msg_voicechat);
            actions.add(ACTION_LIVE);
        }
        if (BuildVars.TIGI_POST) {
            items.add(LocaleController.getString(R.string.TigiCreateText));
            icons.add(R.drawable.msg_edit);
            actions.add(ACTION_TEXT);
        }
        if (actions.isEmpty()) {
            return;
        }
        final int[] iconArray = new int[icons.size()];
        for (int i = 0; i < iconArray.length; i++) {
            iconArray[i] = icons.get(i);
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(fragment.getParentActivity(), false, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.TigiCreate), true);
        builder.setItems(items.toArray(new CharSequence[0]), iconArray, (dialog, which) -> {
            if (which < 0 || which >= actions.size()) {
                return;
            }
            switch (actions.get(which)) {
                case ACTION_POST:
                case ACTION_REEL:
                    pickChannel(fragment, ChatActivity.TIGI_COMPOSE_MEDIA);
                    break;
                case ACTION_TEXT:
                    pickChannel(fragment, ChatActivity.TIGI_COMPOSE_TEXT);
                    break;
                case ACTION_STORY:
                    StoryRecorder.getInstance(fragment.getParentActivity(), account).open(null);
                    break;
            }
        });
        fragment.showDialog(builder.create());
    }

    /* the channel */

    /** One channel: straight to it. Several: "Post as" with the last used first. None: offer a personal channel. */
    static void pickChannel(BaseFragment fragment, int composeMode) {
        pickChannel(fragment, composeMode, chat -> compose(fragment, chat, composeMode));
    }

    static void pickChannel(BaseFragment fragment, int composeMode, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onPicked) {
        final int account = fragment.getCurrentAccount();
        final ArrayList<TLRPC.Chat> channels = TigiPosting.postableChannels(account);
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
