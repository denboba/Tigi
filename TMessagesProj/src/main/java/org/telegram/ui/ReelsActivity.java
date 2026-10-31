package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Tigi Reels: full screen vertical pager of videos from the channels the user follows.
 * One player at a time (TigiInlinePlayer), only the next video is preloaded, everything is released off screen.
 */
public class ReelsActivity extends TigiFeedBase implements MainTabsActivity.TabFragmentDelegate {

    private static final int MAX_CHANNELS = 30;
    private static final int CHANNELS_PER_ROUND = 6;
    private static final int PER_CHANNEL = 10;
    private static final int PARALLEL_REQUESTS = 3;
    // preloading a whole video only makes sense for short clips
    private static final long PRELOAD_MAX_BYTES = 20L * 1024 * 1024;

    /** Videos longer than this are not reels. */
    public static int MAX_DURATION_SECONDS = 180;

    private static class ChannelCursor {
        final TLRPC.Chat chat;
        int offsetId;
        boolean done;

        ChannelCursor(TLRPC.Chat chat) {
            this.chat = chat;
        }
    }

    private final ArrayList<ChannelCursor> cursors = new ArrayList<>();
    private final HashSet<String> seen = new HashSet<>();
    private int roundStart;
    private boolean loading;
    private boolean loadFailed;
    private int generation;
    private int currentPosition = -1;
    private TLRPC.Document preloading;

    private LinearLayoutManager pagerLayout;
    private ReelsAdapter reelsAdapter;
    private TextView stateView;
    private TextView retryButton;
    private ImageView muteButton;

