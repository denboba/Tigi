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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * One Ask question: the topic's title and first message, then its answers sorted by 👍, each with its replies count.
 * Writing an answer opens the topic in Telegram's chat, where the person's message becomes the answer.
 */
public class NilakQuestionActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MAX_MESSAGES = 100;
    private static final int CHOSEN_COLOR = 0xFF2E9E62;

    private long chatId;
    private int topicId;
    private TLRPC.Chat board;
    private TLRPC.TL_forumTopic topic;
    private MessageObject details;
    private final ArrayList<MessageObject> answers = new ArrayList<>();
    private final HashMap<Integer, Integer> repliesCount = new HashMap<>();
    private boolean loading;
    // answers the asker chose with their ✅
    private final java.util.HashSet<Integer> chosen = new java.util.HashSet<>();
    private MessageObject replyTo;
    private android.widget.EditText answerField;
    private TextView replyChip;

    private LinearLayout content;
    private TextView statusView;

    public static void open(BaseFragment fragment, long chatId, int topicId) {
        final Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        args.putInt("topic_id", topicId);
        fragment.presentFragment(new NilakQuestionActivity(args));
    }

    public NilakQuestionActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        chatId = arguments.getLong("chat_id");
        topicId = arguments.getInt("topic_id");
        board = getMessagesController().getChat(chatId);
        topic = getMessagesController().getTopicsController().findTopic(chatId, topicId);
        if (topic == null && board != null) {
            // just asked: Telegram's topic list catches up a moment later
            topic = new TLRPC.TL_forumTopic();
            topic.id = topicId;
            topic.title = "";
            topic.date = getConnectionsManager().getCurrentTime();
            topic.from_id = getMessagesController().getPeer(getUserConfig().getClientUserId());
        }
        if (board == null) {
            return false;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.topicsDidLoaded);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().removeObserver(this, NotificationCenter.topicsDidLoaded);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didUpdateReactions) {
            if ((Long) args[0] != -chatId || !(args[2] instanceof TLRPC.TL_messageReactions)) {
                return;
            }
            final int msgId = (Integer) args[1];
            for (MessageObject message : allMessages()) {
                if (message.getId() == msgId) {
                    MessageObject.updateReactions(message.messageOwner, (TLRPC.TL_messageReactions) args[2]);
                    bind();
                }
            }
        } else if (id == NotificationCenter.topicsDidLoaded) {
            final TLRPC.TL_forumTopic loaded = getMessagesController().getTopicsController().findTopic(chatId, topicId);
            if ((Long) args[0] == chatId && loaded != null) {
                topic = loaded;
                bind();
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            // a new answer in this topic: load again so it shows in its place
            if ((Long) args[0] == -chatId) {
                load();
            }
        }
    }

    private ArrayList<MessageObject> allMessages() {
        final ArrayList<MessageObject> all = new ArrayList<>(answers);
        if (details != null) {
            all.add(details);
        }
        return all;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        TigiFeedBase.applyPageBackground(root);

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        final FrameLayout bar = new FrameLayout(context);
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        final ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        back.setColorFilter(new PorterDuffColorFilter(TigiFeedBase.inkColor(), PorterDuff.Mode.SRC_IN));
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> finishFragment());
        if (LocaleController.isRTL) {
            back.setScaleX(-1f);
        }
        bar.addView(back, LayoutHelper.createFrame(48, 56, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakAskQuestion));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(TigiStyle.displayBold());
        title.setTextColor(TigiFeedBase.inkColor());
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 60, 0, 60, 0));
        column.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(6), 0, dp(90) + AndroidUtilities.navigationBarHeight);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // the answer box: an answer, or a reply to the answer picked with Reply; sent into the question's topic
        final LinearLayout composer = new LinearLayout(context);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(14), dp(6), dp(8), dp(6));
        composer.setBackground(Theme.createRoundRectDrawable(dp(24), TigiFeedBase.cardColor()));
        composer.setElevation(dp(2));
        replyChip = new TextView(context);
        replyChip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        replyChip.setTypeface(TigiStyle.displayBold());
        replyChip.setTextColor(TigiStyle.accent());
        replyChip.setPadding(dp(4), dp(4), dp(4), 0);
        replyChip.setVisibility(View.GONE);
        // tapping the chip goes back to answering the question itself
        replyChip.setOnClickListener(v -> setReplyTo(null));
        composer.addView(replyChip);
        final LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        answerField = new android.widget.EditText(context);
        answerField.setHint(LocaleController.getString(R.string.NilakAskWriteAnswer));
        answerField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        answerField.setTextColor(TigiFeedBase.inkColor());
        answerField.setHintTextColor(TigiFeedBase.metaColor());
        answerField.setBackground(null);
        answerField.setMaxLines(5);
        answerField.setPadding(dp(4), dp(8), dp(4), dp(8));
        answerField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        inputRow.addView(answerField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final ImageView send = new ImageView(context);
        send.setImageResource(R.drawable.ic_send);
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
        send.setContentDescription(LocaleController.getString(R.string.NilakAskSend));
        send.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.15f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        send.setOnClickListener(v -> sendAnswer());
        inputRow.addView(send, LayoutHelper.createLinear(44, 44));
        composer.addView(inputRow);
        root.addView(composer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 12, 0, 12, 12 + AndroidUtilities.navigationBarHeight / AndroidUtilities.density));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTextColor(TigiFeedBase.metaColor());
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(24), dp(24), dp(24), dp(24));

        fragmentView = root;
        bind();
        load();
        return root;
    }

    private void setReplyTo(MessageObject answer) {
        replyTo = answer;
        if (answer == null) {
            replyChip.setVisibility(View.GONE);
            answerField.setHint(LocaleController.getString(R.string.NilakAskWriteAnswer));
            return;
        }
        final TLRPC.User user = getMessagesController().getUser(answer.getFromChatId());
        replyChip.setText(LocaleController.formatString(R.string.NilakAskReplyingTo, user != null ? UserObject.getFirstName(user) : "") + "   ✕");
        replyChip.setVisibility(View.VISIBLE);
        answerField.requestFocus();
        AndroidUtilities.showKeyboard(answerField);
    }

    private void sendAnswer() {
        final String text = answerField.getText().toString().trim();
        if (text.isEmpty()) {
            AndroidUtilities.shakeView(answerField);
            return;
        }
        if (ChatObject.isNotInChat(board)) {
            // only members write in a group: join it in its chat first
            ForumUtilities.openTopic(this, chatId, topic, 0);
            return;
        }
        NilakAsk.send(this, chatId, topicId, replyTo, text);
        answerField.setText("");
        setReplyTo(null);
        AndroidUtilities.hideKeyboard(answerField);
    }

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        final TLRPC.TL_messages_getReplies request = new TLRPC.TL_messages_getReplies();
        request.peer = getMessagesController().getInputPeer(-chatId);
        request.msg_id = topicId;
        request.limit = MAX_MESSAGES;
        final int requestId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            if (!(response instanceof TLRPC.messages_Messages) || fragmentView == null) {
                return;
            }
            onLoaded((TLRPC.messages_Messages) response);
        }));
        getConnectionsManager().bindRequestToGuid(requestId, classGuid);
    }

    private void onLoaded(TLRPC.messages_Messages res) {
        getMessagesController().putUsers(res.users, false);
        getMessagesController().putChats(res.chats, false);
        details = null;
        answers.clear();
        repliesCount.clear();
        int firstId = Integer.MAX_VALUE;
        for (TLRPC.Message message : res.messages) {
            if (message instanceof TLRPC.TL_message && message.id < firstId) {
                firstId = message.id;
            }
        }
        for (TLRPC.Message message : res.messages) {
            if (!(message instanceof TLRPC.TL_message)) {
                continue;
            }
            final MessageObject object = new MessageObject(currentAccount, message, false, false);
            final int replyTo = message.reply_to != null ? message.reply_to.reply_to_msg_id : 0;
            if (message.id == firstId) {
                details = object;
            } else if (replyTo == 0 || replyTo == topicId || replyTo == firstId) {
                answers.add(object);
            } else {
                final Integer counted = repliesCount.get(replyTo);
                repliesCount.put(replyTo, counted != null ? counted + 1 : 1);
            }
        }
        bind();
        // the asker's ✅ marks the chosen answer
        chosen.clear();
        final long askerId = topic.from_id != null ? MessageObject.getPeerId(topic.from_id) : 0;
        if (askerId != 0) {
            NilakAsk.loadChosen(this, chatId, askerId, answers, answerId -> {
                chosen.add(answerId);
                bind();
            });
        }
    }

    private void bind() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        content.addView(questionCard(), cardParams());
        // the most 👍 first; ties: the earlier answer
        final ArrayList<MessageObject> sorted = new ArrayList<>(answers);
        Collections.sort(sorted, (a, b) -> {
            final int byChosen = Boolean.compare(chosen.contains(b.getId()), chosen.contains(a.getId()));
            if (byChosen != 0) {
                return byChosen;
            }
            final int byVotes = Integer.compare(NilakAsk.count(b, NilakAsk.VOTE), NilakAsk.count(a, NilakAsk.VOTE));
            return byVotes != 0 ? byVotes : Integer.compare(a.getId(), b.getId());
        });
        final TextView header = new TextView(content.getContext());
        header.setText(LocaleController.formatPluralString("NilakAskAnswers", sorted.size()));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(TigiStyle.displayBold());
        header.setTextColor(TigiFeedBase.inkColor());
        header.setPadding(dp(20), dp(6), dp(20), dp(8));
        content.addView(header);
        for (MessageObject answer : sorted) {
            content.addView(answerCard(answer), cardParams());
        }
        if (sorted.isEmpty()) {
            if (statusView.getParent() != null) {
                ((android.view.ViewGroup) statusView.getParent()).removeView(statusView);
            }
            statusView.setText(LocaleController.getString(loading ? R.string.Loading : R.string.NilakAskNoAnswers));
            content.addView(statusView);
        }
    }

    private LinearLayout.LayoutParams cardParams() {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10);
    }

    private View questionCard() {
        final Context context = content.getContext();
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Theme.createRoundRectDrawable(dp(TigiFeedBase.cardRadius()), TigiFeedBase.cardColor()));
        card.addView(authorRow(details != null ? details.messageOwner : null, topic.from_id, topic.date));
        final TextView title = new TextView(context);
        title.setText(topic.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        title.setTypeface(TigiStyle.displayBold());
        title.setTextColor(TigiFeedBase.inkColor());
        card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        if (details != null && !TextUtils.isEmpty(details.messageText)) {
            final TextView text = new TextView(context);
            text.setText(details.messageText);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setTextColor(TigiFeedBase.inkColor());
            text.setLineSpacing(dp(2), 1f);
            text.setTextIsSelectable(true);
            card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }
        if (details != null) {
            final LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.addView(votePill(details), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
            card.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }
        return card;
    }

    private View answerCard(MessageObject answer) {
        final Context context = content.getContext();
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        final boolean isChosen = chosen.contains(answer.getId());
        if (isChosen) {
            final android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
            outline.setColor(TigiFeedBase.cardColor());
            outline.setCornerRadius(dp(20));
            outline.setStroke(dp(2), CHOSEN_COLOR);
            card.setBackground(outline);
            final TextView label = new TextView(context);
            label.setText("✓ " + LocaleController.getString(R.string.NilakAskChosen));
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            label.setTypeface(TigiStyle.displayBold());
            label.setTextColor(CHOSEN_COLOR);
            card.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        } else {
            card.setBackground(Theme.createRoundRectDrawable(dp(20), TigiFeedBase.cardColor()));
        }
        card.addView(authorRow(answer.messageOwner, answer.messageOwner.from_id, answer.messageOwner.date));
        final TextView text = new TextView(context);
        text.setText(TextUtils.isEmpty(answer.messageText) ? LocaleController.getString(R.string.AttachPhoto) : answer.messageText);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        text.setTextColor(TigiFeedBase.inkColor());
        text.setLineSpacing(dp(2), 1f);
        text.setTextIsSelectable(true);
        card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(votePill(answer), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        actions.addView(textAction(context, LocaleController.getString(R.string.NilakAskReply), () -> setReplyTo(answer)),
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        if (NilakAsk.isMine(currentAccount, topic.from_id) && !NilakAsk.isMine(currentAccount, answer.messageOwner.from_id)) {
            // the asker picks the answer that helped; their ✅ reaction, visible in every Telegram app
            actions.addView(textAction(context, isChosen ? "✓ " + LocaleController.getString(R.string.NilakAskChosen)
                : LocaleController.getString(R.string.NilakAskChoose), () -> {
                NilakAsk.toggleChosen(this, answer);
                if (!chosen.remove(answer.getId())) {
                    chosen.add(answer.getId());
                }
                bind();
            }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        }
        final Integer counted = repliesCount.get(answer.getId());
        final int replies = counted != null ? counted : 0;
        if (replies > 0) {
            final TextView more = new TextView(context);
            more.setText(LocaleController.formatPluralString("NilakAskReplies", replies));
            more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            more.setTypeface(TigiStyle.displayBold());
            more.setTextColor(TigiStyle.accent());
            more.setPadding(dp(12), 0, dp(12), 0);
            more.setGravity(Gravity.CENTER_VERTICAL);
            more.setOnClickListener(v -> ForumUtilities.openTopic(this, chatId, topic, answer.getId()));
            actions.addView(more, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        }
        card.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        return card;
    }

    private View authorRow(TLRPC.Message message, TLRPC.Peer from, int date) {
        final Context context = content.getContext();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final long fromId = from != null ? org.telegram.messenger.MessageObject.getPeerId(from) : 0;
        final TLRPC.User user = fromId > 0 ? getMessagesController().getUser(fromId) : null;
        final TLRPC.Chat chat = fromId < 0 ? getMessagesController().getChat(-fromId) : null;
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(14));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (user != null) {
            avatarDrawable.setInfo(currentAccount, user);
            avatar.setForUserOrChat(user, avatarDrawable);
        } else if (chat != null) {
            avatarDrawable.setInfo(currentAccount, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
        }
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, LayoutHelper.createLinear(28, 28));
        final TextView name = new TextView(context);
        name.setText(user != null ? UserObject.getUserName(user) : chat != null ? chat.title : "");
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(TigiFeedBase.inkColor());
        name.setSingleLine(true);
        row.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 6, 0));
        final TextView time = new TextView(context);
        time.setText(LocaleController.stringForMessageListDate(date));
        time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        time.setTextColor(TigiFeedBase.metaColor());
        row.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        if (user != null) {
            final long userId = user.id;
            avatar.setOnClickListener(v -> TigiNavigation.openProfile(this, userId));
            name.setOnClickListener(v -> TigiNavigation.openProfile(this, userId));
        }
        return row;
    }

    private TextView textAction(Context context, String text, Runnable action) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTypeface(TigiStyle.displayBold());
        view.setTextColor(TigiFeedBase.metaColor());
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /** ▲ and the 👍 count; a tap votes or takes the vote back. */
    private View votePill(MessageObject message) {
        final TextView pill = new TextView(content.getContext());
        final boolean voted = NilakAsk.chosenByMe(message, NilakAsk.VOTE);
        pill.setText("▲  " + LocaleController.formatShortNumber(NilakAsk.count(message, NilakAsk.VOTE), null));
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        pill.setTypeface(TigiStyle.displayBold());
        pill.setTextColor(voted ? 0xFFFFFFFF : TigiStyle.accent());
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(14), 0, dp(14), 0);
        pill.setBackground(voted ? TigiStyle.primaryPill(17) : TigiStyle.tintedPill(17));
        pill.setContentDescription(LocaleController.getString(R.string.NilakAskVote));
        pill.setOnClickListener(v -> {
            NilakAsk.toggleVote(this, message);
            bind();
        });
        return pill;
    }
}
