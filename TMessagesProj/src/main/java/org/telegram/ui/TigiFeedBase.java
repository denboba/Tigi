package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TigiHeart;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.ReactionsContainerLayout;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.URLSpanUserMention;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Shared base of the Tigi Instagram-style feeds (Home and a single channel):
 * post cards, photo carousel, hearts, the full Telegram reaction picker and long captions.
 */
public abstract class TigiFeedBase extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int CAPTION_LINES = 3;

    protected static class FeedPost {
        TLRPC.Chat chat;
        TLRPC.User author;
        final ArrayList<MessageObject> messages = new ArrayList<>();
        final ArrayList<MessageObject> media = new ArrayList<>();
        CharSequence text;
        // a post the cards can't show (voice, file, poll, sticker...): a short label and a "View in chat" button
        CharSequence fallback;
        int date;
        long groupId;

        MessageObject primary() {
            return messages.get(0);
        }

        boolean isValid() {
            return !messages.isEmpty() && (!media.isEmpty() || !TextUtils.isEmpty(text) || !TextUtils.isEmpty(fallback));
        }
    }

    protected final ArrayList<FeedPost> posts = new ArrayList<>();
    protected RecyclerListView listView;
    protected LinearLayoutManager layoutManager;
    protected FeedAdapter adapter;
    protected TextView emptyView;
    protected boolean hasMainTabs;

    private FrameLayout overlay;
    private FrameLayout rootView;
    protected TigiInlinePlayer inlinePlayer;
    private boolean paused = true;
    private ReactionsContainerLayout pickerLayout;
    private View pickerScrim;

    protected TigiFeedBase(Bundle args) {
        super(args);
    }

    /* to implement */

    protected abstract View createHeaderView(Context context);

    protected abstract boolean isLoadingPosts();

    protected void onNearEnd() {
    }

    protected int bottomPadding() {
        return hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 32) : dp(24);
    }

    /** The avatar or name on a card: the person who posted (groups) or the channel. */
    protected void onHeaderClicked(FeedPost post) {
        if (post == null || post.chat == null) {
            return;
        }
        if (post.author != null) {
            TigiNavigation.openProfile(this, post.author.id);
        } else {
            TigiNavigation.openProfile(this, -post.chat.id);
        }
    }

    /** A plain tap on a card. mediaIndex is the photo that was tapped, or -1 for the rest of the card. */
    protected void onPostTapped(FeedPost post, int mediaIndex) {
        if (mediaIndex >= 0) {
            openMediaViewer(post, mediaIndex);
        }
    }

    /* view */

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.messageReceivedByServer);
        getNotificationCenter().addObserver(this, NotificationCenter.messageSendError);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().removeObserver(this, NotificationCenter.messageReceivedByServer);
        getNotificationCenter().removeObserver(this, NotificationCenter.messageSendError);
        if (inlinePlayer != null) {
            inlinePlayer.stop();
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();
        paused = true;
        hideReactionPicker();
        if (inlinePlayer != null) {
            // nothing plays from a screen that is not visible (another screen on top, app in background, screen off)
            inlinePlayer.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        paused = false;
        if (listView != null) {
            listView.post(this::checkAutoplay);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (pickerLayout != null) {
            if (invoked) {
                hideReactionPicker();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    /** True once the fragment is gone; network callbacks must not touch views after that. */
    protected boolean isDead() {
        return isFinishing() || fragmentView == null || getParentActivity() == null;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didUpdateReactions) {
            final long dialogId = (Long) args[0];
            final int msgId = (Integer) args[1];
            final FeedPost post = findPost(dialogId, msgId);
            if (post != null && args[2] instanceof TLRPC.TL_messageReactions) {
                MessageObject.updateReactions(post.primary().messageOwner, (TLRPC.TL_messageReactions) args[2]);
                refreshPost(post);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            final boolean scheduled = (Boolean) args[2];
            final int mode = args.length > 3 && args[3] instanceof Integer ? (Integer) args[3] : 0;
            if (scheduled || mode != 0) {
                return;
            }
            onNewMessages((Long) args[0], (ArrayList<MessageObject>) args[1]);
        } else if (id == NotificationCenter.messageReceivedByServer || id == NotificationCenter.messageSendError) {
            // the message object is updated in place by SendMessagesHelper; only the card needs a redraw
            if (listView == null) {
                return;
            }
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View child = listView.getChildAt(i);
                if (child instanceof FeedCell) {
                    ((FeedCell) child).bindStatus();
                }
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            if ((Boolean) args[2]) {
                return; // scheduled
            }
            final ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            final long channelId = (Long) args[1];
            boolean changed = false;
            for (int i = posts.size() - 1; i >= 0; i--) {
                final FeedPost post = posts.get(i);
                if (post.chat == null || (channelId != 0 && post.chat.id != channelId)) {
                    continue;
                }
                for (int m = post.messages.size() - 1; m >= 0; m--) {
                    if (ids.contains(post.messages.get(m).getId())) {
                        post.media.remove(post.messages.get(m));
                        post.messages.remove(m);
                        changed = true;
                    }
                }
                if (!post.isValid()) {
                    posts.remove(i);
                }
            }
            if (changed && adapter != null) {
                adapter.notifyDataSetChanged();
                updateEmptyView();
            }
        }
    }

    /** Whether a message that just arrived (or that the user just sent) belongs on this feed. */
    protected boolean acceptsNewMessage(long dialogId, MessageObject message) {
        return false;
    }

    private void onNewMessages(long dialogId, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty() || dialogId >= 0 || adapter == null) {
            return;
        }
        final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (chat == null) {
            return;
        }
        final ArrayList<FeedPost> added = new ArrayList<>();
        // oldest first, so album photos keep their order
        for (int i = messages.size() - 1; i >= 0; i--) {
            final MessageObject message = messages.get(i);
            if (message == null || message.messageOwner == null || message.messageOwner instanceof TLRPC.TL_messageService || !acceptsNewMessage(dialogId, message)) {
                continue;
            }
            if (findPostContaining(dialogId, message.getId()) != null) {
                continue;
            }
            final long groupId = message.getGroupId();
            FeedPost post = null;
            if (groupId != 0) {
                for (int p = 0; p < Math.min(posts.size(), 5) && post == null; p++) {
                    if (posts.get(p).groupId == groupId && posts.get(p).chat != null && posts.get(p).chat.id == chat.id) {
                        post = posts.get(p);
                    }
                }
                for (int p = 0; p < added.size() && post == null; p++) {
                    if (added.get(p).groupId == groupId) {
                        post = added.get(p);
                    }
                }
            }
            if (post == null) {
                post = new FeedPost();
                post.chat = chat;
                post.groupId = groupId;
                post.date = message.messageOwner.date;
                if (!chat.broadcast && message.messageOwner.from_id instanceof TLRPC.TL_peerUser) {
                    post.author = getMessagesController().getUser(message.messageOwner.from_id.user_id);
                }
                added.add(post);
            }
            post.messages.add(message);
            if (message.photoThumbs != null && !message.photoThumbs.isEmpty() && (message.isPhoto() || message.isVideo() || message.isGif())) {
                post.media.add(message);
            }
            if (TextUtils.isEmpty(post.text)) {
                post.text = !TextUtils.isEmpty(message.caption) ? message.caption : (post.media.isEmpty() && !TextUtils.isEmpty(message.messageText) && describeUnsupported(message) == null ? message.messageText : null);
            }
            if (post.media.isEmpty() && post.fallback == null) {
                post.fallback = describeUnsupported(message);
            }
        }
        boolean atTop = layoutManager == null || layoutManager.findFirstVisibleItemPosition() <= 0;
        int insertedCount = 0;
        for (int i = added.size() - 1; i >= 0; i--) {
            final FeedPost post = added.get(i);
            if (post.isValid() || post.primary().isSending()) {
                posts.add(0, post);
                insertedCount++;
            }
        }
        if (insertedCount > 0) {
            adapter.notifyItemRangeInserted(0, insertedCount);
            if (atTop && listView != null) {
                listView.scrollToPosition(0);
            }
            updateEmptyView();
        } else {
            // photos added to an album card that is already shown
            adapter.notifyDataSetChanged();
        }
    }

    protected FeedPost findPostContaining(long dialogId, int msgId) {
        for (int i = 0; i < posts.size(); i++) {
            final FeedPost post = posts.get(i);
            if (post.chat == null || -post.chat.id != dialogId) {
                continue;
            }
            for (int m = 0; m < post.messages.size(); m++) {
                if (post.messages.get(m).getId() == msgId) {
                    return post;
                }
            }
        }
        return null;
    }

    /** Posts that are still uploading or failed; a reload must keep them, the server does not know them yet. */
    protected ArrayList<FeedPost> unsentPosts() {
        final ArrayList<FeedPost> out = new ArrayList<>();
        for (int i = 0; i < posts.size(); i++) {
            final FeedPost post = posts.get(i);
            if (!post.messages.isEmpty() && (post.primary().isSending() || post.primary().isSendError())) {
                out.add(post);
            }
        }
        return out;
    }

    protected FeedPost findPost(long dialogId, int msgId) {
        for (int i = 0; i < posts.size(); i++) {
            final FeedPost post = posts.get(i);
            if (post.chat != null && -post.chat.id == dialogId && !post.messages.isEmpty() && post.primary().getId() == msgId) {
                return post;
            }
        }
        return null;
    }

    /** Puts the header below the status bar using the real window insets (statusBarHeight can still be 0 here). */
    protected void applyTopInset(View header) {
        header.setPadding(header.getPaddingLeft(), AndroidUtilities.statusBarHeight, header.getPaddingRight(), header.getPaddingBottom());
        ViewCompat.setOnApplyWindowInsetsListener(header, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(v.getPaddingLeft(), Math.max(bars.top, 0), v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        final FrameLayout root = new FrameLayout(context);
        rootView = root;
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(createHeaderView(context), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final FrameLayout body = new FrameLayout(context);
        content.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(5), 0, bottomPadding());
        adapter = new FeedAdapter();
        listView.setAdapter(adapter);
        inlinePlayer = new TigiInlinePlayer(currentAccount);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() >= posts.size() - 3) {
                    onNearEnd();
                }
                stopIfScrolledAway();
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    checkAutoplay();
                }
            }
        });
        body.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), 0, dp(32), 0);
        body.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        overlay = new FrameLayout(context);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateEmptyView();
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ArrayList<ThemeDescription> list = new ArrayList<>();
        final ThemeDescription.ThemeDescriptionDelegate delegate = this::updateColors;
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteBlackText));
        return list;
    }

    /** Re-applies theme colors after a theme or night mode switch. */
    protected void updateColors() {
        if (rootView == null) {
            return;
        }
        rootView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        if (emptyView != null) {
            emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
        updateHeaderColors();
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View child = listView.getChildAt(i);
                if (child instanceof FeedCell) {
                    ((FeedCell) child).updateColors();
                }
            }
            // cached cells were built with the old colors
            listView.getRecycledViewPool().clear();
        }
    }

    protected void updateHeaderColors() {
    }

    protected void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        if (!posts.isEmpty()) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText(LocaleController.getString(isLoadingPosts() ? R.string.TigiFeedLoading : R.string.TigiFeedEmpty));
    }

    /* posts from messages */

    protected ArrayList<FeedPost> buildPosts(TLRPC.Chat chat, ArrayList<TLRPC.Message> messages) {
        return buildPosts(chat, messages, new HashMap<>());
    }

    /**
     * albums maps grouped_id to posts already shown; photos of an album that continues from the previous page are
     * appended to that post instead of making a second card (the post object is then not in the returned list).
     */
    protected ArrayList<FeedPost> buildPosts(TLRPC.Chat chat, ArrayList<TLRPC.Message> messages, HashMap<Long, FeedPost> albums) {
        final ArrayList<FeedPost> out = new ArrayList<>();
        if (chat == null || messages == null) {
            return out;
        }
        // history comes newest first, albums are shown oldest photo first
        for (int i = messages.size() - 1; i >= 0; i--) {
            final TLRPC.Message message = messages.get(i);
            if (message == null || message instanceof TLRPC.TL_messageEmpty || message instanceof TLRPC.TL_messageService) {
                continue;
            }
            final MessageObject object = new MessageObject(currentAccount, message, true, false);
            final long groupId = message.grouped_id;
            FeedPost post = groupId != 0 ? albums.get(groupId) : null;
            if (post != null && !out.contains(post)) {
                // the rest of an album from an earlier page: those messages are older, they go first
                post.messages.add(0, object);
                if (object.photoThumbs != null && !object.photoThumbs.isEmpty() && (object.isPhoto() || object.isVideo() || object.isGif())) {
                    post.media.add(0, object);
                }
                if (TextUtils.isEmpty(post.text) && !TextUtils.isEmpty(object.caption)) {
                    post.text = object.caption;
                }
                continue;
            }
            if (post == null) {
                post = new FeedPost();
                post.chat = chat;
                post.date = message.date;
                post.groupId = groupId;
                if (!chat.broadcast && message.from_id instanceof TLRPC.TL_peerUser) {
                    post.author = getMessagesController().getUser(message.from_id.user_id);
                }
                out.add(post);
                if (groupId != 0) {
                    albums.put(groupId, post);
                }
            }
            post.messages.add(object);
            if (object.photoThumbs != null && !object.photoThumbs.isEmpty() && (object.isPhoto() || object.isVideo() || object.isGif())) {
                post.media.add(object);
            }
            if (post.text == null || post.text.length() == 0) {
                post.text = !TextUtils.isEmpty(object.caption) ? object.caption : (post.media.isEmpty() && !TextUtils.isEmpty(object.messageText) && describeUnsupported(object) == null ? object.messageText : null);
            }
            if (post.media.isEmpty() && post.fallback == null) {
                post.fallback = describeUnsupported(object);
            }
        }
        final ArrayList<FeedPost> valid = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).isValid()) {
                valid.add(out.get(i));
            }
        }
        Collections.sort(valid, (a, b) -> b.date - a.date);
        return valid;
    }

    /* hearts and reactions */

    protected String heartEmoji() {
        final String heart = TigiHeart.find(getMediaDataController().getReactionsList());
        return heart != null ? heart : TigiHeart.HEART;
    }

    protected static boolean isHeart(TLRPC.Reaction reaction) {
        return TigiHeart.isHeart(reaction);
    }

    /** Whether the channel allows this reaction at all. Unknown (no full info yet) counts as allowed. */
    protected boolean isReactionAllowed(FeedPost post, ReactionsLayoutInBubble.VisibleReaction reaction) {
        final TLRPC.ChatFull full = getMessagesController().getChatFull(post.chat.id);
        if (full == null || full.available_reactions == null || reaction == null) {
            return true;
        }
        if (full.available_reactions instanceof TLRPC.TL_chatReactionsNone) {
            return false;
        }
        if (full.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            if (reaction.emojicon == null) {
                return false;
            }
            final ArrayList<TLRPC.Reaction> list = ((TLRPC.TL_chatReactionsSome) full.available_reactions).reactions;
            for (int i = 0; i < list.size(); i++) {
                final TLRPC.Reaction r = list.get(i);
                if (r instanceof TLRPC.TL_reactionEmoji && reaction.emojicon.equals(((TLRPC.TL_reactionEmoji) r).emoticon)) {
                    return true;
                }
            }
            return false;
        }
        if (full.available_reactions instanceof TLRPC.TL_chatReactionsAll) {
            return reaction.emojicon != null || ((TLRPC.TL_chatReactionsAll) full.available_reactions).allow_custom;
        }
        return true;
    }

    protected static int heartCount(MessageObject message) {
        if (message.messageOwner.reactions == null) {
            return 0;
        }
        for (int i = 0; i < message.messageOwner.reactions.results.size(); i++) {
            final TLRPC.ReactionCount count = message.messageOwner.reactions.results.get(i);
            if (isHeart(count.reaction)) {
                return count.count;
            }
        }
        return 0;
    }

    protected static boolean heartChosen(MessageObject message) {
        if (message.messageOwner.reactions == null) {
            return false;
        }
        for (int i = 0; i < message.messageOwner.reactions.results.size(); i++) {
            final TLRPC.ReactionCount count = message.messageOwner.reactions.results.get(i);
            if (isHeart(count.reaction) && count.chosen) {
                return true;
            }
        }
        return false;
    }

    /** Returns false when the reaction could not be sent at all (not allowed in this channel). */
    protected boolean applyReaction(FeedPost post, ReactionsLayoutInBubble.VisibleReaction reaction, boolean big, boolean addToRecent) {
        if (post == null || post.messages.isEmpty() || reaction == null) {
            return false;
        }
        if (!isReactionAllowed(post, reaction)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiReactionsOff)).show();
            return false;
        }
        final MessageObject message = post.primary();
        final boolean added = message.selectReaction(reaction, big, false);
        final ArrayList<ReactionsLayoutInBubble.VisibleReaction> chosen = new ArrayList<>(message.getChoosenReactions());
        getSendMessagesHelper().sendReaction(message, chosen, added ? reaction : null, big, addToRecent, this, null, error -> {
            // the server said no (limit, reactions turned off meanwhile, flood): undo the local change
            message.selectReaction(reaction, big, false);
            refreshPost(post);
            if (!isDead()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiReactionFailed)).show();
            }
        });
        refreshPost(post);
        return true;
    }

    protected boolean toggleHeart(FeedPost post) {
        return applyReaction(post, ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(heartEmoji()), false, false);
    }

    protected void refreshPost(FeedPost post) {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof FeedCell && ((FeedCell) child).post == post) {
                ((FeedCell) child).bindActions();
            }
        }
    }

    /* video */

    /** Autoplay follows Telegram's own settings: autoplay videos/GIFs and the auto-download rules for this network. */
    protected boolean canAutoplay(MessageObject message) {
        if (message == null || message.isRoundVideo()) {
            return false;
        }
        if (message.isGif()) {
            return SharedConfig.isAutoplayGifs() && DownloadController.getInstance(currentAccount).canDownloadMedia(message);
        }
        if (message.isVideo()) {
            return SharedConfig.isAutoplayVideo() && DownloadController.getInstance(currentAccount).canDownloadMedia(message);
        }
        return false;
    }

    private float visibleFraction(View cell, View part) {
        if (listView == null || cell == null || part == null || part.getHeight() <= 0 || part.getVisibility() != View.VISIBLE) {
            return 0;
        }
        final int top = cell.getTop() + part.getTop();
        final int bottom = top + part.getHeight();
        final int visibleTop = Math.max(top, listView.getPaddingTop());
        final int visibleBottom = Math.min(bottom, listView.getHeight() - listView.getPaddingBottom());
        return Math.max(0, visibleBottom - visibleTop) / (float) part.getHeight();
    }

    /** Plays the video of the most visible card (if autoplay is allowed) and stops any other. */
    protected void checkAutoplay() {
        if (listView == null || inlinePlayer == null || paused || isDead()) {
            return;
        }
        FeedCell best = null;
        float bestFraction = 0;
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof FeedCell) {
                final float fraction = visibleFraction(child, ((FeedCell) child).mediaFrame);
                if (fraction > bestFraction) {
                    bestFraction = fraction;
                    best = (FeedCell) child;
                }
            }
        }
        final FeedCell.PhotoPage page = best != null && bestFraction >= 0.6f ? best.visiblePage() : null;
        final MessageObject message = page != null ? page.message : null;
        if (message != null && inlinePlayer.isPlaying(message)) {
            return;
        }
        if (message != null && canAutoplay(message)) {
            page.startPlayback();
        } else if (inlinePlayer.getMessage() != null && (best == null || !best.hosts(inlinePlayer.getMessage()))) {
            inlinePlayer.stop();
        }
    }

    private void stopIfScrolledAway() {
        if (listView == null || inlinePlayer == null || inlinePlayer.getMessage() == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof FeedCell && ((FeedCell) child).hosts(inlinePlayer.getMessage())) {
                if (visibleFraction(child, ((FeedCell) child).mediaFrame) < 0.3f) {
                    inlinePlayer.stop();
                }
                return;
            }
        }
        // its card was recycled
        inlinePlayer.stop();
    }

    /* comments and links */

    /** The post's comment thread in the linked discussion group; without one, the post in the channel chat. */
    protected void openComments(FeedPost post) {
        if (post == null || post.messages.isEmpty() || post.chat == null || isDead()) {
            return;
        }
        final MessageObject message = post.primary();
        final TLRPC.MessageReplies replies = message.messageOwner.replies;
        if (replies == null || !replies.comments || message.getId() <= 0) {
            openPostInChat(post, message);
            return;
        }
        final TLRPC.Chat chat = post.chat;
        final TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = getMessagesController().getInputPeer(-chat.id);
        req.msg_id = message.getId();
        final int requestId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isDead()) {
                return;
            }
            if (response instanceof TLRPC.TL_messages_discussionMessage) {
                final TLRPC.TL_messages_discussionMessage res = (TLRPC.TL_messages_discussionMessage) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                final ArrayList<MessageObject> thread = new ArrayList<>();
                for (int i = 0; i < res.messages.size(); i++) {
                    thread.add(new MessageObject(currentAccount, res.messages.get(i), true, true));
                }
                if (!thread.isEmpty()) {
                    // the same way Telegram opens a comments link
                    final Bundle args = new Bundle();
                    args.putLong("chat_id", -thread.get(0).getDialogId());
                    args.putInt("message_id", Math.max(1, message.getId()));
                    final ChatActivity chatActivity = new ChatActivity(args);
                    chatActivity.setThreadMessages(thread, chat, req.msg_id, res.read_inbox_max_id, res.read_outbox_max_id, null);
                    presentFragment(chatActivity);
                    return;
                }
            }
            openPostInChat(post, message);
        }));
        getConnectionsManager().bindRequestToGuid(requestId, classGuid);
    }

    /** Taps on links, @mentions and #hashtags in captions go where they go in a chat. */
    protected void onCaptionLinkClicked(ClickableSpan span) {
        if (getParentActivity() == null) {
            return;
        }
        if (span instanceof URLSpanUserMention) {
            final long userId = org.telegram.messenger.Utilities.parseLong(((URLSpanUserMention) span).getURL());
            if (userId != 0) {
                TigiNavigation.openProfile(this, userId);
            }
        } else if (span instanceof URLSpan) {
            final String url = ((URLSpan) span).getURL();
            if (url == null) {
                return;
            }
            if (url.startsWith("@")) {
                getMessagesController().openByUserName(url.substring(1), this, 1);
            } else if (url.startsWith("#") || url.startsWith("$")) {
                presentFragment(new org.telegram.ui.Components.HashtagActivity(url, getResourceProvider()));
            } else if (url.startsWith("/")) {
                // bot commands mean nothing outside the chat
            } else {
                Browser.openUrl(getParentActivity(), url);
            }
        } else {
            span.onClick(fragmentView);
        }
    }

    private final LinkMovementMethod captionLinks = new LinkMovementMethod() {
        @Override
        public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
            final int action = event.getAction();
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) {
                return false;
            }
            final Layout layout = widget.getLayout();
            if (layout == null) {
                return false;
            }
            final int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
            final int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
            final int line = layout.getLineForVertical(y);
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) {
                return false;
            }
            final int offset = layout.getOffsetForHorizontal(line, x);
            final ClickableSpan[] spans = buffer.getSpans(offset, offset, ClickableSpan.class);
            if (spans.length == 0) {
                return false;
            }
            if (action == MotionEvent.ACTION_UP) {
                onCaptionLinkClicked(spans[0]);
            }
            return true;
        }
    };

    /** A label for posts the cards can't show; null when the post is shown normally. */
    protected CharSequence describeUnsupported(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return null;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
            return null;
        }
        if (message.isRoundVideo()) {
            return LocaleController.getString(R.string.AttachRound);
        }
        if (message.isVoice()) {
            return LocaleController.getString(R.string.AttachAudio);
        }
        if (message.isMusic()) {
            return LocaleController.getString(R.string.AttachMusic);
        }
        if (message.isSticker() || message.isAnimatedSticker()) {
            return LocaleController.getString(R.string.AttachSticker);
        }
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            final TLRPC.TL_messageMediaPoll poll = (TLRPC.TL_messageMediaPoll) media;
            final String question = poll.poll != null && poll.poll.question != null ? poll.poll.question.text : null;
            return TextUtils.isEmpty(question) ? LocaleController.getString(R.string.Poll) : LocaleController.getString(R.string.Poll) + ": " + question;
        }
        if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive || media instanceof TLRPC.TL_messageMediaVenue) {
            return LocaleController.getString(R.string.AttachLocation);
        }
        if (media instanceof TLRPC.TL_messageMediaContact) {
            return LocaleController.getString(R.string.AttachContact);
        }
        if (media instanceof TLRPC.TL_messageMediaGame) {
            return LocaleController.getString(R.string.AttachGame);
        }
        if (message.getDocument() != null && !message.isVideo() && !message.isGif()) {
            final String name = FileLoader.getDocumentFileName(message.getDocument());
            return TextUtils.isEmpty(name) ? LocaleController.getString(R.string.AttachDocument) : LocaleController.getString(R.string.AttachDocument) + ": " + name;
        }
        if (message.isPhoto() || message.isVideo() || message.isGif()) {
            return null;
        }
        return LocaleController.getString(R.string.TigiPostUnsupported);
    }

    /** Shows Telegram's reaction bar right above the point (screen coordinates) that was pressed. */
    protected void showReactionPicker(FeedPost post, float pressRawX, float pressRawY) {
        if (overlay == null || isDead() || post == null || post.messages.isEmpty()) {
            return;
        }
        hideReactionPicker();
        final Context context = fragmentView.getContext();
        final MessageObject message = post.primary();
        final TLRPC.ChatFull full = getMessagesController().getChatFull(post.chat.id);
        if (full == null) {
            getMessagesController().loadFullChat(post.chat.id, 0, true);
        }

        pickerScrim = new View(context);
        pickerScrim.setBackgroundColor(0x33000000);
        pickerScrim.setOnClickListener(v -> hideReactionPicker());
        overlay.addView(pickerScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        pickerLayout = new ReactionsContainerLayout(ReactionsContainerLayout.TYPE_DEFAULT, this, context, currentAccount, getResourceProvider());
        pickerLayout.setClipChildren(false);
        pickerLayout.setClipToPadding(false);
        pickerLayout.setFlippedVertically(true);
        pickerLayout.setPadding(dp(4), dp(22), dp(4), dp(4));
        pickerLayout.setDelegate(new ReactionsContainerLayout.ReactionsContainerDelegate() {
            @Override
            public void onReactionClicked(View view, ReactionsLayoutInBubble.VisibleReaction visibleReaction, boolean longpress, boolean addToRecent) {
                applyReaction(post, visibleReaction, longpress, addToRecent);
                AndroidUtilities.runOnUIThread(() -> hideReactionPicker());
            }

            @Override
            public void hideMenu() {
                hideReactionPicker();
            }
        });

        final int[] overlayLocation = new int[2];
        overlay.getLocationInWindow(overlayLocation);
        final int pickerHeight = dp(70 + 22);
        final int minTop = Math.max(AndroidUtilities.statusBarHeight, dp(24)) + dp(8);
        final int maxTop = Math.max(minTop, overlay.getHeight() - pickerHeight - dp(8));
        final int top = Math.max(minTop, Math.min(maxTop, (int) (pressRawY - overlayLocation[1]) + dp(8)));
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pickerHeight);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = dp(8);
        params.topMargin = top;
        overlay.addView(pickerLayout, params);
        overlay.setVisibility(View.VISIBLE);
        pickerLayout.setMessage(message, full, true);
    }

    protected void hideReactionPicker() {
        if (overlay == null) {
            return;
        }
        if (pickerLayout != null) {
            overlay.removeView(pickerLayout);
            pickerLayout = null;
        }
        if (pickerScrim != null) {
            overlay.removeView(pickerScrim);
            pickerScrim = null;
        }
        overlay.setVisibility(View.GONE);
    }

    /* navigation */

    protected void openChannelFeed(TLRPC.Chat chat) {
        if (chat == null) {
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        presentFragment(new ChannelFeedActivity(args));
    }

    protected void openMediaViewer(FeedPost post, int index) {
        if (getParentActivity() == null || post == null || post.media.isEmpty()) {
            return;
        }
        if (inlinePlayer != null) {
            inlinePlayer.stop();
        }
        PhotoViewer.getInstance().setParentActivity(this);
        PhotoViewer.getInstance().openPhoto(new ArrayList<>(post.media), Math.max(0, Math.min(index, post.media.size() - 1)), -post.chat.id, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    protected void openPostInChat(FeedPost post, MessageObject message) {
        if (post == null || post.chat == null || message == null) {
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", post.chat.id);
        args.putInt("message_id", message.getId());
        presentFragment(new ChatActivity(args));
    }

    /** The card's three-dot menu: everything Telegram allows for this post, plus report for other people's posts. */
    protected void showPostMenu(FeedPost post, View anchor) {
        if (post == null || post.messages.isEmpty() || post.chat == null || anchor == null || isDead()) {
            return;
        }
        final MessageObject primary = post.primary();
        // the caption lives on one message of an album; that is the one to edit
        MessageObject captionMessage = primary;
        for (int i = 0; i < post.messages.size(); i++) {
            if (!TextUtils.isEmpty(post.messages.get(i).caption)) {
                captionMessage = post.messages.get(i);
                break;
            }
        }
        final MessageObject editTarget = captionMessage;
        final TLRPC.Chat chat = getMessagesController().getChat(post.chat.id) != null ? getMessagesController().getChat(post.chat.id) : post.chat;
        final boolean sent = primary.getId() > 0 && !primary.isSending() && !primary.isSendError();
        final TLRPC.ChatFull full = getMessagesController().getChatFull(chat.id);
        final org.telegram.ui.Components.ItemOptions options = org.telegram.ui.Components.ItemOptions.makeOptions(this, anchor);
        options.add(R.drawable.msg_openin, LocaleController.getString(R.string.ViewInChat), () -> openPostInChat(post, primary));
        if (sent) {
            options.add(R.drawable.msg_link, LocaleController.getString(R.string.CopyLink), () -> {
                final String username = ChatObject.getPublicUsername(chat);
                final String link = !TextUtils.isEmpty(username)
                    ? "https://t.me/" + username + "/" + primary.getId()
                    : "https://t.me/c/" + chat.id + "/" + primary.getId();
                AndroidUtilities.addToClipboard(link);
                BulletinFactory.of(this).createCopyLinkBulletin(TextUtils.isEmpty(username)).show();
            });
        }
        if (sent && editTarget.canEditMessage(chat)) {
            options.add(R.drawable.msg_edit, LocaleController.getString(post.media.isEmpty() ? R.string.Edit : R.string.EditCaption), () -> {
                final Bundle args = new Bundle();
                args.putLong("chat_id", chat.id);
                args.putInt("message_id", editTarget.getId());
                final ChatActivity chatActivity = new ChatActivity(args);
                chatActivity.setTigiEditMessage(editTarget);
                presentFragment(chatActivity);
            });
        }
        if (sent && ChatObject.canPinMessages(chat)) {
            final boolean pinned = primary.messageOwner.pinned;
            options.add(pinned ? R.drawable.msg_unpin : R.drawable.msg_pin, LocaleController.getString(pinned ? R.string.UnpinMessage : R.string.PinMessage), () -> {
                getMessagesController().pinMessage(chat, null, primary.getId(), pinned, false, false);
                primary.messageOwner.pinned = !pinned;
            });
        }
        if (sent && full != null && full.can_view_stats) {
            options.add(R.drawable.msg_stats, LocaleController.getString(R.string.Statistics), () -> presentFragment(new MessageStatisticActivity(primary)));
        }
        if (sent && !primary.isOut() && !ChatObject.canPost(chat)) {
            // other people's content must stay reportable (Google Play user-generated content policy)
            options.add(R.drawable.msg_report, LocaleController.getString(R.string.ReportChat), () -> ReportBottomSheet.openMessage(this, primary));
        }
        if (primary.canDeleteMessage(false, chat)) {
            options.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDelete(post, chat));
        }
        options.show();
    }

    private void confirmDelete(FeedPost post, TLRPC.Chat chat) {
        if (getParentActivity() == null) {
            return;
        }
        new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(LocaleController.getString(R.string.TigiDeletePostTitle))
            .setMessage(LocaleController.getString(R.string.TigiDeletePostText))
            .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                final ArrayList<Integer> ids = new ArrayList<>();
                for (int i = 0; i < post.messages.size(); i++) {
                    ids.add(post.messages.get(i).getId());
                }
                // the feeds drop the card when the messagesDeleted notification comes back
                getMessagesController().deleteMessages(ids, null, null, -chat.id, 0, true, 0);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .makeRed(org.telegram.ui.ActionBar.AlertDialog.BUTTON_POSITIVE)
            .show();
    }

    protected void sharePost(FeedPost post) {
        final String username = ChatObject.getPublicUsername(post.chat);
        if (getParentActivity() == null) {
            return;
        }
        if (TextUtils.isEmpty(username)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiSharePrivate)).show();
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, "https://t.me/" + username + "/" + post.primary().getId());
        getParentActivity().startActivity(Intent.createChooser(intent, post.chat.title));
    }

    /* adapter */

    protected class FeedAdapter extends RecyclerListView.SelectionAdapter {
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
            final FeedCell cell = new FeedCell(parent.getContext());
            final RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(8), dp(5), dp(8), dp(5));
            cell.setLayoutParams(params);
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((FeedCell) holder.itemView).bind(posts.get(position));
        }
    }

    /* cell */

    protected class FeedCell extends LinearLayout {
        private final BackupImageView avatarView;
        private final TextView nameView;
        private final TextView timeView;
        private final FrameLayout mediaFrame;
        private final RecyclerView pager;
        private final LinearLayoutManager pagerManager;
        private final PhotoAdapter photoAdapter = new PhotoAdapter();
        private final TextView counterView;
        private final ImageView bigHeart;
        private final DotsView dotsView;
        private final ImageView heartButton;
        private final TextView heartCountView;
        private final TextView commentCountView;
        private final ImageView commentButton;
        private final ImageView shareButton;
        private final LinearLayout chipsRow;
        private final TextView captionView;
        private final TextView moreView;
        private final TextView statusView;
        private final TextView forwardedView;
        private final LinearLayout fallbackBox;
        private final TextView fallbackLabel;
        FeedPost post;
        private int currentPage;
        private boolean captionExpanded;

        FeedCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackground(Theme.createRoundRectDrawable(dp(20), Theme.getColor(Theme.key_windowBackgroundWhite)));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
                }
            });
            setClipToOutline(true);
            setOnClickListener(v -> {
                if (post != null) {
                    onPostTapped(post, -1);
                }
            });

            final LinearLayout headerRow = new LinearLayout(context);
            headerRow.setOrientation(HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            headerRow.setPadding(dp(12), dp(10), dp(12), dp(10));
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(16));
            headerRow.addView(avatarView, LayoutHelper.createLinear(32, 32));
            final LinearLayout names = new LinearLayout(context);
            names.setOrientation(VERTICAL);
            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            timeView = new TextView(context);
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            names.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            names.addView(timeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            headerRow.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            headerRow.setOnClickListener(v -> {
                if (post != null) {
                    onHeaderClicked(post);
                }
            });
            final ImageView moreButton = new ImageView(context);
            moreButton.setScaleType(ImageView.ScaleType.CENTER);
            moreButton.setImageResource(R.drawable.ic_ab_other);
            moreButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
            moreButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
            moreButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            moreButton.setOnClickListener(v -> showPostMenu(post, v));
            headerRow.addView(moreButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 4, 0, -8, 0));
            addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            statusView = new TextView(context);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            statusView.setPadding(dp(12), 0, dp(12), dp(8));
            statusView.setVisibility(GONE);
            statusView.setOnClickListener(v -> {
                if (post != null && !post.messages.isEmpty() && post.primary().isSendError()) {
                    // Telegram's chat has the retry and delete actions for unsent messages
                    openPostInChat(post, post.primary());
                }
            });
            addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            forwardedView = new TextView(context);
            forwardedView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            forwardedView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            forwardedView.setSingleLine(true);
            forwardedView.setEllipsize(TextUtils.TruncateAt.END);
            forwardedView.setPadding(dp(12), 0, dp(12), dp(8));
            forwardedView.setVisibility(GONE);
            addView(forwardedView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            // posts the cards can't show: what it is, and the way to the classic chat
            fallbackBox = new LinearLayout(context);
            fallbackBox.setOrientation(HORIZONTAL);
            fallbackBox.setGravity(Gravity.CENTER_VERTICAL);
            fallbackBox.setPadding(dp(14), dp(10), dp(10), dp(10));
            fallbackBox.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundGray)));
            fallbackLabel = new TextView(context);
            fallbackLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            fallbackLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            fallbackLabel.setMaxLines(2);
            fallbackLabel.setEllipsize(TextUtils.TruncateAt.END);
            fallbackBox.addView(fallbackLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final TextView fallbackOpen = new TextView(context);
            fallbackOpen.setText(LocaleController.getString(R.string.ViewInChat));
            fallbackOpen.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            fallbackOpen.setTypeface(AndroidUtilities.bold());
            fallbackOpen.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            fallbackOpen.setGravity(Gravity.CENTER);
            fallbackOpen.setMinHeight(dp(44));
            fallbackOpen.setPadding(dp(10), 0, dp(6), 0);
            fallbackBox.addView(fallbackOpen, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            fallbackBox.setOnClickListener(v -> {
                if (post != null && !post.messages.isEmpty()) {
                    openPostInChat(post, post.primary());
                }
            });
            fallbackBox.setVisibility(GONE);
            addView(fallbackBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 8));

            mediaFrame = new FrameLayout(context);
            pager = new RecyclerView(context);
            pagerManager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
            pager.setLayoutManager(pagerManager);
            pager.setAdapter(photoAdapter);
            pager.setOverScrollMode(OVER_SCROLL_NEVER);
            new PagerSnapHelper().attachToRecyclerView(pager);
            pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    final int page = pagerManager.findFirstCompletelyVisibleItemPosition();
                    if (page >= 0 && page != currentPage) {
                        currentPage = page;
                        updatePageIndicators();
                        // swiped to another photo or video of the album
                        TigiFeedBase.this.listView.post(TigiFeedBase.this::checkAutoplay);
                    }
                }
            });
            mediaFrame.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            counterView = new TextView(context);
            counterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            counterView.setTypeface(AndroidUtilities.bold());
            counterView.setTextColor(0xFFFFFFFF);
            counterView.setGravity(Gravity.CENTER);
            counterView.setPadding(dp(12), 0, dp(12), 0);
            counterView.setBackground(Theme.createRoundRectDrawable(dp(14), 0xCC111827));
            mediaFrame.addView(counterView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28, Gravity.TOP | Gravity.RIGHT, 0, 12, 12, 0));

            bigHeart = new ImageView(context);
            bigHeart.setImageResource(R.drawable.tigi_heart_filled_24);
            bigHeart.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            bigHeart.setVisibility(GONE);
            mediaFrame.addView(bigHeart, LayoutHelper.createFrame(96, 96, Gravity.CENTER));
            addView(mediaFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200));

            dotsView = new DotsView(context);
            addView(dotsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 20));

            final LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setPadding(dp(6), 0, dp(6), 0);

            heartButton = new ImageView(context);
            heartButton.setScaleType(ImageView.ScaleType.CENTER);
            heartButton.setContentDescription(LocaleController.getString(R.string.TigiLike));
            heartButton.setOnClickListener(v -> {
                if (post == null) {
                    return;
                }
                if (toggleHeart(post)) {
                    popHeart();
                }
            });
            heartButton.setOnLongClickListener(v -> {
                if (post != null) {
                    final int[] location = new int[2];
                    heartButton.getLocationOnScreen(location);
                    showReactionPicker(post, location[0] + heartButton.getWidth() / 2f, location[1]);
                }
                return true;
            });
            actions.addView(heartButton, LayoutHelper.createLinear(48, 48));
            heartCountView = makeCountView(context);
            actions.addView(heartCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            commentButton = new ImageView(context);
            commentButton.setScaleType(ImageView.ScaleType.CENTER);
            commentButton.setImageResource(R.drawable.tigi_comment_24);
            commentButton.setContentDescription(LocaleController.getString(R.string.TigiComments));
            commentButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            commentButton.setOnClickListener(v -> {
                if (post != null && !post.messages.isEmpty()) {
                    openComments(post);
                }
            });
            actions.addView(commentButton, LayoutHelper.createLinear(48, 48));
            commentCountView = makeCountView(context);
            actions.addView(commentCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            shareButton = new ImageView(context);
            shareButton.setScaleType(ImageView.ScaleType.CENTER);
            shareButton.setImageResource(R.drawable.tigi_share_24);
            shareButton.setContentDescription(LocaleController.getString(R.string.ShareFile));
            shareButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            shareButton.setOnClickListener(v -> {
                if (post != null) {
                    sharePost(post);
                }
            });
            actions.addView(shareButton, LayoutHelper.createLinear(48, 48));
            addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

            chipsRow = new LinearLayout(context);
            chipsRow.setOrientation(HORIZONTAL);
            chipsRow.setPadding(dp(12), 0, dp(12), dp(4));
            addView(chipsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final LinearLayout captionBox = new LinearLayout(context);
            captionBox.setOrientation(VERTICAL);
            captionBox.setPadding(dp(12), dp(2), dp(12), dp(14));
            captionView = new TextView(context);
            captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            captionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            captionView.setLineSpacing(dp(2), 1f);
            captionView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
            captionView.setMovementMethod(captionLinks);
            // a tap that is not on a link opens or closes a long caption
            captionView.setOnClickListener(v -> {
                if (moreView.getVisibility() == VISIBLE) {
                    captionExpanded = !captionExpanded;
                    updateCaption();
                }
            });
            captionBox.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            moreView = new TextView(context);
            moreView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            moreView.setTypeface(AndroidUtilities.bold());
            moreView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            moreView.setPadding(0, dp(4), 0, dp(4));
            moreView.setVisibility(GONE);
            moreView.setOnClickListener(v -> {
                captionExpanded = !captionExpanded;
                updateCaption();
            });
            captionBox.addView(moreView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            addView(captionBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void updateColors() {
            setBackground(Theme.createRoundRectDrawable(dp(20), Theme.getColor(Theme.key_windowBackgroundWhite)));
            final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            nameView.setTextColor(text);
            timeView.setTextColor(gray);
            heartCountView.setTextColor(text);
            commentCountView.setTextColor(text);
            captionView.setTextColor(text);
            moreView.setTextColor(gray);
            commentButton.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
            shareButton.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
            forwardedView.setTextColor(gray);
            fallbackLabel.setTextColor(text);
            fallbackBox.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundGray)));
            captionView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
            for (int i = 0; i < pager.getChildCount(); i++) {
                pager.getChildAt(i).setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            }
            pager.getRecycledViewPool().clear();
            dotsView.invalidate();
            bindActions();
        }

        private TextView makeCountView(Context context) {
            final TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            view.setTypeface(AndroidUtilities.bold());
            view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            return view;
        }

        PhotoPage visiblePage() {
            if (post == null || post.media.isEmpty() || mediaFrame.getVisibility() != VISIBLE) {
                return null;
            }
            final View view = pagerManager.findViewByPosition(currentPage);
            return view instanceof PhotoPage ? (PhotoPage) view : null;
        }

        boolean hosts(MessageObject message) {
            return post != null && message != null && post.media.contains(message);
        }

        boolean isOverMultiPhoto(MotionEvent ev) {
            if (post == null || post.media.size() < 2 || mediaFrame.getVisibility() != VISIBLE) {
                return false;
            }
            final int[] location = new int[2];
            mediaFrame.getLocationOnScreen(location);
            final float x = ev.getRawX();
            final float y = ev.getRawY();
            return x >= location[0] && x <= location[0] + mediaFrame.getWidth() && y >= location[1] && y <= location[1] + mediaFrame.getHeight();
        }

        void bind(FeedPost post) {
            this.post = post;
            if (post == null || post.messages.isEmpty()) {
                return;
            }
            currentPage = 0;
            captionExpanded = false;

            final AvatarDrawable avatarDrawable = new AvatarDrawable();
            if (post.author != null) {
                avatarDrawable.setInfo(currentAccount, post.author);
                avatarView.setForUserOrChat(post.author, avatarDrawable);
                nameView.setText(UserObject.getUserName(post.author));
            } else {
                avatarDrawable.setInfo(currentAccount, post.chat);
                avatarView.setForUserOrChat(post.chat, avatarDrawable);
                nameView.setText(post.chat.title);
            }
            timeView.setText(LocaleController.stringForMessageListDate(post.date));

            if (post.media.isEmpty()) {
                mediaFrame.setVisibility(GONE);
                dotsView.setVisibility(GONE);
            } else {
                mediaFrame.setVisibility(VISIBLE);
                final int width = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) - dp(16);
                final MessageObject first = post.media.get(0);
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(first.photoThumbs, AndroidUtilities.getPhotoSize());
                float ratio = size != null && size.h > 0 ? size.w / (float) size.h : 1f;
                ratio = Math.max(0.8f, Math.min(1.91f, ratio));
                final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mediaFrame.getLayoutParams();
                params.height = (int) (width / ratio);
                mediaFrame.setLayoutParams(params);
                photoAdapter.setItems(post.media);
                pager.scrollToPosition(0);
                dotsView.setVisibility(post.media.size() > 1 ? VISIBLE : GONE);
                updatePageIndicators();
            }

            if (post.media.isEmpty() && !TextUtils.isEmpty(post.fallback)) {
                fallbackLabel.setText(post.fallback);
                fallbackBox.setVisibility(VISIBLE);
            } else {
                fallbackBox.setVisibility(GONE);
            }
            final MessageObject first = post.primary();
            final String forwardedFrom = first.isForwarded() ? first.getForwardedName() : null;
            if (!TextUtils.isEmpty(forwardedFrom)) {
                forwardedView.setText(LocaleController.getString(R.string.ForwardedFrom) + " " + forwardedFrom);
                forwardedView.setVisibility(VISIBLE);
            } else {
                forwardedView.setVisibility(GONE);
            }

            updateCaption();
            bindActions();
            bindStatus();
        }

        void bindStatus() {
            if (post == null || post.messages.isEmpty()) {
                statusView.setVisibility(GONE);
                return;
            }
            final MessageObject message = post.primary();
            if (message.isSendError()) {
                statusView.setVisibility(VISIBLE);
                statusView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                statusView.setText(LocaleController.getString(R.string.TigiPostFailed));
            } else if (message.isSending()) {
                statusView.setVisibility(VISIBLE);
                statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                statusView.setText(LocaleController.getString(R.string.TigiPostSending));
            } else {
                statusView.setVisibility(GONE);
            }
        }

        private void updateCaption() {
            if (post == null || TextUtils.isEmpty(post.text)) {
                captionView.setVisibility(GONE);
                moreView.setVisibility(GONE);
                return;
            }
            captionView.setVisibility(VISIBLE);
            captionView.setMaxLines(captionExpanded ? Integer.MAX_VALUE : CAPTION_LINES);
            captionView.setEllipsize(captionExpanded ? null : TextUtils.TruncateAt.END);
            captionView.setText(post.text);
            moreView.setVisibility(GONE);
            captionView.post(() -> {
                final Layout layout = captionView.getLayout();
                if (layout == null || post == null) {
                    return;
                }
                final int lines = layout.getLineCount();
                final boolean truncated = lines > 0 && layout.getEllipsisCount(lines - 1) > 0;
                moreView.setText(LocaleController.getString(captionExpanded ? R.string.TigiLess : R.string.TigiMore));
                moreView.setVisibility(captionExpanded || truncated ? VISIBLE : GONE);
            });
        }

        private void updatePageIndicators() {
            final int count = post == null ? 0 : post.media.size();
            counterView.setVisibility(count > 1 ? VISIBLE : GONE);
            counterView.setText(LocaleController.formatString(R.string.TigiPageCounter, currentPage + 1, count));
            dotsView.set(count, currentPage);
        }

        void bindActions() {
            if (post == null || post.messages.isEmpty()) {
                return;
            }
            final MessageObject message = post.primary();
            final boolean liked = heartChosen(message);
            heartButton.setImageResource(liked ? R.drawable.tigi_heart_filled_24 : R.drawable.tigi_heart_outline_24);
            heartButton.setContentDescription(LocaleController.getString(liked ? R.string.TigiUnlike : R.string.TigiLike));
            if (liked) {
                heartButton.clearColorFilter();
            } else {
                heartButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            }
            final int hearts = heartCount(message);
            heartCountView.setText(hearts > 0 ? String.valueOf(hearts) : "");
            final int comments = message.messageOwner.replies != null ? message.messageOwner.replies.replies : 0;
            commentCountView.setText(comments > 0 ? String.valueOf(comments) : "");

            chipsRow.removeAllViews();
            if (message.messageOwner.reactions != null) {
                int added = 0;
                for (int i = 0; i < message.messageOwner.reactions.results.size() && added < 4; i++) {
                    final TLRPC.ReactionCount count = message.messageOwner.reactions.results.get(i);
                    if (isHeart(count.reaction) || !(count.reaction instanceof TLRPC.TL_reactionEmoji)) {
                        continue;
                    }
                    final TextView chip = new TextView(getContext());
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    chip.setTypeface(AndroidUtilities.bold());
                    chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    chip.setGravity(Gravity.CENTER);
                    chip.setPadding(dp(10), 0, dp(10), 0);
                    chip.setBackground(Theme.createRoundRectDrawable(dp(15), count.chosen ? Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.18f) : Theme.getColor(Theme.key_windowBackgroundGray)));
                    chip.setText(((TLRPC.TL_reactionEmoji) count.reaction).emoticon + " " + count.count);
                    final TLRPC.Reaction reaction = count.reaction;
                    chip.setOnClickListener(v -> {
                        if (post != null) {
                            applyReaction(post, ReactionsLayoutInBubble.VisibleReaction.fromTL(reaction), false, false);
                        }
                    });
                    chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
                    added++;
                }
            }
            chipsRow.setVisibility(chipsRow.getChildCount() > 0 ? VISIBLE : GONE);
        }

        private void popHeart() {
            heartButton.animate().cancel();
            heartButton.setScaleX(1f);
            heartButton.setScaleY(1f);
            heartButton.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110).withEndAction(() -> heartButton.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
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
        }

        private void onPhotoDoubleTap() {
            if (post == null) {
                return;
            }
            if (!heartChosen(post.primary())) {
                if (!toggleHeart(post)) {
                    return;
                }
                popHeart();
            }
            showBigHeart();
        }

        private void onPhotoTap(int position) {
            if (post != null) {
                onPostTapped(post, position);
            }
        }

        private void onPhotoLongPress(float rawX, float rawY) {
            if (post != null) {
                showReactionPicker(post, rawX, rawY);
            }
        }

        private class PhotoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            private final ArrayList<MessageObject> items = new ArrayList<>();

            void setItems(ArrayList<MessageObject> list) {
                items.clear();
                items.addAll(list);
                notifyDataSetChanged();
            }

            @Override
            public int getItemCount() {
                return items.size();
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                final PhotoPage page = new PhotoPage(parent.getContext());
                page.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                return new RecyclerView.ViewHolder(page) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                ((PhotoPage) holder.itemView).bind(items.get(position), position);
            }
        }

        private class PhotoPage extends FrameLayout {
            private final BackupImageView imageView;
            private final ImageView playView;
            private final ImageView muteButton;
            private final ImageView fullscreenButton;
            private int position;
            MessageObject message;

            PhotoPage(Context context) {
                super(context);
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
                imageView = new BackupImageView(context);
                addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                // index 1 is kept free for the player's TextureView (above the image, below the controls)
                playView = new ImageView(context);
                playView.setScaleType(ImageView.ScaleType.CENTER);
                playView.setImageResource(R.drawable.tigi_play_24);
                playView.setBackground(Theme.createRoundRectDrawable(dp(30), 0x99111827));
                playView.setVisibility(GONE);
                playView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(playView, LayoutHelper.createFrame(60, 60, Gravity.CENTER));

                muteButton = makeControl(context, R.drawable.media_mute);
                muteButton.setOnClickListener(v -> toggleMute());
                addView(muteButton, LayoutHelper.createFrame(36, 36, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 10));

                fullscreenButton = makeControl(context, R.drawable.msg_expand);
                fullscreenButton.setContentDescription(LocaleController.getString(R.string.AccDescrOpenInPhotoViewer));
                fullscreenButton.setOnClickListener(v -> onPhotoTap(position));
                addView(fullscreenButton, LayoutHelper.createFrame(36, 36, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 54, 10));

                final GestureDetector detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (isPlayable()) {
                            // videos: tap plays, tap again toggles the sound; the corner button goes full screen
                            if (inlinePlayer != null && inlinePlayer.isPlaying(message)) {
                                toggleMute();
                            } else {
                                startPlayback();
                            }
                        } else {
                            onPhotoTap(position);
                        }
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        onPhotoLongPress(e.getRawX(), e.getRawY());
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        onPhotoDoubleTap();
                        return true;
                    }
                });
                setOnTouchListener((v, event) -> detector.onTouchEvent(event));
            }

            private ImageView makeControl(Context context, int icon) {
                final ImageView view = new ImageView(context);
                view.setScaleType(ImageView.ScaleType.CENTER);
                view.setImageResource(icon);
                view.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
                view.setBackground(Theme.createRoundRectDrawable(dp(18), 0x99111827));
                view.setVisibility(GONE);
                return view;
            }

            boolean isPlayable() {
                return message != null && (message.isVideo() || message.isGif()) && !message.isRoundVideo() && message.getDocument() != null;
            }

            void bind(MessageObject message, int position) {
                if (this.message != message && inlinePlayer != null && inlinePlayer.isPlaying(this.message)) {
                    inlinePlayer.stop();
                }
                this.message = message;
                this.position = position;
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
                imageView.setImage(ImageLocation.getForObject(size, message.photoThumbsObject), "800_800", (Drawable) null, message.photoThumbsObject);
                setContentDescription(LocaleController.getString(message.isVideo() ? R.string.AttachVideo : message.isGif() ? R.string.AttachGif : R.string.AttachPhoto));
                updateControls();
            }

            void startPlayback() {
                if (inlinePlayer == null || !isPlayable()) {
                    return;
                }
                if (inlinePlayer.play(this, message, (msg, rendering, failed) -> {
                    if (msg == message) {
                        updateControls();
                    }
                })) {
                    updateControls();
                }
            }

            private void toggleMute() {
                if (inlinePlayer == null || message == null || message.isGif()) {
                    return;
                }
                inlinePlayer.setMuted(!inlinePlayer.isMuted());
                updateControls();
            }

            void updateControls() {
                final boolean playable = isPlayable();
                final boolean playing = playable && inlinePlayer != null && inlinePlayer.isPlaying(message);
                playView.setVisibility(playable && !playing ? VISIBLE : GONE);
                fullscreenButton.setVisibility(playable ? VISIBLE : GONE);
                // GIFs have no sound
                final boolean showMute = playing && !message.isGif();
                muteButton.setVisibility(showMute ? VISIBLE : GONE);
                if (showMute) {
                    final boolean muted = inlinePlayer.isMuted();
                    muteButton.setImageResource(muted ? R.drawable.media_mute : R.drawable.media_unmute);
                    muteButton.setContentDescription(LocaleController.getString(muted ? R.string.TigiUnmute : R.string.TigiMute));
                }
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                // recycled or the screen went away: never leave a player running behind a card that is gone
                if (inlinePlayer != null && inlinePlayer.isPlaying(message)) {
                    inlinePlayer.stop();
                }
            }
        }
    }

    /* page dots */

    private static class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;
        private int selected;

        DotsView(Context context) {
            super(context);
        }

        void set(int count, int selected) {
            this.count = count;
            this.selected = selected;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (count < 2) {
                return;
            }
            final float dot = dp(6);
            final float wide = dp(18);
            final float gap = dp(6);
            final float total = (count - 1) * (dot + gap) + wide;
            float x = (getWidth() - total) / 2f;
            final float cy = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                final boolean active = i == selected;
                paint.setColor(active ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText) : Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.4f));
                final float w = active ? wide : dot;
                canvas.drawRoundRect(x, cy - dot / 2f, x + w, cy + dot / 2f, dot / 2f, dot / 2f, paint);
                x += w + gap;
            }
        }
    }
}
