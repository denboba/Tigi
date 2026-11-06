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
import java.util.HashMap;

/**
 * Tigi Home: an Instagram-style feed of recent posts from the channels the user is in.
 */
public class HomeFeedActivity extends TigiFeedBase implements MainTabsActivity.TabFragmentDelegate {

    private static final int MAX_CHANNELS = 25;
    private static final int POSTS_PER_CHANNEL = 12;
    private static final int MAX_POSTS = 60;
    private static final long RELOAD_AFTER_MS = 2 * 60 * 1000L;
    private static final int PARALLEL_REQUESTS = 4;
    private static final int DISCOVER_CHANNELS = 8;
    /** Discover only needs a taste of each suggested channel: fewer posts each, so the first screen comes fast. */
    private static final int DISCOVER_POSTS_PER_CHANNEL = 6;

    private boolean loading;
    private int loadGeneration;
    private TextView titleView;
    private long lastLoadTime;

    public HomeFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    protected boolean appliesFeedFilter() {
        return true;
    }

    @Override
    protected void onFeedFilterUndone() {
        lastLoadTime = 0;
        loadFeed();
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
        applyTopInset(bar);
        // Lilac: the corner sprig sits in the bar, under the title and the buttons
        sprigView = TigiLilac.addSprig(bar, 120);
        final TextView title = new TextView(context);
        titleView = title;
        // the floret + "nilak" wordmark, drawn as an image span so TalkBack still reads the app name
        title.setFocusable(true);
        title.setContentDescription(LocaleController.getString(R.string.AppName));
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bindWordmark(title);
        // the wordmark with "For you ⌄" / "Following ⌄" beside it; tapping either switches, like Instagram's logo menu
        final LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 56));
        modeLabel = new TextView(context);
        modeLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        modeLabel.setTypeface(TigiStyle.displayBold());
        modeLabel.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        bindModeLabel();
        titleRow.addView(modeLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 2, 0, 0));
        titleRow.setOnClickListener(v -> pickFeedMode());
        bar.addView(titleRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 0, 16, 0));
        // For you · Following · Read as clear pills under the title (the small "For you ⌄" label hides)
        modeTabs = new LinearLayout(context);
        modeTabs.setOrientation(LinearLayout.HORIZONTAL);
        final int[] modeNames = {R.string.TigiReelsForYou, R.string.NilakFollowing, R.string.NilakRead};
        for (int i = 0; i < modeNames.length; i++) {
            final int index = i;
            final TextView tab = new TextView(context);
            tab.setText(LocaleController.getString(modeNames[i]));
            tab.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tab.setTypeface(TigiStyle.displayBold());
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
            tab.setOnClickListener(v -> onModeTab(index));
            modeTabs.addView(tab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        }
        modeLabel.setVisibility(View.GONE);
        titleRow.setOnClickListener(null);
        titleRow.setClickable(false);
        bar.addView(modeTabs, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 58, 16, 10));
        bindModeTabs();
        searchButton = new ImageView(context);
        searchButton.setScaleType(ImageView.ScaleType.CENTER);
        searchButton.setImageResource(R.drawable.outline_header_search);
        searchButton.setContentDescription(LocaleController.getString(R.string.Search));
        searchButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        searchButton.setOnClickListener(v -> {
            if (BuildVars.TIGI_EXPLORE) {
                presentFragment(new ExploreActivity(null));
                return;
            }
            // without Explore: Telegram's search for chats, channels and messages
            final MainTabsActivity tabs = TigiNavigation.findMainTabs(this);
            if (tabs != null) {
                tabs.openChatsSearch();
            }
        });
        bar.addView(searchButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 6, 4, 6, 0));
        // Create lives here, like Instagram's "+", when the bottom bar has Read in its place
        if (BuildVars.TIGI_READ_TAB && (BuildVars.TIGI_POST || BuildVars.TIGI_LIVE)) {
            createButton = new ImageView(context);
            createButton.setScaleType(ImageView.ScaleType.CENTER);
            createButton.setImageResource(R.drawable.tigi_add_24);
            createButton.setContentDescription(LocaleController.getString(R.string.TigiCreate));
            createButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            createButton.setOnClickListener(v -> TigiCreateSheet.openCompose(this));
            final int offset = BuildVars.TIGI_ACTIVITY ? 102 : 54;
            bar.addView(createButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), LocaleController.isRTL ? offset : 6, 4, LocaleController.isRTL ? 6 : offset, 0));
        }
        // Activity: what happened to the user's posts
        activityButton = new ImageView(context);
        activityButton.setScaleType(ImageView.ScaleType.CENTER);
        activityButton.setImageResource(R.drawable.tigi_bell_24);
        activityButton.setContentDescription(LocaleController.getString(R.string.NilakActivity));
        activityButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        activityButton.setOnClickListener(v -> presentFragment(new TigiActivityActivity()));
        activityButton.setVisibility(BuildVars.TIGI_ACTIVITY ? View.VISIBLE : View.GONE);
        bar.addView(activityButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), LocaleController.isRTL ? 54 : 6, 4, LocaleController.isRTL ? 6 : 54, 0));
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
    private ImageView sprigView;

    /** Settings can turn the flowers off (or on again) while Home is open behind. */
    private void syncSprig() {
        if (titleBar == null || TigiLilac.flowers() == (sprigView != null)) {
            return;
        }
        if (sprigView != null) {
            titleBar.removeView(sprigView);
            sprigView = null;
        } else {
            sprigView = TigiLilac.addSprig(titleBar, 120);
        }
    }

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
    private ImageView activityButton;
    private ImageView createButton;
    private TextView modeLabel;

    private static final String KEY_FOLLOWING = "nilak_home_following";

    /** "For you" (default): the best posts first. "Following": newest first. */
    private boolean followingMode() {
        return org.telegram.messenger.MessagesController.getMainSettings(currentAccount).getBoolean(KEY_FOLLOWING, false);
    }

    private void bindModeLabel() {
        if (modeLabel != null) {
            modeLabel.setText(LocaleController.getString(followingMode() ? R.string.NilakFollowing : R.string.TigiReelsForYou) + " ⌄");
            modeLabel.setContentDescription(LocaleController.getString(R.string.NilakFeedMode) + ": " + LocaleController.getString(followingMode() ? R.string.NilakFollowing : R.string.TigiReelsForYou));
        }
    }

    private LinearLayout modeTabs;

    private void bindModeTabs() {
        if (modeTabs == null) {
            return;
        }
        final boolean following = followingMode();
        final int ink = TigiLilac.on() ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        for (int i = 0; i < modeTabs.getChildCount(); i++) {
            final TextView tab = (TextView) modeTabs.getChildAt(i);
            final boolean on = i == 0 && !following || i == 1 && following;
            tab.setSelected(on);
            tab.setTextColor(on ? 0xFFFFFFFF : ink);
            tab.setBackground(on ? TigiStyle.primaryPill(18)
                : Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(18), TigiLilac.on() ? TigiLilac.chip() : Theme.getColor(Theme.key_windowBackgroundGray), Theme.multAlpha(TigiStyle.accent(), 0.15f)));
        }
    }

    private void onModeTab(int index) {
        if (index == 2) {
            presentFragment(TigiTopicFeedActivity.of(TigiTopicFeedActivity.MODE_READ));
            return;
        }
        final boolean wantFollowing = index == 1;
        if (wantFollowing == followingMode()) {
            if (layoutManager != null) {
                layoutManager.scrollToPositionWithOffset(0, 0);
            }
            return;
        }
        org.telegram.messenger.MessagesController.getMainSettings(currentAccount).edit().putBoolean(KEY_FOLLOWING, wantFollowing).apply();
        bindModeLabel();
        bindModeTabs();
        lastLoadTime = 0;
        loadFeed();
        if (layoutManager != null) {
            layoutManager.scrollToPositionWithOffset(0, 0);
        }
    }

    private void pickFeedMode() {
        if (getParentActivity() == null) {
            return;
        }
        final boolean following = followingMode();
        // Read (articles) sits here too while it has no tab of its own
        final boolean readHere = !BuildVars.TIGI_READ_TAB;
        final CharSequence[] items = readHere ? new CharSequence[]{
            (following ? "" : "✓  ") + LocaleController.getString(R.string.TigiReelsForYou),
            (following ? "✓  " : "") + LocaleController.getString(R.string.NilakFollowing),
            "     " + LocaleController.getString(R.string.NilakRead)
        } : new CharSequence[]{
            (following ? "" : "✓  ") + LocaleController.getString(R.string.TigiReelsForYou),
            (following ? "✓  " : "") + LocaleController.getString(R.string.NilakFollowing)
        };
        final org.telegram.ui.ActionBar.BottomSheet.Builder builder = new org.telegram.ui.ActionBar.BottomSheet.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NilakFeedMode));
        builder.setItems(items, (dialog, which) -> {
            if (which == 2) {
                presentFragment(TigiTopicFeedActivity.of(TigiTopicFeedActivity.MODE_READ));
                return;
            }
            final boolean wantFollowing = which == 1;
            if (wantFollowing == followingMode()) {
                return;
            }
            org.telegram.messenger.MessagesController.getMainSettings(currentAccount).edit().putBoolean(KEY_FOLLOWING, wantFollowing).apply();
            bindModeLabel();
            lastLoadTime = 0;
            loadFeed();
            if (layoutManager != null) {
                layoutManager.scrollToPositionWithOffset(0, 0);
            }
        });
        showDialog(builder.create());
    }

    /**
     * Articles (Nilak articles, Instant View pages, long posts) are taken out and placed back every few posts, in
     * an order that changes each day, so the feed mixes reading with photos and video instead of burying them.
     */
    private void spreadArticles(ArrayList<FeedPost> result) {
        final ArrayList<FeedPost> articles = new ArrayList<>();
        for (int i = result.size() - 1; i >= 0; i--) {
            if (TigiTopicFeedActivity.isArticle(result.get(i))) {
                articles.add(0, result.remove(i));
            }
        }
        if (articles.isEmpty()) {
            return;
        }
        Collections.shuffle(articles, new java.util.Random(getConnectionsManager().getCurrentTime() / 86400 + currentAccount));
        int position = Math.min(2, result.size());
        for (FeedPost article : articles) {
            result.add(Math.min(position, result.size()), article);
            position += 5;
        }
    }

    /** For you: views (log scale) lifted by how often the user opens the channel, fading over two days. */
    private double forYouScore(FeedPost post) {
        final int now = getConnectionsManager().getCurrentTime();
        final double hoursOld = Math.max(0, (now - post.date) / 3600.0);
        final TLRPC.Chat own = TigiPosting.ownChannel(currentAccount);
        if (own != null && post.chat != null && post.chat.id == own.id && hoursOld < 6) {
            // the user's own fresh post stays on top for a while
            return 1000 - hoursOld;
        }
        final int views = Math.max(post.primary().messageOwner.views, 1);
        final int affinity = post.chat != null ? TigiFeedPrefs.affinityOf(currentAccount, post.chat.id) : 0;
        return Math.log10(views + 10) * (1 + 0.12 * Math.min(affinity, 20)) / (1 + hoursOld / 24.0);
    }
    private LinearLayout briefCard;
    private TextView briefTitle;

    private static final int BRIEF_MIN_UNREAD = 5;

    /** "312 new posts in your channels · Open brief": shown while the channels hold enough unread posts. */
    private LinearLayout createBriefCard(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        final android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{TigiStyle.PRIMARY, TigiStyle.PRIMARY_PRESSED});
        background.setCornerRadius(AndroidUtilities.dp(18));
        card.setBackground(background);
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView kicker = new TextView(context);
        kicker.setText(LocaleController.getString(R.string.NilakBriefKicker));
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        kicker.setTypeface(TigiStyle.displayBold());
        kicker.setTextColor(0xCCFFFFFF);
        texts.addView(kicker);
        briefTitle = new TextView(context);
        briefTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        briefTitle.setTypeface(TigiStyle.displayExtraBold());
        briefTitle.setTextColor(0xFFFFFFFF);
        texts.addView(briefTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));
        final TextView open = new TextView(context);
        open.setText(LocaleController.getString(R.string.NilakOpenBrief));
        open.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        open.setTypeface(TigiStyle.displayBold());
        open.setTextColor(TigiStyle.PRIMARY);
        open.setGravity(Gravity.CENTER);
        open.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        open.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(17), 0xFFFFFFFF));
        card.addView(open, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        card.setOnClickListener(v -> presentFragment(TigiTopicFeedActivity.of(TigiTopicFeedActivity.MODE_BRIEF)));
        card.setVisibility(View.GONE);
        return card;
    }

    private void updateBriefCard() {
        if (briefCard == null) {
            return;
        }
        int unread = 0;
        for (TLRPC.Chat chat : TigiPosting.followedChannels(currentAccount)) {
            final TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(-chat.id);
            if (dialog != null) {
                unread += dialog.unread_count;
            }
        }
        final boolean show = unread >= BRIEF_MIN_UNREAD;
        if (show) {
            briefTitle.setText(LocaleController.formatPluralString("NilakBriefNewPosts", unread));
            briefCard.setContentDescription(briefTitle.getText() + ", " + LocaleController.getString(R.string.NilakOpenBrief));
        }
        briefCard.setVisibility(show ? View.VISIBLE : View.GONE);
    }
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
        if (modeTabs != null) {
            modeTabs.setAlpha(shown);
            modeTabs.setEnabled(shown > 0.5f);
        }
        searchButton.setAlpha(shown);
        searchButton.setEnabled(shown > 0.5f);
        activityButton.setAlpha(shown);
        activityButton.setEnabled(shown > 0.5f);
    }

    @Override
    protected void updateHeaderColors() {
        final boolean lilac = TigiLilac.on();
        final int background = Theme.getColor(Theme.key_windowBackgroundWhite);
        if (titleBar != null) {
            if (lilac) {
                // the bar carries the top of the page, so it reads as one surface with the glow behind the sprig
                titleBar.setBackground(TigiLilac.pageBar());
            } else {
                titleBar.setBackgroundColor(background);
            }
        }
        if (scrollingPart != null) {
            if (lilac) {
                scrollingPart.setBackground(null);
            } else {
                scrollingPart.setBackgroundColor(background);
            }
        }
        final int icon = lilac ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        for (ImageView button : new ImageView[]{activityButton, searchButton, createButton}) {
            if (button != null) {
                button.setBackground(lilac
                    ? new android.graphics.drawable.InsetDrawable(TigiLilac.roundButton(), AndroidUtilities.dp(4))
                    : Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
                button.setColorFilter(new android.graphics.PorterDuffColorFilter(icon, android.graphics.PorterDuff.Mode.SRC_IN));
            }
        }
        if (suggestedRow != null) {
            suggestedRow.updateColors();
        }
        if (titleView != null) {
            bindWordmark(titleView);
        }
        if (modeLabel != null) {
            modeLabel.setTextColor(lilac ? TigiLilac.meta() : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
        bindModeTabs();
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
        syncSprig();
        if (storiesRow != null) {
            // read state changes while the story viewer was open
            storiesRow.update();
        }
        updateBriefCard();
        org.telegram.messenger.TigiBriefReminder.schedule(getParentActivity());
        if (org.telegram.messenger.TigiBriefReminder.pendingOpen) {
            org.telegram.messenger.TigiBriefReminder.pendingOpen = false;
            // from the daily reminder: For you, from the top
            AndroidUtilities.runOnUIThread(() -> {
                if (!isDead()) {
                    final MainTabsActivity tabs = TigiNavigation.findMainTabs(this);
                    if (tabs != null) {
                        tabs.openHomeTab();
                    }
                    if (layoutManager != null) {
                        layoutManager.scrollToPositionWithOffset(0, 0);
                    }
                }
            }, 300);
        }
        if (BuildVars.TIGI_POST) {
            TigiPosting.archiveOwnChannelOnce(currentAccount);
            TigiPosting.setPersonalChannelOnce(currentAccount);
            TigiPosting.ensureNilakIdentity(currentAccount);
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
        // the user's own channel counts as following nothing (Discover still shows), but its posts join the feed
        final TLRPC.Chat own = TigiPosting.ownChannel(currentAccount);
        if (own != null) {
            channels.remove(own);
        }
        noChannels = channels.isEmpty() && (!dialogs.isEmpty() || getMessagesController().dialogsLoaded);
        if (!channels.isEmpty() && own != null && !own.left) {
            // archived, so its dialog may not be loaded with the chat list: added by hand
            channels.add(0, own);
        }
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
            final ArrayList<TLRPC.Chat> list = new ArrayList<>(suggested.subList(0, Math.min(DISCOVER_CHANNELS, suggested.size())));
            final TLRPC.Chat own = TigiPosting.ownChannel(currentAccount);
            if (own != null && !own.left && !list.contains(own)) {
                list.add(0, own);
            }
            loadPostsFrom(list);
        });
    }

    private void loadPostsFrom(ArrayList<TLRPC.Chat> channels) {
        loading = true;
        final int generation = ++loadGeneration;
        updateEmptyView();
        if (posts.isEmpty()) {
            showInstant(channels);
        }
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
            req.limit = discover ? DISCOVER_POSTS_PER_CHANNEL : POSTS_PER_CHANNEL;
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
                    // nothing on screen yet: show the first answers now instead of waiting for every channel
                    if (posts.isEmpty() && !result.isEmpty() && channels.size() - pending[0] == PARALLEL_REQUESTS) {
                        showPartial(new ArrayList<>(result));
                    }
                    startNext[0].run();
                }
            }));
        };
        for (int i = 0; i < PARALLEL_REQUESTS; i++) {
            startNext[0].run();
        }
    }

    /**
     * Instant start: the newest post of each channel is already in memory with the chat list, so the feed shows
     * those at once (newest first) while the full feed loads; finishLoad replaces them.
     */
    private void showInstant(ArrayList<TLRPC.Chat> channels) {
        final ArrayList<FeedPost> instant = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            final TLRPC.Chat chat = channels.get(i);
            final ArrayList<MessageObject> last = getMessagesController().dialogMessage.get(-chat.id);
            if (last == null || last.isEmpty()) {
                continue;
            }
            final ArrayList<TLRPC.Message> messages = new ArrayList<>();
            for (int j = 0; j < last.size(); j++) {
                final MessageObject message = last.get(j);
                if (message != null && message.messageOwner != null && message.getId() > 0) {
                    messages.add(message.messageOwner);
                }
            }
            instant.addAll(buildPosts(chat, messages));
        }
        if (instant.size() < 2) {
            return; // not worth a flash of one card
        }
        Collections.sort(instant, (a, b) -> b.date - a.date);
        showPartial(instant);
    }

    /** The first answers of a load, shown while the rest arrive; the full list replaces them in finishLoad. */
    private void showPartial(ArrayList<FeedPost> result) {
        final boolean wasLoading = loading;
        finishLoad(result);
        loading = wasLoading;
        updateEmptyView();
    }

    private void finishLoad(ArrayList<FeedPost> result) {
        loading = false;
        lastLoadTime = System.currentTimeMillis();
        if (isDead()) {
            return;
        }
        // hidden channels and "Not interested" posts stay out
        final TigiFeedPrefs.Filter filter = TigiFeedPrefs.filter(currentAccount);
        for (int i = result.size() - 1; i >= 0; i--) {
            final FeedPost item = result.get(i);
            if (item.chat != null && !item.messages.isEmpty() && filter.skips(item.chat.id, item.primary().getId())) {
                result.remove(i);
            }
        }
        if (followingMode()) {
            Collections.sort(result, (a, b) -> b.date - a.date);
        } else {
            // the same post reposted in several channels shows once, then the best first
            final java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int i = result.size() - 1; i >= 0; i--) {
                final MessageObject first = result.get(i).primary();
                final String key = first.getDocument() != null ? "d" + first.getDocument().id
                    : first.messageOwner.media != null && first.messageOwner.media.photo != null ? "p" + first.messageOwner.media.photo.id
                    : "t" + (result.get(i).text != null ? result.get(i).text.toString().trim() : result.get(i).chat.id + ":" + first.getId());
                if (!seen.add(key)) {
                    result.remove(i);
                }
            }
            final HashMap<FeedPost, Double> scores = new HashMap<>();
            for (FeedPost item : result) {
                scores.put(item, forYouScore(item));
            }
            Collections.sort(result, (a, b) -> Double.compare(scores.get(b), scores.get(a)));
            spreadArticles(result);
        }
        final ArrayList<FeedPost> unsent = unsentPosts();
        posts.clear();
        posts.addAll(unsent);
        posts.addAll(result.size() > MAX_POSTS ? result.subList(0, MAX_POSTS) : result);
        adapter.notifyDataSetChanged();
        updateEmptyView();
        prefetchOnWifi(posts);
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
            updateBriefCard();
            // on a cold start the chat list arrives after this tab was created
            if (posts.isEmpty() && lastLoadTime == 0 && !loading && !isDead()) {
                loadFeed();
            }
            return;
        }
        super.didReceivedNotification(id, account, args);
    }
}
