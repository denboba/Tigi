package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.util.ArrayList;

/**
 * Tigi: the Instagram-style row of story avatars at the top of Home. Unread stories get the brand gradient ring,
 * seen ones a thin grey ring; a tap opens Telegram's story viewer and swipes on through the row.
 */
public class TigiStoriesRow extends HorizontalScrollView {

    private final BaseFragment fragment;
    private final LinearLayout items;
    private final ArrayList<Long> peerIds = new ArrayList<>();

    public TigiStoriesRow(Context context, BaseFragment fragment) {
        super(context);
        this.fragment = fragment;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        items = new LinearLayout(context);
        items.setOrientation(LinearLayout.HORIZONTAL);
        items.setPadding(dp(8), dp(4), dp(8), dp(8));
        addView(items, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    /** Rebuilds the row from the stories list; hides it when nobody has active stories. */
    public void update() {
        final int account = fragment.getCurrentAccount();
        final StoriesController controller = MessagesController.getInstance(account).getStoriesController();
        final ArrayList<TL_stories.PeerStories> list = controller.getDialogListStories();
        peerIds.clear();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                final TL_stories.PeerStories stories = list.get(i);
                if (stories == null || stories.peer == null || stories.stories.isEmpty()) {
                    continue;
                }
                final long did = DialogObject.getPeerDialogId(stories.peer);
                if (did != 0 && did != selfId() && !peerIds.contains(did)) {
                    peerIds.add(did);
                }
            }
        }
        // Who is live first, then unread, keeping Telegram's order within each group
        final ArrayList<Long> live = new ArrayList<>();
        final ArrayList<Long> unread = new ArrayList<>();
        final ArrayList<Long> seen = new ArrayList<>();
        for (int i = 0; i < peerIds.size(); i++) {
            final long did = peerIds.get(i);
            (TigiLive.hasLiveStory(account, did) ? live : controller.hasUnreadStories(did) ? unread : seen).add(did);
        }
        peerIds.clear();
        peerIds.addAll(live);
        peerIds.addAll(unread);
        peerIds.addAll(seen);

        items.removeAllViews();
        // "Your story" first, as on Instagram: watch your own stories, or add one when posting stories is allowed
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        final boolean hasOwn = controller.hasSelfStories();
        if (self != null && (hasOwn || TigiPosting.storiesOffered(account))) {
            items.addView(createSelfItem(self, hasOwn, hasOwn && controller.hasUnreadStories(self.id)), LayoutHelper.createLinear(76, LayoutHelper.WRAP_CONTENT));
        }
        for (int i = 0; i < peerIds.size(); i++) {
            final long did = peerIds.get(i);
            final TLObject peer = MessagesController.getInstance(account).getUserOrChat(did);
            if (peer == null) {
                continue;
            }
            items.addView(createItem(peer, did, i < live.size() + unread.size()), LayoutHelper.createLinear(76, LayoutHelper.WRAP_CONTENT));
        }
        setVisibility(items.getChildCount() > 0 ? VISIBLE : GONE);
    }

    private long selfId() {
        return UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId();
    }

