package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.TigiHeart;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Nilak: comments in a sheet over the post instead of a jump into Telegram's discussion chat. Comments are the
 * replies in the channel's linked discussion group (messages.getReplies); writing one replies to the post's
 * discussion message, likes are heart reactions on the comment. A channel without a discussion group shows
 * "Comments are off", and its owner can turn them on (a quiet linked group, see TigiPosting).
 */
public class TigiCommentsSheet extends BottomSheet {

    private static final int PAGE = 50;

    private final BaseFragment fragment;
    private final int account;
    private final TLRPC.Chat channel;
    private final MessageObject post;
    private final ArrayList<MessageObject> comments = new ArrayList<>();
    private MessageObject discussionTop;
    private long discussionChatId;
    private MessageObject replyTo;
    private boolean loading = true;
    private boolean commentsOff;
    private String errorText;
    private boolean destroyed;
    private int maxListHeight;
    private int lastKeyboardHeight;

    private final RecyclerListView listView;
    private final Adapter adapter;
    private final TextView titleView;
    private final LinearLayout inputBar;
    private final EditTextBoldCursor input;
    private final TextView sendButton;
    private final TextView replyingView;

    public TigiCommentsSheet(BaseFragment fragment, TLRPC.Chat channel, MessageObject post) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        this.channel = channel;
        this.post = post;
        final Context context = fragment.getParentActivity();
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));

        final LinearLayout content = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // the list takes what is left above the input, so the input stays visible with the keyboard open
                final int screen = AndroidUtilities.displaySize.y;
                final int others = dp(44) + (replyingView.getVisibility() == VISIBLE ? dp(30) : 0) + (inputBar.getVisibility() == VISIBLE ? dp(62) : 0);
                final int available = screen - AndroidUtilities.statusBarHeight - keyboardHeight - others - dp(72);
                final int height = Math.max(dp(100), Math.min(maxListHeight, available));
                final ViewGroup.LayoutParams params = listView.getLayoutParams();
                if (params != null && params.height != height) {
                    params.height = height;
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(TigiStyle.displayBold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(LocaleController.getString(R.string.TigiComments));
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        maxListHeight = (int) (Math.min(AndroidUtilities.displaySize.y, AndroidUtilities.displaySize.x * 2) * 0.55f);
        content.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight));

        replyingView = new TextView(context);
        replyingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        replyingView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        replyingView.setPadding(dp(16), dp(6), dp(16), dp(2));
        replyingView.setVisibility(View.GONE);
        replyingView.setOnClickListener(v -> setReplyTo(null));
        content.addView(replyingView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputBar = new LinearLayout(context);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setPadding(dp(12), dp(6), dp(8), dp(8));
        final BackupImageView me = new BackupImageView(context);
        me.setRoundRadius(dp(16));
        final TLRPC.User self = fragment.getUserConfig().getCurrentUser();
        if (self != null) {
            final AvatarDrawable selfAvatar = new AvatarDrawable();
            selfAvatar.setInfo(account, self);
            me.setForUserOrChat(self, selfAvatar);
        }
        inputBar.addView(me, LayoutHelper.createLinear(32, 32));
        input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        input.setHint(LocaleController.getString(R.string.NilakAddComment));
        input.setCursorColor(TigiStyle.accent());
        input.setCursorWidth(1.5f);
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setPadding(dp(14), dp(9), dp(14), dp(9));
        input.setBackground(Theme.createRoundRectDrawable(dp(20), Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider)));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSend();
            }
        });
        inputBar.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 6, 0));
        sendButton = new TextView(context);
        sendButton.setText(LocaleController.getString(R.string.NilakPostComment));
        sendButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sendButton.setTypeface(TigiStyle.displayBold());
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setPadding(dp(10), 0, dp(10), 0);
        sendButton.setOnClickListener(v -> send());
        inputBar.addView(sendButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44));
        content.addView(inputBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        inputBar.setVisibility(View.GONE);
        updateSend();

        setCustomView(content);
        loadDiscussion();
    }

    @Override
    protected void onContainerTranslationYChanged(float translationY) {
        super.onContainerTranslationYChanged(translationY);
        if (lastKeyboardHeight != keyboardHeight) {
            lastKeyboardHeight = keyboardHeight;
            containerView.requestLayout();
            if (!comments.isEmpty()) {
                listView.post(() -> listView.scrollToPosition(comments.size() - 1));
            }
        }
    }

    @Override
    public void dismiss() {
        destroyed = true;
        AndroidUtilities.hideKeyboard(input);
        super.dismiss();
    }

    private void updateSend() {
        final boolean has = input.length() > 0 && discussionTop != null;
        sendButton.setEnabled(has);
        sendButton.setTextColor(has ? TigiStyle.accent() : Theme.multAlpha(TigiStyle.accent(), 0.4f));
    }

    private void setReplyTo(MessageObject comment) {
        replyTo = comment;
        if (comment == null) {
            replyingView.setVisibility(View.GONE);
            return;
        }
        replyingView.setText(LocaleController.formatString(R.string.NilakReplyingTo, authorName(comment)) + "  ✕");
        replyingView.setVisibility(View.VISIBLE);
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    /* loading */

    private void loadDiscussion() {
        final TLRPC.MessageReplies replies = post.messageOwner.replies;
        if (replies == null || !replies.comments || post.getId() <= 0) {
            showCommentsOff();
            return;
        }
        final TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = fragment.getMessagesController().getInputPeer(-channel.id);
        req.msg_id = post.getId();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (destroyed) {
                return;
            }
            if (response instanceof TLRPC.TL_messages_discussionMessage) {
                final TLRPC.TL_messages_discussionMessage res = (TLRPC.TL_messages_discussionMessage) response;
                fragment.getMessagesController().putUsers(res.users, false);
                fragment.getMessagesController().putChats(res.chats, false);
                if (!res.messages.isEmpty()) {
                    // the oldest message of the thread is the post's copy in the discussion group
                    TLRPC.Message top = res.messages.get(0);
                    for (int i = 1; i < res.messages.size(); i++) {
                        if (res.messages.get(i).id < top.id) {
                            top = res.messages.get(i);
                        }
                    }
                    discussionTop = new MessageObject(account, top, true, true);
                    discussionChatId = -discussionTop.getDialogId();
                    if (fragment.getMessagesController().getChatFull(discussionChatId) == null) {
                        fragment.getMessagesController().loadFullChat(discussionChatId, 0, true);
                    }
                    inputBar.setVisibility(View.VISIBLE);
                    updateSend();
                    loadComments();
                    return;
                }
            }
            loading = false;
            errorText = LocaleController.getString(R.string.TigiFeedError);
            adapter.notifyDataSetChanged();
        }));
    }

    private void loadComments() {
        final TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = fragment.getMessagesController().getInputPeer(-channel.id);
        req.msg_id = post.getId();
        req.limit = PAGE;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (destroyed) {
                return;
            }
            loading = false;
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                fragment.getMessagesController().putUsers(res.users, false);
                fragment.getMessagesController().putChats(res.chats, false);
                comments.clear();
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    if (message instanceof TLRPC.TL_messageService || TextUtils.isEmpty(message.message) && message.media == null) {
                        continue;
                    }
                    comments.add(new MessageObject(account, message, true, true));
                }
                // oldest first, newest at the bottom next to the input
                Collections.reverse(comments);
                final int total = res.count > 0 ? res.count : comments.size();
                titleView.setText(total > 0 ? LocaleController.formatPluralString("NilakCommentsCount", total) : LocaleController.getString(R.string.TigiComments));
                errorText = null;
            } else {
                errorText = LocaleController.getString(R.string.TigiFeedError);
            }
            adapter.notifyDataSetChanged();
            if (!comments.isEmpty()) {
                listView.scrollToPosition(comments.size() - 1);
            }
        }));
    }

    private void showCommentsOff() {
        loading = false;
        commentsOff = true;
        adapter.notifyDataSetChanged();
    }

    /* actions */

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() || discussionTop == null) {
            return;
        }
        final MessageObject target = replyTo != null ? replyTo : discussionTop;
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, -discussionChatId, target, discussionTop, null, false, null, null, null, true, 0, 0, null, false));
        input.setText("");
        setReplyTo(null);
        // the server's copy arrives through updates; reread the thread shortly after
        AndroidUtilities.runOnUIThread(() -> {
            if (!destroyed) {
                loadComments();
            }
        }, 1200);
    }

    /** The heart when the discussion group allows it, else the first reaction it allows; null when reactions are off. */
    private ReactionsLayoutInBubble.VisibleReaction likeReaction() {
        final String heart = TigiHeart.find(fragment.getMediaDataController().getReactionsList());
        final String wanted = heart != null ? heart : TigiHeart.HEART;
        final TLRPC.ChatFull full = fragment.getMessagesController().getChatFull(discussionChatId);
        if (full == null || full.available_reactions == null || full.available_reactions instanceof TLRPC.TL_chatReactionsAll) {
            return ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(wanted);
        }
        if (full.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            final ArrayList<TLRPC.Reaction> allowed = ((TLRPC.TL_chatReactionsSome) full.available_reactions).reactions;
            for (int i = 0; i < allowed.size(); i++) {
                if (TigiHeart.isHeart(allowed.get(i))) {
                    return ReactionsLayoutInBubble.VisibleReaction.fromTL(allowed.get(i));
                }
            }
            return allowed.isEmpty() ? null : ReactionsLayoutInBubble.VisibleReaction.fromTL(allowed.get(0));
        }
        return null;
    }

    private String reactionError(TLRPC.TL_error error) {
        final String text = error != null ? error.text : null;
        if (text != null && text.startsWith("REACTION")) {
            return LocaleController.getString(R.string.NilakCommentLikesOff);
        }
        if (text != null && (text.contains("PARTICIPANT") || text.contains("WRITE_FORBIDDEN") || text.contains("PRIVATE") || text.contains("GUEST"))) {
            return LocaleController.getString(R.string.NilakCommentJoinToReact);
        }
        return LocaleController.getString(R.string.TigiReactionFailed) + (text != null ? " (" + text + ")" : "");
    }

    private void toggleLike(MessageObject comment) {
        final ReactionsLayoutInBubble.VisibleReaction reaction = likeReaction();
        if (reaction == null) {
            BulletinFactory.of(container, resourcesProvider).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakCommentLikesOff)).show();
            return;
        }
        final boolean added = comment.selectReaction(reaction, false, false);
        final ArrayList<ReactionsLayoutInBubble.VisibleReaction> chosen = new ArrayList<>(comment.getChoosenReactions());
        SendMessagesHelper.getInstance(account).sendReaction(comment, chosen, added ? reaction : null, false, false, fragment, null, error -> {
            comment.selectReaction(reaction, false, false);
            adapter.notifyDataSetChanged();
            if (!destroyed) {
                BulletinFactory.of(container, resourcesProvider).createSimpleBulletin(R.raw.error, reactionError(error)).show();
            }
        });
        adapter.notifyDataSetChanged();
    }

    private void turnOnComments() {
        loading = true;
        commentsOff = false;
        adapter.notifyDataSetChanged();
        TigiPosting.createDiscussionGroup(account, channel, group -> {
            if (destroyed) {
                return;
            }
            // new posts get comments from now on; this one was published before the group existed
            loading = false;
            commentsOff = true;
            adapter.notifyDataSetChanged();
            BulletinFactory.of(container, resourcesProvider).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.NilakCommentsTurnedOn)).show();
        }, message -> {
            if (destroyed) {
                return;
            }
            loading = false;
            commentsOff = true;
            adapter.notifyDataSetChanged();
            BulletinFactory.of(container, resourcesProvider).createSimpleBulletin(R.raw.error, message).show();
        });
    }

    private String authorName(MessageObject comment) {
        final long from = comment.getFromChatId();
        final TLObject peer = fragment.getMessagesController().getUserOrChat(from);
        if (peer instanceof TLRPC.User) {
            return UserObject.getUserName((TLRPC.User) peer);
        } else if (peer instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) peer).title;
        }
        return "";
    }

    private static int heartCount(MessageObject message) {
        if (message.messageOwner.reactions == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < message.messageOwner.reactions.results.size(); i++) {
            final TLRPC.ReactionCount reaction = message.messageOwner.reactions.results.get(i);
            if (TigiHeart.isHeart(reaction.reaction)) {
                count += reaction.count;
            }
        }
        return count;
    }

    private static boolean liked(MessageObject message) {
        if (message.messageOwner.reactions == null) {
            return false;
        }
        for (int i = 0; i < message.messageOwner.reactions.results.size(); i++) {
            final TLRPC.ReactionCount reaction = message.messageOwner.reactions.results.get(i);
            if (TigiHeart.isHeart(reaction.reaction) && reaction.chosen) {
                return true;
            }
        }
        return false;
    }

    /* list */

    private class Adapter extends RecyclerListView.SelectionAdapter {
        static final int TYPE_COMMENT = 0;
        static final int TYPE_LOADING = 1;
        static final int TYPE_STATE = 2;

        @Override
        public int getItemCount() {
            return comments.isEmpty() ? 1 : comments.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (comments.isEmpty()) {
                return loading ? TYPE_LOADING : TYPE_STATE;
            }
            return TYPE_COMMENT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_COMMENT) {
                view = new CommentCell(parent.getContext());
            } else if (viewType == TYPE_LOADING) {
                final FlickerLoadingView flicker = new FlickerLoadingView(parent.getContext(), resourcesProvider);
                flicker.setViewType(FlickerLoadingView.DIALOG_TYPE);
                flicker.setIsSingleCell(false);
                view = flicker;
            } else {
                final LinearLayout box = new LinearLayout(parent.getContext());
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                box.setPadding(dp(32), dp(48), dp(32), dp(48));
                view = box;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, viewType == TYPE_LOADING ? dp(300) : ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_COMMENT) {
                ((CommentCell) holder.itemView).bind(comments.get(position));
            } else if (holder.getItemViewType() == TYPE_STATE) {
                final LinearLayout box = (LinearLayout) holder.itemView;
                box.removeAllViews();
                final TextView text = new TextView(box.getContext());
                text.setGravity(Gravity.CENTER);
                text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                text.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
                box.addView(text);
                if (errorText != null) {
                    text.setText(errorText);
                } else if (commentsOff) {
                    text.setText(LocaleController.getString(R.string.NilakCommentsOff));
                    final TLRPC.ChatFull full = fragment.getMessagesController().getChatFull(channel.id);
                    if (ChatObject.canChangeChatInfo(channel) && (full == null || full.linked_chat_id == 0)) {
                        final TextView turnOn = new TextView(box.getContext());
                        turnOn.setText(LocaleController.getString(R.string.NilakTurnOnComments));
                        turnOn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                        turnOn.setTypeface(TigiStyle.displayBold());
                        turnOn.setTextColor(0xFFFFFFFF);
                        turnOn.setGravity(Gravity.CENTER);
                        turnOn.setPadding(dp(20), 0, dp(20), 0);
                        turnOn.setBackground(TigiStyle.primaryPill(10));
                        turnOn.setOnClickListener(v -> turnOnComments());
                        box.addView(turnOn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER, 0, 16, 0, 0));
                    }
                } else {
                    text.setText(LocaleController.getString(R.string.NilakNoCommentsYet));
                }
            }
        }
    }

    private class CommentCell extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView header;
        private final TextView text;
        private final TextView reply;
        private final ImageView heart;
        private final TextView heartCount;
        private MessageObject comment;

        CommentCell(Context context) {
            super(context);
            setPadding(dp(14), dp(8), dp(8), dp(8));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(16));
            addView(avatar, LayoutHelper.createFrame(32, 32, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)));
            avatar.setOnClickListener(v -> {
                if (comment != null) {
                    dismiss();
                    TigiNavigation.openProfile(fragment, comment.getFromChatId());
                }
            });

            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            header = new TextView(context);
            header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            header.setSingleLine(true);
            header.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(header);
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            text.setLineSpacing(dp(1.5f), 1f);
            texts.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            reply = new TextView(context);
            reply.setText(LocaleController.getString(R.string.Reply));
            reply.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            reply.setTypeface(TigiStyle.displayBold());
            reply.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
            reply.setPadding(0, dp(6), dp(16), dp(4));
            reply.setOnClickListener(v -> setReplyTo(comment));
            texts.addView(reply, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, LocaleController.isRTL ? 44 : 44, 0, 44, 0));

            final LinearLayout like = new LinearLayout(context);
            like.setOrientation(LinearLayout.VERTICAL);
            like.setGravity(Gravity.CENTER_HORIZONTAL);
            heart = new ImageView(context);
            heart.setScaleType(ImageView.ScaleType.CENTER);
            like.addView(heart, LayoutHelper.createLinear(36, 30));
            heartCount = new TextView(context);
            heartCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            heartCount.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
            like.addView(heartCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            like.setOnClickListener(v -> {
                if (comment != null) {
                    toggleLike(comment);
                }
            });
            addView(like, LayoutHelper.createFrame(44, 48, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
        }

        void bind(MessageObject comment) {
            this.comment = comment;
            final TLObject from = fragment.getMessagesController().getUserOrChat(comment.getFromChatId());
            final AvatarDrawable drawable = new AvatarDrawable();
            if (from instanceof TLRPC.User) {
                drawable.setInfo(account, (TLRPC.User) from);
                avatar.setForUserOrChat(from, drawable);
            } else if (from instanceof TLRPC.Chat) {
                drawable.setInfo(account, (TLRPC.Chat) from);
                avatar.setForUserOrChat(from, drawable);
            } else {
                avatar.setImageDrawable(drawable);
            }
            final String name = authorName(comment);
            final boolean isAuthor = from instanceof TLRPC.Chat && ((TLRPC.Chat) from).id == channel.id;
            final android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
            line.append(name);
            line.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, name.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new android.text.style.ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider)), 0, name.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (isAuthor) {
                final int start = line.length();
                line.append("  ").append(LocaleController.getString(R.string.NilakAuthor));
                line.setSpan(new android.text.style.ForegroundColorSpan(TigiStyle.accent()), start, line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            line.append("  ").append(LocaleController.stringForMessageListDate(comment.messageOwner.date));
            header.setText(line);
            header.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
            text.setText(comment.messageText);
            final boolean isLiked = liked(comment);
            heart.setImageResource(isLiked ? R.drawable.tigi_heart_filled_24 : R.drawable.tigi_heart_outline_24);
            if (isLiked) {
                heart.clearColorFilter();
            } else {
                heart.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider), PorterDuff.Mode.SRC_IN));
            }
            final int hearts = heartCount(comment);
            heartCount.setText(hearts > 0 ? LocaleController.formatShortNumber(hearts, null) : "");
            setContentDescription(name + ", " + comment.messageText);
        }
    }
}
