package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * "Communities" on the person's own profile: the Telegram Communities they belong to, and New community.
 * Telegram tells only the person which communities they joined, so this row exists on the own profile only.
 */
public class NilakProfileCommunitiesRow extends LinearLayout {

    private static final int TILE_WIDTH = 70;
    private static final int TILE_ICON_SIZE = 50;

    private final BaseFragment fragment;
    private final TextView titleView;
    private final LinearLayout tiles;
    private boolean requested;

    public NilakProfileCommunitiesRow(Context context, BaseFragment fragment) {
        super(context);
        this.fragment = fragment;
        setOrientation(VERTICAL);
        setVisibility(GONE);

        titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.NilakCommunities));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(TigiStyle.displayBold());
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        androidx.core.view.ViewCompat.setAccessibilityHeading(titleView, true);
        // The title, with "See all" opening the Communities tab
        final LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView seeAllView = new TextView(context);
        seeAllView.setText(LocaleController.getString(R.string.NilakSeeAll));
        seeAllView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        seeAllView.setTypeface(TigiStyle.displayBold());
        seeAllView.setTextColor(TigiStyle.accent());
        seeAllView.setPadding(dp(8), dp(6), dp(8), dp(6));
        seeAllView.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.10f), 8, 8));
        seeAllView.setOnClickListener(v -> {
            final MainTabsActivity tabs = TigiNavigation.findMainTabs(fragment);
            if (tabs != null) {
                tabs.openCommunitiesTab();
            }
        });
        titleRow.addView(seeAllView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 8, 4));

        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(12), 0, dp(12), 0);
        tiles = new LinearLayout(context);
        tiles.setOrientation(HORIZONTAL);
        scroll.addView(tiles);
        addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    /** Shows the communities Telegram already knows about and asks for the current list once. */
    public void update() {
        bind(MessagesController.getInstance(fragment.getCurrentAccount()).getJoinedCommunities());
        if (requested) {
            return;
        }
        requested = true;
        MessagesController.getInstance(fragment.getCurrentAccount()).fetchJoinedCommunities(result -> {
            if (result != null && fragment.getParentActivity() != null) {
                bind(result);
            }
        }, fragment.getClassGuid());
    }

    private void bind(ArrayList<TLRPC.Chat> communities) {
        tiles.removeAllViews();
        titleView.setTextColor(TigiLilac.on() ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        // nothing to show yet: New community alone would look like an ad on the profile
        setVisibility(communities == null || communities.isEmpty() ? GONE : VISIBLE);
        if (communities == null || communities.isEmpty()) {
            return;
        }
        for (TLRPC.Chat community : communities) {
            final BackupImageView avatar = new BackupImageView(getContext());
            avatar.setRoundRadius(dp(15));
            final AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(fragment.getCurrentAccount(), community);
            avatar.setForUserOrChat(community, avatarDrawable);
            addTile(avatar, community.title, () -> NilakCommunitySheets.openCommunity(fragment, community.id));
        }
        final ImageView plus = new ImageView(getContext());
        plus.setImageResource(R.drawable.tigi_add_24);
        plus.setScaleType(ImageView.ScaleType.CENTER);
        plus.setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
        plus.setBackground(Theme.createRoundRectDrawable(dp(15), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
        addTile(plus, LocaleController.getString(R.string.Create), () -> NilakCommunitySheets.showNewCommunity(fragment));
        tiles.getChildAt(tiles.getChildCount() - 1).setContentDescription(LocaleController.getString(R.string.NilakCommunityNew));
    }

    private void addTile(View icon, String name, Runnable action) {
        final LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        tile.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.10f), dp(14), dp(14)));
        tile.setOnClickListener(v -> action.run());
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(icon, LayoutHelper.createLinear(TILE_ICON_SIZE, TILE_ICON_SIZE));
        final TextView label = new TextView(getContext());
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        label.setTextColor(TigiLilac.on() ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        tile.setContentDescription(name);
        tiles.addView(tile, LayoutHelper.createLinear(TILE_WIDTH, LayoutHelper.WRAP_CONTENT));
    }
}
