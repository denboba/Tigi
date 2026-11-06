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
import org.telegram.ui.Components.BulletinFactory;
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
    /** "With you": what you and this person shared in your chat (the old Reels slot; reels are in Posts now). */
    private static final int TAB_SHARED = TAB_REELS;
    private static final int TABS = 3;
    private static final int PAGE = 60;

    private long chatId;
    private long userId;
    private boolean self;
    private int knownTopMessage;
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
        applyPage(root);
        listView = new RecyclerListView(context);
        gridManager = new GridLayoutManager(context, 3);
        gridManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return 3; // rows lay out their own tiles (TileRow)
            }
        });
        listView.setLayoutManager(gridManager);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (gridManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 4) {
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
        // Lilac: a sprig in the top corner, behind the profile (tiles and text scroll over it)
        TigiLilac.addSprig(root, 120);
        fragmentView = root;
        load(TAB_POSTS);
        return fragmentView;
    }

    private static void applyPage(View view) {
        if (TigiLilac.on()) {
            view.setBackground(TigiLilac.page());
        } else {
            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
    }

    private TextView topTitle;
    private ImageView settingsButton;
    private ImageView regionButton;
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
        bar.addView(topTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 160 : 16, 0, LocaleController.isRTL ? 16 : 160, 0));

        final LinearLayout icons = new LinearLayout(context);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        if (self) {
            telegramProfileButton = topIcon(context, R.drawable.msg_qrcode, R.string.TigiProfileAccount);
            telegramProfileButton.setOnClickListener(v -> openInfo());
            icons.addView(telegramProfileButton, LayoutHelper.createLinear(48, 48));
            regionButton = topIcon(context, R.drawable.ic_ab_other, R.string.NilakMore);
            regionButton.setOnClickListener(v -> showNilakMenu());
            icons.addView(regionButton, LayoutHelper.createLinear(48, 48));
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
        // Lilac: solid round buttons, so the icons read clearly in front of the corner flowers
        view.setBackground(TigiLilac.on()
            ? new android.graphics.drawable.InsetDrawable(TigiLilac.roundButton(), dp(4))
            : Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
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
        if (regionButton != null) {
            regionButton.setColorFilter(filter);
        }
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
        } else {
            // a post made since the last visit: read the grid again so it shows
            final TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(-chatId);
            final int top = dialog != null ? dialog.top_message : 0;
            if (tiles[tab].isEmpty() && !loading[tab] || top != 0 && knownTopMessage != 0 && top != knownTopMessage) {
                resetGrid();
            }
            knownTopMessage = top;
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
                applyPage(fragmentView);
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
        if (which == TAB_SHARED || which == TAB_SAVED && chip != CHIP_BOOKMARKS) {
            loadShared();
            return;
        }
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
        // Posts: everything the channel posted (photos, videos, text posts, articles); Reels: videos
        req.filter = which == TAB_REELS ? new TLRPC.TL_inputMessagesFilterVideo() : new TLRPC.TL_inputMessagesFilterEmpty();
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
                if (!reachedEnd[which] && tiles[which].size() < PAGE / 2) {
                    // a page of service messages (photo or name changes) gave too few tiles: fetch on without
                    // waiting for a scroll, which never comes while the grid shows only the loading shimmer
                    AndroidUtilities.runOnUIThread(() -> load(which));
                }
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
        if (message.messageOwner instanceof TLRPC.TL_messageService || message.needDrawBluredPreview()) {
            return;
        }
        // every kind of post gets a tile (TigiGridTile draws it); only photos and videos of an album are merged
        final boolean visual = TigiFeedBase.isCardMedia(message);
        if (!visual) {
            tiles[which].add(message);
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
        if (TigiArticle.isArticle(message)) {
            TigiArticleReaderActivity.open(this, message);
            return;
        }
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

    /** Nilak's own settings and places: country and language, data saver, apps, friends, brief. */
    private void showNilakMenu() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] items = {
            LocaleController.getString(R.string.NilakContentRegion) + " · " + TigiRegion.summary(),
            LocaleController.getString(R.string.NilakDataSaver),
            LocaleController.getString(R.string.NilakApps),
            LocaleController.getString(R.string.NilakFriends),
            LocaleController.getString(R.string.NilakBriefReminder),
            LocaleController.getString(TigiLilac.flowers() ? R.string.NilakFlowersOn : R.string.NilakFlowersOff)
        };
        final int[] icons = {R.drawable.msg_language, R.drawable.msg2_data, R.drawable.msg_bots, R.drawable.msg_contacts, R.drawable.msg_saved, R.drawable.msg_theme};
        final CharSequence[] shown = TigiLilac.on() ? items : java.util.Arrays.copyOf(items, items.length - 1);
        final int[] shownIcons = TigiLilac.on() ? icons : java.util.Arrays.copyOf(icons, icons.length - 1);
        final org.telegram.ui.ActionBar.BottomSheet.Builder builder = new org.telegram.ui.ActionBar.BottomSheet.Builder(getParentActivity());
        builder.setItems(shown, shownIcons, (dialog, which) -> {
            switch (which) {
                case 0:
                    TigiRegion.showPicker(this, () -> {
                        TigiPosting.clearSuggestedCache(currentAccount);
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.NilakContentFrom, TigiRegion.summary())).show();
                    });
                    break;
                case 1: presentFragment(new TigiDataSaverActivity()); break;
                case 2: TigiAppsActivity.open(this); break;
                case 3: presentFragment(TigiTopicFeedActivity.of(TigiTopicFeedActivity.MODE_FRIENDS)); break;
                case 4: TigiTopicFeedActivity.showReminderPicker(this); break;
                case 5:
                    TigiLilac.setFlowers(!TigiLilac.flowers());
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                        LocaleController.getString(TigiLilac.flowers() ? R.string.NilakFlowersOn : R.string.NilakFlowersOff)).show();
                    break;
            }
        });
        showDialog(builder.create());
    }

    /** The profile photo full screen with every earlier photo to swipe through; without a photo, where one is set. */
    private void openPhotos() {
        final boolean hasPhoto = user != null ? user.photo != null && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty)
            : chat != null && chat.photo != null && !(chat.photo instanceof TLRPC.TL_chatPhotoEmpty);
        if (!hasPhoto) {
            if (self) {
                openInfo();
            }
            return;
        }
        final Bundle args = new Bundle();
        if (user != null) {
            args.putLong("user_id", user.id);
            if (self) {
                args.putBoolean("my_profile", true);
            }
        } else {
            args.putLong("chat_id", chatId);
        }
        args.putBoolean("expandPhoto", true);
        presentFragment(new ProfileActivity(args));
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
            // no band behind the avatar: the profile photo shows only in the avatar, as on Instagram
            cover.setVisibility(GONE);
            addView(cover, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0));

            final LinearLayout top = new LinearLayout(context);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.BOTTOM);
            avatar = new StoryRingAvatar(context);
            avatar.setOnClickListener(v -> {
                final long dialogId = user != null ? user.id : -chatId;
                if (getMessagesController().getStoriesController().hasStories(dialogId)) {
                    openStories();
                } else {
                    openPhotos();
                }
            });
            avatar.setOnLongClickListener(v -> {
                openPhotos();
                return true;
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
            following.setVisibility(self || userId != 0 ? VISIBLE : GONE);
            if (!self) {
                // who someone follows stays private: their count only, no list
                following.setOnClickListener(null);
                following.setClickable(false);
            }
            top.addView(following, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

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
                    presentFragment(new TigiEditProfileActivity());
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

            if (TigiLilac.on()) {
                // Lilac: the photo centred and larger, the name under it, the numbers on a white card
                top.removeView(posts);
                top.removeView(followers);
                top.removeView(following);
                top.setGravity(Gravity.CENTER_HORIZONTAL);
                avatar.setLayoutParams(LayoutHelper.createLinear(100, 100));
                ((LinearLayout.LayoutParams) top.getLayoutParams()).topMargin = dp(4);
                nameView.setGravity(Gravity.CENTER);
                nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
                nameView.setTypeface(TigiStyle.displayExtraBold());
                usernameView.setGravity(Gravity.CENTER);
                aboutView.setGravity(Gravity.CENTER);
                aboutView.setPadding(dp(16), dp(4), dp(16), 0);
                final LinearLayout stats = new LinearLayout(context);
                stats.setOrientation(HORIZONTAL);
                stats.setGravity(Gravity.CENTER_VERTICAL);
                stats.setPadding(0, dp(10), 0, dp(10));
                stats.setBackground(Theme.createRoundRectDrawable(dp(18), TigiLilac.card()));
                stats.setElevation(dp(1));
                stats.addView(posts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                stats.addView(statDivider(context), LayoutHelper.createLinear(1, 28));
                stats.addView(followers, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                final View secondDivider = statDivider(context);
                secondDivider.setVisibility(following.getVisibility());
                stats.addView(secondDivider, LayoutHelper.createLinear(1, 28));
                stats.addView(following, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                addView(stats, indexOfChild(buttons), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
            }

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
            // one grid for every kind of post (reels stand tall inside Posts); this slot is "With you" on people's profiles
            reelsTab.setText(LocaleController.getString(R.string.NilakWithYou));
            reelsTab.setVisibility(!self && userId != 0 ? VISIBLE : GONE);
            if (self) {
                // bookmarks are private: only on the own profile
                tabs.addView(savedTab, LayoutHelper.createLinear(0, 44, 1f));
            }
            addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, -16, 0, -16, 0));

            updateColors();
            bind();
        }

        private View statDivider(Context context) {
            final View line = new View(context);
            line.setBackgroundColor(Theme.multAlpha(TigiStyle.accent(), 0.15f));
            return line;
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
                    if (which == TAB_SHARED || which == TAB_SAVED) {
                        selectChip(which == TAB_SAVED ? CHIP_BOOKMARKS : CHIP_MEDIA);
                        loadCounters();
                    }
                    updateColors();
                    adapter.notifyDataSetChanged();
                    load(which);
                }
            });
            return view;
        }

        void updateColors() {
            final int text = TigiLilac.on() ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            final int gray = TigiLilac.on() ? TigiLilac.meta() : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
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
            TigiIdentity.sync(currentAccount, false);
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
            // people who are each other's contacts are friends
            final boolean friends = user != null && !self && user.mutual_contact;
            final String handle = TextUtils.isEmpty(username) ? null : "@" + username;
            final String line = friends ? (handle != null ? handle + " · " : "") + LocaleController.getString(R.string.NilakFriendsBadge) : handle;
            usernameView.setText(line);
            usernameView.setVisibility(TextUtils.isEmpty(line) ? GONE : VISIBLE);
            if (self) {
                bindSelf();
                return;
            }
            final String about = user != null ? (getMessagesController().getUserFull(user.id) != null ? getMessagesController().getUserFull(user.id).about : null) : (full != null ? full.about : null);
            aboutView.setText(TigiIdentity.strip(about));
            aboutView.setVisibility(TextUtils.isEmpty(TigiIdentity.strip(about)) ? GONE : VISIBLE);
            postsValue.setText(postsCount >= 0 ? LocaleController.formatShortNumber(postsCount, null) : "–");
            if (userId != 0) {
                // Following: the count their Nilak shares (only the number, if they chose to); "–" otherwise
                final int shared = TigiIdentity.decode(full != null ? full.about : null);
                followingValue.setText(shared >= 0 ? LocaleController.formatShortNumber(shared, null) : "–");
            }
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
            if (self) {
                // your past stories, only you see them
                items.addView(archiveItem(), LayoutHelper.createLinear(70, LayoutHelper.WRAP_CONTENT));
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

        private View archiveItem() {
            final Context context = getContext();
            final LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            final ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageResource(R.drawable.msg_archive);
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            final android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ring.setStroke(dp(1.5f), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            icon.setBackground(ring);
            item.addView(icon, LayoutHelper.createLinear(56, 56));
            final TextView caption = new TextView(context);
            caption.setText(LocaleController.getString(R.string.NilakArchive));
            caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            caption.setGravity(Gravity.CENTER);
            item.addView(caption, LayoutHelper.createLinear(64, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            item.setContentDescription(caption.getText());
            item.setOnClickListener(v -> {
                final Bundle args = new Bundle();
                args.putInt("type", org.telegram.ui.Components.MediaActivity.TYPE_ARCHIVED_CHANNEL_STORIES);
                args.putLong("dialog_id", getUserConfig().getClientUserId());
                presentFragment(new org.telegram.ui.Components.MediaActivity(args, null));
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

        static final int TYPE_CHIPS = 4;
        static final int TYPE_ITEM = 5;
        static final int TYPE_GROUP = 6;

        boolean shared() {
            return tab == TAB_SHARED || tab == TAB_SAVED;
        }

        /** Index of the first content row: after the header, and the chips on With you / Saved. */
        int first() {
            return shared() ? 2 : 1;
        }

        int contentCount() {
            if (shared() && chip == CHIP_GROUPS) {
                return commonChats.size();
            }
            if (shared() && chip != CHIP_MEDIA && chip != CHIP_BOOKMARKS) {
                return sharedItems.size();
            }
            buildRows();
            return rows.size();
        }

        @Override
        public int getItemCount() {
            final int count = contentCount();
            return first() + (count == 0 ? 1 : count);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_HEADER;
            }
            if (shared() && position == 1) {
                return TYPE_CHIPS;
            }
            if (contentCount() == 0) {
                if (shared() && (chip != CHIP_BOOKMARKS)) {
                    return sharedLoading || !sharedEnd ? TYPE_LOADING : TYPE_STATE;
                }
                return loading[tab] || (!reachedEnd[tab] && errorText == null) ? TYPE_LOADING : TYPE_STATE;
            }
            if (shared() && chip == CHIP_GROUPS) {
                return TYPE_GROUP;
            }
            if (shared() && chip != CHIP_MEDIA && chip != CHIP_BOOKMARKS) {
                return TYPE_ITEM;
            }
            return TYPE_TILE;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false; // the tiles inside a row take the taps
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
            } else if (viewType == TYPE_CHIPS) {
                view = new ChipsRow(parent.getContext());
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (viewType == TYPE_ITEM) {
                view = new SharedItem(parent.getContext());
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (viewType == TYPE_GROUP) {
                view = new GroupRow(parent.getContext());
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                view = new TileRow(parent.getContext());
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_TILE) {
                final int[] row = rows.get(position - first());
                ((TileRow) holder.itemView).bind(row[0], row[1]);
            } else if (holder.getItemViewType() == TYPE_CHIPS) {
                ((ChipsRow) holder.itemView).bind();
            } else if (holder.getItemViewType() == TYPE_ITEM) {
                ((SharedItem) holder.itemView).bind(sharedItems.get(position - first()));
            } else if (holder.getItemViewType() == TYPE_GROUP) {
                ((GroupRow) holder.itemView).bind(commonChats.get(position - first()));
            } else if (holder.getItemViewType() == TYPE_STATE && shared() && chip != CHIP_BOOKMARKS) {
                final TextView state = (TextView) holder.itemView;
                state.setOnClickListener(null);
                state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                state.setText(LocaleController.getString(R.string.NilakSharedEmpty));
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
                    state.setOnClickListener(v -> TigiCreateSheet.openCompose(TigiProfileActivity.this));
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

    /* the grid: rows of three, and every few rows a video standing two rows tall, alternating sides */

    private static final int ROW_THREE = 0;
    private static final int ROW_TALL_LEFT = 1;
    private static final int ROW_TALL_RIGHT = 2;
    /** Each row: {type, index of its first post}. Rebuilt from tiles[tab] whenever the list is counted. */
    private final ArrayList<int[]> rows = new ArrayList<>();

    private static boolean standsTall(MessageObject message) {
        return message.isVideo() && !message.isRoundVideo() && !message.isGif() && TigiFeedBase.isCardMedia(message);
    }

    /** The posts the grid shows now: the profile's posts, bookmarks, or the media of With you / Saved. */
    private ArrayList<MessageObject> gridList() {
        if (tab == TAB_SHARED || tab == TAB_SAVED && chip == CHIP_MEDIA) {
            return sharedMedia;
        }
        return tiles[tab];
    }

    private void buildRows() {
        rows.clear();
        final ArrayList<MessageObject> list = gridList();
        final boolean mosaic = tab == TAB_POSTS;
        boolean left = true;
        int sinceTall = 1;
        int i = 0;
        while (i < list.size()) {
            if (mosaic && sinceTall >= 1 && list.size() - i >= 5 && standsTall(list.get(i))) {
                rows.add(new int[]{left ? ROW_TALL_LEFT : ROW_TALL_RIGHT, i});
                left = !left;
                sinceTall = 0;
                i += 5;
            } else {
                rows.add(new int[]{ROW_THREE, i});
                sinceTall++;
                i += 3;
            }
        }
    }

    private class TileRow extends ViewGroup {
        private final TigiGridTile[] cells = new TigiGridTile[5];
        private int type;
        private int count;

        TileRow(Context context) {
            super(context);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = new TigiGridTile(context);
                cells[i].setOnClickListener(v -> {
                    final MessageObject message = ((TigiGridTile) v).getMessage();
                    if (message == null) {
                        return;
                    }
                    if (gridList() == sharedMedia) {
                        openSharedMedia(message);
                    } else {
                        openPost(message);
                    }
                });
                cells[i].setBackground(null);
                addView(cells[i]);
            }
        }

        void bind(int type, int start) {
            this.type = type;
            final ArrayList<MessageObject> list = gridList();
            count = Math.min(type == ROW_THREE ? 3 : 5, list.size() - start);
            for (int i = 0; i < cells.length; i++) {
                if (i < count) {
                    final MessageObject message = list.get(start + i);
                    final Integer album = albumSizes.get(message);
                    cells[i].setVisibility(VISIBLE);
                    cells[i].bind(message, album != null ? album : 1, type != ROW_THREE && i == 0);
                } else {
                    cells[i].setVisibility(GONE);
                }
            }
            requestLayout();
        }

        private int gap() {
            return dp(2);
        }

        private int side(int width) {
            return (width - 2 * gap()) / 3;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int s = side(width);
            final int height = type == ROW_THREE ? s : 2 * s + gap();
            for (int i = 0; i < count; i++) {
                final int h = type != ROW_THREE && i == 0 ? height : s;
                cells[i].measure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, height + gap());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int s = side(r - l);
            final int g = gap();
            if (type == ROW_THREE) {
                for (int i = 0; i < count; i++) {
                    final int x = i * (s + g);
                    cells[i].layout(x, 0, x + s, s);
                }
                return;
            }
            // tall video in its column, the four others in a 2x2 square beside it
            final int tallColumn = type == ROW_TALL_LEFT ? 0 : 2;
            final int tallX = tallColumn * (s + g);
            cells[0].layout(tallX, 0, tallX + s, 2 * s + g);
            final int firstColumn = type == ROW_TALL_LEFT ? 1 : 0;
            for (int i = 1; i < count; i++) {
                final int column = firstColumn + (i - 1) % 2;
                final int row = (i - 1) / 2;
                final int x = column * (s + g);
                final int y = row * (s + g);
                cells[i].layout(x, y, x + s, y + s);
            }
        }
    }

    /* With you (someone's profile) and Saved (your own): Telegram's shared media, as chips */

    private static final int CHIP_BOOKMARKS = 0;
    private static final int CHIP_MEDIA = 1;
    private static final int CHIP_FILES = 2;
    private static final int CHIP_LINKS = 3;
    private static final int CHIP_VOICE = 4;
    private static final int CHIP_MUSIC = 5;
    private static final int CHIP_GROUPS = 6;
    private static final int[] CHIP_NAMES = {R.string.NilakBookmarks, R.string.SharedMediaTab2, R.string.SharedFilesTab2, R.string.SharedLinksTab2,
        R.string.SharedVoiceTab2, R.string.SharedMusicTab2, R.string.SharedGroupsTab2};

    private int chip = CHIP_MEDIA;
    private final int[] chipCounts = {-1, -1, -1, -1, -1, -1, -1};
    private final ArrayList<MessageObject> sharedMedia = new ArrayList<>();
    private final ArrayList<MessageObject> sharedItems = new ArrayList<>();
    private final ArrayList<TLRPC.Chat> commonChats = new ArrayList<>();
    private int sharedOffset;
    private boolean sharedEnd;
    private boolean sharedLoading;
    private int sharedRequest;

    /** The chat whose shared media is shown: yours with this person, or your Saved Messages. */
    private long sharedDialog() {
        return self ? getUserConfig().getClientUserId() : userId;
    }

    private int[] chipsOnScreen() {
        return self ? new int[]{CHIP_BOOKMARKS, CHIP_MEDIA, CHIP_FILES, CHIP_LINKS, CHIP_VOICE, CHIP_MUSIC}
            : new int[]{CHIP_MEDIA, CHIP_FILES, CHIP_LINKS, CHIP_VOICE, CHIP_MUSIC, CHIP_GROUPS};
    }

    private static TLRPC.MessagesFilter filterOf(int chip) {
        switch (chip) {
            case CHIP_MEDIA:
                return new TLRPC.TL_inputMessagesFilterPhotoVideo();
            case CHIP_FILES:
                return new TLRPC.TL_inputMessagesFilterDocument();
            case CHIP_LINKS:
                return new TLRPC.TL_inputMessagesFilterUrl();
            case CHIP_VOICE:
                return new TLRPC.TL_inputMessagesFilterRoundVoice();
            case CHIP_MUSIC:
                return new TLRPC.TL_inputMessagesFilterMusic();
            default:
                return null;
        }
    }

    private void selectChip(int value) {
        if (sharedRequest != 0) {
            getConnectionsManager().cancelRequest(sharedRequest, true);
            sharedRequest = 0;
        }
        chip = value;
        sharedMedia.clear();
        sharedItems.clear();
        commonChats.clear();
        sharedOffset = 0;
        sharedEnd = false;
        sharedLoading = false;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        load(tab);
    }

    /** How many of each kind there are (one request), so empty chips can hide. */
    private void loadCounters() {
        final long dialog = sharedDialog();
        if (dialog == 0) {
            return;
        }
        final TLRPC.TL_messages_getSearchCounters req = new TLRPC.TL_messages_getSearchCounters();
        req.peer = getMessagesController().getInputPeer(dialog);
        if (req.peer == null) {
            return;
        }
        final int[] order = {CHIP_MEDIA, CHIP_FILES, CHIP_LINKS, CHIP_VOICE, CHIP_MUSIC};
        for (int c : order) {
            req.filters.add(filterOf(c));
        }
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof org.telegram.tgnet.Vector) || isDead()) {
                return;
            }
            final org.telegram.tgnet.Vector res = (org.telegram.tgnet.Vector) response;
            for (int i = 0; i < res.objects.size() && i < order.length; i++) {
                if (res.objects.get(i) instanceof TLRPC.TL_messages_searchCounter) {
                    chipCounts[order[i]] = ((TLRPC.TL_messages_searchCounter) res.objects.get(i)).count;
                }
            }
            if (!self && userId != 0) {
                final TLRPC.UserFull full = getMessagesController().getUserFull(userId);
                if (full != null) {
                    chipCounts[CHIP_GROUPS] = full.common_chats_count;
                }
            }
            if (adapter != null && (tab == TAB_SHARED || tab == TAB_SAVED)) {
                adapter.notifyItemChanged(1);
            }
        }));
    }

    private void loadShared() {
        if (sharedLoading || sharedEnd || isDead()) {
            return;
        }
        final long dialog = sharedDialog();
        if (dialog == 0) {
            sharedEnd = true;
            return;
        }
        sharedLoading = true;
        if (chip == CHIP_GROUPS) {
            final TLRPC.TL_messages_getCommonChats req = new TLRPC.TL_messages_getCommonChats();
            req.user_id = getMessagesController().getInputUser(userId);
            req.max_id = 0;
            req.limit = 100;
            sharedRequest = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                sharedRequest = 0;
                sharedLoading = false;
                sharedEnd = true;
                if (response instanceof TLRPC.messages_Chats && !isDead()) {
                    final ArrayList<TLRPC.Chat> chats = ((TLRPC.messages_Chats) response).chats;
                    getMessagesController().putChats(chats, false);
                    commonChats.addAll(chats);
                }
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }));
            return;
        }
        final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = getMessagesController().getInputPeer(dialog);
        req.q = "";
        req.filter = filterOf(chip);
        req.limit = 40;
        req.offset_id = sharedOffset;
        if (req.peer == null || req.filter == null) {
            sharedLoading = false;
            sharedEnd = true;
            return;
        }
        final int requestedChip = chip;
        sharedRequest = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            sharedRequest = 0;
            sharedLoading = false;
            if (isDead() || requestedChip != chip) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    if (message.id > 0 && (sharedOffset == 0 || message.id < sharedOffset)) {
                        sharedOffset = message.id;
                    }
                    final MessageObject object = new MessageObject(currentAccount, message, false, true);
                    (chip == CHIP_MEDIA ? sharedMedia : sharedItems).add(object);
                }
                sharedEnd = res.messages.size() < req.limit;
            } else {
                sharedEnd = true;
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }));
    }

    /** Photos and videos of With you / Saved open in Telegram's viewer, swiping through the rest. */
    private void openSharedMedia(MessageObject message) {
        if (getParentActivity() == null) {
            return;
        }
        final int index = sharedMedia.indexOf(message);
        PhotoViewer.getInstance().setParentActivity(this);
        PhotoViewer.getInstance().openPhoto(new ArrayList<>(sharedMedia), Math.max(0, index), sharedDialog(), 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    private class ChipsRow extends android.widget.HorizontalScrollView {
        private final LinearLayout items;

        ChipsRow(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            items = new LinearLayout(context);
            items.setOrientation(LinearLayout.HORIZONTAL);
            items.setPadding(dp(12), dp(8), dp(12), dp(8));
            addView(items);
        }

        void bind() {
            items.removeAllViews();
            for (int c : chipsOnScreen()) {
                final int count = chipCounts[c];
                if (count == 0 && c != chip && c != CHIP_BOOKMARKS) {
                    continue; // nothing of this kind: no chip
                }
                final TextView view = new TextView(getContext());
                view.setText(LocaleController.getString(CHIP_NAMES[c]) + (count > 0 ? " " + LocaleController.formatShortNumber(count, null) : ""));
                view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                view.setTypeface(AndroidUtilities.bold());
                view.setGravity(Gravity.CENTER);
                view.setPadding(dp(14), 0, dp(14), 0);
                final boolean on = c == chip;
                view.setTextColor(on ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                view.setBackground(on ? TigiStyle.primaryPill(16) : Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_windowBackgroundGray)));
                view.setOnClickListener(v -> {
                    if (chip != c) {
                        selectChip(c);
                    }
                });
                items.addView(view, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
            }
        }
    }

    /** A file, link, voice message or song: the same card the feeds use (download, PDF reader, player). */
    private class SharedItem extends FrameLayout {
        private final TigiAttachmentView attachment;
        private final TextView fallback;

        SharedItem(Context context) {
            super(context);
            attachment = new TigiAttachmentView(context, TigiProfileActivity.this);
            addView(attachment, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            fallback = new TextView(context);
            fallback.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            fallback.setPadding(dp(16), dp(10), dp(16), dp(10));
            fallback.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
            fallback.setLinkTextColor(TigiStyle.accent());
            fallback.setMaxLines(3);
            fallback.setEllipsize(TextUtils.TruncateAt.END);
            addView(fallback, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(MessageObject message) {
            if (attachment.bind(message)) {
                fallback.setVisibility(GONE);
            } else {
                // a link written in text, without a preview
                fallback.setVisibility(VISIBLE);
                fallback.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                fallback.setText(message.messageOwner.message);
            }
        }
    }

    /** A group or channel you are both in; opens it. */
    private class GroupRow extends LinearLayout {
        private final BackupImageView avatar;
        private final org.telegram.ui.Components.AvatarDrawable avatarDrawable = new org.telegram.ui.Components.AvatarDrawable();
        private final TextView title;
        private final TextView subtitle;
        private TLRPC.Chat chat;

        GroupRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(8), dp(16), dp(8));
            setBackground(Theme.getSelectorDrawable(false));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(22));
            addView(avatar, LayoutHelper.createLinear(44, 44));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setSingleLine(true);
            texts.addView(title);
            texts.addView(subtitle);
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
            setOnClickListener(v -> {
                if (chat != null) {
                    final Bundle args = new Bundle();
                    args.putLong("chat_id", chat.id);
                    presentFragment(new ChatActivity(args));
                }
            });
        }

        void bind(TLRPC.Chat chat) {
            this.chat = chat;
            avatarDrawable.setInfo(currentAccount, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
            title.setText(chat.title);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitle.setText(chat.participants_count > 0 ? LocaleController.formatPluralString(ChatObject.isChannelAndNotMegaGroup(chat) ? "Subscribers" : "Members", chat.participants_count) : "");
            setContentDescription(chat.title);
        }
    }
}
