package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Tigi About: version and build, the unofficial-app notice Telegram's API terms ask for, the GPL source link,
 * privacy policies and a way to report a problem.
 */
public class TigiAboutActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.TigiAbout));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        final TextCell version = new TextCell(context);
        version.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        version.setTextAndValue(LocaleController.getString(R.string.TigiAboutVersion), versionText(), false);
        version.setOnLongClickListener(v -> {
            org.telegram.messenger.AndroidUtilities.addToClipboard(versionText());
            return true;
        });
        list.addView(version, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell notice = new TextInfoPrivacyCell(context);
        notice.setText(LocaleController.getString(R.string.TigiAboutNotice));
        list.addView(notice, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        list.addView(row(context, R.string.TigiAboutSource, R.drawable.msg_link2, true, () -> Browser.openUrl(getParentActivity(), BuildVars.TIGI_SOURCE_URL)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        list.addView(row(context, R.string.TigiAboutLicense, R.drawable.msg_info, true, () -> Browser.openUrl(getParentActivity(), "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (!TextUtils.isEmpty(BuildVars.TIGI_PRIVACY_POLICY_URL)) {
            list.addView(row(context, R.string.TigiAboutPrivacy, R.drawable.msg_policy, true, () -> Browser.openUrl(getParentActivity(), BuildVars.TIGI_PRIVACY_POLICY_URL)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        // the messages themselves are handled by Telegram's servers under Telegram's privacy policy
        list.addView(row(context, R.string.TigiAboutTelegramPrivacy, R.drawable.msg_policy, false, () -> Browser.openUrl(getParentActivity(), "https://telegram.org/privacy")), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        list.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        list.addView(row(context, R.string.TigiAboutReport, R.drawable.msg_report, BuildVars.LOGS_ENABLED, this::reportProblem), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (BuildVars.LOGS_ENABLED) {
            list.addView(row(context, R.string.DebugSendLogs, R.drawable.msg_log, false, () -> ProfileActivity.sendLogs(getParentActivity(), false)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        final TextInfoPrivacyCell reportInfo = new TextInfoPrivacyCell(context);
        reportInfo.setText(LocaleController.getString(R.string.TigiAboutReportInfo));
        list.addView(reportInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = scroll;
        return fragmentView;
    }

    private TextCell row(Context context, int text, int icon, boolean divider, Runnable onClick) {
        final TextCell cell = new TextCell(context);
        cell.setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));
        cell.setTextAndIcon(LocaleController.getString(text), icon, divider);
        cell.setOnClickListener(v -> onClick.run());
        return cell;
    }

    static String versionText() {
        String code = "";
        try {
            final PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            code = " (" + (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode) + ")";
        } catch (Exception e) {
            FileLog.e(e);
        }
        return BuildVars.BUILD_VERSION_STRING + code + " " + Build.SUPPORTED_ABIS[0];
    }

    /** An email to the configured support address, or a new issue on the public tracker (no personal data in it). */
    private void reportProblem() {
        if (getParentActivity() == null) {
            return;
        }
        final String subject = "Tigi " + versionText();
        final String body = "\n\n---\n" + versionText() + "\nAndroid " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL;
        try {
            if (!TextUtils.isEmpty(BuildVars.TIGI_SUPPORT_EMAIL)) {
                final Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + BuildVars.TIGI_SUPPORT_EMAIL));
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
                intent.putExtra(Intent.EXTRA_TEXT, body);
                getParentActivity().startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.TigiAboutReport)));
            } else {
                Browser.openUrl(getParentActivity(), BuildVars.TIGI_SOURCE_URL + "/issues/new?title=" + Uri.encode(subject) + "&body=" + Uri.encode(body));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
