package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * One Ask question as Nilak cards: the question (its title over the first message of the topic, with any photos,
 * files, polls or voice it has), then the answers, sorted by votes (👍) or newest. Cards render every kind of message
 * the way the feeds do; writing opens Telegram's own composer in the question's topic.
 */
public class NilakQuestionActivity extends TigiFeedBase {

    private static final int MAX_MESSAGES = 100;
    private static final int CHOSEN_COLOR = 0xFF2E9E62;
    private static final float TITLE_SIZE = 1.3f;

    private long chatId;
    private int topicId;
    private TLRPC.Chat board;
    private TLRPC.TL_forumTopic topic;
    private final HashMap<Integer, Integer> repliesCount = new HashMap<>();
    // answers the asker chose with their ✅
    private final HashSet<Integer> chosen = new HashSet<>();
    private final ArrayList<FeedPost> answers = new ArrayList<>();
    private FeedPost question;
    // each card's own text, before the title or the chosen label is put on it
    private final HashMap<FeedPost, CharSequence> originalText = new HashMap<>();
    private boolean loading;
    private boolean loaded;
    private boolean sortNew;
    private TextView answersTitle;
    private TextView sortTop;
    private TextView sortNewView;

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
        if (board == null) {
            return false;
        }
        // just asked: Telegram's topic list catches up a moment later
        topic = NilakAsk.newTopic(currentAccount, chatId, topicId, "");
        getNotificationCenter().addObserver(this, NotificationCenter.topicsDidLoaded);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.topicsDidLoaded);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.topicsDidLoaded) {
            final TLRPC.TL_forumTopic found = getMessagesController().getTopicsController().findTopic(chatId, topicId);
            if ((Long) args[0] == chatId && found != null) {
                topic = found;
                rebuild();
            }
            return;
        }
        if (id == NotificationCenter.didReceiveNewMessages && (Long) args[0] == -chatId) {
            // a new answer or reply: load again so it shows in its place
            load();
            return;
        }
        super.didReceivedNotification(id, account, args);
    }

    /* header, answers bar and composer */

    @Override
    protected View createHeaderView(Context context) {
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (TigiLilac.on()) {
            header.setBackground(TigiLilac.pageBar());
        } else {
            header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        applyTopInset(header);
        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(new PorterDuffColorFilter(inkColor(), PorterDuff.Mode.SRC_IN));
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> finishFragment());
        if (LocaleController.isRTL) {
            back.setScaleX(-1f);
        }
        header.addView(back, LayoutHelper.createLinear(48, 56));
        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakAskQuestion));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(TigiStyle.displayBold());
        title.setTextColor(inkColor());
        names.addView(title);
        final TextView where = new TextView(context);
        where.setText(board.title);
        where.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        where.setTextColor(metaColor());
        where.setSingleLine(true);
        where.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(where);
        header.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 4, 0, 16, 0));

        // between the question and its answers: how many, and the order
        inlineRow = answersBar(context);
        return header;
    }

    private View answersBar(Context context) {
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(4), dp(14), dp(8));
        answersTitle = new TextView(context);
        answersTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        answersTitle.setTypeface(TigiStyle.displayBold());
        answersTitle.setTextColor(inkColor());
        bar.addView(answersTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        sortTop = sortChip(context, LocaleController.getString(R.string.NilakAskTop), () -> setSortNew(false));
        bar.addView(sortTop, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        sortNewView = sortChip(context, LocaleController.getString(R.string.NilakAskNew), () -> setSortNew(true));
        bar.addView(sortNewView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30));
        bindAnswersBar();
        return bar;
    }

    private TextView sortChip(Context context, String text, Runnable action) {
        final TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTypeface(TigiStyle.displayBold());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    private void setSortNew(boolean value) {
        if (sortNew != value) {
            sortNew = value;
            rebuild();
        }
    }

    private void bindAnswersBar() {
        if (answersTitle == null) {
            return;
        }
        answersTitle.setText(LocaleController.formatPluralString("NilakAskAnswers", answers.size()));
        sortTop.setTextColor(!sortNew ? 0xFFFFFFFF : TigiStyle.accent());
        sortTop.setBackground(!sortNew ? TigiStyle.primaryPill(15) : TigiStyle.tintedPill(15));
        sortNewView.setTextColor(sortNew ? 0xFFFFFFFF : TigiStyle.accent());
        sortNewView.setBackground(sortNew ? TigiStyle.primaryPill(15) : TigiStyle.tintedPill(15));
    }

    @Override
    protected int inlineRowAfter() {
        return 1;
    }

    @Override
    protected int bottomPadding() {
        return dp(96) + AndroidUtilities.navigationBarHeight;
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        // the answer bar: Telegram's own composer in the question's topic (keyboard, emoji, stickers, GIFs, voice,
        // attachments, polls, mentions), opened ready to type
        final LinearLayout composer = new LinearLayout(context);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(6), 0, dp(6), 0);
        composer.setBackground(Theme.createRoundRectDrawable(dp(24), cardColor()));
        composer.setElevation(dp(2));
        composer.setOnClickListener(v -> openComposer(null, ChatActivity.TIGI_COMPOSE_TEXT, 0));
        composer.addView(barIcon(context, R.drawable.input_smile, () -> openComposer(null, ChatActivity.TIGI_COMPOSE_TEXT, 0)),
            LayoutHelper.createLinear(44, 44));
        final TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.NilakAskWriteAnswer));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        hint.setTextColor(metaColor());
        hint.setSingleLine(true);
        composer.addView(hint, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));
        composer.addView(barIcon(context, R.drawable.input_attach, () -> openComposer(null, 0, ChatActivity.TIGI_ACTION_ATTACH)),
            LayoutHelper.createLinear(44, 44));
        composer.addView(barIcon(context, R.drawable.input_mic, () -> openComposer(null, ChatActivity.TIGI_COMPOSE_TEXT, 0)),
            LayoutHelper.createLinear(44, 44));
        composer.setContentDescription(hint.getText());
        final FrameLayout root = getRootLayout();
        root.addView(composer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.BOTTOM, 12, 0, 12,
            12 + AndroidUtilities.navigationBarHeight / AndroidUtilities.density));
        load();
        return view;
    }

    private ImageView barIcon(Context context, int icon, Runnable action) {
        final ImageView view = new ImageView(context);
        view.setImageResource(icon);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setColorFilter(new PorterDuffColorFilter(metaColor(), PorterDuff.Mode.SRC_IN));
        view.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.15f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        view.setOnClickListener(v -> action.run());
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /**
     * Opens the question's topic in Telegram's chat: replying to an answer, or ready to type, or with a tool open.
     * Members only write in a group: Telegram's chat offers Join first when needed.
     */
    private void openComposer(MessageObject replyTo, int compose, int action) {
        final Bundle args = new Bundle();
        if (compose != 0) {
            args.putInt("tigi_compose", compose);
        }
        if (action != 0) {
            args.putInt("tigi_action", action);
        }
        final ChatActivity chat = ForumUtilities.getChatActivityForTopic(this, chatId, topic, replyTo != null ? replyTo.getId() : 0, args);
        if (chat == null) {
            return;
        }
        if (replyTo != null && !ChatObject.isNotInChat(board)) {
            chat.setNilakReplyMessage(replyTo);
        }
        presentFragment(chat);
    }

    /* loading */

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        updateEmptyView();
        final TLRPC.TL_messages_getReplies request = new TLRPC.TL_messages_getReplies();
        request.peer = getMessagesController().getInputPeer(-chatId);
        request.msg_id = topicId;
        request.limit = MAX_MESSAGES;
        final int requestId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            loaded = true;
            if (isDead()) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                onLoaded((TLRPC.messages_Messages) response);
            } else {
                updateEmptyView();
            }
        }));
        getConnectionsManager().bindRequestToGuid(requestId, classGuid);
    }

    private void onLoaded(TLRPC.messages_Messages res) {
        getMessagesController().putUsers(res.users, false);
        getMessagesController().putChats(res.chats, false);
        int firstId = Integer.MAX_VALUE;
        for (TLRPC.Message message : res.messages) {
            if (message instanceof TLRPC.TL_message && message.id < firstId) {
                firstId = message.id;
            }
        }
        // the oldest message is the question's details; messages answering the topic or the details are answers,
        // the rest are replies to answers
        final ArrayList<TLRPC.Message> details = new ArrayList<>();
        final ArrayList<TLRPC.Message> answerMessages = new ArrayList<>();
        repliesCount.clear();
        TLRPC.Message first = null;
        for (TLRPC.Message message : res.messages) {
            if (message.id == firstId) {
                first = message;
            }
        }
        for (TLRPC.Message message : res.messages) {
            if (!(message instanceof TLRPC.TL_message)) {
                continue;
            }
            final int replyTo = message.reply_to != null ? message.reply_to.reply_to_msg_id : 0;
            final boolean sameAlbumAsFirst = first != null && first.grouped_id != 0 && message.grouped_id == first.grouped_id;
            if (message.id == firstId || sameAlbumAsFirst) {
                details.add(message);
            } else if (replyTo == 0 || replyTo == topicId || replyTo == firstId) {
                answerMessages.add(message);
            } else {
                final Integer counted = repliesCount.get(replyTo);
                repliesCount.put(replyTo, counted != null ? counted + 1 : 1);
            }
        }
        final ArrayList<FeedPost> questionPosts = buildPosts(board, details);
        question = questionPosts.isEmpty() ? null : questionPosts.get(0);
        answers.clear();
        answers.addAll(buildPosts(board, answerMessages));
        originalText.clear();
        if (question != null) {
            originalText.put(question, question.text);
        }
        for (FeedPost answer : answers) {
            originalText.put(answer, answer.text);
        }
        rebuild();
        // the asker's ✅ marks the chosen answer
        chosen.clear();
        final long askerId = topic.from_id != null ? MessageObject.getPeerId(topic.from_id) : 0;
        if (askerId != 0) {
            final ArrayList<MessageObject> answerObjects = new ArrayList<>();
            for (FeedPost answer : answers) {
                answerObjects.add(answer.primary());
            }
            NilakAsk.loadChosen(this, chatId, askerId, answerObjects, answerId -> {
                chosen.add(answerId);
                rebuild();
            });
        }
    }

    /** The question card (title over its details), then the answers in the chosen order. */
    private void rebuild() {
        posts.clear();
        final FeedPost questionCard = question != null ? question : titleOnlyQuestion();
        if (questionCard != null) {
            questionCard.text = questionText(question != null ? originalText.get(question) : null);
            posts.add(questionCard);
        }
        final ArrayList<FeedPost> sorted = new ArrayList<>(answers);
        Collections.sort(sorted, (a, b) -> {
            final int byChosen = Boolean.compare(chosen.contains(b.primary().getId()), chosen.contains(a.primary().getId()));
            if (byChosen != 0) {
                return byChosen;
            }
            if (sortNew) {
                return Integer.compare(b.date, a.date);
            }
            final int byVotes = Integer.compare(NilakAsk.count(b.primary(), NilakAsk.VOTE), NilakAsk.count(a.primary(), NilakAsk.VOTE));
            return byVotes != 0 ? byVotes : Integer.compare(a.date, b.date);
        });
        for (FeedPost answer : sorted) {
            answer.text = answerText(answer);
            posts.add(answer);
        }
        bindAnswersBar();
        notifyPostsChanged();
    }

    /** A question asked with a title only: a card from the topic's first message, so it can still be shown and answered. */
    private FeedPost titleOnlyQuestion() {
        if (!loaded) {
            return null;
        }
        final FeedPost post = new FeedPost();
        post.chat = board;
        post.date = NilakAsk.dateOf(topic);
        if (topic.from_id instanceof TLRPC.TL_peerUser) {
            post.author = getMessagesController().getUser(topic.from_id.user_id);
        }
        post.messages.add(NilakAsk.topicStart(currentAccount, chatId, topicId));
        return post;
    }

    private CharSequence questionText(CharSequence details) {
        final SpannableStringBuilder text = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(topic.title)) {
            text.append(topic.title);
            text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new RelativeSizeSpan(TITLE_SIZE), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!TextUtils.isEmpty(details)) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(details);
        }
        return text;
    }

    private CharSequence answerText(FeedPost answer) {
        final CharSequence body = originalText.get(answer);
        final String label = "✓ " + LocaleController.getString(R.string.NilakAskChosen);
        if (!chosen.contains(answer.primary().getId())) {
            return body;
        }
        final SpannableStringBuilder text = new SpannableStringBuilder(label);
        text.setSpan(new ForegroundColorSpan(CHOSEN_COLOR), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!TextUtils.isEmpty(body)) {
            text.append('\n').append(body);
        }
        return text;
    }

    /* cards: 👍 is the vote, the comment button replies, a tap opens the message in the topic */

    @Override
    protected ReactionsLayoutInBubble.VisibleReaction likeReaction(FeedPost post) {
        final MessageObject message = post.primary();
        if (message.messageOwner instanceof TLRPC.TL_messageService) {
            return null;
        }
        return ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(NilakAsk.VOTE);
    }

    @Override
    protected int likeIcon(boolean liked) {
        return liked ? R.drawable.nilak_vote_filled_24 : R.drawable.nilak_vote_24;
    }

    @Override
    protected int commentCount(FeedPost post) {
        if (!posts.isEmpty() && post == posts.get(0)) {
            return answers.size();
        }
        final Integer counted = repliesCount.get(post.primary().getId());
        return counted != null ? counted : 0;
    }

    @Override
    protected boolean commentsOn(FeedPost post) {
        return true;
    }

    @Override
    protected void openComments(FeedPost post) {
        // on the question: answer it; on an answer: reply to it
        final boolean isQuestion = !posts.isEmpty() && post == posts.get(0);
        openComposer(isQuestion ? null : post.primary(), isQuestion ? ChatActivity.TIGI_COMPOSE_TEXT : 0, 0);
    }

    @Override
    protected void onPostTapped(FeedPost post, int mediaIndex) {
        if (mediaIndex >= 0) {
            openMediaViewer(post, mediaIndex);
            return;
        }
        ForumUtilities.openTopic(this, chatId, topic, post.primary().getId() > 0 ? post.primary().getId() : 0);
    }

    @Override
    protected boolean expandsCaptions() {
        return true;
    }

    @Override
    protected boolean dropsLowValue() {
        return false;
    }

    @Override
    protected boolean acceptsNewMessage(long dialogId, MessageObject message) {
        // new answers arrive with the next load, in their place
        return false;
    }

    @Override
    protected void addPostMenuRows(FeedPost post, LinearLayout content, Runnable dismiss) {
        final boolean isQuestion = !posts.isEmpty() && post == posts.get(0);
        final MessageObject message = post.primary();
        if (isQuestion || !NilakAsk.isMine(currentAccount, topic.from_id) || NilakAsk.isMine(currentAccount, message.messageOwner.from_id)) {
            return;
        }
        // the asker picks the answer that helped: their ✅ reaction, visible in every Telegram app
        final boolean isChosen = chosen.contains(message.getId());
        content.addView(sheetRow(content.getContext(), R.drawable.msg_select,
            LocaleController.getString(isChosen ? R.string.NilakAskUnchoose : R.string.NilakAskChooseBest), false, () -> {
                dismiss.run();
                NilakAsk.toggleChosen(this, message);
                if (!chosen.remove(message.getId())) {
                    chosen.add(message.getId());
                }
                rebuild();
            }));
    }

    @Override
    protected boolean isLoadingPosts() {
        return loading;
    }

    @Override
    protected boolean isInitialLoading() {
        return loading && posts.isEmpty();
    }
}
