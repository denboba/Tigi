package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Nilak's Edit profile: who you are (photo, name, username, bio: Telegram's own editor) and how people see your posts
 * (visibility, comments, reactions, insights, followers, sharing the Following count). The personal channel behind
 * the posts is never named; its name, photo and description follow the profile (TigiIdentity).
 */
public class TigiEditProfileActivity extends BaseFragment {

    private LinearLayout column;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> finishFragment());
        if (LocaleController.isRTL) {
            back.setScaleX(-1f);
        }
        bar.addView(back, LayoutHelper.createLinear(48, 56));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.TigiProfileEdit));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        bar.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 8, 0));
        page.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final ScrollView scroll = new ScrollView(context);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(24));
        scroll.addView(column);
        page.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(page, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        build();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (column != null) {
            build();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        // whatever changed here (name, photo, bio) shows on the posts' side too
        TigiIdentity.sync(currentAccount, true);
    }

    private void build() {
        column.removeAllViews();
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final TLRPC.User me = getUserConfig().getCurrentUser();
        final TLRPC.UserFull meFull = me != null ? getMessagesController().getUserFull(me.id) : null;
        final TLRPC.Chat posts = TigiPosting.ownChannel(currentAccount);

        // photo and who you are: Telegram's own profile editor (photo, name, username, bio, birthday)
        final LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_HORIZONTAL);
        identity.setPadding(0, dp(8), 0, dp(12));
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(40));
        if (me != null) {
            final AvatarDrawable drawable = new AvatarDrawable();
            drawable.setInfo(currentAccount, me);
            avatar.setForUserOrChat(me, drawable);
        }
        identity.addView(avatar, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL));
        final TextView change = new TextView(context);
        change.setText(LocaleController.getString(R.string.NilakEditIdentity));
        change.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        change.setTypeface(AndroidUtilities.bold());
        change.setTextColor(TigiStyle.accent());
        change.setPadding(dp(12), dp(8), dp(12), dp(8));
        identity.addView(change, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        identity.setOnClickListener(v -> presentFragment(new UserInfoActivity()));
        column.addView(identity);

        field(context, R.string.NilakFieldName, me != null ? UserObject.getUserName(me) : "");
        final String username = me != null ? UserObject.getPublicUsername(me) : null;
        field(context, R.string.NilakFieldUsername, TextUtils.isEmpty(username) ? "—" : "@" + username);
        field(context, R.string.NilakFieldBio, meFull != null && !TextUtils.isEmpty(meFull.about) ? meFull.about : "—");

        section(context, R.string.NilakYourPosts);
        if (posts == null) {
            // nothing posted yet: the settings appear with the first post
            final TextView none = new TextView(context);
            none.setText(LocaleController.getString(R.string.NilakYourPostsLater));
            none.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            none.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            none.setPadding(dp(18), dp(6), dp(18), dp(10));
            column.addView(none);
        } else {
            final boolean isPublic = !TextUtils.isEmpty(org.telegram.messenger.ChatObject.getPublicUsername(posts));
            row(context, R.string.NilakWhoCanSee, LocaleController.getString(isPublic ? R.string.NilakEveryone : R.string.NilakInviteOnly),
                () -> presentFragment(new ChatEditTypeActivity(posts.id, false)));
            final TLRPC.ChatFull full = getMessagesController().getChatFull(posts.id);
            row(context, R.string.NilakComments, LocaleController.getString(full != null && full.linked_chat_id != 0 ? R.string.NilakOn : R.string.NilakOff),
                () -> presentFragment(new ChatLinkActivity(posts.id)));
            row(context, R.string.NilakReactions, null, () -> {
                final Bundle args = new Bundle();
                args.putLong("chat_id", posts.id);
                presentFragment(new ChatReactionsEditActivity(args));
            });
            row(context, R.string.NilakInsights, null, () -> presentFragment(StatisticActivity.create(posts)));
            row(context, R.string.TigiProfileFollowers, full != null && full.participants_count > 0 ? LocaleController.formatShortNumber(Math.max(0, full.participants_count - 1), null) : null, () -> {
                final Bundle args = new Bundle();
                args.putLong("chat_id", posts.id);
                args.putInt("type", ChatUsersActivity.TYPE_USERS);
                presentFragment(new ChatUsersActivity(args));
            });
            toggle(context, R.string.NilakShareFollowing, R.string.NilakShareFollowingInfo, TigiIdentity.sharesFollowing(currentAccount),
                value -> TigiIdentity.setSharesFollowing(currentAccount, value));
        }

        section(context, R.string.NilakAccount);
        row(context, R.string.NilakPrivacy, null, () -> presentFragment(new PrivacySettingsActivity()));
    }

    private void field(Context context, int label, String value) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        box.setBackground(Theme.getSelectorDrawable(false));
        final TextView l = new TextView(context);
        l.setText(LocaleController.getString(label));
        l.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        l.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        final TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        box.addView(l);
        box.addView(v);
        box.setOnClickListener(x -> presentFragment(new UserInfoActivity()));
        column.addView(box);
    }

    private void section(Context context, int text) {
        final TextView view = new TextView(context);
        view.setText(LocaleController.getString(text).toUpperCase());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(TigiStyle.accent());
        view.setPadding(dp(18), dp(18), dp(18), dp(4));
        column.addView(view);
    }

    private void row(Context context, int text, String value, Runnable onClick) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setBackground(Theme.getSelectorDrawable(false));
        final TextView label = new TextView(context);
        label.setText(LocaleController.getString(text));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView right = new TextView(context);
        right.setText((value != null ? value + "  " : "") + (LocaleController.isRTL ? "‹" : "›"));
        right.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        right.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(right);
        row.setOnClickListener(v -> onClick.run());
        column.addView(row);
    }

    private void toggle(Context context, int text, int info, boolean checked, org.telegram.messenger.Utilities.Callback<Boolean> onChange) {
        final org.telegram.ui.Cells.TextCheckCell cell = new org.telegram.ui.Cells.TextCheckCell(context);
        cell.setTextAndValueAndCheck(LocaleController.getString(text), LocaleController.getString(info), checked, true, false);
        cell.setOnClickListener(v -> {
            final boolean value = !cell.isChecked();
            cell.setChecked(value);
            onChange.run(value);
        });
        column.addView(cell);
    }
}
