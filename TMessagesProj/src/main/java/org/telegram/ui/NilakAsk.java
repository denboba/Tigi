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

import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.tl.TL_forum;
import org.telegram.tgnet.tl.TL_update;

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

    /** The topic's first (service) message, which a message must name to be written in the topic. */
    public static MessageObject topicStart(int account, long boardId, int topicId) {
        final TLRPC.TL_messageActionTopicCreate action = new TLRPC.TL_messageActionTopicCreate();
        final TLRPC.TL_forumTopic topic = MessagesController.getInstance(account).getTopicsController().findTopic(boardId, topicId);
        action.title = topic != null ? topic.title : "";
        final TLRPC.TL_messageService message = new TLRPC.TL_messageService();
        message.action = action;
        message.peer_id = MessagesController.getInstance(account).getPeer(-boardId);
        message.dialog_id = -boardId;
        message.id = topicId;
        final MessageObject object = new MessageObject(account, message, false, false);
        // as Telegram does for a topic's first message, so the reply is marked as written in the topic
        object.isTopicMainMessage = true;
        return object;
    }

    /** Sends an answer (replyTo null) or a reply to an answer into the question's topic. */
    public static void send(BaseFragment fragment, long boardId, int topicId, MessageObject replyTo, String text) {
        final MessageObject start = topicStart(fragment.getCurrentAccount(), boardId, topicId);
        final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, -boardId,
            replyTo != null ? replyTo : start, start, null, true, null, null, null, true, 0, 0, null, false);
        fragment.getSendMessagesHelper().sendMessage(params);
    }

    /**
     * Creates a question: a topic with the question as its title, then its details (with the category as a #hashtag) as
     * the first message, the same as writing them in Telegram. onCreated gets the new topic's ID.
     */
    public static void createQuestion(BaseFragment fragment, TLRPC.Chat board, String title, String details, String category,
                                      Utilities.Callback<Integer> onCreated, Runnable onError) {
        final int account = fragment.getCurrentAccount();
        final TL_forum.TL_messages_createForumTopic request = new TL_forum.TL_messages_createForumTopic();
        request.peer = MessagesController.getInstance(account).getInputPeer(-board.id);
        request.title = title;
        request.random_id = Utilities.random.nextLong();
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            int topicId = 0;
            if (response instanceof TLRPC.Updates) {
                final TLRPC.Updates updates = (TLRPC.Updates) response;
                for (int i = 0; i < updates.updates.size(); i++) {
                    if (updates.updates.get(i) instanceof TL_update.TL_updateMessageID) {
                        topicId = ((TL_update.TL_updateMessageID) updates.updates.get(i)).id;
                    }
                }
            }
            if (topicId == 0) {
                onError.run();
                return;
            }
            final StringBuilder text = new StringBuilder(details == null ? "" : details.trim());
            if (!TextUtils.isEmpty(category) && (text.indexOf("#" + category) < 0)) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append('#').append(category);
            }
            if (text.length() > 0) {
                final MessageObject start = topicStart(account, board.id, topicId);
                final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text.toString(), -board.id,
                    start, start, null, true, null, null, null, true, 0, 0, null, false);
                fragment.getSendMessagesHelper().sendMessage(params);
            }
            MessagesController.getInstance(account).getTopicsController().loadTopics(board.id, false, org.telegram.messenger.TopicsController.LOAD_TYPE_PRELOAD);
            onCreated.run(topicId);
        }));
    }

    /** The answers the asker marked with ✅: only their own ✅ counts, so anyone else's ✅ is just a reaction. */
    public static void loadChosen(BaseFragment fragment, long boardId, long askerId, ArrayList<MessageObject> answers,
                                  Utilities.Callback<Integer> onChosen) {
        final int account = fragment.getCurrentAccount();
        for (MessageObject answer : answers) {
            if (count(answer, CHOSEN) <= 0) {
                continue;
            }
            final TLRPC.TL_messages_getMessageReactionsList request = new TLRPC.TL_messages_getMessageReactionsList();
            request.peer = MessagesController.getInstance(account).getInputPeer(-boardId);
            request.id = answer.getId();
            final TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
            reaction.emoticon = CHOSEN;
            request.reaction = reaction;
            request.flags |= 1;
            request.limit = 50;
            final int requestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.TL_messages_messageReactionsList)) {
                    return;
                }
                for (TLRPC.MessagePeerReaction peerReaction : ((TLRPC.TL_messages_messageReactionsList) response).reactions) {
                    if (peerReaction.peer_id != null && MessageObject.getPeerId(peerReaction.peer_id) == askerId) {
                        onChosen.run(answer.getId());
                        return;
                    }
                }
            }));
            ConnectionsManager.getInstance(account).bindRequestToGuid(requestId, fragment.getClassGuid());
        }
    }

    /** The asker chooses or un-chooses an answer: their ✅ reaction on it, next to any other reactions they gave. */
    public static void toggleChosen(BaseFragment fragment, MessageObject answer) {
        final ReactionsLayoutInBubble.VisibleReaction chosen = ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(CHOSEN);
        final boolean added = answer.selectReaction(chosen, false, false);
        final ArrayList<ReactionsLayoutInBubble.VisibleReaction> all = new ArrayList<>(answer.getChoosenReactions());
        fragment.getSendMessagesHelper().sendReaction(answer, all, added ? chosen : null, false, false, fragment, null,
            error -> answer.selectReaction(chosen, false, false));
    }

    public static boolean isMine(int account, TLRPC.Peer from) {
        return from != null && MessageObject.getPeerId(from) == UserConfig.getInstance(account).getClientUserId();
    }

    // categories offered when the board has none yet
    private static final String[] DEFAULT_CATEGORIES = {"question", "study", "technology", "jobs", "personal"};
    private static final int TITLE_MAX = 128;

    /** Ask the community: the question (the topic's title), details and a category; Post creates it on the board. */
    public static void showAskSheet(BaseFragment fragment, TLRPC.Chat board, java.util.Collection<String> categories,
                                    Utilities.Callback<Integer> onCreated) {
        final android.content.Context context = fragment.getParentActivity();
        if (context == null || board == null) {
            return;
        }
        final android.widget.LinearLayout column = new android.widget.LinearLayout(context);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(18));
        final int ink = org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack);
        final int gray = org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextGray3);
        final int field = org.telegram.ui.ActionBar.Theme.multAlpha(TigiStyle.accent(), 0.08f);

        final android.widget.TextView title = new android.widget.TextView(context);
        title.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NilakAskSheetTitle));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(ink);
        column.addView(title);
        final android.widget.TextView where = new android.widget.TextView(context);
        where.setText(board.title);
        where.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        where.setTextColor(gray);
        column.addView(where, org.telegram.ui.Components.LayoutHelper.createLinear(-1, -2, 0, 2, 0, 12));

        final android.widget.EditText question = input(context, org.telegram.messenger.R.string.NilakAskQuestionHint, field, ink, gray);
        question.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(TITLE_MAX)});
        question.setSingleLine(false);
        question.setMaxLines(3);
        column.addView(question, org.telegram.ui.Components.LayoutHelper.createLinear(-1, -2));
        final android.widget.EditText details = input(context, org.telegram.messenger.R.string.NilakAskDetailsHint, field, ink, gray);
        details.setMinLines(3);
        details.setGravity(android.view.Gravity.TOP);
        column.addView(details, org.telegram.ui.Components.LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));

        final android.widget.TextView catLabel = new android.widget.TextView(context);
        catLabel.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NilakAskCategory));
        catLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        catLabel.setTypeface(TigiStyle.displayBold());
        catLabel.setTextColor(TigiStyle.accent());
        column.addView(catLabel, org.telegram.ui.Components.LayoutHelper.createLinear(-1, -2, 0, 14, 0, 6));
        final java.util.LinkedHashSet<String> offered = new java.util.LinkedHashSet<>(categories);
        if (offered.isEmpty()) {
            offered.addAll(java.util.Arrays.asList(DEFAULT_CATEGORIES));
        }
        final String[] picked = {null};
        final android.widget.LinearLayout chips = new android.widget.LinearLayout(context);
        chips.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        final ArrayList<android.widget.TextView> chipViews = new ArrayList<>();
        for (String category : offered) {
            final android.widget.TextView chip = new android.widget.TextView(context);
            chip.setText("#" + category);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setTypeface(TigiStyle.displayBold());
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
            chip.setOnClickListener(v -> {
                picked[0] = category.equals(picked[0]) ? null : category;
                for (android.widget.TextView view : chipViews) {
                    final boolean on = view.getText().toString().equals("#" + picked[0]);
                    view.setTextColor(on ? 0xFFFFFFFF : TigiStyle.accent());
                    view.setBackground(on ? TigiStyle.primaryPill(15) : TigiStyle.tintedPill(15));
                }
            });
            chip.setTextColor(TigiStyle.accent());
            chip.setBackground(TigiStyle.tintedPill(15));
            chipViews.add(chip);
            chips.addView(chip, org.telegram.ui.Components.LayoutHelper.createLinear(-2, 30, 0, 0, 6, 0));
        }
        final android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        column.addView(chipScroll, org.telegram.ui.Components.LayoutHelper.createLinear(-1, -2));

        final android.widget.TextView post = new android.widget.TextView(context);
        post.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NilakAskPost));
        post.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        post.setTypeface(TigiStyle.displayBold());
        post.setTextColor(0xFFFFFFFF);
        post.setGravity(android.view.Gravity.CENTER);
        post.setBackground(TigiStyle.primaryPill(23));
        column.addView(post, org.telegram.ui.Components.LayoutHelper.createLinear(-1, 46, 0, 16, 0, 0));

        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(column);
        final org.telegram.ui.ActionBar.BottomSheet.Builder builder = new org.telegram.ui.ActionBar.BottomSheet.Builder(context, true, fragment.getResourceProvider());
        builder.setCustomView(scroll);
        final org.telegram.ui.ActionBar.BottomSheet sheet = builder.create();
        post.setOnClickListener(v -> {
            final String text = question.getText().toString().trim();
            if (text.isEmpty()) {
                AndroidUtilities.shakeView(question);
                return;
            }
            post.setEnabled(false);
            post.setAlpha(0.6f);
            createQuestion(fragment, board, text, details.getText().toString(), picked[0], topicId -> {
                sheet.dismiss();
                onCreated.run(topicId);
            }, () -> {
                post.setEnabled(true);
                post.setAlpha(1f);
                org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.error,
                    org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NilakAskFailed)).show();
            });
        });
        fragment.showDialog(sheet);
        question.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(question), 300);
    }

    private static android.widget.EditText input(android.content.Context context, int hint, int background, int ink, int gray) {
        final android.widget.EditText edit = new android.widget.EditText(context);
        edit.setHint(org.telegram.messenger.LocaleController.getString(hint));
        edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        edit.setTextColor(ink);
        edit.setHintTextColor(gray);
        edit.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        edit.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(AndroidUtilities.dp(14), background));
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return edit;
    }
}
