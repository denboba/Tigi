package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.StatsController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/**
 * Nilak data saver: how much mobile data Nilak used (Telegram's own counters), whether feed videos play by
 * themselves on mobile data, and a way into the download settings (what loads automatically on mobile data, Wi-Fi
 * and roaming).
 */
public class TigiDataSaverActivity extends BaseFragment {

    private static final String KEY_AUTOPLAY_MOBILE = "nilak_autoplay_mobile";

    /** Feed videos may start by themselves: always on Wi-Fi, on mobile data only when the user allows it. */
    public static boolean autoplayAllowed() {
        if (MessagesController.getGlobalMainSettings().getBoolean(KEY_AUTOPLAY_MOBILE, true)) {
            return true;
        }
        return !isMetered();
    }

    private static boolean isMetered() {
        try {
            final ConnectivityManager manager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            return manager != null && manager.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

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
        title.setText(LocaleController.getString(R.string.NilakDataSaver));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        bar.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 8, 0));
        column.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(24));

        // usage card
        final StatsController stats = StatsController.getInstance(currentAccount);
        final long mobile = stats.getReceivedBytesCount(StatsController.TYPE_MOBILE, StatsController.TYPE_TOTAL) + stats.getSentBytesCount(StatsController.TYPE_MOBILE, StatsController.TYPE_TOTAL);
        final long wifi = stats.getReceivedBytesCount(StatsController.TYPE_WIFI, StatsController.TYPE_TOTAL) + stats.getSentBytesCount(StatsController.TYPE_WIFI, StatsController.TYPE_TOTAL);
        final long since = stats.getResetStatsDate(StatsController.TYPE_MOBILE);
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.multAlpha(TigiStyle.accent(), 0.10f)));
        final TextView label = new TextView(context);
        label.setText(since > 0 ? LocaleController.formatString(R.string.NilakDataUsedSince, LocaleController.getInstance().getFormatterDay().format(since)) : LocaleController.getString(R.string.NilakDataUsed));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(label);
        final TextView big = new TextView(context);
        big.setText(AndroidUtilities.formatFileSize(mobile));
        big.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        big.setTypeface(TigiStyle.displayExtraBold());
        big.setTextColor(TigiStyle.accent());
        card.addView(big, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 2));
        final TextView wifiLine = new TextView(context);
        wifiLine.setText(LocaleController.formatString(R.string.NilakDataWifi, AndroidUtilities.formatFileSize(wifi)));
        wifiLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        wifiLine.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        card.addView(wifiLine);
        card.setOnClickListener(v -> presentFragment(new DataUsage2Activity()));
        body.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        body.addView(toggleRow(context, R.string.NilakAutoplayMobile, R.string.NilakAutoplayMobileHint, KEY_AUTOPLAY_MOBILE));
        body.addView(linkRow(context, R.string.NilakAutoDownload, R.string.NilakAutoDownloadHint, () -> presentFragment(new DataSettingsActivity())));
        body.addView(linkRow(context, R.string.NilakDataDetails, R.string.NilakDataDetailsHint, () -> presentFragment(new DataUsage2Activity())));

        scroll.addView(body);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return root;
    }

    private LinearLayout baseRow(Context context, int titleRes, int hintRes) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(0, dp(8), 0, dp(8));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(titleRes));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(TigiStyle.displayBold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        texts.addView(title);
        final TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(hintRes));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        texts.addView(hint);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0));
        return row;
    }

    private View toggleRow(Context context, int titleRes, int hintRes, String key) {
        final LinearLayout row = baseRow(context, titleRes, hintRes);
        final Switch toggle = new Switch(context);
        toggle.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        toggle.setChecked(MessagesController.getGlobalMainSettings().getBoolean(key, true), false);
        row.addView(toggle, LayoutHelper.createLinear(37, 40));
        row.setOnClickListener(v -> {
            final boolean value = !MessagesController.getGlobalMainSettings().getBoolean(key, true);
            MessagesController.getGlobalMainSettings().edit().putBoolean(key, value).apply();
            toggle.setChecked(value, true);
        });
        return row;
    }

    private View linkRow(Context context, int titleRes, int hintRes, Runnable onClick) {
        final LinearLayout row = baseRow(context, titleRes, hintRes);
        final ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.msg_arrowright);
        arrow.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
        if (LocaleController.isRTL) {
            arrow.setScaleX(-1f);
        }
        row.addView(arrow, LayoutHelper.createLinear(24, 24));
        row.setOnClickListener(v -> onClick.run());
        return row;
    }
}
