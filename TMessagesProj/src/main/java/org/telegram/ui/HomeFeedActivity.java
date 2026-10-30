package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
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
    private static final int DISCOVER_CHANNELS = 12;

    private boolean loading;
    private int loadGeneration;
    private TextView titleView;
    private long lastLoadTime;

    public HomeFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    protected boolean headerScrollsWithList() {
        return true;
    }

    @Override
    protected View createHeaderView(Context context) {
        // Instagram's Home: the stories row scrolls away with the posts, the title bar hides while scrolling down
        // and comes back on the way up. Both float over the list, which uses the whole screen behind them.
        final FrameLayout header = new FrameLayout(context);

        // the part that scrolls with the posts: stories, and "Suggested for you" while the user follows no channels
        scrollingPart = new LinearLayout(context);
        scrollingPart.setOrientation(LinearLayout.VERTICAL);
        storiesRow = new TigiStoriesRow(context, this);
        scrollingPart.addView(storiesRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        suggestedRow = new TigiSuggestedRow(context, this, () -> followedSinceLoad = true);
        scrollingPart.addView(suggestedRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        header.addView(scrollingPart, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        final FrameLayout bar = new FrameLayout(context);
        titleBar = bar;
        bar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        applyTopInset(bar);
        final TextView title = new TextView(context);
        titleView = title;
        // the floret + "nilak" wordmark, drawn as an image span so TalkBack still reads the app name
        title.setFocusable(true);
        title.setContentDescription(LocaleController.getString(R.string.AppName));
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bindWordmark(title);
        bar.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 0, 16, 0));
        searchButton = new ImageView(context);
        searchButton.setScaleType(ImageView.ScaleType.CENTER);
        searchButton.setImageResource(R.drawable.outline_header_search);
        searchButton.setContentDescription(LocaleController.getString(R.string.Search));
        searchButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        searchButton.setOnClickListener(v -> {
            // until Explore exists: Telegram's search for chats, channels and messages
            final MainTabsActivity tabs = TigiNavigation.findMainTabs(this);
            if (tabs != null) {
                tabs.openChatsSearch();
            }
        });
        bar.addView(searchButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 6, 4, 6, 0));
        header.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        // the stories row sits right under the bar, whose height includes the status bar
        bar.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) scrollingPart.getLayoutParams();
            if (params.topMargin != bottom - top) {
                params.topMargin = bottom - top;
                scrollingPart.post(() -> scrollingPart.setLayoutParams(params));
            }
        });

        headerView = header;
        updateHeaderColors();
        storiesRow.update();
        return header;
    }

    private View headerView;
    private FrameLayout titleBar;

    private void bindWordmark(TextView title) {
        final android.graphics.drawable.Drawable logo = title.getContext().getResources().getDrawable(R.drawable.telegram_logo_2).mutate();
        final int height = AndroidUtilities.dp(24);
        final int width = (int) (logo.getIntrinsicWidth() * (height / (float) logo.getIntrinsicHeight()));
        logo.setBounds(0, 0, width, height);
        logo.setColorFilter(new android.graphics.PorterDuffColorFilter(TigiStyle.accent(), android.graphics.PorterDuff.Mode.SRC_IN));
        final android.text.SpannableString text = new android.text.SpannableString(LocaleController.getString(R.string.AppName));
        text.setSpan(new android.text.style.ImageSpan(logo, android.text.style.ImageSpan.ALIGN_BOTTOM), 0, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(text);
    }
    private ImageView searchButton;
    private TigiStoriesRow storiesRow;
    private LinearLayout scrollingPart;
    private TigiSuggestedRow suggestedRow;
    private boolean discover;
    private boolean followedSinceLoad;
    private float titleOffset;

    /** Discover mode: the feed shows suggested channels' posts, with the follow row on top. */
    private void setDiscover(boolean on) {
        discover = on;
        if (suggestedRow != null) {
            if (on) {
                TigiPosting.loadSuggestedChannels(currentAccount, suggestedRow::setChannels);
            } else {
                suggestedRow.setChannels(null);
            }
        }
        if (on) {
            followedSinceLoad = false;
        }
    }

    @Override
    protected void onListScrolled(int dy) {
        if (titleBar == null || scrollingPart == null) {
            return;
        }
        final int scrolled = listScrollFromTop();
        // stories (and suggestions) move exactly with the first post
        final int storiesSpace = scrollingPart.getHeight() + titleBar.getHeight();
        scrollingPart.setTranslationY(-Math.min(scrolled, storiesSpace));
        // the bar slides up by its title part (the status bar strip stays) and returns when scrolling back
        final int hideable = titleBar.getHeight() - titleBar.getPaddingTop();
        titleOffset = Math.max(-hideable, Math.min(0, titleOffset - dy));
        if (scrolled == 0) {
            titleOffset = 0;
        }
        titleBar.setTranslationY(titleOffset);
        final float shown = hideable > 0 ? 1f + titleOffset / hideable : 1f;
        titleView.setAlpha(shown);
        searchButton.setAlpha(shown);
        searchButton.setEnabled(shown > 0.5f);
    }

    @Override
    protected void updateHeaderColors() {
        final int background = Theme.getColor(Theme.key_windowBackgroundWhite);
        if (titleBar != null) {
            titleBar.setBackgroundColor(background);
        }
        if (scrollingPart != null) {
            scrollingPart.setBackgroundColor(background);
        }
        if (suggestedRow != null) {
            suggestedRow.updateColors();
        }
        if (titleView != null) {
            bindWordmark(titleView);
        }
        if (searchButton != null) {
            searchButton.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), android.graphics.PorterDuff.Mode.SRC_IN));
        }
    }

    @Override
    protected boolean acceptsNewMessage(long dialogId, MessageObject message) {
        // the user's own new posts go straight to the top; other channels' new posts wait for the next refresh
        final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        return message.isOut() && ChatObject.isChannelAndNotMegaGroup(chat);
    }

    @Override
    protected boolean isLoadingPosts() {
        return loading;
    }

    private boolean noChannels;

    @Override
    protected boolean isInitialLoading() {
        // before the first load finished there is nothing to say "empty" about yet
        return loading || (lastLoadTime == 0 && !noChannels);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (storiesRow != null) {
            // read state changes while the story viewer was open
            storiesRow.update();
        }
        if (BuildVars.TIGI_POST) {
            TigiPosting.archiveOwnChannelOnce(currentAccount);
        }
        if (posts.isEmpty() || System.currentTimeMillis() - lastLoadTime > RELOAD_AFTER_MS || (discover && followedSinceLoad)) {
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
        noChannels = channels.isEmpty() && (!dialogs.isEmpty() || getMessagesController().dialogsLoaded);
        if (channels.isEmpty()) {
            if (noChannels) {
                // follows nothing yet: a Discover feed from channels Telegram recommends, instead of an empty page
                loadDiscover();
            } else {
                updateEmptyView();
            }
            return;
        }
        noChannels = false;
        setDiscover(false);
        loadPostsFrom(channels);
    }

    private void loadDiscover() {
        loading = true;
        final int generation = ++loadGeneration;
        updateEmptyView();
        TigiPosting.loadSuggestedChannels(currentAccount, suggested -> {
            if (generation != loadGeneration || isDead()) {
                return;
            }
            loading = false;
            if (suggested.isEmpty()) {
                setDiscover(false);
                lastLoadTime = System.currentTimeMillis();
                updateEmptyView();
                return;
            }
            setDiscover(true);
            loadPostsFrom(new ArrayList<>(suggested.subList(0, Math.min(DISCOVER_CHANNELS, suggested.size()))));
        });
    }

    private void loadPostsFrom(ArrayList<TLRPC.Chat> channels) {
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
        final ArrayList<FeedPost> unsent = unsentPosts();
        posts.clear();
        posts.addAll(unsent);
        posts.addAll(result.size() > MAX_POSTS ? result.subList(0, MAX_POSTS) : result);
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.storiesUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.storiesUpdated);
        loadGeneration++;
        loading = false;
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.storiesUpdated) {
            if (storiesRow != null) {
                storiesRow.update();
            }
            return;
        }
        if (id == NotificationCenter.dialogsNeedReload) {
            if (BuildVars.TIGI_POST) {
                TigiPosting.archiveOwnChannelOnce(currentAccount);
            }
            // on a cold start the chat list arrives after this tab was created
            if (posts.isEmpty() && lastLoadTime == 0 && !loading && !isDead()) {
                loadFeed();
            }
            return;
        }
        super.didReceivedNotification(id, account, args);
    }
}
