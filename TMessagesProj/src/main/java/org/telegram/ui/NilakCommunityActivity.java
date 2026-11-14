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
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_communities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.JoinGroupAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.community.CommunityChatType;
import org.telegram.ui.community.CommunityEditActivity;
import org.telegram.ui.community.CommunityPendingRequestsActivity;
import org.telegram.ui.community.CommunityUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * A Telegram Community as a Nilak page: Feed (the posts of its channels as Nilak cards), Chats (its linked chats in
 * Telegram's own sections: in, can view, can join, hidden) and About.
 * Joining, requests and every admin tool open Telegram's own screens, so they follow Telegram's updates.
 */
public class NilakCommunityActivity extends TigiFeedBase {

    private static final int TAB_FEED = 0;
    private static final int TAB_ASK = 1;
    private static final int TAB_CHATS = 2;
    private static final int TAB_ABOUT = 3;
    private static final int SORT_HOT = 0;
    private static final int SORT_NEW = 1;
    private static final int SORT_TOP = 2;
    private static final int SORT_UNANSWERED = 3;
    private static final int POSTS_PER_CHANNEL = 10;
    private static final int MAX_POSTS = 60;
    private static final int PREVIEW_LENGTH = 70;

    private long communityId;
    private TLRPC.Chat community;
    private final HashSet<Long> sourceChannels = new HashSet<>();
    private boolean loadingPosts;
    private boolean postsLoaded;
    private int loadGeneration;
    private int pendingLoads;
    private int tab = TAB_FEED;
    private final AlertDialog[] addChatProgress = new AlertDialog[1];

    private LinearLayout headerView;
    private ImageView backButton;
    private ImageView moreButton;
    private TextView titleView;
    private TextView subtitleView;
    private TextView joinButton;
    private final TextView[] tabViews = new TextView[4];
    // Ask: the board (a group with topics in the community) and its questions
    private TLRPC.Chat askBoard;
    private final HashMap<Long, NilakAsk.Question> askKnown = new HashMap<>();
    private ArrayList<NilakAsk.Question> askQuestions = new ArrayList<>();
    private int askSort = SORT_HOT;
    private String askCategory;
    private View tabsLine;
    private LinearLayout shareBar;
    private TextView shareText;
    private ScrollView pageScroll;
    private LinearLayout pageContent;

    /** Opens the Nilak page for a community; false when the feature is off, so the caller keeps Telegram's sheet. */
    public static boolean open(BaseFragment fragment, long communityId) {
        if (!BuildVars.TIGI_COMMUNITIES_TAB || fragment == null || communityId == 0) {
            return false;
        }
        final Bundle args = new Bundle();
        args.putLong("community_id", communityId);
        fragment.presentFragment(new NilakCommunityActivity(args));
        return true;
    }

    public NilakCommunityActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            communityId = arguments.getLong("community_id", 0);
        }
        community = getMessagesController().getChat(communityId);
        if (community == null || !super.onFragmentCreate()) {
            return false;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.topicsDidLoaded);
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateReactions);
        getMessagesController().loadFullChat(communityId, classGuid, true);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.topicsDidLoaded);
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdateReactions);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.topicsDidLoaded) {
            if (askBoard != null && (Long) args[0] == askBoard.id) {
                refreshAsk();
            }
            return;
        }
        if (id == NotificationCenter.didUpdateReactions && askBoard != null && (Long) args[0] == -askBoard.id) {
            // a vote on a question: its count and the Hot and Top order change
            final int msgId = (Integer) args[1];
            for (NilakAsk.Question question : askQuestions) {
                if (question.details != null && question.details.getId() == msgId && args[2] instanceof TLRPC.TL_messageReactions) {
                    MessageObject.updateReactions(question.details.messageOwner, (TLRPC.TL_messageReactions) args[2]);
                    if (tab == TAB_ASK) {
                        bindPage();
                    }
                }
            }
            super.didReceivedNotification(id, account, args);
            return;
        }
        if (id == NotificationCenter.chatInfoDidLoad) {
            final TLRPC.ChatFull info = (TLRPC.ChatFull) args[0];
            if (info != null && info.id == communityId) {
                bindHeader();
                bindPage();
                if (collectSources() || !postsLoaded) {
                    loadPosts();
                }
            }
            return;
        }
        if (id == NotificationCenter.dialogsNeedReload || id == NotificationCenter.updateInterfaces) {
            final TLRPC.Chat current = getMessagesController().getChat(communityId);
            if (current != null) {
                community = current;
            }
            bindHeader();
            if (tab != TAB_FEED) {
                bindPage();
            }
            return;
        }
        super.didReceivedNotification(id, account, args);
    }

    /* header */

    @Override
    protected View createHeaderView(Context context) {
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        headerView = header;
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
        avatar.setRoundRadius(dp(12));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(currentAccount, community);
        avatar.setForUserOrChat(community, avatarDrawable);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        avatar.setOnClickListener(v -> selectTab(TAB_ABOUT));
        row.addView(avatar, LayoutHelper.createLinear(38, 38));

        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleView.setTypeface(TigiStyle.displayBold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        names.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        names.setOnClickListener(v -> selectTab(TAB_ABOUT));
        row.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        // until the person is in one of its chats, Join leads to the chats they can join
        joinButton = new TextView(context);
        joinButton.setText(LocaleController.getString(R.string.TigiJoin));
        joinButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        joinButton.setTypeface(TigiStyle.displayBold());
        joinButton.setTextColor(0xFFFFFFFF);
        joinButton.setGravity(Gravity.CENTER);
        joinButton.setPadding(dp(16), 0, dp(16), 0);
        joinButton.setOnClickListener(v -> selectTab(TAB_CHATS));
        row.addView(joinButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        moreButton = new ImageView(context);
        moreButton.setScaleType(ImageView.ScaleType.CENTER);
        moreButton.setImageResource(R.drawable.ic_ab_other);
        moreButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        moreButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        moreButton.setOnClickListener(v -> showMenu());
        row.addView(moreButton, LayoutHelper.createLinear(48, 56));
        header.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final int[] names2 = {R.string.NilakCommunityFeed, R.string.NilakAsk, R.string.NilakCommunityChats, R.string.NilakCommunityAbout};
        for (int i = 0; i < tabViews.length; i++) {
            final int index = i;
            final TextView view = new TextView(context);
            view.setText(LocaleController.getString(names2[i]));
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            view.setTypeface(TigiStyle.displayBold());
            view.setGravity(Gravity.CENTER);
            view.setOnClickListener(v -> selectTab(index));
            tabViews[i] = view;
            tabs.addView(view, LayoutHelper.createLinear(0, 44, 1f));
        }
        header.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 8, 0, 8, 0));
        tabsLine = new View(context);
        header.addView(tabsLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        // Feed: write in one of the community's chats (Telegram's compose screen opens there)
        shareBar = new LinearLayout(context);
        shareBar.setOrientation(LinearLayout.HORIZONTAL);
        shareBar.setGravity(Gravity.CENTER_VERTICAL);
        shareBar.setPadding(dp(16), 0, dp(12), 0);
        shareBar.setOnClickListener(v -> NilakCommunitySheets.showPostTo(this, communityId));
        shareText = new TextView(context);
        shareText.setText(LocaleController.getString(R.string.NilakCommunityShare));
        shareText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        shareText.setSingleLine(true);
        shareBar.addView(shareText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        final ImageView sharePhoto = new ImageView(context);
        sharePhoto.setImageResource(R.drawable.msg_photos);
        sharePhoto.setScaleType(ImageView.ScaleType.CENTER);
        sharePhoto.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shareBar.addView(sharePhoto, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL));
        shareBar.setContentDescription(shareText.getText());
        header.addView(shareBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 12, 10, 12, 4));

        updateHeaderColors();
        bindHeader();
        return header;
    }

    @Override
    protected void updateHeaderColors() {
        if (headerView == null) {
            return;
        }
        if (TigiLilac.on()) {
            headerView.setBackground(TigiLilac.pageBar());
        } else {
            headerView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        final int ink = inkColor();
        backButton.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
        moreButton.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
        titleView.setTextColor(ink);
        subtitleView.setTextColor(metaColor());
        joinButton.setBackground(TigiStyle.primaryPill(18));
        tabsLine.setBackgroundColor(TigiLilac.on() ? TigiLilac.inset() : Theme.getColor(Theme.key_divider));
        shareBar.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(22), cardColor(), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
        shareText.setTextColor(metaColor());
        ((ImageView) shareBar.getChildAt(1)).setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
        bindTabs();
        if (pageContent != null) {
            bindPage();
        }
    }

    private void bindHeader() {
        if (titleView == null || community == null) {
            return;
        }
        titleView.setText(community.title);
        final TLRPC.ChatFull info = getMessagesController().getChatFull(communityId);
        final int chats = info != null && info.linked_peers != null ? info.linked_peers.size() : 0;
        subtitleView.setText(chats > 0 ? LocaleController.formatPluralString("CommunityWithChats", chats) : LocaleController.getString(R.string.Community));
        final MessagesController.CommunityPeersDialog peers = getMessagesController().buildCommunityPeers(communityId);
        final boolean isIn = peers != null && !peers.chatsYouAreIn.isEmpty();
        joinButton.setVisibility(info != null && !isIn && tab != TAB_CHATS ? View.VISIBLE : View.GONE);
        shareBar.setVisibility(tab == TAB_FEED && NilakCommunitySheets.hasChatsToPostIn(currentAccount, communityId) ? View.VISIBLE : View.GONE);
    }

    private void bindTabs() {
        final int accent = TigiStyle.accent();
        for (int i = 0; i < tabViews.length; i++) {
            final boolean on = i == tab;
            tabViews[i].setTextColor(on ? accent : metaColor());
            tabViews[i].setBackground(on ? new UnderlineDrawable(accent) : null);
            tabViews[i].setSelected(on);
        }
    }

    private void selectTab(int index) {
        if (tab == index) {
            if (index == TAB_FEED && listView != null) {
                listView.smoothScrollToPosition(0);
            }
            return;
        }
        tab = index;
        if (tab == TAB_ASK) {
            refreshAsk();
        }
        bindTabs();
        bindHeader();
        final boolean feed = tab == TAB_FEED;
        if (listView != null) {
            listView.setVisibility(feed ? View.VISIBLE : View.GONE);
        }
        if (pageScroll != null) {
            pageScroll.setVisibility(feed ? View.GONE : View.VISIBLE);
            pageScroll.scrollTo(0, 0);
        }
        if (!feed && inlinePlayer != null) {
            inlinePlayer.stop();
        }
        updateEmptyView();
        bindPage();
    }

    private void showMenu() {
        if (getParentActivity() == null || community == null) {
            return;
        }
        final ArrayList<CharSequence> items = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        if (ChatObject.hasAdminRights(community)) {
            items.add(LocaleController.getString(R.string.CommunityMenuSettings));
            actions.add(this::openSettings);
        }
        if (ChatObject.canAddChatToCommunity(community)) {
            items.add(LocaleController.getString(R.string.CommunityMenuAddChat));
            actions.add(this::addChat);
        }
        final boolean collapsed = community.collapsed_in_dialogs;
        items.add(LocaleController.getString(collapsed ? R.string.CommunityMenuShowAsSeparateChats : R.string.CommunityMenuShowAsOneChat));
        actions.add(() -> getMessagesController().toggleCommunityCollapsedInDialogs(communityId, !collapsed));
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setItems(items.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run());
        showDialog(builder.create());
    }

    private void openSettings() {
        final Bundle args = new Bundle();
        args.putLong("community_id", communityId);
        presentFragment(new CommunityEditActivity(args));
    }

    private void addChat() {
        CommunityUtils.showChatsToAddToCommunity(addChatProgress, this, currentAccount, community);
    }

    /* Chats and About, in a scroll view over the feed */

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        final ViewGroup body = (ViewGroup) listView.getParent();
        pageScroll = new ScrollView(context);
        pageScroll.setClipToPadding(false);
        pageScroll.setPadding(0, dp(12), 0, dp(24) + AndroidUtilities.navigationBarHeight);
        pageScroll.setVisibility(View.GONE);
        pageContent = new LinearLayout(context);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageScroll.addView(pageContent, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(pageScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return view;
    }

    private void bindPage() {
        if (pageContent == null) {
            return;
        }
        pageContent.removeAllViews();
        if (tab == TAB_ASK) {
            bindAsk();
        } else if (tab == TAB_CHATS) {
            bindChats();
        } else if (tab == TAB_ABOUT) {
            bindAbout();
        }
    }

    /* Ask */

    /** Finds the board and asks Telegram for its topics; the list fills in as topics and details arrive. */
    private void refreshAsk() {
        askBoard = NilakAsk.findBoard(currentAccount, communityId);
        if (askBoard == null) {
            askQuestions = new ArrayList<>();
        } else {
            if (getMessagesController().getTopicsController().getTopics(askBoard.id) == null) {
                getMessagesController().getTopicsController().loadTopics(askBoard.id);
            }
            askQuestions = NilakAsk.questionsOf(currentAccount, askBoard, askKnown);
            NilakAsk.loadDetails(this, askBoard, askQuestions, () -> {
                if (tab == TAB_ASK) {
                    bindPage();
                }
            });
        }
        if (tab == TAB_ASK) {
            bindPage();
        }
    }

    private void bindAsk() {
        final Context context = pageContent.getContext();
        if (askBoard == null) {
            final LinearLayout card = card();
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(20), dp(22), dp(20), dp(20));
            final TextView title = new TextView(context);
            title.setText(LocaleController.getString(R.string.NilakAskEmptyTitle));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            title.setTypeface(TigiStyle.displayExtraBold());
            title.setTextColor(inkColor());
            title.setGravity(Gravity.CENTER);
            card.addView(title);
            final TextView text = new TextView(context);
            text.setText(LocaleController.getString(R.string.NilakAskEmptyText));
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            text.setTextColor(metaColor());
            text.setGravity(Gravity.CENTER);
            card.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            pageContent.addView(card, cardParams());
            return;
        }

        // sorting and categories
        final LinearLayout sorts = chipRow(context);
        final int[] sortNames = {R.string.NilakAskHot, R.string.NilakAskNew, R.string.NilakAskTop, R.string.NilakAskUnanswered};
        for (int i = 0; i < sortNames.length; i++) {
            final int sort = i;
            sorts.addView(chip(context, LocaleController.getString(sortNames[i]), askSort == sort, () -> {
                askSort = sort;
                bindPage();
            }));
        }
        pageContent.addView(wrapChips(sorts), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        final java.util.LinkedHashSet<String> categories = new java.util.LinkedHashSet<>();
        for (NilakAsk.Question question : askQuestions) {
            if (question.category != null) {
                categories.add(question.category);
            }
        }
        if (!categories.isEmpty()) {
            final LinearLayout cats = chipRow(context);
            cats.addView(chip(context, LocaleController.getString(R.string.NilakAskAll), askCategory == null, () -> {
                askCategory = null;
                bindPage();
            }));
            for (String category : categories) {
                cats.addView(chip(context, "#" + category, category.equals(askCategory), () -> {
                    askCategory = category;
                    bindPage();
                }));
            }
            pageContent.addView(wrapChips(cats), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }

        // Ask: Telegram's new topic screen in the board
        final TextView ask = new TextView(context);
        ask.setText(LocaleController.getString(R.string.NilakAskButton));
        ask.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        ask.setTypeface(TigiStyle.displayBold());
        ask.setTextColor(0xFFFFFFFF);
        ask.setGravity(Gravity.CENTER);
        ask.setBackground(TigiStyle.primaryPill(22));
        ask.setOnClickListener(v -> askQuestion());
        pageContent.addView(ask, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 12, 0, 12, 12));

        final ArrayList<NilakAsk.Question> shown = new ArrayList<>();
        for (NilakAsk.Question question : askQuestions) {
            if (askCategory != null && !askCategory.equals(question.category)) {
                continue;
            }
            if (askSort == SORT_UNANSWERED && question.answers != 0) {
                continue;
            }
            shown.add(question);
        }
        final int now = getConnectionsManager().getCurrentTime();
        if (askSort == SORT_HOT) {
            Collections.sort(shown, (a, b) -> Double.compare(b.hot(now), a.hot(now)));
        } else if (askSort == SORT_TOP) {
            Collections.sort(shown, (a, b) -> Integer.compare(b.votes(), a.votes()));
        }
        for (NilakAsk.Question question : shown) {
            pageContent.addView(questionRow(question), cardParams());
        }
        if (shown.isEmpty()) {
            pageContent.addView(note(LocaleController.getString(getMessagesController().getTopicsController().getTopics(askBoard.id) == null
                ? R.string.Loading : R.string.NilakAskNoQuestions)), cardParams());
        }
    }

    private void askQuestion() {
        if (askBoard == null) {
            return;
        }
        if (ChatObject.isNotInChat(askBoard)) {
            // join the board first, in its chat
            final Bundle args = new Bundle();
            args.putLong("chat_id", askBoard.id);
            presentFragment(new ChatActivity(args));
            return;
        }
        final java.util.LinkedHashSet<String> categories = new java.util.LinkedHashSet<>();
        for (NilakAsk.Question question : askQuestions) {
            if (question.category != null) {
                categories.add(question.category);
            }
        }
        final TLRPC.Chat board = askBoard;
        NilakAsk.showAskSheet(this, board, categories, topicId -> NilakQuestionActivity.open(this, board.id, topicId));
    }

    private View questionRow(NilakAsk.Question question) {
        final Context context = pageContent.getContext();
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(10), dp(12), dp(14), dp(12));
        card.setBackground(Theme.createRoundRectDrawable(dp(cardRadius()), cardColor()));
        card.setOnClickListener(v -> NilakQuestionActivity.open(this, askBoard.id, question.topic.id));

        // ▲ and the votes; a tap votes without opening the question
        final LinearLayout vote = new LinearLayout(context);
        vote.setOrientation(LinearLayout.VERTICAL);
        vote.setGravity(Gravity.CENTER_HORIZONTAL);
        final boolean voted = question.voted();
        final TextView arrow = new TextView(context);
        arrow.setText("▲");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        arrow.setTextColor(voted ? TigiStyle.accent() : metaColor());
        arrow.setGravity(Gravity.CENTER);
        vote.addView(arrow);
        final TextView votes = new TextView(context);
        votes.setText(LocaleController.formatShortNumber(question.votes(), null));
        votes.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        votes.setTypeface(TigiStyle.displayBold());
        votes.setTextColor(voted ? TigiStyle.accent() : metaColor());
        votes.setGravity(Gravity.CENTER);
        vote.addView(votes);
        vote.setContentDescription(LocaleController.getString(R.string.NilakAskVote));
        if (question.details != null) {
            vote.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.12f), dp(12), dp(12)));
            vote.setOnClickListener(v -> {
                NilakAsk.toggleVote(this, question.details);
                bindPage();
            });
        }
        card.addView(vote, LayoutHelper.createLinear(42, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(question.topic.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        title.setTypeface(TigiStyle.displayBold());
        title.setTextColor(inkColor());
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        if (question.details != null && !TextUtils.isEmpty(question.details.messageText)) {
            final TextView excerpt = new TextView(context);
            excerpt.setText(question.details.messageText.toString().replace('\n', ' '));
            excerpt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            excerpt.setTextColor(metaColor());
            excerpt.setMaxLines(2);
            excerpt.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(excerpt, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }
        final TextView meta = new TextView(context);
        final StringBuilder line = new StringBuilder();
        if (question.category != null) {
            line.append('#').append(question.category).append("  ·  ");
        }
        line.append(LocaleController.stringForMessageListDate(question.topic.date));
        if (question.answers >= 0) {
            line.append("  ·  ").append(LocaleController.formatPluralString("NilakAskAnswers", question.answers));
        }
        meta.setText(line);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        meta.setTextColor(metaColor());
        texts.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 6, 0, 0, 0));
        card.setContentDescription(question.topic.title);
        return card;
    }

    private LinearLayout chipRow(Context context) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        return row;
    }

    private View wrapChips(LinearLayout row) {
        final android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(row.getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private View chip(Context context, String text, boolean on, Runnable action) {
        final TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTypeface(TigiStyle.displayBold());
        chip.setTextColor(on ? 0xFFFFFFFF : inkColor());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setBackground(on ? TigiStyle.primaryPill(16) : Theme.createRoundRectDrawable(dp(16), cardColor()));
        chip.setOnClickListener(v -> action.run());
        final LinearLayout.LayoutParams params = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 6, 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private void bindChats() {
        final TLRPC.ChatFull info = getMessagesController().getChatFull(communityId);
        if (ChatObject.canAddChatToCommunity(community) || info != null && info.requests_pending > 0 && ChatObject.hasAdminRights(community)) {
            final LinearLayout admin = card();
            if (ChatObject.canAddChatToCommunity(community)) {
                admin.addView(actionRow(R.drawable.msg_add, LocaleController.getString(R.string.CommunityAddAChatToCommunity), null, true, this::addChat));
            }
            if (info != null && info.requests_pending > 0 && ChatObject.hasAdminRights(community)) {
                admin.addView(actionRow(R.drawable.community_requests_outline_24, LocaleController.getString(R.string.CommunityPendingRequests),
                    LocaleController.formatShortNumber(info.requests_pending, null), false, this::openRequests));
            }
            pageContent.addView(admin, cardParams());
        }
        final MessagesController.CommunityPeersDialog peers = getMessagesController().buildCommunityPeers(communityId);
        if (peers == null || peers.getDialogsCount() == 0) {
            pageContent.addView(note(LocaleController.getString(info == null ? R.string.Loading : R.string.CommunityNoChatsToAdd)), cardParams());
            return;
        }
        addSection(R.string.CommunitySectionChatsYouAreIn, peers.chatsYouAreIn);
        addSection(R.string.CommunitySectionChatsYouCanView, peers.chatsYouCanView);
        addSection(R.string.CommunitySectionChatsYouCanRequestToJoin, peers.chatsYouCanJoin);
        addSection(R.string.CommunitySectionHiddenChats, peers.chatsOther);
    }

    private void addSection(int title, ArrayList<MessagesController.CommunityPeerDialog> chats) {
        if (chats.isEmpty()) {
            return;
        }
        final LinearLayout card = card();
        final TextView header = new TextView(card.getContext());
        header.setText(LocaleController.getString(title));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        header.setTypeface(TigiStyle.displayBold());
        header.setTextColor(TigiStyle.accent());
        header.setPadding(dp(10), dp(6), dp(10), dp(2));
        androidx.core.view.ViewCompat.setAccessibilityHeading(header, true);
        card.addView(header);
        for (int i = 0; i < chats.size(); i++) {
            card.addView(chatRow(chats.get(i)));
        }
        pageContent.addView(card, cardParams());
    }

    private View chatRow(MessagesController.CommunityPeerDialog peer) {
        final Context context = pageContent.getContext();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.10f), dp(14), dp(14)));
        row.setOnClickListener(v -> openPeer(peer));

        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(22));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (peer.chat != null) {
            avatarDrawable.setInfo(currentAccount, peer.chat);
            avatar.setForUserOrChat(peer.chat, avatarDrawable);
        } else {
            avatarDrawable.setInfo(currentAccount, peer.user);
            avatar.setForUserOrChat(peer.user, avatarDrawable);
        }
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, LayoutHelper.createLinear(44, 44));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(DialogObject.getName(peer.chat != null ? peer.chat : peer.user));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(inkColor());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        final TextView sub = new TextView(context);
        sub.setText(peerSubtitle(peer));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setTextColor(metaColor());
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 8, 0));

        final CommunityChatType type = CommunityUtils.getCommunityChatType(peer.chat, peer.user, peer.dialog, peer.peer);
        final View end;
        if (type == CommunityChatType.YouAreIn) {
            final int unread = peer.dialog != null ? getMessagesController().getDialogUnreadCount(peer.dialog) : 0;
            final TextView badge = new TextView(context);
            if (unread > 0) {
                badge.setText(LocaleController.formatShortNumber(unread, null));
                badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                badge.setTypeface(AndroidUtilities.bold());
                badge.setTextColor(0xFFFFFFFF);
                badge.setGravity(Gravity.CENTER);
                badge.setMinWidth(dp(22));
                badge.setPadding(dp(6), 0, dp(6), 0);
                final boolean muted = getMessagesController().isDialogMuted(peer.dialogId, 0);
                badge.setBackground(Theme.createRoundRectDrawable(dp(11), muted ? Theme.multAlpha(metaColor(), 0.55f) : TigiStyle.accent()));
            }
            end = badge;
        } else if (type == CommunityChatType.HiddenUnavailable) {
            end = null;
        } else {
            final TextView action = new TextView(context);
            action.setText(LocaleController.getString(type == CommunityChatType.YouCanSendJoinRequest ? R.string.NilakCommunityRequest
                : peer.user != null ? R.string.Open : R.string.TigiJoin));
            action.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            action.setTypeface(TigiStyle.displayBold());
            action.setTextColor(TigiStyle.accent());
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(14), 0, dp(14), 0);
            action.setBackground(TigiStyle.tintedPill(16));
            action.setOnClickListener(v -> openPeer(peer));
            end = action;
        }
        if (end != null) {
            row.addView(end, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, end instanceof TextView && type == CommunityChatType.YouAreIn ? 22 : 32, Gravity.CENTER_VERTICAL));
        }
        row.setContentDescription(name.getText() + ", " + sub.getText());
        return row;
    }

    private CharSequence peerSubtitle(MessagesController.CommunityPeerDialog peer) {
        final CommunityChatType type = CommunityUtils.getCommunityChatType(peer.chat, peer.user, peer.dialog, peer.peer);
        if (type == CommunityChatType.YouAreIn && peer.dialog != null) {
            final CharSequence last = lastMessage(peer.dialogId);
            if (!TextUtils.isEmpty(last)) {
                return last;
            }
        }
        if (peer.user != null) {
            return LocaleController.getString(R.string.NilakCommunityBot);
        }
        final boolean channel = ChatObject.isChannelAndNotMegaGroup(peer.chat);
        final String kind = LocaleController.getString(channel ? R.string.AccDescrChannel : R.string.AccDescrGroup);
        if (peer.chat != null && peer.chat.participants_count > 0) {
            return kind + " · " + LocaleController.formatPluralString(channel ? "Subscribers" : "Members", peer.chat.participants_count);
        }
        return kind;
    }

    private CharSequence lastMessage(long dialogId) {
        final ArrayList<MessageObject> last = getMessagesController().dialogMessage.get(dialogId);
        final MessageObject message = last != null && !last.isEmpty() ? last.get(0) : null;
        if (message == null || message.messageText == null) {
            return null;
        }
        String text = message.messageText.toString().replace('\n', ' ');
        if (text.length() > PREVIEW_LENGTH) {
            text = text.substring(0, PREVIEW_LENGTH) + "…";
        }
        if (message.isOut()) {
            return LocaleController.getString(R.string.FromYou) + ": " + text;
        }
        final TLRPC.Chat chat = dialogId < 0 ? getMessagesController().getChat(-dialogId) : null;
        if (chat != null && !ChatObject.isChannelAndNotMegaGroup(chat)) {
            final TLRPC.User user = getMessagesController().getUser(message.getFromChatId());
            if (user != null) {
                return UserObject.getFirstName(user) + ": " + text;
            }
        }
        return text;
    }

    /** The same as Telegram's community screen: open the chat, ask to join, or explain that it is hidden. */
    private void openPeer(MessagesController.CommunityPeerDialog peer) {
        final CommunityChatType type = CommunityUtils.getCommunityChatType(peer.chat, peer.user, peer.dialog, peer.peer);
        if (type == CommunityChatType.YouAreIn || type == CommunityChatType.YouCanView) {
            final Bundle args = new Bundle();
            if (peer.dialogId > 0) {
                args.putLong("user_id", peer.dialogId);
            } else {
                args.putLong("chat_id", -peer.dialogId);
            }
            if (ChatObject.isForum(peer.chat)) {
                if (ChatObject.areTabsEnabled(peer.chat)) {
                    final ChatActivity activity = new ChatActivity(args);
                    ForumUtilities.applyTopic(activity, MessagesStorage.TopicKey.of(peer.dialogId, getMessagesController().getForumLastTopicId(peer.chat.id)));
                    presentFragment(activity);
                } else {
                    presentFragment(new TopicsFragment(args));
                }
            } else {
                presentFragment(new ChatActivity(args));
            }
        } else if (type == CommunityChatType.YouCanSendJoinRequest && peer.chat != null) {
            new JoinGroupAlert(getParentActivity(), peer.chat, null, this, getResourceProvider()).show();
        } else {
            final boolean channel = ChatObject.isChannelAndNotMegaGroup(peer.chat);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.e_hand_2, LocaleController.getString(channel
                ? R.string.CommunityHiddenChannelUnavailable : R.string.CommunityHiddenGroupUnavailable)).show();
        }
    }

    private void openRequests() {
        final Bundle args = new Bundle();
        args.putLong("community_id", communityId);
        presentFragment(new CommunityPendingRequestsActivity(args));
    }

    private void bindAbout() {
        final TLRPC.ChatFull info = getMessagesController().getChatFull(communityId);
        if (info != null && !TextUtils.isEmpty(info.about)) {
            final LinearLayout about = card();
            final TextView text = new TextView(about.getContext());
            text.setText(info.about);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setTextColor(inkColor());
            text.setLineSpacing(dp(2), 1f);
            text.setTextIsSelectable(true);
            text.setPadding(dp(10), dp(8), dp(10), dp(8));
            about.addView(text);
            pageContent.addView(about, cardParams());
        }
        final LinearLayout facts = card();
        final int chats = info != null && info.linked_peers != null ? info.linked_peers.size() : 0;
        facts.addView(actionRow(R.drawable.outline_groups_24, chats > 0 ? LocaleController.formatPluralString("CommunityWithChats", chats)
            : LocaleController.getString(R.string.Community), null, false, () -> selectTab(TAB_CHATS)));
        if (info != null && info.admins_count > 0) {
            facts.addView(actionRow(R.drawable.msg_admins, LocaleController.formatPluralString("NilakCommunityAdmins", info.admins_count), null, false,
                ChatObject.hasAdminRights(community) ? this::openSettings : null));
        }
        final boolean collapsed = community.collapsed_in_dialogs;
        facts.addView(actionRow(R.drawable.msg_folders, LocaleController.getString(R.string.CommunityShowAsOneChat),
            LocaleController.getString(collapsed ? R.string.NilakOn : R.string.NilakOff), false,
            () -> getMessagesController().toggleCommunityCollapsedInDialogs(communityId, !collapsed)));
        if (ChatObject.hasAdminRights(community)) {
            facts.addView(actionRow(R.drawable.msg_settings, LocaleController.getString(R.string.CommunityMenuSettings), null, false, this::openSettings));
        }
        pageContent.addView(facts, cardParams());
        pageContent.addView(note(LocaleController.getString(R.string.CommunityShowAsOneChatInfo)), cardParams());
    }

    private LinearLayout card() {
        final LinearLayout card = new LinearLayout(pageContent.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(6), dp(4), dp(6));
        card.setBackground(Theme.createRoundRectDrawable(dp(cardRadius()), cardColor()));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 12);
    }

    private View note(String text) {
        final TextView view = new TextView(pageContent.getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTextColor(metaColor());
        view.setPadding(dp(8), 0, dp(8), 0);
        return view;
    }

    private View actionRow(int icon, CharSequence title, CharSequence value, boolean accentTitle, Runnable action) {
        final Context context = pageContent.getContext();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(12), dp(10));
        if (action != null) {
            row.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.10f), dp(14), dp(14)));
            row.setOnClickListener(v -> action.run());
        }
        final ImageView iconView = new ImageView(context);
        iconView.setImageResource(icon);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
        iconView.setBackground(Theme.createRoundRectDrawable(dp(12), TigiLilac.on() ? TigiLilac.inset() : Theme.multAlpha(TigiStyle.accent(), 0.10f)));
        row.addView(iconView, LayoutHelper.createLinear(38, 38));
        final TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(TigiStyle.displayBold());
        titleView.setTextColor(accentTitle ? TigiStyle.accent() : inkColor());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 8, 0));
        if (value != null) {
            final TextView valueView = new TextView(context);
            valueView.setText(value);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            valueView.setTextColor(metaColor());
            row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }
        return row;
    }

    /* Feed: the posts of its channels */

    @Override
    public void onResume() {
        super.onResume();
        bindHeader();
        if (tab != TAB_FEED) {
            bindPage();
        }
        if (!postsLoaded && collectSources()) {
            loadPosts();
        }
    }

    /** Channels of the community the person can read (joined, or open to view); true when the set changed. */
    private boolean collectSources() {
        final MessagesController.CommunityPeersDialog peers = getMessagesController().buildCommunityPeers(communityId);
        final HashSet<Long> found = new HashSet<>();
        if (peers != null) {
            for (ArrayList<MessagesController.CommunityPeerDialog> list : new ArrayList[]{peers.chatsYouAreIn, peers.chatsYouCanView}) {
                for (MessagesController.CommunityPeerDialog peer : list) {
                    if (ChatObject.isChannelAndNotMegaGroup(peer.chat)) {
                        found.add(peer.chat.id);
                    }
                }
            }
        }
        if (found.equals(sourceChannels)) {
            return false;
        }
        sourceChannels.clear();
        sourceChannels.addAll(found);
        return true;
    }

    private void loadPosts() {
        if (listView == null || getMessagesController().getChatFull(communityId) == null) {
            return;
        }
        final int generation = ++loadGeneration;
        if (sourceChannels.isEmpty()) {
            loadingPosts = false;
            postsLoaded = true;
            posts.clear();
            notifyPostsChanged();
            return;
        }
        loadingPosts = true;
        updateEmptyView();
        final ArrayList<FeedPost> collected = new ArrayList<>();
        final HashMap<Long, FeedPost> albums = new HashMap<>();
        pendingLoads = sourceChannels.size();
        for (long chatId : sourceChannels) {
            final TLRPC.Chat chat = getMessagesController().getChat(chatId);
            final TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
            history.peer = getMessagesController().getInputPeer(-chatId);
            history.limit = POSTS_PER_CHANNEL;
            final int requestId = getConnectionsManager().sendRequest(history, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (isDead() || generation != loadGeneration) {
                    return;
                }
                if (response instanceof TLRPC.messages_Messages && chat != null) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    collected.addAll(buildPosts(chat, res.messages, albums));
                }
                if (--pendingLoads == 0) {
                    loadingPosts = false;
                    postsLoaded = true;
                    Collections.sort(collected, (a, b) -> Integer.compare(b.date, a.date));
                    posts.clear();
                    for (int i = 0; i < collected.size() && posts.size() < MAX_POSTS; i++) {
                        posts.add(collected.get(i));
                    }
                    notifyPostsChanged();
                    if (listView != null) {
                        listView.post(this::checkAutoplay);
                    }
                }
            }));
            getConnectionsManager().bindRequestToGuid(requestId, classGuid);
        }
    }

    @Override
    protected boolean acceptsNewMessage(long dialogId, MessageObject message) {
        return dialogId < 0 && sourceChannels.contains(-dialogId);
    }

    @Override
    protected boolean isLoadingPosts() {
        return loadingPosts;
    }

    @Override
    protected boolean isInitialLoading() {
        return loadingPosts || !postsLoaded;
    }

    @Override
    protected void updateEmptyView() {
        super.updateEmptyView();
        if (emptyView == null) {
            return;
        }
        if (tab != TAB_FEED) {
            emptyView.setVisibility(View.GONE);
            if (shimmerView != null) {
                shimmerView.setVisibility(View.GONE);
            }
            return;
        }
        if (posts.isEmpty() && postsLoaded && !loadingPosts) {
            emptyView.setText(LocaleController.getString(R.string.NilakCommunityNoPosts));
        }
    }

    /** The accent line under the selected tab. */
    private static final class UnderlineDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        UnderlineDrawable(int color) {
            paint.setColor(color);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect bounds = getBounds();
            final float width = Math.min(bounds.width() * 0.6f, dp(72));
            final float left = bounds.centerX() - width / 2f;
            canvas.drawRoundRect(left, bounds.bottom - dp(3), left + width, bounds.bottom, dp(1.5f), dp(1.5f), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