    private View createSelfItem(TLRPC.User self, boolean hasOwn, boolean unread) {
        final LinearLayout item = (LinearLayout) createItem(self, self.id, unread);
        final TextView nameView = (TextView) item.getChildAt(1);
        nameView.setText(LocaleController.getString(R.string.TigiYourStory));
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        final FrameLayout avatarFrame = (FrameLayout) item.getChildAt(0);
        if (!hasOwn) {
            // no ring, a "+" badge instead
            avatarFrame.getChildAt(0).setVisibility(INVISIBLE);
            final TextView plus = new TextView(getContext());
            plus.setText("+");
            plus.setTextColor(0xFFFFFFFF);
            plus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            plus.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
            plus.setGravity(Gravity.CENTER);
            plus.setIncludeFontPadding(false);
            final android.graphics.drawable.GradientDrawable badge = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BL_TR, new int[]{TigiStyle.RING_START, TigiStyle.RING_END});
            badge.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            badge.setStroke(dp(2), TigiLilac.on() ? TigiLilac.pageTop() : Theme.getColor(Theme.key_windowBackgroundWhite));
            plus.setBackground(badge);
            avatarFrame.addView(plus, LayoutHelper.createFrame(22, 22, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 4, 4));
        }
        item.setContentDescription(LocaleController.getString(hasOwn ? R.string.TigiYourStory : R.string.TigiAddStory));
        item.setOnClickListener(v -> {
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (MessagesController.getInstance(fragment.getCurrentAccount()).getStoriesController().hasSelfStories()) {
                fragment.getOrCreateStoryViewer().open(fragment.getParentActivity(), self.id, (dialogId, messageId, storyId, type, holder) -> false);
            } else {
                TigiPosting.openStoryRecorder(fragment);
            }
        });
        return item;
    }

    private View createItem(TLObject peer, long did, boolean unread) {
        final Context context = getContext();
        final LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));

        final boolean live = TigiLive.hasLiveStory(fragment.getCurrentAccount(), did);
        final FrameLayout avatarFrame = new FrameLayout(context);
        avatarFrame.addView(new RingView(context, unread, live), LayoutHelper.createFrame(66, 66, Gravity.CENTER));
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(28));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(fragment.getCurrentAccount(), peer);
        avatar.setForUserOrChat(peer, avatarDrawable);
        avatarFrame.addView(avatar, LayoutHelper.createFrame(56, 56, Gravity.CENTER));
        if (live) {
            avatarFrame.addView(liveTag(context), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 16, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }
        item.addView(avatarFrame, LayoutHelper.createLinear(66, 66, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

        final String name = DialogObject.getDialogTitle(peer);
        final TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        nameView.setTextColor(Theme.getColor(unread ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setGravity(Gravity.CENTER);
        nameView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 0));

        item.setContentDescription(live ? name + ", " + LocaleController.getString(R.string.TigiLiveBadge)
            : LocaleController.formatString(unread ? R.string.TigiStoryUnread : R.string.TigiStorySeen, name));
        item.setOnClickListener(v -> open(did));
        return item;
    }

    private void open(long did) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final int position = peerIds.indexOf(did);
        if (position < 0) {
            return;
        }
        fragment.getOrCreateStoryViewer().open(fragment.getParentActivity(), null, new ArrayList<>(peerIds), position, null, null, (dialogId, messageId, storyId, type, holder) -> false, false);
    }

    /** The LIVE tag on a live person's photo, in Telegram's live colour */
    static TextView liveTag(Context context) {
        final TextView tag = new TextView(context);
        tag.setText(LocaleController.getString(R.string.TigiLiveBadge));
        tag.setTextColor(0xFFFFFFFF);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
        tag.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        tag.setGravity(Gravity.CENTER);
        tag.setIncludeFontPadding(false);
        tag.setPadding(dp(5), 0, dp(5), 0);
        final android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(TigiLive.color());
        background.setCornerRadius(dp(5));
        background.setStroke(dp(1.5f), TigiLilac.on() ? TigiLilac.pageTop() : Theme.getColor(Theme.key_windowBackgroundWhite));
        tag.setBackground(background);
        tag.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return tag;
    }

    private static class RingView extends View {

        private final boolean unread;
        private final boolean live;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RingView(Context context, boolean unread, boolean live) {
            super(context);
            this.unread = unread;
            this.live = live;
            paint.setStyle(Paint.Style.STROKE);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (unread && !live) {
                paint.setShader(new LinearGradient(0, h, w, 0, new int[]{TigiStyle.RING_START, TigiStyle.RING_END}, null, Shader.TileMode.CLAMP));
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float stroke = unread || live ? dp(2.5f) : dp(1.2f);
            paint.setStrokeWidth(stroke);
            if (live) {
                paint.setColor(TigiLive.color());
            } else if (!unread) {
                paint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), 0.5f));
            }
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - stroke / 2f, paint);
        }
    }
}
