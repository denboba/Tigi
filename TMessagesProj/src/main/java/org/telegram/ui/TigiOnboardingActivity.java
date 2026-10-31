package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Nilak first run, once, for people who follow fewer than three channels: pick interests, follow a few suggested
 * channels, land on Home with content. Telegram has no interest API, so suggestions come from searching public
 * channels by the interest's name (contacts.search) plus Telegram's own channel recommendations.
 */
public class TigiOnboardingActivity extends BaseFragment {

    private static final String KEY_DONE = "nilak_onboarding_done";
    private static final int MIN_FOLLOWED = 3;

    private static final int[] INTERESTS = {
        R.string.NilakInterestNews, R.string.NilakInterestFootball, R.string.NilakInterestMusic, R.string.NilakInterestComedy,
        R.string.NilakInterestTech, R.string.NilakInterestFaith, R.string.NilakInterestBusiness, R.string.NilakInterestLearning,
        R.string.NilakInterestFood, R.string.NilakInterestMovies, R.string.NilakInterestSports, R.string.NilakInterestTravel,
        R.string.NilakInterestEducation, R.string.NilakInterestHealth, R.string.NilakInterestFashion, R.string.NilakInterestArt,
        R.string.NilakInterestGaming, R.string.NilakInterestScience, R.string.NilakInterestCulture, R.string.NilakInterestJobs,
        R.string.NilakInterestLanguages
    };

    private final HashSet<Integer> picked = new HashSet<>();
    private final ArrayList<TLRPC.Chat> suggestions = new ArrayList<>();
    private int step;
    private int followedNow;

    private LinearLayout body;
    private TextView titleView;
    private TextView subtitleView;
    private TextView continueButton;
    private View progressBar;

    /** Whether to show first run now: never done, flag on, and the user follows fewer than three channels. */
    public static boolean shouldShow(int account) {
        if (!BuildVars.TIGI_ONBOARDING || MessagesController.getMainSettings(account).getBoolean(KEY_DONE, false)) {
            return false;
        }
        if (!MessagesController.getInstance(account).dialogsLoaded) {
            return false;
        }
        if (TigiPosting.followedChannels(account).size() >= MIN_FOLLOWED) {
            // already has a feed: never ask
            markDone(account);
            return false;
        }
        return true;
    }

