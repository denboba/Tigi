package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.TigiHeart;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Nilak Activity, the bell: everything that happened around the user, like a social app's notifications. Reactions on
 * their posts (anonymous in channels, so counts), comments, new followers, view milestones, replies to their comments
 * anywhere, mentions and reactions to their messages in chats, who viewed or reacted to their stories, and contacts who
 * joined. Read from Telegram on open; nothing is stored.
 */
public class TigiActivityActivity extends BaseFragment {

    private static final int RECENT_POSTS = 20;
    private static final int POSTS_WITH_COMMENTS = 5;
    private static final int[] VIEW_MILESTONES = {100000, 50000, 10000, 5000, 1000, 500, 100};

    private static final int KIND_REACTIONS = 0;
    private static final int KIND_COMMENT = 1;
    private static final int KIND_FOLLOWERS = 2;
    private static final int KIND_VIEWS = 3;
    private static final int KIND_REPLY = 4;
    private static final int KIND_MENTION = 5;
    private static final int KIND_MESSAGE_REACTION = 6;
    private static final int KIND_STORY = 7;
    private static final int KIND_JOINED = 8;
    private static final int DIALOGS_TO_ASK = 6;

    private static class Item {
        int kind;
        int date;
        CharSequence text;
        TLObject who;
        MessageObject post;
        String detail;
        long dialogId;
        int messageId;
    }

    private TLRPC.Chat channel;
    private final ArrayList<Object> rows = new ArrayList<>();
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<MessageObject> posts = new ArrayList<>();
    private int pending;
    private boolean loading = true;
    private RecyclerListView listView;
    private Adapter adapter;

