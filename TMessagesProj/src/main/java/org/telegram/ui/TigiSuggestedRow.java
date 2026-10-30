package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Tigi: "Suggested for you" at the top of Home when the user follows no channels. A short explanation and cards of
 * recommended channels with a Follow button; a tap on a card opens the channel's profile.
 */
public class TigiSuggestedRow extends LinearLayout {

    private final BaseFragment fragment;
    private final TextView titleView;
    private final TextView textView;
    private final LinearLayout cards;
    private final Runnable onFollowed;

    public TigiSuggestedRow(Context context, BaseFragment fragment, Runnable onFollowed) {
        super(context);
        this.fragment = fragment;
        this.onFollowed = onFollowed;
        setOrientation(VERTICAL);
        setPadding(0, dp(4), 0, dp(10));

        titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.TigiSuggestedTitle));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        androidx.core.view.ViewCompat.setAccessibilityHeading(titleView, true);
        addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        textView = new TextView(context);
        textView.setText(LocaleController.getString(R.string.TigiSuggestedText));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 8));

        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        cards = new LinearLayout(context);
        cards.setOrientation(HORIZONTAL);
        cards.setPadding(dp(10), 0, dp(10), 0);
        scroll.addView(cards, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateColors();
        setVisibility(GONE);
    }

    public void setChannels(ArrayList<TLRPC.Chat> channels) {
        cards.removeAllViews();
        if (channels != null) {
            for (int i = 0; i < channels.size(); i++) {
                cards.addView(createCard(channels.get(i)), LayoutHelper.createLinear(132, LayoutHelper.WRAP_CONTENT, 6, 0, 6, 0));
            }
        }
        setVisibility(cards.getChildCount() > 0 ? VISIBLE : GONE);
    }

    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    private View createCard(TLRPC.Chat chat) {
        final Context context = getContext();
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(12), dp(8), dp(10));
        card.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_windowBackgroundGray)));

        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(30));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(fragment.getCurrentAccount(), chat);
        avatar.setForUserOrChat(chat, avatarDrawable);
        card.addView(avatar, LayoutHelper.createLinear(60, 60, Gravity.CENTER_HORIZONTAL));

        final TextView name = new TextView(context);
        name.setText(chat.title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        card.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        final TextView count = new TextView(context);
        count.setText(chat.participants_count > 0 ? LocaleController.formatShortNumber(chat.participants_count, null) + " " + LocaleController.getString(R.string.TigiProfileFollowers) : "");
        count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        count.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        count.setSingleLine(true);
        count.setGravity(Gravity.CENTER);
        card.addView(count, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        final TextView follow = new TextView(context);
        follow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        follow.setTypeface(AndroidUtilities.bold());
        follow.setGravity(Gravity.CENTER);
        bindFollow(follow, !ChatObject.isNotInChat(chat));
        follow.setOnClickListener(v -> {
            final TLRPC.Chat current = MessagesController.getInstance(fragment.getCurrentAccount()).getChat(chat.id);
            if (current == null || !ChatObject.isNotInChat(current)) {
                return;
            }
            bindFollow(follow, true);
            MessagesController.getInstance(fragment.getCurrentAccount()).addUserToChat(chat.id, UserConfig.getInstance(fragment.getCurrentAccount()).getCurrentUser(), 0, null, fragment, true, () -> {
                if (onFollowed != null) {
                    onFollowed.run();
                }
            }, error -> {
                bindFollow(follow, false);
                return true;
            });
        });
        card.addView(follow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 10, 0, 0));

        card.setOnClickListener(v -> TigiNavigation.openProfile(fragment, -chat.id));
        card.setContentDescription(chat.title);
        return card;
    }

    private void bindFollow(TextView follow, boolean following) {
        if (following) {
            follow.setText(LocaleController.getString(R.string.TigiProfileFollowing));
            follow.setTextColor(TigiStyle.accent());
            follow.setBackground(TigiStyle.tintedPill(10));
        } else {
            follow.setText(LocaleController.getString(R.string.TigiProfileFollow));
            follow.setTextColor(0xFFFFFFFF);
            follow.setBackground(TigiStyle.primaryPill(10));
        }
    }
}
