package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * One channel shown as an Instagram-style feed of cards, newest first, loading more as you scroll.
 */
public class ChannelFeedActivity extends TigiFeedBase {

    private static final int PAGE_SIZE = 20;

    private long chatId;
    private TLRPC.Chat chat;
    private int oldestId;
    private boolean loading;
    private boolean reachedEnd;
    private boolean loadFailed;
    private boolean mediaOnly;
    private boolean markedRead;
    private int requestId;
    private final HashMap<Long, FeedPost> albums = new HashMap<>();

    private View headerView;
    private ImageView backButton;
    private TextView titleView;
    private TextView subtitleView;
    private ImageView chatButton;
    private TextView joinButton;

    public ChannelFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            chatId = arguments.getLong("chat_id", 0);
            mediaOnly = arguments.getBoolean("media_only", false);
        }
        chat = getMessagesController().getChat(chatId);
        return chat != null && super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (requestId != 0) {
            getConnectionsManager().cancelRequest(requestId, true);
            requestId = 0;
        }
        super.onFragmentDestroy();
    }

    @Override
    protected View createHeaderView(Context context) {
        final FrameLayout header = new FrameLayout(context);
        headerView = header;
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        applyTopInset(header);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        backButton = new ImageView(context);
        backButton.setScaleType(ImageView.ScaleType.CENTER);
        backButton.setImageResource(R.drawable.ic_ab_back);
        backButton.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        backButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        backButton.setOnClickListener(v -> finishFragment());
        if (LocaleController.isRTL) {
            backButton.setScaleX(-1f);
        }
        row.addView(backButton, LayoutHelper.createLinear(48, 56));

        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(18));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(currentAccount, chat);
        avatar.setForUserOrChat(chat, avatarDrawable);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, LayoutHelper.createLinear(36, 36));

        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setText(chat.title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView = new TextView(context);
        subtitleView.setText(LocaleController.getString(mediaOnly ? R.string.TigiMediaFeed : R.string.TigiChannelLabel));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        names.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        row.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        // avatar and name open Telegram's info screen: members, notifications, admin tools, shared media
        final View.OnClickListener openInfo = v -> TigiNavigation.openChatInfo(this, chatId);
        avatar.setOnClickListener(openInfo);
        names.setOnClickListener(openInfo);
        names.setContentDescription(chat.title);

        joinButton = new TextView(context);
        joinButton.setText(LocaleController.getString(R.string.TigiJoin));
        joinButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        joinButton.setTypeface(AndroidUtilities.bold());
        joinButton.setGravity(Gravity.CENTER);
        joinButton.setPadding(dp(16), 0, dp(16), 0);
        joinButton.setMinHeight(dp(36));
        joinButton.setOnClickListener(v -> joinChannel());
        row.addView(joinButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        chatButton = new ImageView(context);
        chatButton.setScaleType(ImageView.ScaleType.CENTER);
        chatButton.setImageResource(R.drawable.tigi_comment_24);
        chatButton.setContentDescription(LocaleController.getString(R.string.TigiOpenChatView));
        chatButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        chatButton.setOnClickListener(v -> {
            final Bundle args = new Bundle();
            args.putLong("chat_id", chatId);
            presentFragment(new ChatActivity(args));
        });
        row.addView(chatButton, LayoutHelper.createLinear(48, 56));

        header.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP | Gravity.LEFT));
        updateHeaderColors();
        updateJoinButton();
        return header;
    }

    @Override
    protected void updateHeaderColors() {
        if (headerView == null) {
            return;
        }
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        headerView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        backButton.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
        chatButton.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
        titleView.setTextColor(text);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        joinButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        joinButton.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.getColor(Theme.key_featuredStickers_addButton)));
    }

    private void updateJoinButton() {
        if (joinButton == null) {
            return;
        }
        final TLRPC.Chat current = getMessagesController().getChat(chatId);
        if (current != null) {
            chat = current;
        }
        joinButton.setVisibility(ChatObject.isNotInChat(chat) && !chat.kicked && !chat.join_request ? View.VISIBLE : View.GONE);
    }

    private void joinChannel() {
        if (chat == null || !ChatObject.isNotInChat(chat)) {
            return;
        }
        joinButton.setEnabled(false);
        getMessagesController().addUserToChat(chat.id, getUserConfig().getCurrentUser(), 0, null, this, true, () -> {
            joinButton.setEnabled(true);
            updateJoinButton();
        }, err -> {
            joinButton.setEnabled(true);
            return true;
        });
    }

    @Override
    protected boolean acceptsNewMessage(long dialogId, MessageObject message) {
        if (dialogId != -chatId) {
            return false;
        }
        return !mediaOnly || message.isPhoto() || message.isVideo();
    }

    @Override
    protected boolean isLoadingPosts() {
        return loading;
    }

    @Override
    protected void updateEmptyView() {
        super.updateEmptyView();
        if (emptyView != null && posts.isEmpty() && !loading && loadFailed) {
            emptyView.setText(LocaleController.getString(R.string.TigiFeedError));
        }
    }

    @Override
    protected void onHeaderClicked(FeedPost post) {
        if (post != null && post.author != null) {
            // a member's post in a group's photo feed
            TigiNavigation.openProfile(this, post.author.id);
        }
        // a channel's own post: already on its page
    }

    @Override
    protected void onNearEnd() {
        loadPage();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateJoinButton();
        if (posts.isEmpty()) {
            loadFailed = false;
            loadPage();
        }
    }

    private void loadPage() {
        if (loading || reachedEnd || chat == null || listView == null) {
            return;
        }
        loading = true;
        updateEmptyView();
        final TLObject request;
        if (mediaOnly) {
            final TLRPC.TL_messages_search search = new TLRPC.TL_messages_search();
            search.peer = getMessagesController().getInputPeer(-chatId);
            search.q = "";
            search.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
            search.limit = PAGE_SIZE;
            search.offset_id = oldestId;
            request = search;
        } else {
            final TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
            history.peer = getMessagesController().getInputPeer(-chatId);
            history.limit = PAGE_SIZE;
            history.offset_id = oldestId;
            request = history;
        }
        final boolean firstPage = oldestId == 0;
        requestId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            requestId = 0;
            loading = false;
            if (isDead()) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                loadFailed = false;
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                int minId = oldestId;
                int maxId = 0;
                int maxDate = 0;
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    final int id = message.id;
                    if (id > 0 && (minId == 0 || id < minId)) {
                        minId = id;
                    }
                    if (id > maxId) {
                        maxId = id;
                        maxDate = message.date;
                    }
                }
                reachedEnd = res.messages.isEmpty() || minId == oldestId;
                oldestId = minId;
                if (firstPage && !mediaOnly && !markedRead && maxId > 0 && !ChatObject.isNotInChat(chat)) {
                    // like opening the chat: the newest posts are now seen, so the unread badge goes away
                    markedRead = true;
                    getMessagesController().markDialogAsRead(-chatId, maxId, maxId, maxDate, false, 0, 0, true, 0);
                }
                final int before = posts.size();
                final ArrayList<FeedPost> page = buildPosts(chat, res.messages, albums);
                posts.addAll(page);
                if (before == 0) {
                    adapter.notifyDataSetChanged();
                } else {
                    // an album continued from the previous page may have grown the last card
                    adapter.notifyItemChanged(before - 1);
                    adapter.notifyItemRangeInserted(before, page.size());
                }
                if (page.isEmpty() && !reachedEnd && posts.size() < PAGE_SIZE) {
                    // a page of service messages or unsupported media: keep going so the screen is not empty
                    AndroidUtilities.runOnUIThread(this::loadPage);
                }
            } else {
                loadFailed = true;
            }
            updateEmptyView();
        }));
    }
}
