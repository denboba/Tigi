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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.community.CommunitySheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * The Communities tab: the Telegram Communities the person belongs to, each with its newest message and unread
 * count, and below them the newest posts of the communities' channels as Nilak cards.
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
    // newest questions from the communities' Ask boards
    private LinearLayout questionsCard;
    private final java.util.HashMap<Long, NilakAsk.Question> askKnown = new java.util.HashMap<>();
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
            return;
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
        listCard.setPadding(dp(4), dp(4), dp(4), dp(4));
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

        questionsCard = new LinearLayout(context);
        questionsCard.setOrientation(LinearLayout.VERTICAL);
        questionsCard.setPadding(dp(4), dp(8), dp(4), dp(6));
        questionsCard.setVisibility(View.GONE);
        scrollingPart.addView(questionsCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 12, 12, 0));

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
        final android.graphics.drawable.Drawable card = Theme.createRoundRectDrawable(dp(cardRadius()), cardColor());
        listCard.setBackground(card);
        emptyCard.setBackground(Theme.createRoundRectDrawable(dp(cardRadius()), cardColor()));
        emptyTitle.setTextColor(ink);
        emptyText.setTextColor(meta);
        emptyButton.setTextColor(0xFFFFFFFF);
        emptyButton.setBackground(TigiStyle.primaryPill(22));
        latestLabel.setTextColor(ink);
        questionsCard.setBackground(Theme.createRoundRectDrawable(dp(cardRadius()), cardColor()));
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

    private static final int QUESTIONS_SHOWN = 3;

    /** The newest questions across the communities' Ask boards; a tap opens the question. */
    private void bindQuestions(boolean load) {
        if (questionsCard == null) {
            return;
        }
        final ArrayList<Object[]> found = new ArrayList<>();
        for (TLRPC.Chat community : communities) {
            final TLRPC.Chat board = NilakAsk.findBoard(currentAccount, community.id);
            if (board == null) {
                continue;
            }
            if (load && getMessagesController().getTopicsController().getTopics(board.id) == null) {
                getMessagesController().getTopicsController().loadTopics(board.id);
            }
            for (NilakAsk.Question question : NilakAsk.questionsOf(currentAccount, board, askKnown)) {
                found.add(new Object[]{question, board, community});
            }
        }
        Collections.sort(found, (a, b) -> Integer.compare(((NilakAsk.Question) b[0]).topic.date, ((NilakAsk.Question) a[0]).topic.date));
        questionsCard.removeAllViews();
        questionsCard.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
        if (found.isEmpty()) {
            return;
        }
        final TextView header = new TextView(questionsCard.getContext());
        header.setText(LocaleController.getString(R.string.NilakAskNewQuestions));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        header.setTypeface(TigiStyle.displayBold());
        header.setTextColor(TigiStyle.accent());
        header.setPadding(dp(10), 0, dp(10), dp(4));
        questionsCard.addView(header);
        for (int i = 0; i < found.size() && i < QUESTIONS_SHOWN; i++) {
            final NilakAsk.Question question = (NilakAsk.Question) found.get(i)[0];
            final TLRPC.Chat board = (TLRPC.Chat) found.get(i)[1];
            final TLRPC.Chat community = (TLRPC.Chat) found.get(i)[2];
            final LinearLayout row = new LinearLayout(questionsCard.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.10f), dp(14), dp(14)));
            row.setOnClickListener(v -> NilakQuestionActivity.open(this, board.id, question.topic.id));
            final TextView title = new TextView(questionsCard.getContext());
            title.setText(question.topic.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(TigiStyle.displayBold());
            title.setTextColor(inkColor());
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title);
            final TextView meta = new TextView(questionsCard.getContext());
            meta.setText(community.title + "  ·  " + LocaleController.stringForMessageListDate(question.topic.date));
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            meta.setTextColor(metaColor());
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            questionsCard.addView(row);
        }
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
        for (TLRPC.Chat community : sorted) {
            final TLRPC.Dialog newest = newestDialog(community.id);
            dates.put(community.id, newest != null ? newest.last_message_date : 0);
        }
        // the community with the newest message first, like the chat list
        Collections.sort(sorted, (a, b) -> Integer.compare(dates.get(b.id), dates.get(a.id)));
        for (int i = 0; i < sorted.size(); i++) {
            listCard.addView(createRow(sorted.get(i), i < sorted.size() - 1), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
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

    private View createRow(TLRPC.Chat community, boolean divider) {
        final Context context = listCard.getContext();
        final int ink = inkColor();
        final int meta = metaColor();

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.10f), dp(16), dp(16)));
        row.setOnClickListener(v -> openCommunity(community.id));

        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(16));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(currentAccount, community);
        avatar.setForUserOrChat(community, avatarDrawable);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, LayoutHelper.createLinear(50, 50));

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
        final TextView preview = new TextView(context);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        preview.setTextColor(meta);
        preview.setSingleLine(true);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 8, 0));

        final LinearLayout side = new LinearLayout(context);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        final TextView time = new TextView(context);
        time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        time.setTextColor(meta);
        side.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        final TextView badge = new TextView(context);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(0xFFFFFFFF);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(dp(22));
        badge.setPadding(dp(6), 0, dp(6), 0);
        side.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 22, 0, 4, 0, 0));
        row.addView(side, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        final TLRPC.Dialog newest = newestDialog(community.id);
        final CharSequence summary = newest != null ? summary(newest) : null;
        if (!TextUtils.isEmpty(summary)) {
            preview.setText(summary);
        } else {
            final TLRPC.ChatFull info = getMessagesController().getChatFull(community.id);
            final int chats = info != null && info.linked_peers != null ? info.linked_peers.size() : 0;
            preview.setText(chats > 0 ? LocaleController.formatPluralString("CommunityWithChats", chats) : LocaleController.getString(R.string.Community));
        }
        time.setText(newest != null && newest.last_message_date > 0 ? LocaleController.stringForMessageListDate(newest.last_message_date) : "");
        final MessagesController.UnreadCounts unread = getMessagesController().getCommunityUnreadCount(community.id);
        if (unread.unreadCount > 0) {
            badge.setText(LocaleController.formatShortNumber(unread.unreadCount, null));
            badge.setBackground(Theme.createRoundRectDrawable(dp(11), unread.hasUnmutedUnreadDialogs ? TigiStyle.accent() : Theme.multAlpha(meta, 0.55f)));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.INVISIBLE);
        }
        row.setContentDescription(community.title + (summary != null ? ", " + summary : "")
            + (unread.unreadCount > 0 ? ", " + LocaleController.formatPluralString("NewMessages", unread.unreadCount) : ""));

        if (!divider) {
            return row;
        }
        final LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final View line = new View(context);
        line.setBackgroundColor(TigiLilac.on() ? TigiLilac.inset() : Theme.getColor(Theme.key_divider));
        wrap.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 72, 0, 10, 0));
        return wrap;
    }

    /** "General · Sara: Great to be here!" for the newest message in one of the community's chats. */
    private CharSequence summary(TLRPC.Dialog dialog) {
        final ArrayList<MessageObject> last = getMessagesController().dialogMessage.get(dialog.id);
        final MessageObject message = last != null && !last.isEmpty() ? last.get(0) : null;
        final String chatName = chatName(dialog.id);
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
        if (!NilakCommunityActivity.open(this, communityId)) {
            showDialog(new CommunitySheet(this, communityId));
        }
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
