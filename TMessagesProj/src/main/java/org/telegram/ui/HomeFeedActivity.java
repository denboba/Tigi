package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Tigi Home: an Instagram-style feed of recent posts from the channels the user is in.
 */
public class HomeFeedActivity extends TigiFeedBase implements MainTabsActivity.TabFragmentDelegate {

    private static final int MAX_CHANNELS = 25;
    private static final int POSTS_PER_CHANNEL = 12;
    private static final int MAX_POSTS = 60;
    private static final long RELOAD_AFTER_MS = 2 * 60 * 1000L;
    private static final int PARALLEL_REQUESTS = 3;

    private boolean loading;
    private int loadGeneration;
    private TextView titleView;
    private long lastLoadTime;

    public HomeFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    protected View createHeaderView(Context context) {
        final FrameLayout header = new FrameLayout(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        applyTopInset(header);
        headerView = header;
        final TextView title = new TextView(context);
        titleView = title;
        title.setText(LocaleController.getString(R.string.AppName));
        title.setFocusable(true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 0, 16, 0));
        return header;
    }

    private View headerView;

    @Override
    protected void updateHeaderColors() {
        if (headerView != null) {
            headerView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        if (titleView != null) {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        }
    }

    @Override
    protected boolean isLoadingPosts() {
        return loading;
    }

    @Override
    protected void onPostTapped(FeedPost post, int mediaIndex) {
        openChannelFeed(post.chat);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (posts.isEmpty() || System.currentTimeMillis() - lastLoadTime > RELOAD_AFTER_MS) {
            loadFeed();
        }
    }

    @Override
    public void onParentScrollToTop() {
        if (listView == null) {
            return;
        }
        if (layoutManager.findFirstVisibleItemPosition() <= 0) {
            loadFeed();
        } else {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        if (listView == null) {
            return true;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof FeedCell && ((FeedCell) child).isOverMultiPhoto(ev)) {
                return false;
            }
        }
        return true;
    }

    private void loadFeed() {
        if (loading || listView == null) {
            return;
        }
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
        for (int i = 0; i < dialogs.size() && channels.size() < MAX_CHANNELS; i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog == null || !DialogObject.isChatDialog(dialog.id)) {
                continue;
            }
            final TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
            if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !chat.left && !ChatObject.isNotInChat(chat)) {
                channels.add(chat);
            }
        }
        if (channels.isEmpty()) {
            updateEmptyView();
            return;
        }

        loading = true;
        final int generation = ++loadGeneration;
        updateEmptyView();
        final ArrayList<FeedPost> result = new ArrayList<>();
        final int[] next = {0};
        final int[] pending = {channels.size()};
        // A few requests at a time: 25 parallel getHistory calls can trigger FLOOD_WAIT for the whole account.
        final Runnable[] startNext = new Runnable[1];
        startNext[0] = () -> {
            if (generation != loadGeneration || isDead()) {
                return;
            }
            if (next[0] >= channels.size()) {
                return;
            }
            final TLRPC.Chat chat = channels.get(next[0]++);
            final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = getMessagesController().getInputPeer(-chat.id);
            req.limit = POSTS_PER_CHANNEL;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (generation != loadGeneration) {
                    return;
                }
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    result.addAll(buildPosts(chat, res.messages));
                } else if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                    // stop asking; show what we have
                    pending[0] -= channels.size() - next[0];
                    next[0] = channels.size();
                }
                pending[0]--;
                if (pending[0] <= 0) {
                    finishLoad(result);
                } else {
                    startNext[0].run();
                }
            }));
        };
        for (int i = 0; i < PARALLEL_REQUESTS; i++) {
            startNext[0].run();
        }
    }

    private void finishLoad(ArrayList<FeedPost> result) {
        loading = false;
        lastLoadTime = System.currentTimeMillis();
        if (isDead()) {
            return;
        }
        Collections.sort(result, (a, b) -> b.date - a.date);
        posts.clear();
        posts.addAll(result.size() > MAX_POSTS ? result.subList(0, MAX_POSTS) : result);
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    @Override
    public void onFragmentDestroy() {
        loadGeneration++;
        loading = false;
        super.onFragmentDestroy();
    }
}
