package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Nilak Apps: Telegram mini apps, findable. "Popular" is Telegram's own list (bots.getPopularAppBots); each
 * category searches public bots by name (contacts.search). A bot with a mini app opens it in place; any other bot
 * opens its chat.
 */
public class TigiAppsActivity extends BaseFragment {

    private static final int[] CATEGORIES = {
        R.string.NilakAppsPopular, R.string.NilakInterestJobs, R.string.NilakAppsFood, R.string.NilakAppsRides,
        R.string.NilakAppsTickets, R.string.NilakAppsShops, R.string.NilakAppsRates, R.string.NilakInterestGaming,
        R.string.NilakInterestEducation
    };
    /** What each category searches for (English works best for bot names). */
    private static final String[] QUERIES = {null, "jobs", "food delivery", "taxi", "tickets", "shop", "exchange rate", "game", "exam"};

    private int category;
    private int generation;
    private LinearLayout chips;
    private LinearLayout list;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

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
        title.setText(LocaleController.getString(R.string.NilakApps));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        bar.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 8, 0));
        column.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(12), 0, dp(12), dp(8));
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            final TextView chip = new TextView(context);
            chip.setText(LocaleController.getString(CATEGORIES[i]));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setTypeface(TigiStyle.displayBold());
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setOnClickListener(v -> {
                category = index;
                bindChips();
                load();
            });
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
        }
        chipScroll.addView(chips);
        column.addView(chipScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(24) + AndroidUtilities.navigationBarHeight);
        scroll.addView(list);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        bindChips();
        load();
        return root;
    }

    private void bindChips() {
        for (int i = 0; i < chips.getChildCount(); i++) {
            final TextView chip = (TextView) chips.getChildAt(i);
            final boolean on = i == category;
            chip.setSelected(on);
            chip.setTextColor(on ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            chip.setBackground(on ? TigiStyle.primaryPill(17) : Theme.createRoundRectDrawable(dp(17), Theme.getColor(Theme.key_windowBackgroundGray)));
        }
    }

    private void showLoading() {
        list.removeAllViews();
        final RadialProgressView spinner = new RadialProgressView(getParentActivity());
        spinner.setProgressColor(TigiStyle.accent());
        list.addView(spinner, LayoutHelper.createLinear(44, 44, Gravity.CENTER, 0, 40, 0, 0));
    }

    private void load() {
        showLoading();
        final int gen = ++generation;
        if (QUERIES[category] == null) {
            final TL_bots.getPopularAppBots req = new TL_bots.getPopularAppBots();
            req.offset = "";
            req.limit = 50;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                final ArrayList<TLRPC.User> bots = new ArrayList<>();
                if (response instanceof TL_bots.popularAppBots) {
                    final TL_bots.popularAppBots res = (TL_bots.popularAppBots) response;
                    getMessagesController().putUsers(res.users, false);
                    bots.addAll(res.users);
                }
                show(bots, error != null);
            }));
            return;
        }
        final TLRPC.TL_contacts_search req = new TLRPC.TL_contacts_search();
        req.q = QUERIES[category];
        req.limit = 40;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (gen != generation || fragmentView == null) {
                return;
            }
            final ArrayList<TLRPC.User> bots = new ArrayList<>();
            if (response instanceof TLRPC.TL_contacts_found) {
                final TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                final HashSet<Long> ids = new HashSet<>();
                // apps first, then other bots
                for (int pass = 0; pass < 2; pass++) {
                    for (TLRPC.User user : res.users) {
                        if (user != null && user.bot && (pass == 0) == user.bot_has_main_app && ids.add(user.id)) {
                            bots.add(user);
                        }
                    }
                }
            }
            show(bots, error != null);
        }));
    }

    private void show(ArrayList<TLRPC.User> bots, boolean failed) {
        list.removeAllViews();
        if (bots.isEmpty()) {
            final TextView empty = new TextView(getParentActivity());
            empty.setText(LocaleController.getString(failed ? R.string.TigiFeedError : R.string.NilakAppsEmpty));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            if (failed) {
                empty.setOnClickListener(v -> load());
            }
            list.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 48, 0, 0));
            return;
        }
        for (TLRPC.User bot : bots) {
            list.addView(row(bot), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        }
    }

    private View row(TLRPC.User bot) {
        final Context context = getParentActivity();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(13));
        final AvatarDrawable drawable = new AvatarDrawable();
        drawable.setInfo(currentAccount, bot);
        avatar.setForUserOrChat(bot, drawable);
        row.addView(avatar, LayoutHelper.createLinear(46, 46));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(UserObject.getUserName(bot));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        final TextView sub = new TextView(context);
        final String username = UserObject.getPublicUsername(bot);
        final String users = bot.bot_active_users > 0 ? LocaleController.formatPluralStringComma("BotUsers", bot.bot_active_users) : null;
        sub.setText(users != null ? users : (username != null ? "@" + username : ""));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 8, 0));
        final TextView open = new TextView(context);
        open.setText(LocaleController.getString(R.string.NilakOpen));
        open.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        open.setTypeface(TigiStyle.displayBold());
        open.setTextColor(TigiStyle.accent());
        open.setGravity(Gravity.CENTER);
        open.setPadding(dp(14), 0, dp(14), 0);
        open.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
        open.setOnClickListener(v -> openBot(bot));
        row.addView(open, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        row.setOnClickListener(v -> openBot(bot));
        return row;
    }

    private void openBot(TLRPC.User bot) {
        if (bot.bot_has_main_app) {
            getMessagesController().openApp(this, bot, null, classGuid, null);
        } else {
            final Bundle args = new Bundle();
            args.putLong("user_id", bot.id);
            presentFragment(new ChatActivity(args));
        }
    }

    public static void open(BaseFragment fragment) {
        if (fragment != null) {
            fragment.presentFragment(new TigiAppsActivity());
        }
    }
}