    private final ComponentCallbacks2 memoryCallbacks = new ComponentCallbacks2() {
        @Override
        public void onTrimMemory(int level) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                cancelPreload();
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && inlinePlayer != null) {
                    inlinePlayer.stop();
                }
            }
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
        }

        @Override
        public void onLowMemory() {
            cancelPreload();
        }
    };

    public ReelsActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        forYou = getMessagesController().getMainSettings().getBoolean(KEY_FOR_YOU, false);
        try {
            ApplicationLoader.applicationContext.registerComponentCallbacks(memoryCallbacks);
        } catch (Exception ignore) {
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        generation++;
        cancelPreload();
        try {
            ApplicationLoader.applicationContext.unregisterComponentCallbacks(memoryCallbacks);
        } catch (Exception ignore) {
        }
        super.onFragmentDestroy();
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    protected View createHeaderView(Context context) {
        return new View(context);
    }

    @Override
    protected boolean isLoadingPosts() {
        return loading;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);

        inlinePlayer = new TigiInlinePlayer(currentAccount, "tigi_reels_muted_v1");
        inlinePlayer.setFitInside(true);
        inlinePlayer.setCropTall(true);

        listView = new RecyclerListView(context);
        pagerLayout = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        layoutManager = pagerLayout;
        listView.setLayoutManager(pagerLayout);
        listView.setItemAnimator(null);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        reelsAdapter = new ReelsAdapter();
        listView.setAdapter(reelsAdapter);
        new PagerSnapHelper().attachToRecyclerView(listView);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    checkAutoplay();
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING && inlinePlayer != null) {
                    inlinePlayer.pause();
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        shimmerView = new TigiShimmerView(context, TigiShimmerView.STYLE_REEL);
        root.addView(shimmerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        stateView = new TextView(context);
        stateView.setTextColor(0xFFFFFFFF);
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        stateView.setGravity(Gravity.CENTER);
        stateView.setPadding(dp(32), 0, dp(32), 0);
        root.addView(stateView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 0, 0, 40));

        retryButton = new TextView(context);
        retryButton.setText(LocaleController.getString(R.string.TigiRetry));
        retryButton.setTextColor(0xFFFFFFFF);
        retryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        retryButton.setTypeface(AndroidUtilities.bold());
        retryButton.setGravity(Gravity.CENTER);
        retryButton.setPadding(dp(20), 0, dp(20), 0);
        retryButton.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(dp(20), 0x33FFFFFF));
        retryButton.setOnClickListener(v -> {
            loadFailed = false;
            loadMore();
        });
        root.addView(retryButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.CENTER, 0, 60, 0, 0));

        final LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        // Following (channels the user follows) and For you (channels like them), TikTok-style
        final LinearLayout modes = new LinearLayout(context);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        followingTab = modeTab(context, R.string.TigiReelsFollowing, false);
        forYouTab = modeTab(context, R.string.TigiReelsForYou, true);
        modes.addView(followingTab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44));
        modes.addView(forYouTab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 18, 0, 0, 0));
        topBar.addView(modes, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        updateModeTabs();
        muteButton = new ImageView(context);
        muteButton.setScaleType(ImageView.ScaleType.CENTER);
        muteButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        muteButton.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(dp(18), 0x40000000));
        muteButton.setOnClickListener(v -> {
            inlinePlayer.setMuted(!inlinePlayer.isMuted());
            updateMuteButton();
            flashSoundOnCurrent();
        });
        topBar.addView(muteButton, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        topBar.setPadding(dp(16), 0, dp(12), 0);
        applyTopInset(topBar);
        root.addView(topBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        updateMuteButton();

        installOverlay(root);
        fragmentView = root;
        updateEmptyView();
        return fragmentView;
    }

    private static final String KEY_FOR_YOU = "nilak_reels_for_you";

    private TextView followingTab;
    private TextView forYouTab;
    private boolean forYou;

    private TextView modeTab(Context context, int text, boolean forYouTab) {
        final TextView tab = new TextView(context);
        tab.setText(LocaleController.getString(text));
        tab.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        tab.setTypeface(TigiStyle.displayExtraBold());
        tab.setTextColor(0xFFFFFFFF);
        tab.setShadowLayer(dp(2), 0, 0, 0x66000000);
        tab.setGravity(Gravity.CENTER);
        tab.setOnClickListener(v -> setForYou(forYouTab));
        return tab;
    }

    private void updateModeTabs() {
        if (followingTab == null) {
            return;
        }
        followingTab.setAlpha(forYou ? 0.6f : 1f);
        forYouTab.setAlpha(forYou ? 1f : 0.6f);
        followingTab.setSelected(!forYou);
        forYouTab.setSelected(forYou);
        followingTab.setContentDescription(followingTab.getText() + (forYou ? "" : ", " + LocaleController.getString(R.string.TigiSelected)));
        forYouTab.setContentDescription(forYouTab.getText() + (forYou ? ", " + LocaleController.getString(R.string.TigiSelected) : ""));
    }

    /** Switches between Following and For you and starts that list from the top. */
    private void setForYou(boolean value) {
        if (forYou == value) {
            onParentScrollToTop();
            return;
        }
        forYou = value;
        getMessagesController().getMainSettings().edit().putBoolean(KEY_FOR_YOU, value).apply();
        updateModeTabs();
        generation++;
        loading = false;
        loadFailed = false;
        triedSuggestions = false;
        roundStart = 0;
        cursors.clear();
        seen.clear();
        cancelPreload();
        if (inlinePlayer != null) {
            inlinePlayer.stop();
        }
        currentPosition = -1;
        posts.clear();
        if (reelsAdapter != null) {
            reelsAdapter.notifyDataSetChanged();
        }
        updateEmptyView();
        loadMore();
    }

    @Override
    protected boolean appliesFeedFilter() {
        return true;
    }

    private void flashSoundOnCurrent() {
        if (listView == null || inlinePlayer == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof ReelCell && child.getTop() <= listView.getHeight() / 2 && child.getBottom() >= listView.getHeight() / 2) {
                ((ReelCell) child).flashSound(inlinePlayer.isMuted());
            }
        }
    }

    private void updateMuteButton() {
        if (muteButton == null || inlinePlayer == null) {
            return;
        }
        final boolean muted = inlinePlayer.isMuted();
        muteButton.setImageResource(muted ? R.drawable.media_mute : R.drawable.media_unmute);
        muteButton.setContentDescription(LocaleController.getString(muted ? R.string.TigiUnmute : R.string.TigiMute));
    }

    @Override
    protected void updateEmptyView() {
        if (stateView == null) {
            return;
        }
        if (!posts.isEmpty()) {
            stateView.setVisibility(View.GONE);
            retryButton.setVisibility(View.GONE);
            shimmerView.setVisibility(View.GONE);
            return;
        }
        final boolean initial = loading || (!loadFailed && cursors.isEmpty() && getMessagesController().getAllDialogs().isEmpty());
        shimmerView.setVisibility(initial ? View.VISIBLE : View.GONE);
        stateView.setVisibility(initial ? View.GONE : View.VISIBLE);
        if (initial) {
            retryButton.setVisibility(View.GONE);
        } else if (loadFailed) {
            stateView.setText(LocaleController.getString(R.string.TigiFeedError));
            retryButton.setVisibility(View.VISIBLE);
        } else {
            stateView.setText(LocaleController.getString(R.string.TigiReelsEmpty));
            retryButton.setVisibility(View.GONE);
        }
    }

    @Override
    protected void updateColors() {
        // always dark
    }

    @Override
    public void onResume() {
        super.onResume();
        updateMuteButton();
        if (posts.isEmpty() && !loading) {
            loadMore();
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null && !posts.isEmpty()) {
            listView.scrollToPosition(0);
            listView.post(this::checkAutoplay);
        }
    }

    /* loading */

    private boolean triedSuggestions;

    /** A few channels the user follows, as seeds for "For you". */
    private ArrayList<TLRPC.Chat> followedSeeds() {
        final ArrayList<TLRPC.Chat> followed = TigiPosting.followedChannels(currentAccount);
        return new ArrayList<>(followed.subList(0, Math.min(4, followed.size())));
    }

    private void collectChannels() {
        cursors.clear();
        final TigiFeedPrefs.Filter filter = TigiFeedPrefs.filter(currentAccount);
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        for (int i = 0; i < dialogs.size() && cursors.size() < MAX_CHANNELS; i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog == null || !DialogObject.isChatDialog(dialog.id)) {
                continue;
            }
            final TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
            if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !ChatObject.isNotInChat(chat) && !filter.hidesChannel(chat.id)) {
                cursors.add(new ChannelCursor(chat));
            }
        }
    }

    /** The next videos from a few channels at a time, newest first within the batch, appended at the end. */
    private void loadMore() {
        if (loading || isDead()) {
            return;
        }
        if (cursors.isEmpty() && forYou && !triedSuggestions) {
            // channels like the ones the user follows, plus Telegram's general suggestions
            triedSuggestions = true;
            loading = true;
            updateEmptyView();
            final int gen = generation;
            TigiPosting.loadRecommendedChannels(currentAccount, followedSeeds(), recommended -> {
                if (gen != generation || isDead()) {
                    return;
                }
                loading = false;
                final TigiFeedPrefs.Filter filter = TigiFeedPrefs.filter(currentAccount);
                for (int i = 0; i < recommended.size() && cursors.size() < MAX_CHANNELS; i++) {
                    final TLRPC.Chat chat = recommended.get(i);
                    if (!filter.hidesChannel(chat.id)) {
                        cursors.add(new ChannelCursor(chat));
                    }
                }
                if (cursors.isEmpty()) {
                    updateEmptyView();
                } else {
                    loadMore();
                }
            });
            return;
        }
        if (cursors.isEmpty() && !forYou) {
            collectChannels();
            if (cursors.isEmpty() && !triedSuggestions && getMessagesController().dialogsLoaded) {
                // follows no channels: reels from channels Telegram recommends
                triedSuggestions = true;
                loading = true;
                updateEmptyView();
                TigiPosting.loadSuggestedChannels(currentAccount, suggested -> {
                    loading = false;
                    if (isDead()) {
                        return;
                    }
                    for (int i = 0; i < suggested.size() && cursors.size() < MAX_CHANNELS; i++) {
                        cursors.add(new ChannelCursor(suggested.get(i)));
                    }
                    if (cursors.isEmpty()) {
                        updateEmptyView();
                    } else {
                        loadMore();
                    }
                });
                return;
            }
            if (cursors.isEmpty()) {
                updateEmptyView();
                return;
            }
        }
        final ArrayList<ChannelCursor> round = new ArrayList<>();
        for (int i = 0; i < cursors.size() && round.size() < CHANNELS_PER_ROUND; i++) {
            final ChannelCursor cursor = cursors.get((roundStart + i) % cursors.size());
            if (!cursor.done) {
                round.add(cursor);
            }
        }
        roundStart = (roundStart + CHANNELS_PER_ROUND) % Math.max(1, cursors.size());
        if (round.isEmpty()) {
            updateEmptyView();
            return;
        }
        loading = true;
        updateEmptyView();
        final int gen = ++generation;
        final ArrayList<FeedPost> batch = new ArrayList<>();
        final int[] next = {0};
        final int[] pending = {round.size()};
        final int[] failures = {0};
        final Runnable[] startNext = new Runnable[1];
        startNext[0] = () -> {
            if (gen != generation || next[0] >= round.size()) {
                return;
            }
            final ChannelCursor cursor = round.get(next[0]++);
            final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = getMessagesController().getInputPeer(-cursor.chat.id);
            req.q = "";
            req.filter = new TLRPC.TL_inputMessagesFilterVideo();
            req.limit = PER_CHANNEL;
            req.offset_id = cursor.offsetId;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    int minId = cursor.offsetId;
                    for (int i = 0; i < res.messages.size(); i++) {
                        final TLRPC.Message message = res.messages.get(i);
                        if (message.id > 0 && (minId == 0 || message.id < minId)) {
                            minId = message.id;
                        }
                        final FeedPost post = toReel(cursor.chat, message);
                        if (post != null) {
                            batch.add(post);
                        }
                    }
                    cursor.done = res.messages.isEmpty() || minId == cursor.offsetId;
                    cursor.offsetId = minId;
                } else {
                    failures[0]++;
                    if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                        pending[0] -= round.size() - next[0];
                        next[0] = round.size();
                    }
                }
                pending[0]--;
                if (pending[0] <= 0) {
                    finishRound(batch, failures[0] == round.size());
                } else {
                    startNext[0].run();
                }
            }));
        };
        for (int i = 0; i < PARALLEL_REQUESTS; i++) {
            startNext[0].run();
        }
    }

    /** Reels are short clips shot upright (or square); size unknown counts as upright, as most channel clips are. */
    static boolean isVerticalOrSquare(MessageObject object) {
        final TLRPC.Document document = object.getDocument();
        if (document == null) {
            return false;
        }
        for (int i = 0; i < document.attributes.size(); i++) {
            final TLRPC.DocumentAttribute attribute = document.attributes.get(i);
            if (attribute instanceof TLRPC.TL_documentAttributeVideo && attribute.w > 0 && attribute.h > 0) {
                return attribute.w <= attribute.h * 1.1f;
            }
        }
        return true;
    }

    private FeedPost toReel(TLRPC.Chat chat, TLRPC.Message message) {
        if (message == null || message instanceof TLRPC.TL_messageService) {
            return null;
        }
        final MessageObject object = new MessageObject(currentAccount, message, true, false);
        if (!object.isVideo() || object.isRoundVideo() || object.getDocument() == null || object.needDrawBluredPreview()) {
            return null;
        }
        if (object.getDuration() > MAX_DURATION_SECONDS || !isVerticalOrSquare(object)) {
            // long-form and landscape videos stay in the feeds, where they open in the full player
            return null;
        }
        if (TigiFeedPrefs.isNotInterested(currentAccount, chat.id, object.getId())) {
            return null;
        }
        // the same clip reposted in several channels shows once
        final String key = "doc" + object.getDocument().id;
        if (!seen.add(key)) {
            return null;
        }
        final FeedPost post = new FeedPost();
        post.chat = chat;
        post.date = message.date;
        post.groupId = 0;
        post.messages.add(object);
        post.media.add(object);
        post.text = object.caption;
        return post;
    }

    private void finishRound(ArrayList<FeedPost> batch, boolean allFailed) {
        loading = false;
        if (isDead()) {
            return;
        }
        loadFailed = allFailed && posts.isEmpty();
        java.util.Collections.sort(batch, (a, b) -> b.date - a.date);
        final int start = posts.size();
        posts.addAll(batch);
        if (reelsAdapter != null && !batch.isEmpty()) {
            reelsAdapter.notifyItemRangeInserted(start, batch.size());
        }
        updateEmptyView();
        if (start == 0 && !batch.isEmpty()) {
            listView.post(this::checkAutoplay);
        } else if (batch.isEmpty() && !allFailed && posts.size() < 3) {
            // channels without videos in this round: try the next ones
            boolean anyLeft = false;
            for (int i = 0; i < cursors.size(); i++) {
                anyLeft |= !cursors.get(i).done;
            }
            if (anyLeft) {
                AndroidUtilities.runOnUIThread(this::loadMore);
            }
        }
    }

    /* playback */

    private ReelCell visibleCell() {
        if (listView == null) {
            return null;
        }
        ReelCell best = null;
        int bestVisible = 0;
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (!(child instanceof ReelCell)) {
                continue;
            }
            final int visible = Math.min(child.getBottom(), listView.getHeight()) - Math.max(child.getTop(), 0);
            if (visible > bestVisible) {
                bestVisible = visible;
                best = (ReelCell) child;
            }
        }
        return best != null && bestVisible > listView.getHeight() * 0.6f ? best : null;
    }

    @Override
    protected void checkAutoplay() {
        if (inlinePlayer == null || isDead()) {
            return;
        }
        final ReelCell cell = visibleCell();
        if (cell == null || cell.post == null) {
            return;
        }
        final int position = listView.getChildAdapterPosition(cell);
        currentPosition = position;
        final MessageObject message = cell.post.primary();
        if (inlinePlayer.isPlaying(message)) {
            inlinePlayer.resume();
            cell.updatePaused();
        } else {
            cell.play();
        }
        preloadAfter(position);
        if (position >= posts.size() - 3) {
            loadMore();
        }
    }

    /** Only the next reel, only if the auto-download rules allow it, only for short files. */
    private void preloadAfter(int position) {
        final int next = position + 1;
        if (next <= 0 || next >= posts.size()) {
            return;
        }
        final MessageObject message = posts.get(next).primary();
        final TLRPC.Document document = message.getDocument();
        if (document == null || document == preloading) {
            return;
        }
        cancelPreload();
        if (document.size > PRELOAD_MAX_BYTES || !DownloadController.getInstance(currentAccount).canDownloadMedia(message)) {
            return;
        }
        preloading = document;
        FileLoader.getInstance(currentAccount).loadFile(document, message, FileLoader.PRIORITY_LOW, 1);
    }

    private void cancelPreload() {
        if (preloading != null) {
            final TLRPC.Document document = preloading;
            preloading = null;
            // a preload that finished is a cached file; one that did not is dropped
            if (!FileLoader.getInstance(currentAccount).getPathToAttach(document, true).exists()) {
                FileLoader.getInstance(currentAccount).cancelLoadFile(document);
            }
        }
    }

    @Override
    protected void onPostRefreshed(FeedPost post) {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof ReelCell && ((ReelCell) child).post == post) {
                ((ReelCell) child).bindActions();
            }
        }
    }

    @Override
    protected void notifyPostsChanged() {
        if (reelsAdapter != null) {
            reelsAdapter.notifyDataSetChanged();
        }
        updateEmptyView();
    }

    /* views */

    private class ReelsAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public int getItemCount() {
            return posts.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final ReelCell cell = new ReelCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((ReelCell) holder.itemView).bind(posts.get(position));
        }

        @Override
        public void onViewRecycled(RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof ReelCell) {
                ((ReelCell) holder.itemView).release();
            }
        }
    }

    private class ReelCell extends FrameLayout {
        private final BackupImageView thumbView;
        private final ImageView pausedView;
        private ImageView soundFlash;
        private boolean userPaused;
        private final ImageView bigHeart;
        private final View progressView;
        private final BackupImageView avatarView;
        private final ImageView heartButton;
        private final TextView heartCount;
        private final TextView commentCount;
        private final TextView nameView;
        private TextView followButton;
        private TextView followBadge;
        private final TextView captionView;
        private final LinearLayout actions;
        private final LinearLayout bottom;
        private boolean captionExpanded;
        FeedPost post;

        private final Runnable progressUpdater = new Runnable() {
            @Override
            public void run() {
                updateProgress();
                if (isAttachedToWindow() && post != null && inlinePlayer != null && inlinePlayer.isPlaying(post.primary())) {
                    AndroidUtilities.runOnUIThread(this, 200);
                }
            }
        };

        ReelCell(Context context) {
            super(context);
            setBackgroundColor(0xFF000000);

            thumbView = new BackupImageView(context);
            thumbView.getImageReceiver().setAspectFit(true);
            addView(thumbView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            // index 1: the player's TextureView

            final View scrim = new View(context);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000}));
            scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 260, Gravity.BOTTOM));

            pausedView = new ImageView(context);
            pausedView.setScaleType(ImageView.ScaleType.CENTER);
            pausedView.setImageResource(R.drawable.tigi_play_big);
            pausedView.setVisibility(GONE);
            pausedView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(pausedView, LayoutHelper.createFrame(88, 88, Gravity.CENTER));

            // a short speaker mark in the middle when the sound is switched
            soundFlash = new ImageView(context);
            soundFlash.setScaleType(ImageView.ScaleType.CENTER);
            soundFlash.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            soundFlash.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(dp(32), 0x59000000));
            soundFlash.setAlpha(0f);
            soundFlash.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(soundFlash, LayoutHelper.createFrame(64, 64, Gravity.CENTER));

            bigHeart = new ImageView(context);
            bigHeart.setImageResource(R.drawable.tigi_heart_filled_24);
            bigHeart.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            bigHeart.setVisibility(GONE);
            addView(bigHeart, LayoutHelper.createFrame(110, 110, Gravity.CENTER));

            final int side = LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT;
            final int bottomInset = bottomBarSpace();

            actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setGravity(Gravity.CENTER_HORIZONTAL);
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(22));
            avatarView.setOnClickListener(v -> openChannel());
            final FrameLayout avatarBox = new FrameLayout(context);
            avatarBox.addView(avatarView, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
            followBadge = new TextView(context);
            followBadge.setText("+");
            followBadge.setTextColor(0xFFFFFFFF);
            followBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            followBadge.setTypeface(TigiStyle.displayExtraBold());
            followBadge.setGravity(Gravity.CENTER);
            followBadge.setIncludeFontPadding(false);
            final GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL);
            badge.setColor(TigiStyle.PRIMARY);
            badge.setStroke(dp(1.5f), 0xFFFFFFFF);
            followBadge.setBackground(badge);
            followBadge.setContentDescription(LocaleController.getString(R.string.TigiProfileFollow));
            followBadge.setOnClickListener(v -> follow());
            avatarBox.addView(followBadge, LayoutHelper.createFrame(20, 20, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            actions.addView(avatarBox, LayoutHelper.createLinear(48, 54, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));
            heartButton = actionIcon(context, R.drawable.tigi_heart_outline_24);
            heartButton.setOnClickListener(v -> {
                if (post != null) {
                    toggleHeart(post);
                }
            });
            heartButton.setOnLongClickListener(v -> {
                if (post != null) {
                    final int[] location = new int[2];
                    v.getLocationOnScreen(location);
                    showReactionPicker(post, location[0], location[1] + v.getHeight());
                }
                return true;
            });
            actions.addView(heartButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL));
            heartCount = countView(context);
            actions.addView(heartCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
            final ImageView commentButton = actionIcon(context, R.drawable.tigi_comment_24);
            commentButton.setContentDescription(LocaleController.getString(R.string.TigiComments));
            commentButton.setOnClickListener(v -> openComments(post));
            actions.addView(commentButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL));
            commentCount = countView(context);
            actions.addView(commentCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
            final ImageView shareButton = actionIcon(context, R.drawable.tigi_share_24);
            shareButton.setContentDescription(LocaleController.getString(R.string.ShareFile));
            shareButton.setOnClickListener(v -> {
                if (post != null) {
                    sharePost(post);
                }
            });
            actions.addView(shareButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
            final ImageView moreButton = actionIcon(context, R.drawable.ic_ab_other);
            moreButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
            moreButton.setOnClickListener(v -> showPostMenu(post, v));
            actions.addView(moreButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL));
            addView(actions, LayoutHelper.createFrame(64, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | side, 4, 0, 4, bottomInset + 12));

            bottom = new LinearLayout(context);
            bottom.setOrientation(LinearLayout.VERTICAL);
            nameView = new TextView(context);
            nameView.setTextColor(0xFFFFFFFF);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTypeface(TigiStyle.displayBold());
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setShadowLayer(dp(2), 0, 0, 0x66000000);
            nameView.setMinHeight(dp(44));
            nameView.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
            nameView.setOnClickListener(v -> openChannel());
            nameView.setMaxWidth(dp(200));
            final LinearLayout nameRow = new LinearLayout(context);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            nameRow.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            followButton = new TextView(context);
            followButton.setText(LocaleController.getString(R.string.TigiProfileFollow));
            followButton.setTextColor(0xFFFFFFFF);
            followButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            followButton.setTypeface(TigiStyle.displayBold());
            followButton.setGravity(Gravity.CENTER);
            followButton.setPadding(dp(12), 0, dp(12), 0);
            final GradientDrawable outline = new GradientDrawable();
            outline.setCornerRadius(dp(8));
            outline.setStroke(dp(1.4f), 0xFFFFFFFF);
            followButton.setBackground(outline);
            followButton.setOnClickListener(v -> follow());
            nameRow.addView(followButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 10, 0, 0, 0));
            bottom.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            captionView = new TextView(context);
            captionView.setTextColor(0xFFFFFFFF);
            captionView.setLinkTextColor(0xFFFFFFFF);
            captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            captionView.setLineSpacing(dp(2), 1f);
            captionView.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
            captionView.setMaxLines(2);
            captionView.setEllipsize(TextUtils.TruncateAt.END);
            captionView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            captionView.setOnClickListener(v -> {
                captionExpanded = !captionExpanded;
                captionView.setMaxLines(captionExpanded ? 12 : 2);
            });
            bottom.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, LocaleController.isRTL ? 76 : 16, 0, LocaleController.isRTL ? 16 : 76, bottomInset + 14));

            progressView = new View(context);
            progressView.setBackgroundColor(0xCCFFFFFF);
            progressView.setPivotX(LocaleController.isRTL ? AndroidUtilities.displaySize.x : 0);
            progressView.setScaleX(0f);
            addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.BOTTOM, 0, 0, 0, bottomInset));

            final GestureDetector detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    togglePause();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (post != null) {
                        if (!heartChosen(post.primary()) && !toggleHeart(post)) {
                            return true;
                        }
                        showBigHeart();
                    }
                    return true;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    if (post != null) {
                        showReactionPicker(post, e.getRawX(), e.getRawY());
                    }
                }
            });
            setOnTouchListener((v, event) -> detector.onTouchEvent(event));
        }

        private int bottomBarSpace() {
            // dp; the tab bar floats above the system navigation bar, and the reel runs behind both
            final int navigation = (int) Math.ceil(AndroidUtilities.navigationBarHeight / AndroidUtilities.density);
            return (hasMainTabs ? DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 8 : 16) + navigation;
        }

        private int appliedBottomSpace = -1;

        /** The navigation bar can change (rotation, gesture vs buttons): keep the side actions and caption above it. */
        private void updateBottomSpace() {
            final int space = bottomBarSpace();
            if (space == appliedBottomSpace) {
                return;
            }
            appliedBottomSpace = space;
            setBottomMargin(actions, dp(space + 12));
            setBottomMargin(bottom, dp(space + 14));
            setBottomMargin(progressView, dp(space));
        }

        private void setBottomMargin(View view, int margin) {
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (params.bottomMargin != margin) {
                params.bottomMargin = margin;
                view.setLayoutParams(params);
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateBottomSpace();
        }

        private ImageView actionIcon(Context context, int icon) {
            final ImageView view = new ImageView(context);
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setImageResource(icon);
            view.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            return view;
        }

        private TextView countView(Context context) {
            final TextView view = new TextView(context);
            view.setTextColor(0xFFFFFFFF);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            view.setTypeface(AndroidUtilities.bold());
            view.setShadowLayer(dp(2), 0, 0, 0x66000000);
            return view;
        }

        void bind(FeedPost post) {
            if (this.post != post) {
                release();
                userPaused = false;
            }
            this.post = post;
            captionExpanded = false;
            captionView.setMaxLines(2);
            final MessageObject message = post.primary();
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
            // like the video: tall clips fill the screen, wide ones keep bars
            thumbView.getImageReceiver().setAspectFit(size == null || size.h < size.w * 1.25f);
            thumbView.setImage(ImageLocation.getForObject(size, message.photoThumbsObject), "1000_1000", (android.graphics.drawable.Drawable) null, message.photoThumbsObject);
            final AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(currentAccount, post.chat);
            avatarView.setForUserOrChat(post.chat, avatarDrawable);
            avatarView.setContentDescription(post.chat.title);
            nameView.setText(post.chat.title);
            bindFollow();
            if (TextUtils.isEmpty(post.text)) {
                captionView.setVisibility(GONE);
            } else {
                captionView.setVisibility(VISIBLE);
                captionView.setText(post.text);
            }
            setContentDescription(post.chat.title + (TextUtils.isEmpty(post.text) ? "" : ", " + post.text));
            progressView.setScaleX(0f);
            bindActions();
            updatePaused();
        }

        void bindActions() {
            if (post == null || post.messages.isEmpty()) {
                return;
            }
            final MessageObject message = post.primary();
            final boolean liked = heartChosen(message);
            heartButton.setImageResource(liked ? R.drawable.tigi_heart_filled_24 : R.drawable.tigi_heart_outline_24);
            if (liked) {
                heartButton.clearColorFilter();
            } else {
                heartButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            }
            heartButton.setContentDescription(LocaleController.getString(liked ? R.string.TigiUnlike : R.string.TigiLike));
            final int hearts = heartCount(message);
            heartCount.setText(hearts > 0 ? LocaleController.formatShortNumber(hearts, null) : "");
            final int comments = message.messageOwner.replies != null ? message.messageOwner.replies.replies : 0;
            commentCount.setText(comments > 0 ? LocaleController.formatShortNumber(comments, null) : "");
        }

        private void bindFollow() {
            final TLRPC.Chat chat = post != null ? getMessagesController().getChat(post.chat.id) : null;
            final int visibility = chat != null && ChatObject.isNotInChat(chat) ? VISIBLE : GONE;
            followButton.setVisibility(visibility);
            followBadge.setVisibility(visibility);
        }

        private void follow() {
            final TLRPC.Chat chat = post != null ? getMessagesController().getChat(post.chat.id) : null;
            if (chat == null || !ChatObject.isNotInChat(chat)) {
                return;
            }
            followButton.setVisibility(GONE);
            followBadge.setVisibility(GONE);
            getMessagesController().addUserToChat(chat.id, getUserConfig().getCurrentUser(), 0, null, ReelsActivity.this, true, () -> {
                if (!isDead()) {
                    BulletinFactory.of(ReelsActivity.this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.TigiFollowingNow, chat.title)).show();
                }
            }, error -> {
                bindFollow();
                return true;
            });
        }

        private void openChannel() {
            if (post != null && post.chat != null) {
                TigiNavigation.openProfile(ReelsActivity.this, -post.chat.id);
            }
        }

        void play() {
            if (post == null || inlinePlayer == null) {
                return;
            }
            inlinePlayer.play(this, post.primary(), (msg, rendering, failed) -> {
                if (post != null && msg == post.primary()) {
                    updatePaused();
                    if (rendering) {
                        AndroidUtilities.cancelRunOnUIThread(progressUpdater);
                        AndroidUtilities.runOnUIThread(progressUpdater);
                    }
                }
            });
            updatePaused();
        }

        private void togglePause() {
            if (post == null || inlinePlayer == null) {
                return;
            }
            if (!inlinePlayer.isPlaying(post.primary())) {
                userPaused = false;
                play();
            } else if (inlinePlayer.isPaused()) {
                userPaused = false;
                inlinePlayer.resume();
                AndroidUtilities.runOnUIThread(progressUpdater);
            } else {
                userPaused = true;
                inlinePlayer.pause();
            }
            updatePaused();
        }

        /** The play mark only when the user paused, never while a video is still loading. */
        void updatePaused() {
            final boolean playing = post != null && inlinePlayer != null && inlinePlayer.isPlaying(post.primary()) && !inlinePlayer.isPaused();
            final boolean show = userPaused && !playing;
            if (show == (pausedView.getVisibility() == VISIBLE)) {
                return;
            }
            pausedView.animate().cancel();
            if (show) {
                pausedView.setVisibility(VISIBLE);
                pausedView.setAlpha(0f);
                pausedView.setScaleX(1.4f);
                pausedView.setScaleY(1.4f);
                pausedView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
            } else {
                pausedView.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(140).withEndAction(() -> pausedView.setVisibility(GONE)).start();
            }
        }

        void flashSound(boolean muted) {
            soundFlash.setImageResource(muted ? R.drawable.media_mute : R.drawable.media_unmute);
            soundFlash.animate().cancel();
            soundFlash.setAlpha(1f);
            soundFlash.setScaleX(0.9f);
            soundFlash.setScaleY(0.9f);
            soundFlash.animate().scaleX(1f).scaleY(1f).setDuration(150).withEndAction(() ->
                soundFlash.animate().alpha(0f).setStartDelay(500).setDuration(250).start()).start();
        }

        private void updateProgress() {
            if (post == null || inlinePlayer == null || !inlinePlayer.isPlaying(post.primary()) || inlinePlayer.getPlayer() == null) {
                return;
            }
            final long duration = inlinePlayer.getPlayer().getDuration();
            final long position = inlinePlayer.getPlayer().getCurrentPosition();
            if (duration > 0) {
                progressView.setScaleX(Math.max(0, Math.min(1, position / (float) duration)));
            }
        }

        private void showBigHeart() {
            bigHeart.animate().cancel();
            bigHeart.setVisibility(VISIBLE);
            bigHeart.setAlpha(0f);
            bigHeart.setScaleX(0.4f);
            bigHeart.setScaleY(0.4f);
            bigHeart.animate().alpha(1f).scaleX(1.1f).scaleY(1.1f).setDuration(160).withEndAction(() ->
                bigHeart.animate().alpha(0f).scaleX(1.4f).scaleY(1.4f).setDuration(260).withEndAction(() -> bigHeart.setVisibility(GONE)).start()
            ).start();
            bindActions();
        }

        void release() {
            AndroidUtilities.cancelRunOnUIThread(progressUpdater);
            if (post != null && inlinePlayer != null && inlinePlayer.isPlaying(post.primary())) {
                inlinePlayer.stop();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            release();
        }
    }
}
