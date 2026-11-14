package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.community.CommunityCreateActivity;

import java.util.ArrayList;

/**
 * The two Nilak sheets around Telegram's Communities: starting a new community (Telegram builds every community
 * around a first chat, then its own New Community screen takes over) and choosing where to post in a community.
 */
public final class NilakCommunitySheets {

    private NilakCommunitySheets() {
    }

    /** New community: from one of the person's chats, from their Nilak channel, or a new group first. */
    public static void showNewCommunity(BaseFragment fragment) {
        final Context context = fragment != null ? fragment.getParentActivity() : null;
        if (context == null) {
            return;
        }
        final LinearLayout column = column(context, R.string.NilakCommunityNew, R.string.NilakCommunitiesEmptyText);
        final BottomSheet[] sheet = new BottomSheet[1];
        column.addView(optionRow(context, R.drawable.msg_groups, R.string.NilakCommunityFromChat, R.string.NilakCommunityFromChatHint, () -> {
            sheet[0].dismiss();
            pickOwnChat(fragment);
        }));
        final TLRPC.Chat mine = TigiPosting.defaultChannel(fragment.getCurrentAccount());
        if (mine != null && !ChatObject.isCommunity(mine) && mine.linked_community_id == 0) {
            column.addView(optionRow(context, R.drawable.msg_channel, R.string.NilakCommunityFromChannel, 0, () -> {
                sheet[0].dismiss();
                openCreate(fragment, mine);
            }));
        }
        column.addView(optionRow(context, R.drawable.msg_groups_create, R.string.NilakCommunityNewGroup, R.string.NilakCommunityNewGroupHint, () -> {
            sheet[0].dismiss();
            fragment.presentFragment(new GroupCreateActivity(new Bundle()));
        }));
        sheet[0] = show(fragment, column);
    }