    private static void markDone(int account) {
        MessagesController.getMainSettings(account).edit().putBoolean(KEY_DONE, true).apply();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), AndroidUtilities.statusBarHeight + dp(12), dp(20), dp(12) + AndroidUtilities.navigationBarHeight);
        ViewCompat.setOnApplyWindowInsetsListener(column, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(20), bars.top + dp(12), dp(20), bars.bottom + dp(12));
            return insets;
        });

        progressBar = new View(context);
        column.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4, 0, 4, 0, 18));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        titleView.setTypeface(TigiStyle.displayExtraBold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        ViewCompat.setAccessibilityHeading(titleView, true);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 18));

        final ScrollView scroll = new ScrollView(context);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        continueButton = new TextView(context);
        continueButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        continueButton.setTypeface(TigiStyle.displayBold());
        continueButton.setTextColor(0xFFFFFFFF);
        continueButton.setGravity(Gravity.CENTER);
        continueButton.setBackground(TigiStyle.primaryPill(14));
        continueButton.setOnClickListener(v -> next());
        column.addView(continueButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 12, 0, 0));
        final TextView skip = new TextView(context);
        skip.setText(LocaleController.getString(R.string.NilakSkipForNow));
        skip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        skip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        skip.setGravity(Gravity.CENTER);
        skip.setOnClickListener(v -> finish());
        column.addView(skip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        showInterests();
        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (step == 1) {
            if (invoked) {
                showInterests();
            }
            return false;
        }
        if (invoked) {
            markDone(currentAccount);
        }
        return super.onBackPressed(invoked);
    }

    private void updateProgress() {
        final android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            step == 0 ? new int[]{TigiStyle.PRIMARY, TigiStyle.PRIMARY, Theme.getColor(Theme.key_windowBackgroundGray), Theme.getColor(Theme.key_windowBackgroundGray)}
                : new int[]{TigiStyle.PRIMARY, TigiStyle.PRIMARY});
        track.setCornerRadius(dp(2));
        progressBar.setBackground(track);
    }

    /* step 1: interests */

    private void showInterests() {
        step = 0;
        updateProgress();
        titleView.setText(LocaleController.getString(R.string.NilakOnboardingTitle));
        subtitleView.setText(LocaleController.getString(R.string.NilakOnboardingText));
        body.removeAllViews();
        addRegionRow();
        LinearLayout row = null;
        int inRow = 0;
        for (int i = 0; i < INTERESTS.length; i++) {
            if (row == null || inRow == 3) {
                row = new LinearLayout(getParentActivity());
                row.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                inRow = 0;
            }
            final int interest = INTERESTS[i];
            final TextView chip = new TextView(getParentActivity());
            chip.setText(LocaleController.getString(interest));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setTypeface(TigiStyle.displayBold());
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            bindChip(chip, picked.contains(interest));
            chip.setOnClickListener(v -> {
                if (!picked.remove(interest)) {
                    picked.add(interest);
                }
                bindChip(chip, picked.contains(interest));
                updateContinue();
            });
            row.addView(chip, LayoutHelper.createLinear(0, 44, 1f, inRow == 0 ? 0 : 5, 0, inRow == 2 ? 0 : 5, 0));
            inRow++;
        }
        updateContinue();
    }

    /** "Content from Ethiopia · አማርኛ  Change": whose channels come first in the suggestions. */
    private void addRegionRow() {
        final TextView region = new TextView(getParentActivity());
        final String change = LocaleController.getString(R.string.NilakChange);
        final android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(LocaleController.formatString(R.string.NilakContentFrom, TigiRegion.summary()));
        text.append("  ");
        final int start = text.length();
        text.append(change);
        text.setSpan(new android.text.style.ForegroundColorSpan(TigiStyle.accent()), start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        region.setText(text);
        region.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        region.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        region.setGravity(Gravity.CENTER_VERTICAL);
        region.setMinHeight(dp(44));
        region.setPadding(dp(14), 0, dp(14), 0);
        region.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundGray)));
        region.setOnClickListener(v -> TigiRegion.showPicker(this, () -> {
            if (fragmentView != null && step == 0) {
                showInterests();
            }
        }));
        body.addView(region, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));
    }

    private void bindChip(TextView chip, boolean on) {
        chip.setSelected(on);
        chip.setTextColor(on ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        chip.setBackground(on ? TigiStyle.primaryPill(22) : Theme.createRoundRectDrawable(dp(22), Theme.getColor(Theme.key_windowBackgroundGray)));
    }

    private void updateContinue() {
        if (step == 0) {
            continueButton.setText(LocaleController.getString(R.string.NilakContinue));
            continueButton.setAlpha(picked.isEmpty() ? 0.5f : 1f);
        } else {
            continueButton.setText(followedNow > 0 ? LocaleController.getString(R.string.NilakDone) : LocaleController.getString(R.string.NilakContinue));
            continueButton.setAlpha(1f);
        }
    }

    private void next() {
        if (step == 0) {
            if (picked.isEmpty()) {
                return;
            }
            if (!TigiRegion.chosen()) {
                // keep what the phone suggested, so it stays the same later
                TigiRegion.set(TigiRegion.country(), TigiRegion.language());
            }
            showSuggestions();
        } else {
            finish();
        }
    }

    /* step 2: suggested channels */

    private void showSuggestions() {
        step = 1;
        updateProgress();
        titleView.setText(LocaleController.getString(R.string.TigiSuggestedTitle));
        subtitleView.setText(LocaleController.getString(R.string.NilakOnboardingFollowText));
        body.removeAllViews();
        final RadialProgressView spinner = new RadialProgressView(getParentActivity());
        spinner.setProgressColor(TigiStyle.accent());
        body.addView(spinner, LayoutHelper.createLinear(48, 48, Gravity.CENTER, 0, 48, 0, 0));
        updateContinue();
        suggestions.clear();
        final HashSet<Long> ids = new HashSet<>();
        final ArrayList<String> queue = new ArrayList<>(TigiRegion.countryQueries());
        for (Integer interest : picked) {
            queue.add(LocaleController.getString(interest));
        }
        final String language = TigiRegion.language();
        final Runnable[] stepper = new Runnable[1];
        stepper[0] = () -> {
            if (fragmentView == null || step != 1) {
                return;
            }
            if (queue.isEmpty()) {
                TigiPosting.loadSuggestedChannels(currentAccount, general -> {
                    for (int i = 0; i < general.size(); i++) {
                        if (ChatObject.isNotInChat(general.get(i)) && ids.add(general.get(i).id)) {
                            suggestions.add(general.get(i));
                        }
                    }
                    showSuggestionList();
                });
                return;
            }
            final TLRPC.TL_contacts_search req = new TLRPC.TL_contacts_search();
            req.q = queue.remove(0);
            req.limit = 20;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_contacts_found) {
                    final TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    // the biggest public channels for this interest, a few per interest
                    final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
                    for (int i = 0; i < res.chats.size(); i++) {
                        final TLRPC.Chat chat = res.chats.get(i);
                        if (ChatObject.isChannelAndNotMegaGroup(chat) && ChatObject.isPublic(chat) && !chat.restricted && ChatObject.isNotInChat(chat)) {
                            channels.add(chat);
                        }
                    }
                    // the chosen language's channels first, then the biggest
                    java.util.Collections.sort(channels, (a, b) -> {
                        final boolean ma = TigiRegion.matchesScript(a.title, language);
                        final boolean mb = TigiRegion.matchesScript(b.title, language);
                        return ma != mb ? (ma ? -1 : 1) : b.participants_count - a.participants_count;
                    });
                    for (int i = 0, n = 0; i < channels.size() && n < 4; i++) {
                        if (ids.add(channels.get(i).id)) {
                            suggestions.add(channels.get(i));
                            n++;
                        }
                    }
                }
                stepper[0].run();
            }));
        };
        stepper[0].run();
    }

    private void showSuggestionList() {
        if (fragmentView == null || step != 1) {
            return;
        }
        body.removeAllViews();
        if (suggestions.isEmpty()) {
            final TextView empty = new TextView(getParentActivity());
            empty.setText(LocaleController.getString(R.string.NilakOnboardingNothing));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            body.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 40, 0, 0));
            return;
        }
        for (int i = 0; i < suggestions.size() && i < 24; i++) {
            body.addView(channelRow(suggestions.get(i)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        }
    }

    private View channelRow(TLRPC.Chat chat) {
        final Context context = getParentActivity();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(22));
        final AvatarDrawable drawable = new AvatarDrawable();
        drawable.setInfo(currentAccount, chat);
        avatar.setForUserOrChat(chat, drawable);
        row.addView(avatar, LayoutHelper.createLinear(44, 44));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(chat.title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        final TextView count = new TextView(context);
        count.setText(chat.participants_count > 0 ? LocaleController.formatShortNumber(chat.participants_count, null) + " " + LocaleController.getString(R.string.TigiProfileFollowers) : "");
        count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        count.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        texts.addView(count);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 8, 0));
        final TextView follow = new TextView(context);
        follow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        follow.setTypeface(TigiStyle.displayBold());
        follow.setGravity(Gravity.CENTER);
        follow.setPadding(dp(14), 0, dp(14), 0);
        bindFollow(follow, false);
        follow.setOnClickListener(v -> {
            final TLRPC.Chat current = getMessagesController().getChat(chat.id);
            if (current == null || !ChatObject.isNotInChat(current) || follow.isSelected()) {
                return;
            }
            bindFollow(follow, true);
            followedNow++;
            updateContinue();
            getMessagesController().addUserToChat(chat.id, getUserConfig().getCurrentUser(), 0, null, this, true, null, error -> {
                bindFollow(follow, false);
                followedNow = Math.max(0, followedNow - 1);
                updateContinue();
                return true;
            });
        });
        row.addView(follow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        return row;
    }

    private void bindFollow(TextView follow, boolean following) {
        follow.setSelected(following);
        follow.setText(LocaleController.getString(following ? R.string.TigiProfileFollowing : R.string.TigiProfileFollow));
        follow.setTextColor(following ? TigiStyle.accent() : 0xFFFFFFFF);
        follow.setBackground(following ? TigiStyle.tintedPill(10) : TigiStyle.primaryPill(10));
    }

    private void finish() {
        markDone(currentAccount);
        final MainTabsActivity tabs = TigiNavigation.findMainTabs(this);
        if (tabs != null) {
            tabs.openHomeTab();
        }
        finishFragment();
    }
}
