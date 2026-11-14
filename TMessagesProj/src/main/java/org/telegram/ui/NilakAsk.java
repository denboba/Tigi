package org.telegram.ui;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ask: questions and answers in a community, built only from Telegram. The Ask board is a group with topics linked to
 * the community; a question is a topic (its title is the question, its first message the details), answers are the
 * messages in the topic. 👍 is the vote, a ✅ from the asker marks the chosen answer, the first #hashtag is the category.
 * People on any Telegram app take part by writing and reacting; Nilak only reads these conventions.
 */
public final class NilakAsk {

    public static final String VOTE = "👍";
    public static final String CHOSEN = "✅";
    // the topics Ask reads at a time, newest first
    private static final int MAX_QUESTIONS = 40;
    private static final int GENERAL_TOPIC_ID = 1;
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]{2,32})");
    // Hot: votes and answers, fading with age (hours)
    private static final double HOT_GRAVITY = 1.5;
    private static final int HOT_ANSWER_WEIGHT = 2;
    private static final int HOUR = 3600;

    private NilakAsk() {
    }

    public static final class Question {
        public TLRPC.TL_forumTopic topic;
        // the first message of the topic: the details; may be missing for questions made without them
        public MessageObject details;
        // -1 until counted
        public int answers = -1;
        public String category;

        public int votes() {
            return details != null ? count(details, VOTE) : 0;
        }

        public boolean voted() {
            return details != null && chosenByMe(details, VOTE);
        }

        public double hot(int now) {
            final double hours = Math.max(0, now - topic.date) / (double) HOUR;
            return (votes() + Math.max(0, answers) * HOT_ANSWER_WEIGHT + 1) / Math.pow(hours + 2, HOT_GRAVITY);
        }
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

    /** Questions from the topics Telegram has loaded for the board, newest first (General and hidden topics left out). */
    public static ArrayList<Question> questionsOf(int account, TLRPC.Chat board, HashMap<Long, Question> known) {
        final ArrayList<Question> result = new ArrayList<>();
        final ArrayList<TLRPC.TL_forumTopic> topics = MessagesController.getInstance(account).getTopicsController().getTopics(board.id);
        if (topics == null) {
            return result;
        }
        final ArrayList<TLRPC.TL_forumTopic> sorted = new ArrayList<>(topics);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.date, a.date));
        for (TLRPC.TL_forumTopic topic : sorted) {
            if (topic.id == GENERAL_TOPIC_ID || topic.hidden || result.size() >= MAX_QUESTIONS) {
                continue;
            }
            Question question = known.get((long) topic.id);
            if (question == null) {
                question = new Question();
                known.put((long) topic.id, question);
            }
            question.topic = topic;
            result.add(question);
        }
        return result;
    }

    /** Loads the details of the questions (the message after each topic starts) and how many answers each has. */
    public static void loadDetails(BaseFragment fragment, TLRPC.Chat board, ArrayList<Question> questions, Runnable onChanged) {
        final int account = fragment.getCurrentAccount();
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.TL_channels_getMessages request = new TLRPC.TL_channels_getMessages();
        request.channel = controller.getInputChannel(board.id);
        for (Question question : questions) {
            if (question.details == null) {
                request.id.add(question.topic.id + 1);
            }
        }
        if (!request.id.isEmpty()) {
            final int requestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.messages_Messages)) {
                    return;
                }
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                controller.putUsers(res.users, false);
                controller.putChats(res.chats, false);
                for (TLRPC.Message message : res.messages) {
                    for (Question question : questions) {
                        if (isInTopic(message, question.topic.id) && message.id == question.topic.id + 1) {
                            question.details = new MessageObject(account, message, false, false);
                            question.category = categoryOf(message.message);
                        }
                    }
                }
                onChanged.run();
            }));
            ConnectionsManager.getInstance(account).bindRequestToGuid(requestId, fragment.getClassGuid());
        }
        for (Question question : questions) {
            if (question.answers >= 0) {
                continue;
            }
            final TLRPC.TL_messages_getReplies replies = new TLRPC.TL_messages_getReplies();
            replies.peer = controller.getInputPeer(-board.id);
            replies.msg_id = question.topic.id;
            replies.limit = 1;
            final int requestId = ConnectionsManager.getInstance(account).sendRequest(replies, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    final int total = res instanceof TLRPC.TL_messages_messages ? res.messages.size() : res.count;
                    // the details message is not an answer
                    question.answers = Math.max(0, total - 1);
                    onChanged.run();
                }
            }));
            ConnectionsManager.getInstance(account).bindRequestToGuid(requestId, fragment.getClassGuid());
        }
    }

    /** A message written in a topic: a plain message points at the topic, a reply also names it as its top. */
    public static boolean isInTopic(TLRPC.Message message, int topicId) {
        final TLRPC.MessageReplyHeader reply = message.reply_to;
        return reply != null && (reply.reply_to_top_id == topicId || reply.reply_to_msg_id == topicId);
    }

    public static String categoryOf(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        final Matcher matcher = HASHTAG.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static int count(MessageObject message, String emoji) {
        return TigiFeedBase.countOf(message, ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(emoji));
    }

    public static boolean chosenByMe(MessageObject message, String emoji) {
        if (message.messageOwner == null || message.messageOwner.reactions == null) {
            return false;
        }
        for (TLRPC.ReactionCount result : message.messageOwner.reactions.results) {
            if (result.chosen && result.reaction instanceof TLRPC.TL_reactionEmoji
                && emoji.equals(((TLRPC.TL_reactionEmoji) result.reaction).emoticon)) {
                return true;
            }
        }
        return false;
    }

    /** Adds or takes back the person's 👍, the way reactions work in the chat. */
    public static void toggleVote(BaseFragment fragment, MessageObject message) {
        final ReactionsLayoutInBubble.VisibleReaction vote = ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(VOTE);
        final boolean added = message.selectReaction(vote, false, false);
        final ArrayList<ReactionsLayoutInBubble.VisibleReaction> chosen = new ArrayList<>(message.getChoosenReactions());
        fragment.getSendMessagesHelper().sendReaction(message, chosen, added ? vote : null, false, false, fragment, null,
            error -> message.selectReaction(vote, false, false));
    }
}
