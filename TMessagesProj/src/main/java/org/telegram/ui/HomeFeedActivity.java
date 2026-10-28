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

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Tigi Home: an Instagram-style feed of recent posts from the channels the user is in.
 */
public class HomeFeedActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int MAX_CHANNELS = 25;
    private static final int POSTS_PER_CHANNEL = 12;
    private static final int MAX_POSTS = 60;
    private static final long RELOAD_AFTER_MS = 2 * 60 * 1000L;
    private static final String HEART_PREFIX = "❤";

    private static class FeedPost {
        TLRPC.Chat chat;
        final ArrayList<MessageObject> messages = new ArrayList<>();
        final ArrayList<MessageObject> media = new ArrayList<>();
        CharSequence text;
        int date;

        MessageObject primary() {
            return messages.get(0);
        }
    }

    private final ArrayList<FeedPost> posts = new ArrayList<>();
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private FeedAdapter adapter;
    private TextView emptyView;
    private boolean loading;
    private long lastLoadTime;
    private boolean hasMainTabs;

    public HomeFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        FrameLayout header = new FrameLayout(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        TextView title = new TextView(context);
        title.setText("Tigi");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.TOP | Gravity.LEFT, 16, 0, 16, 0));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.statusBarHeight + dp(56)));

        FrameLayout body = new FrameLayout(context);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(5), 0, hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 32) : dp(16));
        adapter = new FeedAdapter();
        listView.setAdapter(adapter);
        body.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), 0, dp(32), 0);
        body.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        updateEmptyView();

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (posts.isEmpty() || System.currentTimeMillis() - lastLoadTime > RELOAD_AFTER_MS) {
            loadFeed();
        }
    }

    @Override
    public void onParentScrollToTop() {
        if (listView == null) {
            return;
        }
        if (layoutManager.findFirstVisibleItemPosition() <= 0) {
            loadFeed();
        } else {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        if (listView == null) {
            return true;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof FeedCell && ((FeedCell) child).isOverMultiPhoto(ev)) {
                return false;
            }
        }
        return true;
    }

    /* loading */

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        if (!posts.isEmpty()) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText(LocaleController.getString(loading ? R.string.TigiFeedLoading : R.string.TigiFeedEmpty));
    }

    private void loadFeed() {
        if (loading || listView == null) {
            return;
        }
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
        for (int i = 0; i < dialogs.size() && channels.size() < MAX_CHANNELS; i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog == null || !DialogObject.isChatDialog(dialog.id)) {
                continue;
            }
            final TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
            if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !chat.left) {
                channels.add(chat);
            }
        }
        if (channels.isEmpty()) {
            updateEmptyView();
            return;
        }

        loading = true;
        updateEmptyView();
        final ArrayList<FeedPost> result = new ArrayList<>();
        final int[] pending = {channels.size()};
        for (int c = 0; c < channels.size(); c++) {
            final TLRPC.Chat chat = channels.get(c);
            final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = getMessagesController().getInputPeer(-chat.id);
            req.limit = POSTS_PER_CHANNEL;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    addMessages(result, chat, res.messages);
                }
                pending[0]--;
                if (pending[0] == 0) {
                    finishLoad(result);
                }
            }));
        }
    }

    private void addMessages(ArrayList<FeedPost> out, TLRPC.Chat chat, ArrayList<TLRPC.Message> messages) {
        final HashMap<Long, FeedPost> albums = new HashMap<>();
        // history comes newest first, albums are shown oldest photo first
        for (int i = messages.size() - 1; i >= 0; i--) {
            final TLRPC.Message message = messages.get(i);
            if (message == null || message instanceof TLRPC.TL_messageEmpty || message instanceof TLRPC.TL_messageService) {
                continue;
            }
            final MessageObject object = new MessageObject(currentAccount, message, true, false);
            final long groupId = message.grouped_id;
            FeedPost post = groupId != 0 ? albums.get(groupId) : null;
            if (post == null) {
                post = new FeedPost();
                post.chat = chat;
                post.date = message.date;
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
                post.text = !TextUtils.isEmpty(object.caption) ? object.caption : (post.media.isEmpty() && !TextUtils.isEmpty(object.messageText) ? object.messageText : null);
            }
        }
    }

    private void finishLoad(ArrayList<FeedPost> result) {
        loading = false;
        lastLoadTime = System.currentTimeMillis();
        final ArrayList<FeedPost> filtered = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            final FeedPost post = result.get(i);
            if (!post.media.isEmpty() || !TextUtils.isEmpty(post.text)) {
                filtered.add(post);
            }
        }
        Collections.sort(filtered, (a, b) -> b.date - a.date);
        posts.clear();
        posts.addAll(filtered.size() > MAX_POSTS ? filtered.subList(0, MAX_POSTS) : filtered);
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    /* hearts */

    private String heartEmoji() {
        final List<TLRPC.TL_availableReaction> list = getMediaDataController().getReactionsList();
        for (int i = 0; i < list.size(); i++) {
            final String reaction = list.get(i).reaction;
            if (reaction != null && reaction.startsWith(HEART_PREFIX)) {
                return reaction;
            }
        }
        return HEART_PREFIX + "️";
    }

    private static boolean isHeart(TLRPC.Reaction reaction) {
        if (!(reaction instanceof TLRPC.TL_reactionEmoji)) {
            return false;
        }
        final String emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        return emoticon != null && emoticon.startsWith(HEART_PREFIX);
    }

    private static int heartCount(MessageObject message) {
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

    private static boolean heartChosen(MessageObject message) {
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

    private void toggleHeart(FeedPost post) {
        final MessageObject message = post.primary();
        final ReactionsLayoutInBubble.VisibleReaction heart = ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(heartEmoji());
        final boolean added = message.selectReaction(heart, false, false);
        final ArrayList<ReactionsLayoutInBubble.VisibleReaction> chosen = new ArrayList<>(message.getChoosenReactions());
        getSendMessagesHelper().sendReaction(message, chosen, added ? heart : null, false, false, this, () -> {});
    }

    private void openPost(FeedPost post, MessageObject message) {
        final Bundle args = new Bundle();
        args.putLong("chat_id", post.chat.id);
        args.putInt("message_id", message.getId());
        presentFragment(new ChatActivity(args));
    }

    private void sharePost(FeedPost post) {
        final String username = ChatObject.getPublicUsername(post.chat);
        if (TextUtils.isEmpty(username) || getParentActivity() == null) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, "https://t.me/" + username + "/" + post.primary().getId());
        getParentActivity().startActivity(Intent.createChooser(intent, post.chat.title));
    }

    /* adapter */

    private class FeedAdapter extends RecyclerListView.SelectionAdapter {
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

    private class FeedCell extends LinearLayout {
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
        private final LinearLayout chipsRow;
        private final TextView captionView;
        private FeedPost post;
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
                    openPost(post, post.primary());
                }
            });
            addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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
            heartButton.setOnClickListener(v -> {
                if (post == null) {
                    return;
                }
                toggleHeart(post);
                bindActions();
                popHeart();
            });
            actions.addView(heartButton, LayoutHelper.createLinear(44, 44));
            heartCountView = makeCountView(context);
            actions.addView(heartCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            final ImageView commentButton = new ImageView(context);
            commentButton.setScaleType(ImageView.ScaleType.CENTER);
            commentButton.setImageResource(R.drawable.tigi_comment_24);
            commentButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            commentButton.setOnClickListener(v -> {
                if (post != null) {
                    openPost(post, post.primary());
                }
            });
            actions.addView(commentButton, LayoutHelper.createLinear(44, 44));
            commentCountView = makeCountView(context);
            actions.addView(commentCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            final ImageView shareButton = new ImageView(context);
            shareButton.setScaleType(ImageView.ScaleType.CENTER);
            shareButton.setImageResource(R.drawable.tigi_share_24);
            shareButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            shareButton.setOnClickListener(v -> {
                if (post != null) {
                    sharePost(post);
                }
            });
            actions.addView(shareButton, LayoutHelper.createLinear(44, 44));
            addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

            chipsRow = new LinearLayout(context);
            chipsRow.setOrientation(HORIZONTAL);
            chipsRow.setPadding(dp(12), 0, dp(12), dp(4));
            addView(chipsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            captionView = new TextView(context);
            captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            captionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            captionView.setPadding(dp(12), dp(2), dp(12), dp(14));
            captionView.setLineSpacing(dp(2), 1f);
            captionView.setOnClickListener(v -> {
                captionExpanded = !captionExpanded;
                captionView.setMaxLines(captionExpanded ? Integer.MAX_VALUE : 5);
            });
            addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        private TextView makeCountView(Context context) {
            final TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            view.setTypeface(AndroidUtilities.bold());
            view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            return view;
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
            currentPage = 0;
            captionExpanded = false;

            final AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(currentAccount, post.chat);
            avatarView.setForUserOrChat(post.chat, avatarDrawable);
            nameView.setText(post.chat.title);
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

            final CharSequence text = post.text;
            if (TextUtils.isEmpty(text)) {
                captionView.setVisibility(GONE);
            } else {
                captionView.setVisibility(VISIBLE);
                captionView.setMaxLines(5);
                captionView.setEllipsize(TextUtils.TruncateAt.END);
                captionView.setText(text);
            }
            bindActions();
        }

        private void updatePageIndicators() {
            final int count = post == null ? 0 : post.media.size();
            counterView.setVisibility(count > 1 ? VISIBLE : GONE);
            counterView.setText((currentPage + 1) + "/" + count);
            dotsView.set(count, currentPage);
        }

        private void bindActions() {
            if (post == null) {
                return;
            }
            final MessageObject message = post.primary();
            final boolean liked = heartChosen(message);
            heartButton.setImageResource(liked ? R.drawable.tigi_heart_filled_24 : R.drawable.tigi_heart_outline_24);
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
                    chip.setBackground(Theme.createRoundRectDrawable(dp(15), Theme.getColor(Theme.key_windowBackgroundGray)));
                    chip.setText(((TLRPC.TL_reactionEmoji) count.reaction).emoticon + " " + count.count);
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
                toggleHeart(post);
                bindActions();
                popHeart();
            }
            showBigHeart();
        }

        private void onPhotoTap(int position) {
            if (post != null && position >= 0 && position < post.media.size()) {
                openPost(post, post.media.get(position));
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
            private int position;

            PhotoPage(Context context) {
                super(context);
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
                imageView = new BackupImageView(context);
                addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                playView = new ImageView(context);
                playView.setScaleType(ImageView.ScaleType.CENTER);
                playView.setImageResource(R.drawable.tigi_play_24);
                playView.setBackground(Theme.createRoundRectDrawable(dp(30), 0x99111827));
                playView.setVisibility(GONE);
                addView(playView, LayoutHelper.createFrame(60, 60, Gravity.CENTER));

                final GestureDetector detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        onPhotoTap(position);
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        onPhotoDoubleTap();
                        return true;
                    }
                });
                setOnTouchListener((v, event) -> detector.onTouchEvent(event));
            }

            void bind(MessageObject message, int position) {
                this.position = position;
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
                imageView.setImage(ImageLocation.getForObject(size, message.photoThumbsObject), "800_800", (Drawable) null, message.photoThumbsObject);
                playView.setVisibility(message.isVideo() ? VISIBLE : GONE);
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
            float total = (count - 1) * (dot + gap) + wide;
            float x = (getWidth() - total) / 2f;
            final float cy = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                final boolean active = i == selected;
                paint.setColor(active ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText) : 0xFFD1D5DB);
                final float w = active ? wide : dot;
                canvas.drawRoundRect(x, cy - dot / 2f, x + w, cy + dot / 2f, dot / 2f, dot / 2f, paint);
                x += w + gap;
            }
        }
    }
}