    @Override
    public boolean onFragmentCreate() {
        channel = TigiPosting.ownChannel(currentAccount);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NilakActivity));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
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
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, AndroidUtilities.navigationBarHeight + dp(12));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        load();
        return fragmentView;
    }

    private boolean isDead() {
        return isFinishing() || fragmentView == null || getParentActivity() == null;
    }

    /* loading */

    private void load() {
        // the sources that do not need the user's posts, then their posts' ones
        pending = 1;
        loadReplies();
        loadChatActivity();
        loadStoryViews();
        addJoinedContacts();
        if (channel != null) {
            loadOwnPosts();
        }
        done();
    }

    private void loadOwnPosts() {
        pending += 2;
        final TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
        history.peer = getMessagesController().getInputPeer(-channel.id);
        history.limit = RECENT_POSTS;
        getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(history, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    if (!(message instanceof TLRPC.TL_messageService)) {
                        posts.add(new MessageObject(currentAccount, message, false, false));
                    }
                }
                addPostItems();
                loadComments();
            }
            done();
        })), classGuid);

        final TLRPC.TL_channels_getParticipants members = new TLRPC.TL_channels_getParticipants();
        members.channel = MessagesController.getInputChannel(channel);
        members.filter = new TLRPC.TL_channelParticipantsRecent();
        members.limit = 50;
        getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(members, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.channels_ChannelParticipants) {
                final TLRPC.channels_ChannelParticipants res = (TLRPC.channels_ChannelParticipants) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                addFollowerItems(res.participants);
            }
            done();
        })), classGuid);
    }

    private void done() {
        if (--pending <= 0) {
            loading = false;
        }
        if (!isDead()) {
            rebuild();
        }
    }

    private void addPostItems() {
        for (int i = 0; i < posts.size(); i++) {
            final MessageObject post = posts.get(i);
            int total = 0;
            final StringBuilder emoji = new StringBuilder();
            if (post.messageOwner.reactions != null) {
                final ArrayList<TLRPC.ReactionCount> results = new ArrayList<>(post.messageOwner.reactions.results);
                Collections.sort(results, (a, b) -> b.count - a.count);
                for (int r = 0; r < results.size(); r++) {
                    total += results.get(r).count;
                    if (r < 3 && results.get(r).reaction instanceof TLRPC.TL_reactionEmoji) {
                        if (emoji.length() > 0) {
                            emoji.append(" · ");
                        }
                        emoji.append(((TLRPC.TL_reactionEmoji) results.get(r).reaction).emoticon).append(' ').append(LocaleController.formatShortNumber(results.get(r).count, null));
                    }
                }
            }
            if (total > 0) {
                final Item item = new Item();
                item.kind = KIND_REACTIONS;
                item.date = post.messageOwner.date;
                item.post = post;
                item.text = bold(LocaleController.formatPluralString("NilakActivityReactions", total));
                item.detail = emoji.toString();
                items.add(item);
            }
            final int views = post.messageOwner.views;
            for (int milestone : VIEW_MILESTONES) {
                if (views >= milestone) {
                    final Item item = new Item();
                    item.kind = KIND_VIEWS;
                    item.date = post.messageOwner.date;
                    item.post = post;
                    item.text = bold(LocaleController.formatString(R.string.NilakActivityViews, LocaleController.formatShortNumber(milestone, null)));
                    items.add(item);
                    break;
                }
            }
        }
    }

    private void loadComments() {
        int asked = 0;
        for (int i = 0; i < posts.size() && asked < POSTS_WITH_COMMENTS; i++) {
            final MessageObject post = posts.get(i);
            if (post.messageOwner.replies == null || post.messageOwner.replies.replies <= 0) {
                continue;
            }
            asked++;
            pending++;
            final TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
            req.peer = getMessagesController().getInputPeer(-channel.id);
            req.msg_id = post.getId();
            req.limit = 10;
            getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    for (int m = 0; m < res.messages.size(); m++) {
                        final TLRPC.Message message = res.messages.get(m);
                        if (message instanceof TLRPC.TL_messageService || TextUtils.isEmpty(message.message)) {
                            continue;
                        }
                        final long from = MessageObject.getFromChatId(message);
                        if (from == getUserConfig().getClientUserId() || from == -channel.id) {
                            // the user's own replies are not news
                            continue;
                        }
                        final Item item = new Item();
                        item.kind = KIND_COMMENT;
                        item.date = message.date;
                        item.post = post;
                        item.who = getMessagesController().getUserOrChat(from);
                        final String name = name(item.who);
                        final SpannableStringBuilder text = new SpannableStringBuilder();
                        text.append(bold(name)).append(' ').append(LocaleController.formatString(R.string.NilakActivityCommented, message.message.length() > 80 ? message.message.substring(0, 80) + "…" : message.message));
                        item.text = text;
                        items.add(item);
                    }
                }
                done();
            })), classGuid);
        }
    }

    private static String reactionText(TLRPC.Reaction reaction) {
        return reaction instanceof TLRPC.TL_reactionEmoji ? ((TLRPC.TL_reactionEmoji) reaction).emoticon : "❤️";
    }

    private static String excerpt(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        final String line = text.replace('\n', ' ').trim();
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }

    /** Replies to the user's comments anywhere: Telegram collects them in the "Replies" chat. */
    private void loadReplies() {
        final TLRPC.InputPeer peer = getMessagesController().getInputPeer(UserObject.REPLY_BOT);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
            return;
        }
        pending++;
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.limit = 30;
        getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    if (message instanceof TLRPC.TL_messageService || message.fwd_from == null || message.fwd_from.from_id == null) {
                        continue;
                    }
                    final Item item = new Item();
                    item.kind = KIND_REPLY;
                    item.date = message.date;
                    item.who = getMessagesController().getUserOrChat(DialogObject.getPeerDialogId(message.fwd_from.from_id));
                    item.dialogId = UserObject.REPLY_BOT;
                    item.messageId = message.id;
                    final SpannableStringBuilder text = new SpannableStringBuilder(bold(name(item.who)));
                    text.append(' ').append(LocaleController.formatString(R.string.NilakActivityReplied, excerpt(message.message)));
                    item.text = text;
                    items.add(item);
                }
            }
            done();
        })), classGuid);
    }

    /** Mentions and reactions to the user's messages in their chats and groups (the ones Telegram marks unseen). */
    private void loadChatActivity() {
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        int mentions = 0;
        int reactions = 0;
        for (int i = 0; i < dialogs.size() && i < 300; i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog.unread_mentions_count > 0 && mentions < DIALOGS_TO_ASK) {
                mentions++;
                loadMentions(dialog.id);
            }
            if (dialog.unread_reactions_count > 0 && reactions < DIALOGS_TO_ASK) {
                reactions++;
                loadMessageReactions(dialog.id);
            }
        }
    }

    private String chatTitle(long dialogId) {
        final TLObject chat = getMessagesController().getUserOrChat(dialogId);
        return name(chat);
    }

    private void loadMentions(long dialogId) {
        final TLRPC.TL_messages_getUnreadMentions req = new TLRPC.TL_messages_getUnreadMentions();
        req.peer = getMessagesController().getInputPeer(dialogId);
        req.limit = 5;
        if (req.peer == null) {
            return;
        }
        pending++;
        getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    final Item item = new Item();
                    item.kind = KIND_MENTION;
                    item.date = message.date;
                    item.who = getMessagesController().getUserOrChat(MessageObject.getFromChatId(message));
                    item.dialogId = dialogId;
                    item.messageId = message.id;
                    final SpannableStringBuilder text = new SpannableStringBuilder(bold(name(item.who)));
                    text.append(' ').append(LocaleController.formatString(R.string.NilakActivityMentioned, chatTitle(dialogId), excerpt(message.message)));
                    item.text = text;
                    items.add(item);
                }
            }
            done();
        })), classGuid);
    }

    private void loadMessageReactions(long dialogId) {
        final TLRPC.TL_messages_getUnreadReactions req = new TLRPC.TL_messages_getUnreadReactions();
        req.peer = getMessagesController().getInputPeer(dialogId);
        req.limit = 5;
        if (req.peer == null) {
            return;
        }
        pending++;
        getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    if (message.reactions == null) {
                        continue;
                    }
                    for (int r = 0; r < message.reactions.recent_reactions.size() && r < 3; r++) {
                        final TLRPC.MessagePeerReaction reaction = message.reactions.recent_reactions.get(r);
                        final long who = DialogObject.getPeerDialogId(reaction.peer_id);
                        if (who == getUserConfig().getClientUserId()) {
                            continue;
                        }
                        final Item item = new Item();
                        item.kind = KIND_MESSAGE_REACTION;
                        item.date = reaction.date > 0 ? reaction.date : message.date;
                        item.who = getMessagesController().getUserOrChat(who);
                        item.dialogId = dialogId;
                        item.messageId = message.id;
                        final SpannableStringBuilder text = new SpannableStringBuilder(bold(name(item.who)));
                        text.append(' ').append(LocaleController.formatString(R.string.NilakActivityReactedMessage, reactionText(reaction.reaction), excerpt(message.message)));
                        item.text = text;
                        items.add(item);
                    }
                }
            }
            done();
        })), classGuid);
    }

    /** Who viewed and reacted to the user's current stories. */
    private void loadStoryViews() {
        final long self = getUserConfig().getClientUserId();
        final org.telegram.tgnet.tl.TL_stories.PeerStories stories = getMessagesController().getStoriesController().getStories(self);
        if (stories == null || stories.stories == null) {
            return;
        }
        for (int i = 0; i < stories.stories.size() && i < 3; i++) {
            final org.telegram.tgnet.tl.TL_stories.StoryItem story = stories.stories.get(i);
            final org.telegram.tgnet.tl.TL_stories.TL_stories_getStoryViewsList req = new org.telegram.tgnet.tl.TL_stories.TL_stories_getStoryViewsList();
            req.peer = getMessagesController().getInputPeer(self);
            req.id = story.id;
            req.limit = 30;
            req.offset = "";
            req.reactions_first = true;
            pending++;
            getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof org.telegram.tgnet.tl.TL_stories.StoryViewsList) {
                    final org.telegram.tgnet.tl.TL_stories.StoryViewsList res = (org.telegram.tgnet.tl.TL_stories.StoryViewsList) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    TLObject firstViewer = null;
                    int viewers = 0;
                    int newest = 0;
                    for (int v = 0; v < res.views.size(); v++) {
                        final org.telegram.tgnet.tl.TL_stories.StoryView view = res.views.get(v);
                        if (!(view instanceof org.telegram.tgnet.tl.TL_stories.TL_storyView)) {
                            continue;
                        }
                        final TLObject who = getMessagesController().getUser(view.user_id);
                        if (view.reaction != null) {
                            final Item item = new Item();
                            item.kind = KIND_STORY;
                            item.date = view.date;
                            item.who = who;
                            final SpannableStringBuilder text = new SpannableStringBuilder(bold(name(who)));
                            text.append(' ').append(LocaleController.formatString(R.string.NilakActivityReactedStory, reactionText(view.reaction)));
                            item.text = text;
                            items.add(item);
                        } else {
                            if (firstViewer == null) {
                                firstViewer = who;
                            }
                            viewers++;
                            newest = Math.max(newest, view.date);
                        }
                    }
                    if (viewers > 0) {
                        // "Hana and 12 others viewed your story"
                        final Item item = new Item();
                        item.kind = KIND_STORY;
                        item.date = newest;
                        item.who = firstViewer;
                        final SpannableStringBuilder text = new SpannableStringBuilder(bold(name(firstViewer)));
                        if (viewers > 1) {
                            text.append(' ').append(LocaleController.formatPluralString("NilakActivityAndOthers", viewers - 1));
                        }
                        text.append(' ').append(LocaleController.getString(R.string.NilakActivityViewedStory));
                        item.text = text;
                        items.add(item);
                    }
                }
                done();
            })), classGuid);
        }
    }

    /** Contacts who just joined, from the "joined Telegram" note at the top of their chat. */
    private void addJoinedContacts() {
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        for (int i = 0; i < dialogs.size() && i < 300; i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (!DialogObject.isUserDialog(dialog.id)) {
                continue;
            }
            final ArrayList<MessageObject> top = getMessagesController().dialogMessage.get(dialog.id);
            final MessageObject message = top != null && !top.isEmpty() ? top.get(0) : null;
            if (message == null || !(message.messageOwner.action instanceof TLRPC.TL_messageActionContactSignUp)) {
                continue;
            }
            final Item item = new Item();
            item.kind = KIND_JOINED;
            item.date = message.messageOwner.date;
            item.who = getMessagesController().getUser(dialog.id);
            item.dialogId = dialog.id;
            final SpannableStringBuilder text = new SpannableStringBuilder(bold(name(item.who)));
            text.append(' ').append(LocaleController.getString(R.string.NilakActivityJoined));
            item.text = text;
            items.add(item);
        }
    }

    private void addFollowerItems(ArrayList<TLRPC.ChannelParticipant> participants) {
        // new followers per day: "Yonas and 4 others started following you"
        final ArrayList<TLRPC.ChannelParticipant> joined = new ArrayList<>();
        for (int i = 0; i < participants.size(); i++) {
            final TLRPC.ChannelParticipant participant = participants.get(i);
            final long id = participant.peer != null ? DialogObject.getPeerDialogId(participant.peer) : 0;
            if (participant.date > 0 && id != getUserConfig().getClientUserId() && id != 0) {
                joined.add(participant);
            }
        }
        Collections.sort(joined, (a, b) -> b.date - a.date);
        int i = 0;
        while (i < joined.size()) {
            final int day = joined.get(i).date / 86400;
            int j = i;
            while (j < joined.size() && joined.get(j).date / 86400 == day) {
                j++;
            }
            final TLRPC.ChannelParticipant newest = joined.get(i);
            final Item item = new Item();
            item.kind = KIND_FOLLOWERS;
            item.date = newest.date;
            item.who = getMessagesController().getUserOrChat(DialogObject.getPeerDialogId(newest.peer));
            final int others = j - i - 1;
            final SpannableStringBuilder text = new SpannableStringBuilder(bold(name(item.who)));
            if (others > 0) {
                text.append(' ').append(LocaleController.formatPluralString("NilakActivityAndOthers", others));
            }
            text.append(' ').append(LocaleController.getString(R.string.NilakActivityFollowed));
            item.text = text;
            items.add(item);
            i = j;
        }
    }

    private static CharSequence bold(String text) {
        final SpannableStringBuilder out = new SpannableStringBuilder(text);
        out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private String name(TLObject who) {
        if (who instanceof TLRPC.User) {
            return UserObject.getUserName((TLRPC.User) who);
        } else if (who instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) who).title;
        }
        return "";
    }

    /** Sections Today, This week, Earlier, newest first. */
    private void rebuild() {
        rows.clear();
        final ArrayList<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (a, b) -> b.date - a.date);
        final int now = getConnectionsManager().getCurrentTime();
        int section = -1;
        for (int i = 0; i < sorted.size(); i++) {
            final Item item = sorted.get(i);
            final int age = now - item.date;
            final int itemSection = age < 86400 ? 0 : age < 7 * 86400 ? 1 : 2;
            if (itemSection != section) {
                section = itemSection;
                rows.add(LocaleController.getString(section == 0 ? R.string.NilakActivityToday : section == 1 ? R.string.NilakActivityThisWeek : R.string.NilakActivityEarlier));
            }
            rows.add(item);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onRowClick(int position) {
        if (position < 0 || position >= rows.size() || !(rows.get(position) instanceof Item)) {
            return;
        }
        final Item item = (Item) rows.get(position);
        if (item.kind == KIND_STORY) {
            TigiNavigation.openProfile(this, getUserConfig().getClientUserId());
            return;
        }
        if (item.dialogId != 0) {
            // the chat, at the message
            final Bundle args = new Bundle();
            if (item.dialogId > 0) {
                args.putLong("user_id", item.dialogId);
            } else {
                args.putLong("chat_id", -item.dialogId);
            }
            if (item.messageId != 0) {
                args.putInt("message_id", item.messageId);
            }
            presentFragment(new ChatActivity(args));
            return;
        }
        if (item.kind == KIND_FOLLOWERS) {
            final Bundle args = new Bundle();
            args.putLong("chat_id", channel.id);
            args.putInt("type", ChatUsersActivity.TYPE_USERS);
            presentFragment(new ChatUsersActivity(args));
        } else if (item.kind == KIND_COMMENT && item.post != null) {
            showDialog(new TigiCommentsSheet(this, channel, item.post));
        } else if (item.post != null) {
            final Bundle args = new Bundle();
            args.putLong("chat_id", channel.id);
            args.putInt("start_message_id", item.post.getId());
            presentFragment(new ChannelFeedActivity(args));
        }
    }

    /* list */

    private class Adapter extends RecyclerListView.SelectionAdapter {
        static final int TYPE_SECTION = 0;
        static final int TYPE_ITEM = 1;
        static final int TYPE_LOADING = 2;
        static final int TYPE_EMPTY = 3;

        @Override
        public int getItemCount() {
            return rows.isEmpty() ? 1 : rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (rows.isEmpty()) {
                return loading ? TYPE_LOADING : TYPE_EMPTY;
            }
            return rows.get(position) instanceof Item ? TYPE_ITEM : TYPE_SECTION;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_ITEM;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_ITEM) {
                view = new ItemCell(parent.getContext());
            } else if (viewType == TYPE_LOADING) {
                final FlickerLoadingView flicker = new FlickerLoadingView(parent.getContext());
                flicker.setViewType(FlickerLoadingView.DIALOG_TYPE);
                flicker.setIsSingleCell(false);
                view = flicker;
            } else {
                final TextView text = new TextView(parent.getContext());
                if (viewType == TYPE_SECTION) {
                    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    text.setTypeface(TigiStyle.displayBold());
                    text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    text.setPadding(dp(16), dp(14), dp(16), dp(6));
                } else {
                    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    text.setGravity(Gravity.CENTER);
                    text.setPadding(dp(32), dp(80), dp(32), dp(32));
                }
                view = text;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, viewType == TYPE_LOADING ? dp(500) : ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final int type = holder.getItemViewType();
            if (type == TYPE_ITEM) {
                ((ItemCell) holder.itemView).bind((Item) rows.get(position));
            } else if (type == TYPE_SECTION) {
                ((TextView) holder.itemView).setText((String) rows.get(position));
            } else if (type == TYPE_EMPTY) {
                ((TextView) holder.itemView).setText(LocaleController.getString(R.string.NilakActivityEmpty));
            }
        }
    }

    private class ItemCell extends LinearLayout {
        private final BackupImageView avatar;
        private final ImageView icon;
        private final TextView text;
        private final TextView meta;
        private final BackupImageView thumb;

        ItemCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(8), dp(16), dp(8));
            setMinimumHeight(dp(60));
            final FrameLayout left = new FrameLayout(context);
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(20));
            left.addView(avatar, LayoutHelper.createFrame(40, 40));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            left.addView(icon, LayoutHelper.createFrame(40, 40));
            addView(left, LayoutHelper.createLinear(40, 40));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(text);
            meta = new TextView(context);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            texts.addView(meta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 10, 0));
            thumb = new BackupImageView(context);
            thumb.setRoundRadius(dp(8));
            addView(thumb, LayoutHelper.createLinear(40, 40));
        }

        void bind(Item item) {
            text.setText(item.text);
            final String time = LocaleController.stringForMessageListDate(item.date);
            meta.setText(TextUtils.isEmpty(item.detail) ? time : item.detail + "  ·  " + time);
            if (item.who != null) {
                final AvatarDrawable drawable = new AvatarDrawable();
                if (item.who instanceof TLRPC.User) {
                    drawable.setInfo(currentAccount, (TLRPC.User) item.who);
                } else {
                    drawable.setInfo(currentAccount, (TLRPC.Chat) item.who);
                }
                avatar.setForUserOrChat(item.who, drawable);
                avatar.setVisibility(VISIBLE);
                icon.setVisibility(GONE);
            } else {
                avatar.setVisibility(GONE);
                icon.setVisibility(VISIBLE);
                icon.setImageResource(item.kind == KIND_VIEWS ? R.drawable.tigi_reels_24 : R.drawable.tigi_heart_filled_24);
                icon.setBackground(Theme.createRoundRectDrawable(dp(20), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
                if (item.kind == KIND_VIEWS) {
                    icon.setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
                } else {
                    icon.clearColorFilter();
                }
            }
            if (item.post != null && item.post.photoThumbs != null && !item.post.photoThumbs.isEmpty()) {
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(item.post.photoThumbs, 100);
                thumb.setImage(ImageLocation.getForObject(size, item.post.photoThumbsObject), "40_40", (android.graphics.drawable.Drawable) null, item.post.photoThumbsObject);
                thumb.setVisibility(VISIBLE);
            } else {
                thumb.setVisibility(item.kind == KIND_FOLLOWERS ? GONE : INVISIBLE);
            }
            setContentDescription(item.text + ", " + meta.getText());
        }
    }
}
