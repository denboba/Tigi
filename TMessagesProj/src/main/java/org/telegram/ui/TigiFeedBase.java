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
import org.telegram.ui.Components.ReactionsContainerLayout;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Shared base of the Tigi Instagram-style feeds (Home and a single channel):
 * post cards, photo carousel, hearts, the full Telegram reaction picker and long captions.
 */
public abstract class TigiFeedBase extends BaseFragment {

    protected static final String HEART_PREFIX = "❤";
    private static final int CAPTION_LINES = 3;

    protected static class FeedPost {
        TLRPC.Chat chat;
        final ArrayList<MessageObject> messages = new ArrayList<>();
        final ArrayList<MessageObject> media = new ArrayList<>();
        CharSequence text;
        int date;

        MessageObject primary() {
            return messages.get(0);
        }

        boolean isValid() {
            return !messages.isEmpty() && (!media.isEmpty() || !TextUtils.isEmpty(text));
        }
    }

    protected final ArrayList<FeedPost> posts = new ArrayList<>();
    protected RecyclerListView listView;
    protected LinearLayoutManager layoutManager;
    protected FeedAdapter adapter;
    protected TextView emptyView;
    protected boolean hasMainTabs;

    private FrameLayout overlay;
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

    protected void onHeaderClicked(FeedPost post) {
        openChannelFeed(post.chat);
    }

    /* view */

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

        final FrameLayout root = new FrameLayout(context);
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
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() >= posts.size() - 3) {
                    onNearEnd();
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
        final ArrayList<FeedPost> out = new ArrayList<>();
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
        final List<TLRPC.TL_availableReaction> list = getMediaDataController().getReactionsList();
        for (int i = 0; i < list.size(); i++) {
            final String reaction = list.get(i).reaction;
            if (reaction != null && reaction.startsWith(HEART_PREFIX)) {
                return reaction;
            }
        }
        return HEART_PREFIX + "️";
    }

    protected static boolean isHeart(TLRPC.Reaction reaction) {
        if (!(reaction instanceof TLRPC.TL_reactionEmoji)) {
            return false;
        }
        final String emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        return emoticon != null && emoticon.startsWith(HEART_PREFIX);
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

    protected void applyReaction(FeedPost post, ReactionsLayoutInBubble.VisibleReaction reaction, boolean big, boolean addToRecent) {
        final MessageObject message = post.primary();
        final boolean added = message.selectReaction(reaction, big, false);
        final ArrayList<ReactionsLayoutInBubble.VisibleReaction> chosen = new ArrayList<>(message.getChoosenReactions());
        getSendMessagesHelper().sendReaction(message, chosen, added ? reaction : null, big, addToRecent, this, () -> {});
        refreshPost(post);
    }

    protected void toggleHeart(FeedPost post) {
        applyReaction(post, ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(heartEmoji()), false, false);
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

    protected void showReactionPicker(FeedPost post, View anchor) {
        if (overlay == null || fragmentView == null || post == null) {
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
        pickerLayout.setPadding(dp(4), dp(4), dp(4), dp(22));
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

        final int[] anchorLocation = new int[2];
        final int[] overlayLocation = new int[2];
        anchor.getLocationInWindow(anchorLocation);
        overlay.getLocationInWindow(overlayLocation);
        final int minTop = AndroidUtilities.statusBarHeight + dp(8);
        final int top = Math.max(minTop, anchorLocation[1] - overlayLocation[1] - dp(70));
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(70 + 22));
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
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        presentFragment(new ChannelFeedActivity(args));
    }

    protected void openPostInChat(FeedPost post, MessageObject message) {
        final Bundle args = new Bundle();
        args.putLong("chat_id", post.chat.id);
        args.putInt("message_id", message.getId());
        presentFragment(new ChatActivity(args));
    }

    protected void sharePost(FeedPost post) {
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
        private final LinearLayout chipsRow;
        private final TextView captionView;
        private final TextView moreView;
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
                popHeart();
            });
            heartButton.setOnLongClickListener(v -> {
                if (post != null) {
                    showReactionPicker(post, heartButton);
                }
                return true;
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
                    openPostInChat(post, post.primary());
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

            final LinearLayout captionBox = new LinearLayout(context);
            captionBox.setOrientation(VERTICAL);
            captionBox.setPadding(dp(12), dp(2), dp(12), dp(14));
            captionView = new TextView(context);
            captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            captionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            captionView.setLineSpacing(dp(2), 1f);
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

            updateCaption();
            bindActions();
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
            counterView.setText((currentPage + 1) + "/" + count);
            dotsView.set(count, currentPage);
        }

        void bindActions() {
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
                    chip.setBackground(Theme.createRoundRectDrawable(dp(15), count.chosen ? 0xFFEDE9FE : Theme.getColor(Theme.key_windowBackgroundGray)));
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
                toggleHeart(post);
                popHeart();
            }
            showBigHeart();
        }

        private void onPhotoTap() {
            if (post != null) {
                showReactionPicker(post, mediaFrame);
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
                ((PhotoPage) holder.itemView).bind(items.get(position));
            }
        }

        private class PhotoPage extends FrameLayout {
            private final BackupImageView imageView;
            private final ImageView playView;

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
                        onPhotoTap();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        onPhotoTap();
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        onPhotoDoubleTap();
                        return true;
                    }
                });
                setOnTouchListener((v, event) -> detector.onTouchEvent(event));
            }

            void bind(MessageObject message) {
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
            final float total = (count - 1) * (dot + gap) + wide;
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
