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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

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

    public ChannelFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            chatId = arguments.getLong("chat_id", 0);
        }
        chat = getMessagesController().getChat(chatId);
        return chat != null && super.onFragmentCreate();
    }

    @Override
    protected View createHeaderView(Context context) {
        final FrameLayout header = new FrameLayout(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finishFragment());
        row.addView(back, LayoutHelper.createLinear(48, 56));

        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(18));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(currentAccount, chat);
        avatar.setForUserOrChat(chat, avatarDrawable);
        row.addView(avatar, LayoutHelper.createLinear(36, 36));

        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(chat.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        final TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.TigiChannelLabel));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        names.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        names.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        row.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        final ImageView chatButton = new ImageView(context);
        chatButton.setScaleType(ImageView.ScaleType.CENTER);
        chatButton.setImageResource(R.drawable.tigi_comment_24);
        chatButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        chatButton.setContentDescription("Open chat view");
        chatButton.setOnClickListener(v -> {
            final Bundle args = new Bundle();
            args.putLong("chat_id", chatId);
            presentFragment(new ChatActivity(args));
        });
        row.addView(chatButton, LayoutHelper.createLinear(48, 56));

        header.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP | Gravity.LEFT));
        return header;
    }

    @Override
    protected boolean isLoadingPosts() {
        return loading;
    }

    @Override
    protected void onHeaderClicked(FeedPost post) {
        // already inside this channel
    }

    @Override
    protected void onNearEnd() {
        loadPage();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (posts.isEmpty()) {
            loadPage();
        }
    }

    private void loadPage() {
        if (loading || reachedEnd || chat == null || listView == null) {
            return;
        }
        loading = true;
        updateEmptyView();
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = getMessagesController().getInputPeer(-chatId);
        req.limit = PAGE_SIZE;
        req.offset_id = oldestId;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                int minId = oldestId;
                for (int i = 0; i < res.messages.size(); i++) {
                    final int id = res.messages.get(i).id;
                    if (id > 0 && (minId == 0 || id < minId)) {
                        minId = id;
                    }
                }
                reachedEnd = res.messages.size() < PAGE_SIZE || minId == oldestId;
                oldestId = minId;
                final ArrayList<FeedPost> page = buildPosts(chat, res.messages);
                final int start = posts.size();
                posts.addAll(page);
                adapter.notifyItemRangeInserted(start, page.size());
            }
            updateEmptyView();
        }));
    }
}
