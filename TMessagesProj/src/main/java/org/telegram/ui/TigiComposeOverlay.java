package org.telegram.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Nilak's compose screen: Telegram's channel screen keeps its message bar (emoji, GIFs, stickers, every attachment,
 * articles, scheduling, live), and this overlay covers its chat bubbles with the channel shown as Nilak cards
 * ({@link ChannelFeedActivity}, embedded), from the top bar down to the message bar. What the user posts shows up
 * here as a card the moment it is sent.
 */
public class TigiComposeOverlay {

    private final ChatActivity chatActivity;
    private final ViewGroup contentView;
    private ChannelFeedActivity feed;
    private FrameLayout host;
    private boolean resumed;
    private final int[] location = new int[2];
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateInsets;

    public TigiComposeOverlay(ChatActivity chatActivity, ViewGroup contentView, View chatListView, long chatId) {
        this.chatActivity = chatActivity;
        this.contentView = contentView;
        try {
            final Bundle args = new Bundle();
            args.putLong("chat_id", chatId);
            args.putBoolean("embedded", true);
            final ChannelFeedActivity embedded = new ChannelFeedActivity(args);
            embedded.setParentLayout(chatActivity.getParentLayout());
            if (!embedded.onFragmentCreate()) {
                return;
            }
            final View view = embedded.createView(contentView.getContext());
            if (view == null) {
                embedded.onFragmentDestroy();
                return;
            }
            feed = embedded;
            host = new FrameLayout(contentView.getContext());
            host.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            // right above the chat list, below the top bar, the message bar and their panels
            final int index = contentView.indexOfChild(chatListView);
            contentView.addView(host, index >= 0 ? index + 1 : 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            contentView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        } catch (Exception e) {
            FileLog.e(e);
            feed = null;
        }
    }

    public boolean isActive() {
        return feed != null;
    }

    /** Keeps the cards between the top bar and the message bar as the keyboard and panels move. */
    private void updateInsets() {
        if (host == null) {
            return;
        }
        contentView.getLocationInWindow(location);
        final int contentTop = location[1];
        final int contentBottom = contentTop + contentView.getHeight();
        int top = 0;
        final View actionBar = chatActivity.getActionBar();
        if (actionBar != null && actionBar.getVisibility() == View.VISIBLE) {
            actionBar.getLocationInWindow(location);
            top = Math.max(0, location[1] + actionBar.getHeight() - contentTop);
        }
        int bottom = 0;
        final View input = chatActivity.getChatActivityEnterView();
        if (input != null && input.isShown()) {
            input.getLocationInWindow(location);
            bottom = Math.max(0, contentBottom - location[1]);
        }
        if (host.getPaddingTop() != top || host.getPaddingBottom() != bottom) {
            host.setPadding(0, top, 0, bottom);
        }
    }

    public void onResume() {
        if (feed != null && !resumed) {
            resumed = true;
            feed.onResume();
        }
    }

    public void onPause() {
        if (feed != null && resumed) {
            resumed = false;
            feed.onPause();
        }
    }

    public void onDestroy() {
        try {
            contentView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        } catch (Exception ignore) {
        }
        onPause();
        if (feed != null) {
            feed.onFragmentDestroy();
            feed = null;
        }
    }
}