    private static void pickOwnChat(BaseFragment fragment) {
        MessagesController.getInstance(fragment.getCurrentAccount()).fetchChatsToAddToCommunity((chats, error) -> {
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (error != null) {
                BulletinFactory.of(fragment).showForError(error);
                return;
            }
            if (chats == null || chats.isEmpty()) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.CommunityNoChatsToAdd)).show();
                return;
            }
            final Context context = fragment.getParentActivity();
            final LinearLayout column = column(context, R.string.NilakCommunityFromChat, R.string.NilakCommunityFromChatHint);
            final BottomSheet[] sheet = new BottomSheet[1];
            for (TLRPC.Chat chat : chats) {
                column.addView(chatRow(context, fragment.getCurrentAccount(), chat, kindOf(chat), () -> {
                    sheet[0].dismiss();
                    openCreate(fragment, chat);
                }));
            }
            sheet[0] = show(fragment, column);
        });
    }

    private static void openCreate(BaseFragment fragment, TLRPC.Chat chat) {
        final Bundle args = new Bundle();
        args.putLong("dialog_id", -chat.id);
        fragment.presentFragment(new CommunityCreateActivity(args));
    }

    /** Post to…: the community's chats where the person can write; Telegram's compose screen opens in the chosen one. */
    public static boolean hasChatsToPostIn(int account, long communityId) {
        return !postableChats(account, communityId).isEmpty();
    }

    public static void showPostTo(BaseFragment fragment, long communityId) {
        final Context context = fragment != null ? fragment.getParentActivity() : null;
        if (context == null) {
            return;
        }
        final ArrayList<TLRPC.Chat> chats = postableChats(fragment.getCurrentAccount(), communityId);
        if (chats.size() == 1) {
            openChat(fragment, chats.get(0));
            return;
        }
        final LinearLayout column = column(context, R.string.NilakCommunityPostTo, R.string.NilakCommunityPostToHint);
        final BottomSheet[] sheet = new BottomSheet[1];
        for (TLRPC.Chat chat : chats) {
            column.addView(chatRow(context, fragment.getCurrentAccount(), chat, kindOf(chat), () -> {
                sheet[0].dismiss();
                openChat(fragment, chat);
            }));
        }
        sheet[0] = show(fragment, column);
    }

    private static ArrayList<TLRPC.Chat> postableChats(int account, long communityId) {
        final ArrayList<TLRPC.Chat> result = new ArrayList<>();
        final MessagesController.CommunityPeersDialog peers = MessagesController.getInstance(account).buildCommunityPeers(communityId);
        if (peers == null) {
            return result;
        }
        for (MessagesController.CommunityPeerDialog peer : peers.chatsYouAreIn) {
            if (peer.chat != null && !ChatObject.isNotInChat(peer.chat) && ChatObject.canWriteToChat(peer.chat)) {
                result.add(peer.chat);
            }
        }
        return result;
    }

    private static void openChat(BaseFragment fragment, TLRPC.Chat chat) {
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        if (ChatObject.isForum(chat)) {
            if (ChatObject.areTabsEnabled(chat)) {
                final ChatActivity activity = new ChatActivity(args);
                ForumUtilities.applyTopic(activity, MessagesStorage.TopicKey.of(-chat.id,
                    MessagesController.getInstance(fragment.getCurrentAccount()).getForumLastTopicId(chat.id)));
                fragment.presentFragment(activity);
            } else {
                fragment.presentFragment(new TopicsFragment(args));
            }
        } else {
            fragment.presentFragment(new ChatActivity(args));
        }
    }

    private static String kindOf(TLRPC.Chat chat) {
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return LocaleController.getString(R.string.AccDescrChannel);
        }
        return LocaleController.getString(ChatObject.isForum(chat) ? R.string.NilakCommunityTopics : R.string.AccDescrGroup);
    }

    /* layout, in the style of the Create sheet */

    private static LinearLayout column(Context context, int title, int subtitle) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(14), dp(6), dp(14), dp(18));
        final TextView titleView = new TextView(context);
        titleView.setText(LocaleController.getString(title));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(TigiStyle.displayExtraBold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setPadding(dp(8), dp(8), dp(8), dp(2));
        column.addView(titleView);
        final TextView subtitleView = new TextView(context);
        subtitleView.setText(LocaleController.getString(subtitle));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        subtitleView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        subtitleView.setPadding(dp(8), 0, dp(8), dp(12));
        column.addView(subtitleView);
        return column;
    }

    private static BottomSheet show(BaseFragment fragment, LinearLayout column) {
        final ScrollView scroll = new ScrollView(column.getContext());
        scroll.addView(column);
        final BottomSheet.Builder builder = new BottomSheet.Builder(column.getContext(), false, fragment.getResourceProvider());
        builder.setCustomView(scroll);
        final BottomSheet sheet = builder.create();
        fragment.showDialog(sheet);
        return sheet;
    }

    private static LinearLayout baseRow(Context context, Runnable action) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.12f), dp(16), dp(16)));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private static View optionRow(Context context, int icon, int title, int hint, Runnable action) {
        final LinearLayout row = baseRow(context, action);
        final ImageView iconView = new ImageView(context);
        iconView.setImageResource(icon);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setColorFilter(new android.graphics.PorterDuffColorFilter(TigiStyle.accent(), android.graphics.PorterDuff.Mode.SRC_IN));
        iconView.setBackground(Theme.createRoundRectDrawable(dp(13), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
        row.addView(iconView, LayoutHelper.createLinear(42, 42));
        addTexts(row, LocaleController.getString(title), hint != 0 ? LocaleController.getString(hint) : null);
        row.setContentDescription(LocaleController.getString(title));
        return row;
    }

    private static View chatRow(Context context, int account, TLRPC.Chat chat, String subtitle, Runnable action) {
        final LinearLayout row = baseRow(context, action);
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(21));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(account, chat);
        avatar.setForUserOrChat(chat, avatarDrawable);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, LayoutHelper.createLinear(42, 42));
        addTexts(row, chat.title, subtitle);
        row.setContentDescription(chat.title + ", " + subtitle);
        return row;
    }

    private static void addTexts(LinearLayout row, CharSequence title, CharSequence hint) {
        final Context context = row.getContext();
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        if (!TextUtils.isEmpty(hint)) {
            final TextView hintView = new TextView(context);
            hintView.setText(hint);
            hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            hintView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            hintView.setMaxLines(2);
            texts.addView(hintView);
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
    }
}
