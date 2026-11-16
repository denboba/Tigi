package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_communities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.community.CommunitySheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * The Communities tab: a card for each Telegram Community the person belongs to, with its newest message and unread
 * count, opening to show its chats (and its Ask board's newest question) with Ask, Add chat and Settings; below them
 * the newest posts of the communities' channels as Nilak cards.
 * Everything comes from Telegram (joined communities, their linked chats); nothing is stored by Nilak.
 */
public class NilakCommunitiesActivity extends TigiFeedBase implements MainTabsActivity.TabFragmentDelegate {

    // newest posts taken from each channel; the merged list is sorted by date
    private static final int POSTS_PER_CHANNEL = 8;
    private static final int MAX_POSTS = 40;
    private static final int PREVIEW_LENGTH = 80;
    // coming back to the tab asks Telegram again at most this often
    private static final long RELOAD_INTERVAL_MS = 30_000;

    private final ArrayList<TLRPC.Chat> communities = new ArrayList<>();
    private final HashSet<Long> sourceChannels = new HashSet<>();
    private final HashSet<Long> requestedInfo = new HashSet<>();
    private boolean communitiesLoaded;
    private int pendingLoads;
    private boolean loadingPosts;
    private int loadGeneration;
    private long lastLoadTime;

    private FrameLayout titleBar;
    private LinearLayout scrollingPart;
    private TextView titleView;
    private ImageView searchButton;
    private ImageView createButton;
    private LinearLayout listCard;
    private LinearLayout emptyCard;
    private TextView emptyTitle;
    private TextView emptyText;
    private TextView emptyButton;
    private TextView latestLabel;
    private int titleOffset;

    public NilakCommunitiesActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.topicsDidLoaded);
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        communities.addAll(getMessagesController().getJoinedCommunities());
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.topicsDidLoaded);
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.topicsDidLoaded) {
            bindQuestions(false);
            return;
        }
        if (id == NotificationCenter.chatInfoDidLoad) {
            final TLRPC.ChatFull info = (TLRPC.ChatFull) args[0];
            if (info != null && isJoined(info.id)) {
                // linked chats arrived: their newest messages, channels and Ask board
                bindCommunities();
                bindQuestions(true);
                if (collectSources()) {
                    loadPosts();
                }
            }
        }
        if (id == NotificationCenter.dialogsNeedReload || id == NotificationCenter.updateInterfaces) {
            bindCommunities();
            return;
        }
        super.didReceivedNotification(id, account, args);
    }

    @Override
    protected boolean headerScrollsWithList() {
        return true;
    }

    @Override
    protected View createHeaderView(Context context) {
        // like Home: the list of communities scrolls away with the posts, the title bar hides on the way down
        final FrameLayout header = new FrameLayout(context);

        scrollingPart = new LinearLayout(context);
        scrollingPart.setOrientation(LinearLayout.VERTICAL);
        scrollingPart.setPadding(0, dp(4), 0, 0);

        listCard = new LinearLayout(context);
        listCard.setOrientation(LinearLayout.VERTICAL);
        // one card per community, each opening to show its chats
        scrollingPart.addView(listCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        emptyCard = new LinearLayout(context);
        emptyCard.setOrientation(LinearLayout.VERTICAL);
        emptyCard.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyCard.setPadding(dp(20), dp(22), dp(20), dp(20));
        final ImageView emptyIcon = new ImageView(context);
        emptyIcon.setImageResource(R.drawable.outline_groups_24);
        emptyIcon.setScaleType(ImageView.ScaleType.CENTER);
        emptyIcon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        emptyIcon.setBackground(Theme.createCircleDrawable(dp(64), TigiStyle.accent()));
        emptyIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        emptyCard.addView(emptyIcon, LayoutHelper.createLinear(64, 64));
        emptyTitle = new TextView(context);
        emptyTitle.setText(LocaleController.getString(R.string.NilakCommunitiesEmptyTitle));
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        emptyTitle.setTypeface(TigiStyle.displayExtraBold());
        emptyTitle.setGravity(Gravity.CENTER);
        emptyCard.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        emptyText = new TextView(context);
        emptyText.setText(LocaleController.getString(R.string.NilakCommunitiesEmptyText));
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(dp(2), 1f);
        emptyCard.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        emptyButton = new TextView(context);
        emptyButton.setText(LocaleController.getString(R.string.NilakCommunityNew));
        emptyButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyButton.setTypeface(TigiStyle.displayBold());
        emptyButton.setGravity(Gravity.CENTER);
        emptyButton.setPadding(dp(24), 0, dp(24), 0);
        emptyButton.setOnClickListener(v -> NilakCommunitySheets.showNewCommunity(this));
        emptyCard.addView(emptyButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 0, 16, 0, 0));
        scrollingPart.addView(emptyCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        latestLabel = new TextView(context);
        latestLabel.setText(LocaleController.getString(R.string.NilakCommunitiesLatest));
        latestLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        latestLabel.setTypeface(TigiStyle.displayBold());
        latestLabel.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        androidx.core.view.ViewCompat.setAccessibilityHeading(latestLabel, true);
        scrollingPart.addView(latestLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 2));
        header.addView(scrollingPart, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        final FrameLayout bar = new FrameLayout(context);
        titleBar = bar;
        applyTopInset(bar);
        titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.NilakCommunities));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25);
        titleView.setTypeface(TigiStyle.displayExtraBold());
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setSingleLine(true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(titleView, true);
        bar.addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 18, 0, 18, 0));

        createButton = headerButton(context, R.drawable.tigi_add_24, R.string.NilakCommunityNew);
        createButton.setOnClickListener(v -> NilakCommunitySheets.showNewCommunity(this));
        bar.addView(createButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 6, 4, 6, 0));
        searchButton = headerButton(context, R.drawable.outline_header_search, R.string.Search);
        searchButton.setOnClickListener(v -> {
            // Telegram's search: communities, their chats and messages
            final MainTabsActivity tabs = TigiNavigation.findMainTabs(this);
            if (tabs != null) {
                tabs.openChatsSearch();
            }
        });
        bar.addView(searchButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), LocaleController.isRTL ? 54 : 6, 4, LocaleController.isRTL ? 6 : 54, 0));
        header.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        // the list sits right under the bar, whose height includes the status bar
        bar.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) scrollingPart.getLayoutParams();
            if (params.topMargin != bottom - top) {
                params.topMargin = bottom - top;
                scrollingPart.post(() -> scrollingPart.setLayoutParams(params));
            }
        });

        updateHeaderColors();
        bindCommunities();
        return header;
    }

    private ImageView headerButton(Context context, int icon, int description) {
        final ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(icon);
        button.setContentDescription(LocaleController.getString(description));
        return button;
    }

    @Override
    protected void updateHeaderColors() {
        if (titleBar == null) {
            return;
        }
        final boolean lilac = TigiLilac.on();
        final int background = Theme.getColor(Theme.key_windowBackgroundWhite);
        if (lilac) {
            titleBar.setBackground(TigiLilac.pageBar());
            scrollingPart.setBackground(null);
        } else {
            titleBar.setBackgroundColor(background);
            scrollingPart.setBackgroundColor(background);
        }
        final int ink = inkColor();
        final int meta = metaColor();
        titleView.setTextColor(ink);
        for (ImageView button : new ImageView[]{searchButton, createButton}) {
            button.setBackground(lilac
                ? new InsetDrawable(TigiLilac.roundButton(), dp(4))
                : Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            button.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
        }
        emptyCard.setBackground(Theme.createRoundRectDrawable(dp(cardRadius()), cardColor()));
        emptyTitle.setTextColor(ink);
        emptyText.setTextColor(meta);
        emptyButton.setTextColor(0xFFFFFFFF);
        emptyButton.setBackground(TigiStyle.primaryPill(22));
        latestLabel.setTextColor(ink);
        bindCommunities();
    }

    /* the communities card */

    private boolean isJoined(long chatId) {
        for (int i = 0; i < communities.size(); i++) {
            if (communities.get(i).id == chatId) {
                return true;
            }
        }
        return false;
    }

    /** Loads the topics of the communities' Ask boards (once), so each card can show its newest question */
    private void bindQuestions(boolean load) {
        if (load) {
            for (TLRPC.Chat community : communities) {
                final TLRPC.Chat board = NilakAsk.findBoard(currentAccount, community.id);
                if (board != null && getMessagesController().getTopicsController().getTopics(board.id) == null) {
                    getMessagesController().getTopicsController().loadTopics(board.id);
                }
            }
        }
        bindCommunities();
    }

    private void bindCommunities() {
        if (listCard == null) {
            return;
        }
        listCard.removeAllViews();
        final boolean hasAny = !communities.isEmpty();
        listCard.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        // the invitation shows once Telegram has answered that there are none
        emptyCard.setVisibility(!hasAny && communitiesLoaded ? View.VISIBLE : View.GONE);
        latestLabel.setVisibility(hasAny && !posts.isEmpty() ? View.VISIBLE : View.GONE);
        if (!hasAny) {
            return;
        }
        final ArrayList<TLRPC.Chat> sorted = new ArrayList<>(communities);
        final HashMap<Long, Integer> dates = new HashMap<>();
        final HashMap<Long, Integer> unread = new HashMap<>();
        for (TLRPC.Chat community : sorted) {
            final TLRPC.Dialog newest = newestDialog(community.id);
            dates.put(community.id, newest != null ? newest.last_message_date : 0);
            unread.put(community.id, getMessagesController().getCommunityUnreadCount(community.id).unreadCount);
        }
        // Communities with unread messages first, then the newest message, like the chat list
        Collections.sort(sorted, (a, b) -> {
            final int byUnread = Boolean.compare(unread.get(b.id) > 0, unread.get(a.id) > 0);
            return byUnread != 0 ? byUnread : Integer.compare(dates.get(b.id), dates.get(a.id));
        });
        for (int i = 0; i < sorted.size(); i++) {
            listCard.addView(createCard(sorted.get(i), sorted.size()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, CARD_GAP));
        }
    }

    private static final int CARD_GAP = 8;
    private static final String KEY_OPEN = "nilak_community_open_";

    /** Open when the person opened it last time; otherwise open while it has unread messages or is the only one */
    private boolean isOpen(TLRPC.Chat community, int count) {
        final android.content.SharedPreferences prefs = MessagesController.getMainSettings(currentAccount);
        if (prefs.contains(KEY_OPEN + community.id)) {
            return prefs.getBoolean(KEY_OPEN + community.id, false);
        }
        return count == 1 || getMessagesController().getCommunityUnreadCount(community.id).unreadCount > 0;
    }

    private void toggleOpen(TLRPC.Chat community) {
        final boolean open = isOpen(community, communities.size());
        MessagesController.getMainSettings(currentAccount).edit().putBoolean(KEY_OPEN + community.id, !open).apply();
        bindCommunities();
    }

    private TLRPC.Dialog newestDialog(long communityId) {
        TLRPC.Dialog newest = null;
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getDialogsByCommunity(communityId);
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (newest == null || dialog.last_message_date > newest.last_message_date) {
                newest = dialog;
            }
        }
        return newest;
    }

    /** A community's card: its header, and when open its chats and the actions Telegram allows */
    private View createCard(TLRPC.Chat community, int count) {
        final Context context = listCard.getContext();
        final boolean open = isOpen(community, count);
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(cardRadius()), cardColor()));
        card.addView(createHeader(community, open), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (!open) {
            return card;
        }
        final View line = new View(context);
        line.setBackgroundColor(TigiLilac.on() ? TigiLilac.inset() : Theme.getColor(Theme.key_divider));
        card.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        final TLRPC.Chat board = NilakAsk.findBoard(currentAccount, community.id);
        final MessagesController.CommunityPeersDialog peers = getMessagesController().buildCommunityPeers(community.id);
        if (peers != null) {
            final ArrayList<MessagesController.CommunityPeerDialog> shown = new ArrayList<>(peers.chatsYouAreIn);
            shown.addAll(peers.chatsYouCanView);
            for (MessagesController.CommunityPeerDialog peer : shown) {
                if (peer.chat != null || peer.user != null) {
                    card.addView(createChatRow(peer, board), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
            }
        }
        final View actions = createActions(community, board);
        if (actions != null) {
            card.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        return card;
    }

    private View createHeader(TLRPC.Chat community, boolean open) {
        final Context context = listCard.getContext();
        final int ink = inkColor();
        final int meta = metaColor();

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(6), dp(10));
        row.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.10f), cardRadius(), open ? 0 : cardRadius()));
        // The community itself opens as Telegram shows it
        row.setOnClickListener(v -> openCommunity(community.id));
        row.setOnLongClickListener(v -> showCommunityOptions(community, v));

        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(15));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(currentAccount, community);
        avatar.setForUserOrChat(community, avatarDrawable);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, LayoutHelper.createLinear(48, 48));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(community.title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(ink);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(meta);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 8, 0));

        final MessagesController.UnreadCounts unread = getMessagesController().getCommunityUnreadCount(community.id);
        final TextView badge = badge(context, unread.unreadCount, !unread.hasUnmutedUnreadDialogs);
        row.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 22, Gravity.CENTER_VERTICAL, 0, 0, 2, 0));

        // The arrow opens and closes the card
        final ImageView arrow = new ImageView(context);
        arrow.setScaleType(ImageView.ScaleType.CENTER);
        arrow.setImageResource(R.drawable.arrow_more);
        arrow.setRotation(open ? 180 : 0);
        arrow.setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
        arrow.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.12f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        arrow.setContentDescription(LocaleController.getString(open ? R.string.NilakHideChats : R.string.NilakShowChats));
        arrow.setOnClickListener(v -> toggleOpen(community));
        row.addView(arrow, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        final TLRPC.ChatFull info = getMessagesController().getChatFull(community.id);
        final int chats = info != null && info.linked_peers != null ? info.linked_peers.size() : 0;
        final TLRPC.Dialog newest = newestDialog(community.id);
        final CharSequence summary = newest != null ? summary(newest, true) : null;
        if (open || TextUtils.isEmpty(summary)) {
            // Open: what is inside; closed: the newest message, as in the chat list
            String text = chats > 0 ? LocaleController.formatPluralString("CommunityWithChats", chats) : LocaleController.getString(R.string.Community);
            if (community.participants_count > 0) {
                text += " · " + LocaleController.formatPluralStringComma("Members", community.participants_count);
            }
            subtitle.setText(text);
        } else {
            subtitle.setText(summary);
        }
        row.setContentDescription(community.title + ", " + subtitle.getText()
            + (unread.unreadCount > 0 ? ", " + LocaleController.formatPluralString("NewMessages", unread.unreadCount) : ""));
        return row;
    }

    private TextView badge(Context context, int count, boolean muted) {
        final TextView badge = new TextView(context);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(0xFFFFFFFF);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(dp(22));
        badge.setPadding(dp(6), 0, dp(6), 0);
        if (count > 0) {
            badge.setText(LocaleController.formatShortNumber(count, null));
            badge.setBackground(Theme.createRoundRectDrawable(dp(11), muted ? Theme.multAlpha(metaColor(), 0.55f) : TigiStyle.accent()));
        } else {
            badge.setVisibility(View.GONE);
        }
        return badge;
    }

    /** One chat of an open community: its photo, newest message (the newest question for the Ask board) and unread */
    private View createChatRow(MessagesController.CommunityPeerDialog peer, TLRPC.Chat board) {
        final Context context = listCard.getContext();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(7), dp(14), dp(7));
        row.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.10f), Theme.RIPPLE_MASK_ALL));
        row.setOnClickListener(v -> openPeer(peer));

        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(18));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (peer.chat != null) {
            avatarDrawable.setInfo(currentAccount, peer.chat);
            avatar.setForUserOrChat(peer.chat, avatarDrawable);
        } else {
            avatarDrawable.setInfo(currentAccount, peer.user);
            avatar.setForUserOrChat(peer.user, avatarDrawable);
        }
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, LayoutHelper.createLinear(36, 36));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(peer.chat != null ? peer.chat.title : UserObject.getUserName(peer.user));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(inkColor());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView preview = new TextView(context);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        preview.setTextColor(metaColor());
        preview.setSingleLine(true);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 8, 0));

        final LinearLayout side = new LinearLayout(context);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        final TextView time = new TextView(context);
        time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        time.setTextColor(metaColor());
        side.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        final TLRPC.Dialog dialog = peer.dialog;
        final int unread = dialog != null ? dialog.unread_count : 0;
        side.addView(badge(context, unread, getMessagesController().isDialogMuted(peer.dialogId, 0)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 20, 0, 3, 0, 0));
        row.addView(side, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        int date = dialog != null ? dialog.last_message_date : 0;
        CharSequence text = dialog != null ? summary(dialog, false) : null;
        if (board != null && peer.chat != null && peer.chat.id == board.id) {
            // The Ask board: its newest question
            final ArrayList<TLRPC.TL_forumTopic> questions = NilakAsk.questionsOf(currentAccount, board);
            if (!questions.isEmpty()) {
                text = "“" + questions.get(0).title + "”";
                date = NilakAsk.dateOf(questions.get(0));
            }
        }
        if (TextUtils.isEmpty(text)) {
            text = LocaleController.getString(ChatObject.isChannelAndNotMegaGroup(peer.chat) ? R.string.AccDescrChannel : R.string.AccDescrGroup);
        }
        preview.setText(text);
        time.setText(date > 0 ? LocaleController.stringForMessageListDate(date) : "");
        row.setContentDescription(name.getText() + ", " + text + (unread > 0 ? ", " + LocaleController.formatPluralString("NewMessages", unread) : ""));
        return row;
    }

    /** Ask, Add chat and Settings under an open community, each only where Telegram allows it */
    private View createActions(TLRPC.Chat community, TLRPC.Chat board) {
        final Context context = listCard.getContext();
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(14), dp(4), dp(14), dp(12));
        if (board != null) {
            addAction(actions, R.drawable.msg_topics, LocaleController.getString(R.string.NilakAskShort), () -> openChat(board));
        } else if (ChatObject.canAddChatToCommunity(community)) {
            addAction(actions, R.drawable.msg_topics, LocaleController.getString(R.string.NilakAskAddBoard), () -> addBoard(community));
        }
        if (ChatObject.canAddChatToCommunity(community)) {
            addAction(actions, R.drawable.msg_groups_create, LocaleController.getString(R.string.CommunityMenuAddChat), () ->
                org.telegram.ui.community.CommunityUtils.showChatsToAddToCommunity(addChatProgress, this, currentAccount, community));
        }
        if (ChatObject.hasAdminRights(community)) {
            addAction(actions, R.drawable.msg_settings, LocaleController.getString(R.string.CommunityMenuSettings), () -> openSettings(community));
        }
        return actions.getChildCount() > 0 ? actions : null;
    }

    private final org.telegram.ui.ActionBar.AlertDialog[] addChatProgress = new org.telegram.ui.ActionBar.AlertDialog[1];

    private void addAction(LinearLayout actions, int icon, String text, Runnable onClick) {
        final TextView button = new TextView(actions.getContext());
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        button.setTypeface(TigiStyle.displayBold());
        button.setTextColor(TigiStyle.accent());
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(6), 0, dp(6), 0);
        final android.graphics.drawable.Drawable drawable = androidx.core.content.ContextCompat.getDrawable(actions.getContext(), icon).mutate();
        drawable.setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
        drawable.setBounds(0, 0, dp(ACTION_ICON_SIZE), dp(ACTION_ICON_SIZE));
        button.setCompoundDrawablesRelative(drawable, null, null, null);
        button.setCompoundDrawablePadding(dp(4));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), Theme.multAlpha(TigiStyle.accent(), 0.10f), Theme.multAlpha(TigiStyle.accent(), 0.20f)));
        button.setOnClickListener(v -> onClick.run());
        actions.addView(button, LayoutHelper.createLinear(0, 34, 1f, actions.getChildCount() > 0 ? 6 : 0, 0, 0, 0));
    }

    private static final int ACTION_ICON_SIZE = 18;

    /** Opens a community's chat as Telegram's community list does: topic groups in their topic list */
    private void openPeer(MessagesController.CommunityPeerDialog peer) {
        if (peer.chat != null) {
            openChat(peer.chat);
        } else {
            presentFragment(ChatActivity.of(peer.user.id));
        }
    }

    private void openChat(TLRPC.Chat chat) {
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        if (chat.forum && !chat.monoforum && !ChatObject.areTabsEnabled(chat)) {
            presentFragment(new TopicsFragment(args));
        } else {
            presentFragment(new ChatActivity(args));
        }
    }

    private void openSettings(TLRPC.Chat community) {
        final Bundle args = new Bundle();
        args.putLong("community_id", community.id);
        presentFragment(new org.telegram.ui.community.CommunityEditActivity(args));
    }

    private void addBoard(TLRPC.Chat community) {
        NilakAsk.createBoard(this, community, LocaleController.formatString(R.string.NilakAskBoardTitle, community.title), board -> {
            if (isDead()) {
                return;
            }
            if (board == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakAskBoardFailed)).show();
            } else {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.NilakAskBoardAdded)).show();
                bindQuestions(true);
            }
        });
    }

    /** "General · Sara: Great to be here!" for the newest message in one of the community's chats. */
    private CharSequence summary(TLRPC.Dialog dialog, boolean withChatName) {
        final ArrayList<MessageObject> last = getMessagesController().dialogMessage.get(dialog.id);
        final MessageObject message = last != null && !last.isEmpty() ? last.get(0) : null;
        final String chatName = withChatName ? chatName(dialog.id) : null;
        if (message == null) {
            return chatName;
        }
        String text = message.messageText != null ? message.messageText.toString().replace('\n', ' ') : "";
        if (text.length() > PREVIEW_LENGTH) {
            text = text.substring(0, PREVIEW_LENGTH) + "…";
        }
        String sender = null;
        if (message.isOut()) {
            sender = LocaleController.getString(R.string.FromYou);
        } else if (dialog.id < 0 && !ChatObject.isChannelAndNotMegaGroup(getMessagesController().getChat(-dialog.id))) {
            final TLRPC.User user = getMessagesController().getUser(message.getFromChatId());
            if (user != null) {
                sender = UserObject.getFirstName(user);
            }
        }
        final StringBuilder out = new StringBuilder();
        if (!TextUtils.isEmpty(chatName)) {
            out.append(chatName).append(" · ");
        }
        if (!TextUtils.isEmpty(sender)) {
            out.append(sender).append(": ");
        }
        out.append(text);
        return out;
    }

    private String chatName(long dialogId) {
        if (dialogId < 0) {
            final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            return chat != null ? chat.title : null;
        }
        return UserObject.getUserName(getMessagesController().getUser(dialogId));
    }

    private void openCommunity(long communityId) {
        NilakCommunitySheets.openCommunity(this, communityId);
    }

    /** Long press on a community: Telegram's settings for admins, and the Ask board when it has none yet. */
    private boolean showCommunityOptions(TLRPC.Chat community, View anchor) {
        final org.telegram.ui.Components.ItemOptions options = org.telegram.ui.Components.ItemOptions.makeOptions(this, anchor);
        options.add(R.drawable.msg_openin, LocaleController.getString(R.string.Open), () -> openCommunity(community.id));
        if (NilakAsk.findBoard(currentAccount, community.id) == null && ChatObject.canAddChatToCommunity(community)) {
            options.add(R.drawable.msg_topics, LocaleController.getString(R.string.NilakAskAddBoard), () -> addBoard(community));
        }
        if (ChatObject.hasAdminRights(community)) {
            options.add(R.drawable.msg_settings, LocaleController.getString(R.string.CommunityMenuSettings), () -> openSettings(community));
        }
        options.show();
        return true;
    }

    /* loading */

    @Override
    public void onResume() {
        super.onResume();
        if (android.os.SystemClock.elapsedRealtime() - lastLoadTime > RELOAD_INTERVAL_MS) {
            loadCommunities();
        }
        bindCommunities();
    }

    private void loadCommunities() {
        lastLoadTime = android.os.SystemClock.elapsedRealtime();
        getMessagesController().fetchJoinedCommunities(result -> {
            if (isDead()) {
                return;
            }
            if (result != null) {
                communities.clear();
                communities.addAll(result);
                communitiesLoaded = true;
            }
            bindCommunities();
            bindQuestions(true);
            if (collectSources() || posts.isEmpty()) {
                loadPosts();
            }
            updateEmptyView();
        }, classGuid);
    }

    /** The channels the person reads in their communities; true when that set changed. */
    private boolean collectSources() {
        final HashSet<Long> found = new HashSet<>();
        for (int i = 0; i < communities.size(); i++) {
            final long communityId = communities.get(i).id;
            final TLRPC.ChatFull info = getMessagesController().getChatFull(communityId);
            if (info == null || info.linked_peers == null) {
                if (requestedInfo.add(communityId)) {
                    getMessagesController().loadFullChat(communityId, classGuid, true);
                }
                continue;
            }
            for (TL_communities.CommunityPeer peer : info.linked_peers) {
                final long dialogId = org.telegram.messenger.DialogObject.getPeerDialogId(peer.peer);
                final TLRPC.Chat chat = dialogId < 0 ? getMessagesController().getChat(-dialogId) : null;
                if (ChatObject.isChannelAndNotMegaGroup(chat) && !ChatObject.isNotInChat(chat)) {
                    found.add(chat.id);
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
        if (listView == null) {
            return;
        }
        final int generation = ++loadGeneration;
        if (sourceChannels.isEmpty()) {
            loadingPosts = false;
            if (!posts.isEmpty()) {
                posts.clear();
                notifyPostsChanged();
            }
            bindCommunities();
            updateEmptyView();
            return;
        }
        loadingPosts = true;
        updateEmptyView();
        final ArrayList<FeedPost> collected = new ArrayList<>();
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
                    collected.addAll(buildPosts(chat, res.messages));
                }
                if (--pendingLoads == 0) {
                    showPosts(collected);
                }
            }));
            getConnectionsManager().bindRequestToGuid(requestId, classGuid);
        }
    }

    private void showPosts(ArrayList<FeedPost> collected) {
        loadingPosts = false;
        Collections.sort(collected, (a, b) -> Integer.compare(b.date, a.date));
        posts.clear();
        for (int i = 0; i < collected.size() && posts.size() < MAX_POSTS; i++) {
            posts.add(collected.get(i));
        }
        notifyPostsChanged();
        bindCommunities();
        if (listView != null) {
            listView.post(this::checkAutoplay);
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
        return loadingPosts || !communitiesLoaded && communities.isEmpty();
    }

    @Override
    protected void updateEmptyView() {
        super.updateEmptyView();
        if (emptyView == null || !posts.isEmpty()) {
            return;
        }
        // with no communities the card above invites to create one; with communities but no channel posts yet,
        // the rows above are the content
        emptyView.setVisibility(View.GONE);
        if (shimmerView != null && !communities.isEmpty() && !loadingPosts) {
            shimmerView.setVisibility(View.GONE);
        }
    }

    /* scrolling, tabs */

    @Override
    protected void onListScrolled(int dy) {
        if (titleBar == null || scrollingPart == null) {
            return;
        }
        final int scrolled = listScrollFromTop();
        scrollingPart.setTranslationY(-Math.min(scrolled, scrollingPart.getHeight() + titleBar.getHeight()));
        final int hideable = titleBar.getHeight() - titleBar.getPaddingTop();
        titleOffset = Math.max(-hideable, Math.min(0, titleOffset - dy));
        if (scrolled == 0) {
            titleOffset = 0;
        }
        titleBar.setTranslationY(titleOffset);
        final float shown = hideable > 0 ? 1f + titleOffset / (float) hideable : 1f;
        for (View view : new View[]{titleView, searchButton, createButton}) {
            view.setAlpha(shown);
            view.setEnabled(shown > 0.5f);
        }
    }

    @Override
    public void onParentScrollToTop() {
        if (listView == null) {
            return;
        }
        if (layoutManager.findFirstVisibleItemPosition() <= 0) {
            loadCommunities();
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
}
