package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Ask: questions and answers in a community, made only of Telegram. The Ask board is a group with topics linked to the
 * community: a question is a topic, its answers are the topic's messages, 👍 is the vote and a ✅ from the asker marks
 * the answer that helped. Everything opens in Telegram's own topic list and topic chat; Nilak finds the board, shows
 * the newest questions and lets admins add a board in one tap.
 */
public final class NilakAsk {

    // Nilak's bot posts anonymous questions on the boards it helps run (it answers /askanon in a private chat)
    public static final String BOT_USERNAME = "NilakAppBot";
    // the topics read at a time, newest first
    private static final int MAX_QUESTIONS = 40;
    private static final int GENERAL_TOPIC_ID = 1;

    private NilakAsk() {
    }

    /** When a question was asked: Telegram leaves some topics without a date, their first message has one. */
    public static int dateOf(TLRPC.TL_forumTopic topic) {
        if (topic.date > 0) {
            return topic.date;
        }
        if (topic.topicStartMessage != null && topic.topicStartMessage.date > 0) {
            return topic.topicStartMessage.date;
        }
        return topic.topMessage != null ? topic.topMessage.date : 0;
    }

    /** The community's Ask board: its group with topics, preferring one the person is in. */
    public static TLRPC.Chat findBoard(int account, long communityId) {
        final MessagesController.CommunityPeersDialog peers = MessagesController.getInstance(account).buildCommunityPeers(communityId);
        if (peers == null) {
            return null;
        }
        for (ArrayList<MessagesController.CommunityPeerDialog> list : new ArrayList[]{peers.chatsYouAreIn, peers.chatsYouCanView}) {
            for (MessagesController.CommunityPeerDialog peer : list) {
                if (peer.chat != null && ChatObject.isForum(peer.chat) && !ChatObject.isMonoForum(peer.chat)) {
                    return peer.chat;
                }
            }
        }
        return null;
    }

    /** The board's questions from the topics Telegram has loaded, newest first (General and hidden topics left out). */
    public static ArrayList<TLRPC.TL_forumTopic> questionsOf(int account, TLRPC.Chat board) {
        final ArrayList<TLRPC.TL_forumTopic> result = new ArrayList<>();
        final ArrayList<TLRPC.TL_forumTopic> topics = MessagesController.getInstance(account).getTopicsController().getTopics(board.id);
        if (topics == null) {
            return result;
        }
        final ArrayList<TLRPC.TL_forumTopic> sorted = new ArrayList<>(topics);
        Collections.sort(sorted, (a, b) -> Integer.compare(dateOf(b), dateOf(a)));
        for (TLRPC.TL_forumTopic topic : sorted) {
            if (topic.id != GENERAL_TOPIC_ID && !topic.hidden && result.size() < MAX_QUESTIONS) {
                result.add(topic);
            }
        }
        return result;
    }

    /**
     * Admin: a new Ask board for the community. A group with topics named after it, where members may create topics
     * (ask) but group info and pins stay with admins, linked to the community; the bot joins as a topic admin so it can
     * post anonymous questions. onDone gets the new board, or null.
     */
    public static void createBoard(BaseFragment fragment, TLRPC.Chat community, String title, Utilities.Callback<TLRPC.Chat> onDone) {
        final int account = fragment.getCurrentAccount();
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.TL_channels_createChannel request = new TLRPC.TL_channels_createChannel();
        request.title = title;
        request.about = "";
        request.megagroup = true;
        request.forum = true;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (!(response instanceof TLRPC.Updates) || ((TLRPC.Updates) response).chats.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> onDone.run(null));
                return;
            }
            final TLRPC.Updates updates = (TLRPC.Updates) response;
            controller.processUpdates(updates, false);
            AndroidUtilities.runOnUIThread(() -> {
                final TLRPC.Chat board = updates.chats.get(0);
                controller.putChat(board, false);
                final TLRPC.TL_chatBannedRights rights = new TLRPC.TL_chatBannedRights();
                rights.change_info = true;
                rights.pin_messages = true;
                rights.manage_topics = false;
                controller.setDefaultBannedRole(board.id, rights, true, fragment);
                addBotToBoard(fragment, board.id);
                controller.linkCommunity(-board.id, community.id, false, (res, linkError) -> AndroidUtilities.runOnUIThread(() -> {
                    controller.loadFullChat(community.id, 0, true);
                    onDone.run(linkError == null ? board : null);
                }));
            });
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /** Makes the bot a board admin that may create topics, so it can post anonymous questions. */
    private static void addBotToBoard(BaseFragment fragment, long boardId) {
        final int account = fragment.getCurrentAccount();
        MessagesController.getInstance(account).getUserNameResolver().resolve(BOT_USERNAME, id -> {
            final TLRPC.User bot = id != null ? MessagesController.getInstance(account).getUser(id) : null;
            if (bot == null) {
                return;
            }
            final TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
            rights.manage_topics = true;
            rights.delete_messages = true;
            MessagesController.getInstance(account).setUserAdminRole(boardId, bot, rights, "Nilak", false,
                fragment, true, false, null, null);
        });
    }
}
