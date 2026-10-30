package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Tigi profile of a channel, or of a person through their personal channel: header with counts and actions, a
 * 3-column grid of posts and a grid of reels. Telegram's own profile (members, notifications, admin tools, block,
 * report, shared media) stays one tap away behind "Info".
 *
 * Arguments: chat_id (the channel whose posts are shown), optional user_id (the person who owns it as personal channel).
 * With "self" it is the user's own profile (the Profile tab): their name, bio and photo, the posts of their own Tigi
 * channel (empty until the first post), Edit profile / Share profile, and Settings plus Telegram's full profile on top.
 */
public class TigiProfileActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int TAB_POSTS = 0;
    private static final int TAB_REELS = 1;
    private static final int TAB_SAVED = 2;
    private static final int TABS = 3;
    private static final int PAGE = 60;

    private long chatId;
    private long userId;
    private boolean self;
    private boolean hasMainTabs;
    private TLRPC.Chat chat;
    private TLRPC.User user;

    private int tab = TAB_POSTS;
    private final ArrayList<MessageObject>[] tiles = new ArrayList[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
    private final HashMap<Long, MessageObject>[] albums = new HashMap[]{new HashMap<>(), new HashMap<>(), new HashMap<>()};
    private final HashMap<MessageObject, Integer> albumSizes = new HashMap<>();
    private final int[] offsetId = new int[TABS];
    private final boolean[] reachedEnd = new boolean[TABS];
    private final boolean[] loading = new boolean[TABS];
    private final int[] requestId = new int[TABS];
    private String errorText;
    private int postsCount = -1;

    private RecyclerListView listView;
    private GridLayoutManager gridManager;
    private Adapter adapter;
    private HeaderView header;

    public TigiProfileActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        self = arguments != null && arguments.getBoolean("self", false);
        hasMainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        if (self) {
            user = getUserConfig().getCurrentUser();
            if (user == null) {
                return false;
            }
            userId = user.id;
            chat = TigiPosting.ownChannel(currentAccount);
            chatId = chat != null ? chat.id : 0;
            getMessagesController().loadFullUser(user, classGuid, false);
        } else {
            chatId = arguments != null ? arguments.getLong("chat_id", 0) : 0;
            userId = arguments != null ? arguments.getLong("user_id", 0) : 0;
            chat = getMessagesController().getChat(chatId);
            user = userId != 0 ? getMessagesController().getUser(userId) : null;
            if (chat == null) {
                return false;
            }
        }
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.storiesListUpdated);
        if (chat != null) {
            getMessagesController().loadFullChat(chatId, classGuid, false);
            loadCount();
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.storiesListUpdated);
        if (pinnedStories != null && pinnedLink >= 0) {
            pinnedStories.unlink(pinnedLink);
        }
        for (int i = 0; i < TABS; i++) {
            if (requestId[i] != 0) {
                getConnectionsManager().cancelRequest(requestId[i], true);
            }
        }
        super.onFragmentDestroy();
    }

    private boolean isDead() {
        return isFinishing() || fragmentView == null || getParentActivity() == null;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(user != null ? UserObject.getUserName(user) : chat.title);
        if (hasMainTabs) {
            // a tab page: no back button, its own bar below the status bar (the tab pager has no action bar slot)
            actionBar.setAddToContainer(false);
        }
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        listView = new RecyclerListView(context);
        gridManager = new GridLayoutManager(context, 3);
        gridManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == Adapter.TYPE_TILE ? 1 : 3;
            }
        });
        listView.setLayoutManager(gridManager);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            final int index = position - Adapter.FIRST_TILE;
            if (index >= 0 && index < tiles[tab].size()) {
                openPost(tiles[tab].get(index));
            }
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (gridManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 9) {
                    load(tab);
                }
            }
        });
        if (hasMainTabs) {
            listView.setPadding(0, 0, 0, TigiNavigation.tabsBottomSpace() + dp(16));
            listView.setClipToPadding(false);
            final LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(createTopBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            content.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
            root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        } else {
            root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        fragmentView = root;
        load(TAB_POSTS);
        return fragmentView;
    }

    private TextView topTitle;
    private ImageView settingsButton;
    private ImageView telegramProfileButton;

    /** The tab page's bar: @username (or name) with Telegram's full profile and Settings. */
    private View createTopBar(Context context) {
        final FrameLayout bar = new FrameLayout(context);
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar, (v, insets) -> {
            final androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, Math.max(bars.top, 0), 0, 0);
            return insets;
        });
        topTitle = new TextView(context);
        topTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        topTitle.setTypeface(TigiStyle.displayExtraBold());
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        topTitle.setGravity(Gravity.CENTER_VERTICAL);
        androidx.core.view.ViewCompat.setAccessibilityHeading(topTitle, true);
        bar.addView(topTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 112 : 16, 0, LocaleController.isRTL ? 16 : 112, 0));

        final LinearLayout icons = new LinearLayout(context);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        if (self) {
            telegramProfileButton = topIcon(context, R.drawable.msg_qrcode, R.string.TigiProfileAccount);
            telegramProfileButton.setOnClickListener(v -> openInfo());
            icons.addView(telegramProfileButton, LayoutHelper.createLinear(48, 48));
            settingsButton = topIcon(context, R.drawable.msg_settings, R.string.Settings);
            settingsButton.setOnClickListener(v -> presentFragment(new SettingsActivity()));
            icons.addView(settingsButton, LayoutHelper.createLinear(48, 48));
        }
        bar.addView(icons, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 6, 4, 6, 0));
        updateTopBar();
        return bar;
    }

    private ImageView topIcon(Context context, int icon, int description) {
        final ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setContentDescription(LocaleController.getString(description));
        view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        return view;
    }

    private void updateTopBar() {
        if (topTitle == null) {
            return;
        }
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        topTitle.setTextColor(text);
        final String username = user != null ? UserObject.getPublicUsername(user) : (chat != null ? ChatObject.getPublicUsername(chat) : null);
        topTitle.setText(!TextUtils.isEmpty(username) ? username : (user != null ? UserObject.getUserName(user) : chat.title));
        final PorterDuffColorFilter filter = new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN);
        if (settingsButton != null) {
            settingsButton.setColorFilter(filter);
        }
        if (telegramProfileButton != null) {
            telegramProfileButton.setColorFilter(filter);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!self) {
            return;
        }
        final TLRPC.User current = getUserConfig().getCurrentUser();
        if (current != null) {
            user = current;
        }
        if (chat == null) {
            // the first post made the own channel while this page was open
            final TLRPC.Chat own = TigiPosting.ownChannel(currentAccount);
            if (own != null) {
                chat = own;
                chatId = own.id;
                getMessagesController().loadFullChat(chatId, classGuid, false);
                resetGrid();
            }
        } else if (tiles[tab].isEmpty() && !loading[tab]) {
            resetGrid();
        }
        updateTopBar();
        if (header != null) {
            header.bind();
        }
    }

    private void resetGrid() {
        for (int i = 0; i < TABS; i++) {
            if (requestId[i] != 0) {
                getConnectionsManager().cancelRequest(requestId[i], true);
                requestId[i] = 0;
            }
            tiles[i].clear();
            albums[i].clear();
            offsetId[i] = 0;
            reachedEnd[i] = false;
            loading[i] = false;
        }
        albumSizes.clear();
        errorText = null;
        loadCount();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        load(tab);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ArrayList<ThemeDescription> list = new ArrayList<>();
        final ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (fragmentView != null) {
                fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            if (header != null) {
                header.updateColors();
            }
            updateTopBar();
            if (listView != null) {
                listView.getRecycledViewPool().clear();
                adapter.notifyDataSetChanged();
            }
        };
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteBlackText));
        return list;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.chatInfoDidLoad) {
            final TLRPC.ChatFull full = (TLRPC.ChatFull) args[0];
            if (full != null && full.id == chatId && header != null) {
                header.bind();
            }
        } else if (id == NotificationCenter.storiesListUpdated) {
            if (args.length > 0 && args[0] == pinnedStories && header != null) {
                header.highlights.bind();
            }
        } else if (id == NotificationCenter.userInfoDidLoad) {
            if (user != null && (Long) args[0] == user.id && header != null) {
                header.bind();
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            final TLRPC.Chat current = chatId != 0 ? getMessagesController().getChat(chatId) : null;
            if (current != null) {
                chat = current;
            }
            if (self) {
                final TLRPC.User me = getUserConfig().getCurrentUser();
                if (me != null) {
                    user = me;
                }
                updateTopBar();
            }
            if (header != null) {
                header.bind();
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            final long channelId = (Long) args[1];
            if (channelId != chatId) {
                return;
            }
            final ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            boolean changed = false;
            for (int t = 0; t < TABS; t++) {
                for (int i = tiles[t].size() - 1; i >= 0; i--) {
                    if (ids.contains(tiles[t].get(i).getId())) {
                        tiles[t].remove(i);
                        changed = true;
                    }
                }
            }
            if (changed && adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    /* data */

    private void loadCount() {
        if (chatId == 0) {
            return;
        }
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = getMessagesController().getInputPeer(-chatId);
        req.limit = 1;
        getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_messages_channelMessages) {
                postsCount = ((TLRPC.TL_messages_channelMessages) response).count;
            } else if (response instanceof TLRPC.TL_messages_messagesSlice) {
                postsCount = ((TLRPC.TL_messages_messagesSlice) response).count;
            } else if (response instanceof TLRPC.messages_Messages) {
                postsCount = ((TLRPC.messages_Messages) response).messages.size();
            }
            if (header != null) {
                header.bind();
            }
        })), classGuid);
    }

    private void load(int which) {
        if (which == TAB_SAVED) {
            loadSaved();
            return;
        }
        if (chatId == 0) {
            // own profile before the first post: nothing to load
            reachedEnd[which] = true;
            return;
        }
        if (loading[which] || reachedEnd[which] || isDead()) {
            return;
        }
        loading[which] = true;
        final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = getMessagesController().getInputPeer(-chatId);
        req.q = "";
        req.filter = which == TAB_REELS ? new TLRPC.TL_inputMessagesFilterVideo() : new TLRPC.TL_inputMessagesFilterPhotoVideo();
        req.limit = PAGE;
        req.offset_id = offsetId[which];
        requestId[which] = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            requestId[which] = 0;
            loading[which] = false;
            if (isDead()) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                errorText = null;
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                int minId = offsetId[which];
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    if (message.id > 0 && (minId == 0 || message.id < minId)) {
                        minId = message.id;
                    }
                    addTile(which, new MessageObject(currentAccount, message, false, false));
                }
                reachedEnd[which] = res.messages.isEmpty() || minId == offsetId[which];
                offsetId[which] = minId;
            } else {
                reachedEnd[which] = true;
                if (error != null && ("CHANNEL_PRIVATE".equals(error.text) || "CHANNEL_PUBLIC_GROUP_NA".equals(error.text))) {
                    errorText = LocaleController.getString(R.string.TigiProfilePrivate);
                } else {
                    errorText = LocaleController.getString(R.string.TigiFeedError);
                    // a network error is worth another try when the user comes back
                    reachedEnd[which] = false;
                }
            }
            if (which == tab && adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }));
    }

    private void addTile(int which, MessageObject message) {
        if (message.photoThumbs == null || message.photoThumbs.isEmpty() || message.needDrawBluredPreview()) {
            return;
        }
        if (which == TAB_REELS && (message.isRoundVideo() || message.getDuration() > ReelsActivity.MAX_DURATION_SECONDS)) {
            return;
        }
        final long groupId = message.getGroupId();
        if (groupId != 0) {
            final MessageObject first = albums[which].get(groupId);
            if (first != null) {
                // one tile per album, it shows the album's first (oldest) photo
                albumSizes.put(first, (albumSizes.containsKey(first) ? albumSizes.get(first) : 1) + 1);
                if (message.getId() < first.getId()) {
                    final int index = tiles[which].indexOf(first);
                    if (index >= 0) {
                        tiles[which].set(index, message);
                    }
                    albums[which].put(groupId, message);
                    albumSizes.put(message, albumSizes.remove(first));
                }
                return;
            }
            albums[which].put(groupId, message);
        }
        tiles[which].add(message);
    }

    /** Saved posts (bookmarks kept on the phone), fetched per channel with channels.getMessages, newest saved first. */
    private void loadSaved() {
        if (loading[TAB_SAVED] || reachedEnd[TAB_SAVED] || isDead()) {
            return;
        }
        final ArrayList<long[]> saved = TigiFeedPrefs.saved(currentAccount);
        reachedEnd[TAB_SAVED] = true;
        if (saved.isEmpty()) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }
        loading[TAB_SAVED] = true;
        final java.util.LinkedHashMap<Long, ArrayList<Integer>> byChat = new java.util.LinkedHashMap<>();
        for (int i = 0; i < saved.size() && i < 200; i++) {
            final long savedChat = saved.get(i)[0];
            ArrayList<Integer> ids = byChat.get(savedChat);
            if (ids == null) {
                byChat.put(savedChat, ids = new ArrayList<>());
            }
            ids.add((int) saved.get(i)[1]);
        }
        final HashMap<String, MessageObject> found = new HashMap<>();
        final int[] pending = {byChat.size()};
        for (java.util.Map.Entry<Long, ArrayList<Integer>> entry : byChat.entrySet()) {
            final TLRPC.Chat savedChat = getMessagesController().getChat(entry.getKey());
            if (savedChat == null) {
                pending[0]--;
                continue;
            }
            final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = MessagesController.getInputChannel(savedChat);
            req.id.addAll(entry.getValue());
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    for (int i = 0; i < res.messages.size(); i++) {
                        final TLRPC.Message message = res.messages.get(i);
                        if (!(message instanceof TLRPC.TL_messageEmpty)) {
                            found.put(entry.getKey() + ":" + message.id, new MessageObject(currentAccount, message, false, false));
                        }
                    }
                }
                if (--pending[0] <= 0) {
                    finishSaved(saved, found);
                }
            }));
        }
        if (pending[0] <= 0) {
            finishSaved(saved, found);
        }
    }

    private void finishSaved(ArrayList<long[]> saved, HashMap<String, MessageObject> found) {
        loading[TAB_SAVED] = false;
        if (isDead()) {
            return;
        }
        tiles[TAB_SAVED].clear();
        albums[TAB_SAVED].clear();
        for (int i = 0; i < saved.size(); i++) {
            final MessageObject message = found.get(saved.get(i)[0] + ":" + saved.get(i)[1]);
            if (message != null) {
                addTile(TAB_SAVED, message);
            }
        }
        if (tab == TAB_SAVED && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void openPost(MessageObject message) {
        final Bundle args = new Bundle();
        // saved tiles come from many channels
        args.putLong("chat_id", tab == TAB_SAVED ? -message.getDialogId() : chatId);
        // the album's newest message, so the whole album is on the first page
        int startId = message.getId();
        if (message.getGroupId() != 0 && albumSizes.containsKey(message)) {
            startId += albumSizes.get(message) - 1;
        }
        args.putInt("start_message_id", startId);
        presentFragment(new ChannelFeedActivity(args));
    }

    private void openInfo() {
        if (user != null) {
            final Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            if (self) {
                args.putBoolean("my_profile", true);
            }
            presentFragment(new ProfileActivity(args));
        } else {
            TigiNavigation.openChatInfo(this, chatId);
        }
    }

    /** The own channel's subscribers (Telegram's member list); others' subscriber lists are private. */
    private void openFollowers() {
        if (chat == null || !ChatObject.canChangeChatInfo(chat)) {
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        args.putInt("type", ChatUsersActivity.TYPE_USERS);
        presentFragment(new ChatUsersActivity(args));
    }

    private void openMessage() {
        final Bundle args = new Bundle();
        if (user != null) {
            args.putLong("user_id", user.id);
        } else {
            args.putLong("chat_id", chatId);
        }
        presentFragment(new ChatActivity(args));
    }

    private void shareProfile() {
        if (getParentActivity() == null) {
            return;
        }
        final String username = user != null ? UserObject.getPublicUsername(user) : ChatObject.getPublicUsername(chat);
        if (TextUtils.isEmpty(username)) {
            org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiSharePrivate)).show();
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, "https://t.me/" + username);
        getParentActivity().startActivity(Intent.createChooser(intent, user != null ? UserObject.getUserName(user) : chat.title));
    }

    private void join() {
        if (chat == null || !ChatObject.isNotInChat(chat)) {
            return;
        }
        getMessagesController().addUserToChat(chat.id, getUserConfig().getCurrentUser(), 0, null, this, true, () -> {
            if (header != null) {
                header.bind();
            }
        }, err -> true);
    }

    private void openStories() {
        final long dialogId = user != null ? user.id : -chatId;
        if (getParentActivity() != null && getMessagesController().getStoriesController().hasStories(dialogId)) {
            getOrCreateStoryViewer().open(getParentActivity(), dialogId, (did, messageId, storyId, type, holder) -> false);
        }
    }

    /* views */

    private class HeaderView extends LinearLayout {
        private final StoryRingAvatar avatar;
        private final BackupImageView cover;
        private final TextView postsValue;
        private final TextView followersValue;
        private final TextView postsLabel;
        private final TextView followersLabel;
        private final TextView followingValue;
        private final TextView followingLabel;
        private final TextView nameView;
        private final TextView usernameView;
        private final TextView aboutView;
        private final TextView primaryButton;
        private final TextView messageButton;
        private final TextView shareButton;
        private final ImageView infoButton;
        private final TextView postsTab;
        private final TextView reelsTab;
        private final TextView savedTab;
        private final HighlightsRow highlights;

        HeaderView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(16), 0, dp(16), 0);

            // a soft band from the profile photo behind the avatar
            cover = new BackupImageView(context);
            cover.setAlpha(0.85f);
            addView(cover, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 72, -16, 0, -16, 0));

            final LinearLayout top = new LinearLayout(context);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.BOTTOM);
            avatar = new StoryRingAvatar(context);
            avatar.setOnClickListener(v -> {
                if (self && !getMessagesController().getStoriesController().hasStories(user.id)) {
                    // own photo without stories: Telegram's profile, where the photo is set
                    openInfo();
                } else {
                    openStories();
                }
            });
            top.addView(avatar, LayoutHelper.createLinear(88, 88));
            top.setPadding(0, 0, 0, 0);
            final LinearLayout posts = counter(context);
            postsValue = (TextView) posts.getChildAt(0);
            postsLabel = (TextView) posts.getChildAt(1);
            postsLabel.setText(LocaleController.getString(R.string.TigiProfilePosts));
            top.addView(posts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final LinearLayout followers = counter(context);
            followersValue = (TextView) followers.getChildAt(0);
            followersLabel = (TextView) followers.getChildAt(1);
            followersLabel.setText(LocaleController.getString(R.string.TigiProfileFollowers));
            top.addView(followers, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            followers.setOnClickListener(v -> openFollowers());
            final LinearLayout following = counter(context);
            followingValue = (TextView) following.getChildAt(0);
            followingLabel = (TextView) following.getChildAt(1);
            followingLabel.setText(LocaleController.getString(R.string.TigiProfileFollowingCount));
            following.setOnClickListener(v -> presentFragment(new TigiFollowingActivity()));
            following.setVisibility(self ? VISIBLE : GONE);
            top.addView(following, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            // the avatar overlaps the band
            addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, -40, 0, 0));

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            nameView.setTypeface(TigiStyle.displayBold());
            nameView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            usernameView = new TextView(context);
            usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            usernameView.setSingleLine(true);
            usernameView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            aboutView = new TextView(context);
            aboutView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            aboutView.setAutoLinkMask(Linkify.WEB_URLS);
            aboutView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(aboutView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            final LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(HORIZONTAL);
            primaryButton = button(context);
            primaryButton.setOnClickListener(v -> {
                if (self) {
                    presentFragment(new UserInfoActivity());
                } else if (ChatObject.canChangeChatInfo(chat)) {
                    final Bundle args = new Bundle();
                    args.putLong("chat_id", chatId);
                    presentFragment(new ChatEditActivity(args));
                } else if (ChatObject.isNotInChat(chat)) {
                    join();
                } else {
                    openMessage();
                }
            });
            buttons.addView(primaryButton, LayoutHelper.createLinear(0, 36, 1f, 0, 0, 6, 0));
            messageButton = button(context);
            messageButton.setText(LocaleController.getString(user != null ? R.string.TigiProfileMessage : R.string.TigiOpenChatView));
            messageButton.setOnClickListener(v -> openMessage());
            buttons.addView(messageButton, LayoutHelper.createLinear(0, 36, 1f, 0, 0, 6, 0));
            shareButton = button(context);
            shareButton.setText(LocaleController.getString(self ? R.string.TigiProfileShareProfile : R.string.TigiProfileShare));
            shareButton.setOnClickListener(v -> shareProfile());
            buttons.addView(shareButton, LayoutHelper.createLinear(0, 36, 1f, 0, 0, 6, 0));
            infoButton = new ImageView(context);
            infoButton.setScaleType(ImageView.ScaleType.CENTER);
            infoButton.setImageResource(R.drawable.msg_info);
            infoButton.setContentDescription(LocaleController.getString(R.string.Info));
            infoButton.setOnClickListener(v -> openInfo());
            buttons.addView(infoButton, LayoutHelper.createLinear(44, 36));
            if (self) {
                // Settings and Telegram's profile are in the top bar
                infoButton.setVisibility(GONE);
            }
            addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 10));

            // highlights: stories pinned to the profile
            highlights = new HighlightsRow(context);
            addView(highlights, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -16, 0, -16, 6));

            final LinearLayout tabs = new LinearLayout(context);
            tabs.setOrientation(HORIZONTAL);
            postsTab = tabView(context, R.string.TigiProfilePosts, TAB_POSTS);
            reelsTab = tabView(context, R.string.MainTabsReels, TAB_REELS);
            savedTab = tabView(context, R.string.NilakSavedTab, TAB_SAVED);
            tabs.addView(postsTab, LayoutHelper.createLinear(0, 44, 1f));
            tabs.addView(reelsTab, LayoutHelper.createLinear(0, 44, 1f));
            if (self) {
                // bookmarks are private: only on the own profile
                tabs.addView(savedTab, LayoutHelper.createLinear(0, 44, 1f));
            }
            addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, -16, 0, -16, 0));

            updateColors();
            bind();
        }

        private LinearLayout counter(Context context) {
            final LinearLayout box = new LinearLayout(context);
            box.setOrientation(VERTICAL);
            box.setGravity(Gravity.CENTER);
            final TextView value = new TextView(context);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            value.setTypeface(TigiStyle.displayExtraBold());
            value.setGravity(Gravity.CENTER);
            final TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            label.setGravity(Gravity.CENTER);
            box.addView(value);
            box.addView(label);
            return box;
        }

        private TextView button(Context context) {
            final TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            view.setTypeface(TigiStyle.displayBold());
            view.setGravity(Gravity.CENTER);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setPadding(dp(6), 0, dp(6), 0);
            return view;
        }

        private TextView tabView(Context context, int text, int which) {
            final TextView view = new TextView(context);
            view.setText(LocaleController.getString(text));
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            view.setTypeface(AndroidUtilities.bold());
            view.setGravity(Gravity.CENTER);
            view.setOnClickListener(v -> {
                if (tab != which) {
                    tab = which;
                    if (which == TAB_SAVED) {
                        // bookmarks change in the feeds: read them again
                        reachedEnd[TAB_SAVED] = false;
                    }
                    updateColors();
                    adapter.notifyDataSetChanged();
                    load(which);
                }
            });
            return view;
        }

        void updateColors() {
            final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            postsValue.setTextColor(text);
            followersValue.setTextColor(text);
            postsLabel.setTextColor(gray);
            followersLabel.setTextColor(gray);
            followingValue.setTextColor(text);
            followingLabel.setTextColor(gray);
            usernameView.setTextColor(gray);
            nameView.setTextColor(text);
            aboutView.setTextColor(text);
            aboutView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
            final int accent = TigiStyle.accent();
            messageButton.setTextColor(accent);
            messageButton.setBackground(TigiStyle.tintedPill(10));
            shareButton.setTextColor(accent);
            shareButton.setBackground(TigiStyle.tintedPill(10));
            infoButton.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            infoButton.setBackground(TigiStyle.tintedPill(10));
            postsTab.setTextColor(tab == TAB_POSTS ? accent : gray);
            reelsTab.setTextColor(tab == TAB_REELS ? accent : gray);
            savedTab.setTextColor(tab == TAB_SAVED ? accent : gray);
            postsTab.setBackground(tab == TAB_POSTS ? underline(accent) : null);
            reelsTab.setBackground(tab == TAB_REELS ? underline(accent) : null);
            savedTab.setBackground(tab == TAB_SAVED ? underline(accent) : null);
            bind();
        }

        private android.graphics.drawable.Drawable underline(int color) {
            final android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
            line.setColor(color);
            final android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{line});
            layer.setLayerInset(0, 0, dp(42), 0, 0);
            return layer;
        }

        private void bindCover() {
            // the profile photo, tiny and blurred, over the brand colours
            final TLObject owner = user != null ? user : chat;
            final android.graphics.drawable.GradientDrawable fallback = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BL_TR, new int[]{TigiStyle.PRIMARY, TigiStyle.LILAC});
            final ImageLocation location = owner instanceof TLRPC.User ? ImageLocation.getForUser((TLRPC.User) owner, ImageLocation.TYPE_SMALL)
                : owner instanceof TLRPC.Chat ? ImageLocation.getForChat((TLRPC.Chat) owner, ImageLocation.TYPE_SMALL) : null;
            if (location != null) {
                cover.setImage(location, "40_40_b2", fallback, owner);
            } else {
                cover.setImageDrawable(fallback);
            }
        }

        private void bindSelf() {
            final TLRPC.UserFull userFull = getMessagesController().getUserFull(user.id);
            final String about = userFull != null ? userFull.about : null;
            aboutView.setText(about);
            aboutView.setVisibility(TextUtils.isEmpty(about) ? GONE : VISIBLE);
            final TLRPC.ChatFull full = chatId != 0 ? getMessagesController().getChatFull(chatId) : null;
            postsValue.setText(chat == null ? "0" : postsCount >= 0 ? LocaleController.formatShortNumber(postsCount, null) : "–");
            // followers of the own channel, minus the user (the channel's owner is counted as a member)
            final int members = chat == null ? 0 : full != null && full.participants_count > 0 ? full.participants_count : chat.participants_count;
            followersValue.setText(LocaleController.formatShortNumber(Math.max(0, members - 1), null));
            primaryButton.setText(LocaleController.getString(R.string.TigiProfileEdit));
            primaryButton.setTextColor(0xFFFFFFFF);
            primaryButton.setBackground(TigiStyle.primaryPill(10));
            messageButton.setVisibility(GONE);
            followingValue.setText(LocaleController.formatShortNumber(TigiPosting.followedChannels(currentAccount).size(), null));
        }

        void bind() {
            final TLRPC.ChatFull full = getMessagesController().getChatFull(chatId);
            avatar.bind();
            bindCover();
            highlights.bind();
            nameView.setText(user != null ? UserObject.getUserName(user) : chat.title);
            final String username = user != null ? UserObject.getPublicUsername(user) : ChatObject.getPublicUsername(chat);
            usernameView.setText(TextUtils.isEmpty(username) ? null : "@" + username);
            usernameView.setVisibility(TextUtils.isEmpty(username) ? GONE : VISIBLE);
            if (self) {
                bindSelf();
                return;
            }
            final String about = user != null ? (getMessagesController().getUserFull(user.id) != null ? getMessagesController().getUserFull(user.id).about : null) : (full != null ? full.about : null);
            aboutView.setText(about);
            aboutView.setVisibility(TextUtils.isEmpty(about) ? GONE : VISIBLE);
            postsValue.setText(postsCount >= 0 ? LocaleController.formatShortNumber(postsCount, null) : "–");
            final int followers = full != null && full.participants_count > 0 ? full.participants_count : chat.participants_count;
            followersValue.setText(followers > 0 ? LocaleController.formatShortNumber(followers, null) : "–");
            final boolean member = !ChatObject.isNotInChat(chat);
            final boolean canEdit = ChatObject.canChangeChatInfo(chat);
            if (canEdit) {
                // own (or administered) channel: Instagram's "Edit profile"
                primaryButton.setText(LocaleController.getString(R.string.TigiProfileEdit));
                primaryButton.setTextColor(0xFFFFFFFF);
                primaryButton.setBackground(TigiStyle.primaryPill(10));
            } else if (member) {
                primaryButton.setText(LocaleController.getString(R.string.TigiProfileFollowing));
                primaryButton.setTextColor(TigiStyle.accent());
                primaryButton.setBackground(TigiStyle.tintedPill(10));
            } else {
                primaryButton.setText(LocaleController.getString(R.string.TigiProfileFollow));
                primaryButton.setTextColor(0xFFFFFFFF);
                primaryButton.setBackground(TigiStyle.primaryPill(10));
            }
            // a personal channel's owner is messaged directly, and "Following" opens the channel
            messageButton.setVisibility((user != null && !UserObject.isUserSelf(user)) || !member ? VISIBLE : GONE);
        }
    }

    private org.telegram.ui.Stories.StoriesController.StoriesList pinnedStories;
    private int pinnedLink = -1;

    /** Stories pinned to the profile, Instagram's highlights. Tap opens Telegram's story viewer at that story. */
    private class HighlightsRow extends android.widget.HorizontalScrollView {
        private final LinearLayout items;

        HighlightsRow(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            items = new LinearLayout(context);
            items.setOrientation(LinearLayout.HORIZONTAL);
            items.setPadding(dp(12), dp(4), dp(12), dp(4));
            addView(items, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            setVisibility(GONE);
        }

        void bind() {
            final long dialogId = user != null ? user.id : -chatId;
            if (dialogId == 0) {
                setVisibility(GONE);
                return;
            }
            if (pinnedStories == null) {
                pinnedStories = getMessagesController().getStoriesController().getStoriesList(dialogId, org.telegram.ui.Stories.StoriesController.StoriesList.TYPE_PINNED);
                if (pinnedStories != null) {
                    pinnedLink = pinnedStories.link();
                    pinnedStories.load(false, 20);
                }
            }
            items.removeAllViews();
            final ArrayList<MessageObject> stories = pinnedStories != null ? pinnedStories.messageObjects : new ArrayList<>();
            for (int i = 0; i < stories.size() && i < 20; i++) {
                final MessageObject story = stories.get(i);
                if (story.storyItem == null) {
                    continue;
                }
                items.addView(item(story), LayoutHelper.createLinear(70, LayoutHelper.WRAP_CONTENT));
            }
            if (self && TigiPosting.storiesOffered(currentAccount)) {
                items.addView(newItem(), LayoutHelper.createLinear(70, LayoutHelper.WRAP_CONTENT));
            }
            setVisibility(items.getChildCount() > 0 ? VISIBLE : GONE);
        }

        private View item(MessageObject story) {
            final Context context = getContext();
            final LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            final FrameLayout ring = new FrameLayout(context);
            ring.setBackground(Theme.createRoundRectDrawable(dp(28), Theme.getColor(Theme.key_windowBackgroundGray)));
            final BackupImageView image = new BackupImageView(context);
            image.setRoundRadius(dp(26));
            org.telegram.ui.Stories.StoriesUtilities.setStoryMiniImage(image.getImageReceiver(), story.storyItem);
            ring.addView(image, LayoutHelper.createFrame(52, 52, Gravity.CENTER));
            item.addView(ring, LayoutHelper.createLinear(56, 56));
            final TextView caption = new TextView(context);
            final String text = story.storyItem.caption;
            caption.setText(TextUtils.isEmpty(text) ? LocaleController.formatDateChat(story.storyItem.date) : text);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setGravity(Gravity.CENTER);
            item.addView(caption, LayoutHelper.createLinear(64, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            item.setContentDescription(LocaleController.getString(R.string.NilakHighlight) + ", " + caption.getText());
            item.setOnClickListener(v -> {
                if (getParentActivity() != null && pinnedStories != null) {
                    getOrCreateStoryViewer().open(getParentActivity(), story.storyItem.id, pinnedStories, (did, messageId, storyId, type, holder) -> false);
                }
            });
            return item;
        }

        private View newItem() {
            final Context context = getContext();
            final LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            final TextView plus = new TextView(context);
            plus.setText("+");
            plus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            plus.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            plus.setGravity(Gravity.CENTER);
            final android.graphics.drawable.GradientDrawable dashed = new android.graphics.drawable.GradientDrawable();
            dashed.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dashed.setStroke(dp(1.5f), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), dp(4), dp(3));
            plus.setBackground(dashed);
            item.addView(plus, LayoutHelper.createLinear(56, 56));
            final TextView caption = new TextView(context);
            caption.setText(LocaleController.getString(R.string.NilakNewHighlight));
            caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            caption.setGravity(Gravity.CENTER);
            item.addView(caption, LayoutHelper.createLinear(64, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            // a new story; Telegram keeps it on the profile when the user pins it
            item.setOnClickListener(v -> TigiPosting.openStoryRecorder(TigiProfileActivity.this));
            return item;
        }
    }

    /** Avatar with Telegram's story ring when there are stories to watch. */
    private class StoryRingAvatar extends FrameLayout {
        private final BackupImageView imageView;
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean hasStories;
        private boolean unread;

        StoryRingAvatar(Context context) {
            super(context);
            setWillNotDraw(false);
            imageView = new BackupImageView(context);
            imageView.setRoundRadius(dp(38));
            addView(imageView, LayoutHelper.createFrame(76, 76, Gravity.CENTER));
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2.5f));
        }

        void bind() {
            final AvatarDrawable drawable = new AvatarDrawable();
            if (user != null) {
                drawable.setInfo(currentAccount, user);
                imageView.setForUserOrChat(user, drawable);
            } else {
                drawable.setInfo(currentAccount, chat);
                imageView.setForUserOrChat(chat, drawable);
            }
            final long dialogId = user != null ? user.id : -chatId;
            hasStories = getMessagesController().getStoriesController().hasStories(dialogId);
            unread = hasStories && getMessagesController().getStoriesController().hasUnreadStories(dialogId);
            setContentDescription((user != null ? UserObject.getUserName(user) : chat.title) + (hasStories ? ", " + LocaleController.getString(R.string.TigiProfileStories) : ""));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!hasStories) {
                return;
            }
            if (unread) {
                ringPaint.setShader(new LinearGradient(0, getHeight(), getWidth(), 0, TigiStyle.RING_START, TigiStyle.RING_END, Shader.TileMode.CLAMP));
            } else {
                ringPaint.setShader(null);
                ringPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            }
            final float r = Math.min(getWidth(), getHeight()) / 2f - dp(2);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, ringPaint);
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        static final int TYPE_HEADER = 0;
        static final int TYPE_TILE = 1;
        static final int TYPE_STATE = 2;
        static final int TYPE_LOADING = 3;
        static final int FIRST_TILE = 1;

        @Override
        public int getItemCount() {
            final int count = tiles[tab].size();
            return 1 + (count == 0 ? 1 : count);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_HEADER;
            }
            if (tiles[tab].isEmpty()) {
                return loading[tab] || (!reachedEnd[tab] && errorText == null) ? TYPE_LOADING : TYPE_STATE;
            }
            return TYPE_TILE;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_TILE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_HEADER) {
                if (header == null) {
                    header = new HeaderView(parent.getContext());
                }
                if (header.getParent() instanceof ViewGroup) {
                    ((ViewGroup) header.getParent()).removeView(header);
                }
                view = header;
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (viewType == TYPE_LOADING) {
                // Telegram's own shimmer for photo grids while the first tiles load
                final org.telegram.ui.Components.FlickerLoadingView flicker = new org.telegram.ui.Components.FlickerLoadingView(parent.getContext());
                flicker.setViewType(org.telegram.ui.Components.FlickerLoadingView.PHOTOS_TYPE);
                flicker.setIsSingleCell(false);
                view = flicker;
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
            } else if (viewType == TYPE_STATE) {
                final TextView state = new TextView(parent.getContext());
                state.setGravity(Gravity.CENTER);
                state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                state.setPadding(dp(32), dp(48), dp(32), dp(48));
                view = state;
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                view = new Tile(parent.getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_TILE) {
                final MessageObject message = tiles[tab].get(position - FIRST_TILE);
                ((Tile) holder.itemView).bind(message, albumSizes.containsKey(message));
            } else if (holder.getItemViewType() == TYPE_STATE) {
                final TextView state = (TextView) holder.itemView;
                state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                if (errorText != null) {
                    state.setText(errorText);
                } else if (loading[tab]) {
                    state.setText(LocaleController.getString(R.string.TigiFeedLoading));
                } else if (tab == TAB_SAVED) {
                    state.setText(LocaleController.getString(R.string.NilakSavedEmpty));
                    state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    state.setOnClickListener(null);
                } else if (self) {
                    // own empty profile: an invitation to post, tapping it opens Create
                    state.setText(LocaleController.getString(R.string.TigiProfileFirstPost));
                    state.setTextColor(TigiStyle.accent());
                    state.setOnClickListener(v -> TigiCreateSheet.show(TigiProfileActivity.this));
                } else if (chat.restricted) {
                    state.setText(LocaleController.getString(R.string.TigiProfileRestricted));
                } else {
                    state.setText(LocaleController.getString(R.string.TigiProfileNoPosts));
                }
            } else if (header != null) {
                header.bind();
            }
        }
    }

    private class Tile extends FrameLayout {
        private final BackupImageView imageView;
        private final TextView badge;
        private final ImageView albumIcon;

        Tile(Context context) {
            super(context);
            setPadding(dp(1), dp(1), dp(1), dp(1));
            imageView = new BackupImageView(context);
            imageView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            badge = new TextView(context);
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setShadowLayer(dp(2), 0, 0, 0x99000000);
            addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 6, 0, 0, 4));
            albumIcon = new ImageView(context);
            albumIcon.setImageResource(R.drawable.msg_gallery);
            albumIcon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            addView(albumIcon, LayoutHelper.createFrame(20, 20, Gravity.TOP | Gravity.RIGHT, 0, 6, 6, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // square tiles
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }

        void bind(MessageObject message, boolean album) {
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, 320);
            imageView.setImage(ImageLocation.getForObject(size, message.photoThumbsObject), "200_200", (android.graphics.drawable.Drawable) null, message.photoThumbsObject);
            if (message.isVideo()) {
                badge.setVisibility(VISIBLE);
                badge.setText(AndroidUtilities.formatShortDuration((int) message.getDuration()));
            } else {
                badge.setVisibility(GONE);
            }
            albumIcon.setVisibility(album ? VISIBLE : GONE);
            setContentDescription(LocaleController.getString(message.isVideo() ? R.string.AttachVideo : R.string.AttachPhoto) + (album ? ", " + LocaleController.getString(R.string.TigiProfileAlbum) : ""));
        }
    }
}
