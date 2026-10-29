/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2020.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.android.billingclient.api.ProductDetails;

import java.util.Objects;

public class BuildVars {

    public static boolean DEBUG_VERSION = BuildConfig.DEBUG_VERSION;
    public static boolean LOGS_ENABLED = BuildConfig.DEBUG_VERSION;
    public static boolean DEBUG_PRIVATE_VERSION = BuildConfig.DEBUG_PRIVATE_VERSION;
    public static boolean USE_CLOUD_STRINGS = true;
    public static boolean CHECK_UPDATES = false;
    public static boolean NO_SCOPED_STORAGE = Build.VERSION.SDK_INT <= 29;
    public static String BUILD_VERSION_STRING = BuildConfig.BUILD_VERSION_STRING;

    public static int APP_ID = 0; // injected from TG_API_ID secret at build time
    public static String APP_HASH = ""; // injected from TG_API_HASH secret at build time

    // SafetyNet key for Google Identity SDK, set it to empty to disable
    // Tigi: Telegram's key only works for its own package; empty = login codes come by SMS/app instead of Firebase SMS
    public static String SAFETYNET_KEY = "";
    public static String PLAYSTORE_APP_URL = "https://play.google.com/store/apps/details?id=com.tigi.app";
    public static String HUAWEI_STORE_URL = "https://appgallery.huawei.com/app/C101184875";
    public static String GOOGLE_AUTH_CLIENT_ID = "760348033671-81kmi3pi84p11ub8hp9a1funsv0rn2p9.apps.googleusercontent.com";

    public static String HUAWEI_APP_ID = "101184875";

    // You can use this flag to disable Google Play Billing (If you're making fork and want it to be in Google Play)
    public static boolean IS_BILLING_UNAVAILABLE = true;

    // works only on official app ids, disable on your forks
    public static boolean SUPPORTS_PASSKEYS = false;

    // Tigi: "Sign in with Google" for login email needs an OAuth client registered for com.tigi.app
    public static boolean TIGI_GOOGLE_SIGN_IN = false;

    // Tigi: account type of the synced contacts, must match res/xml/auth.xml and sync_contacts.xml.
    // Must differ from the official app or the two installs fight over the same authenticator.
    public static final String CONTACTS_ACCOUNT_TYPE = "com.tigi.app";

    // Tigi: folder in shared storage (Android 10 and older). Telegram uses "Telegram"; sharing it would let either
    // app clear or move the other's files. The app-private folder (getExternalFilesDir) keeps its old name.
    public static final String PUBLIC_ROOT_DIR = "Tigi";

    // Tigi feature flags. With a flag off the app behaves like the release before that feature.
    public static boolean TIGI_HOME = true;    // Home feed tab, channels open as a feed, media feed menu item
    public static boolean TIGI_NEW_NAV = true; // Home | Reels | + | Chats | Profile bottom bar
    public static boolean TIGI_POST = true;    // "+" Create sheet, posting into own channels
    public static boolean TIGI_REELS = true;   // full screen vertical video tab
    public static boolean TIGI_LIVE = true;    // "Go live" in the Create sheet, LIVE badges in feeds
    public static boolean TIGI_PROFILE_TAB = true; // Profile tab is the Tigi profile (own posts grid); off = Telegram's profile
    // Premium, Stars, Gram wallet, Business and gift rows in Settings. Off while IS_BILLING_UNAVAILABLE: Google Play
    // does not allow buying digital goods outside Play billing, and those screens would offer exactly that.
    public static boolean TIGI_PAID_FEATURES = false;

    // Tigi links (owner: fill these in before a store release)
    public static String TIGI_SOURCE_URL = "https://github.com/denboba/Tigi"; // GPL: where the full source lives
    public static String TIGI_PRIVACY_POLICY_URL = ""; // Tigi's own policy; empty = only Telegram's is shown
    public static String TIGI_SUPPORT_EMAIL = "";      // empty = "Report a problem" opens a GitHub issue

    static {
        if (ApplicationLoader.applicationContext != null) {
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            LOGS_ENABLED = DEBUG_VERSION || sharedPreferences.getBoolean("logsEnabled", DEBUG_VERSION);
            if (LOGS_ENABLED) {
                final Thread.UncaughtExceptionHandler pastHandler = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
                    FileLog.fatal(exception, false);
                    if (pastHandler != null) {
                        pastHandler.uncaughtException(thread, exception);
                    }
                });
            }
        }
    }

    public static boolean useInvoiceBilling() {
        return BillingController.billingClientEmpty || DEBUG_VERSION && false || ApplicationLoader.isStandaloneBuild() || isBetaApp() && false || isHuaweiStoreApp() || hasDirectCurrency();
    }

    private static boolean hasDirectCurrency() {
        if (!BillingController.getInstance().isReady() || BillingController.PREMIUM_PRODUCT_DETAILS == null) {
            return false;
        }
        for (ProductDetails.SubscriptionOfferDetails offerDetails : BillingController.PREMIUM_PRODUCT_DETAILS.getSubscriptionOfferDetails()) {
            for (ProductDetails.PricingPhase phase : offerDetails.getPricingPhases().getPricingPhaseList()) {
                for (String cur : MessagesController.getInstance(UserConfig.selectedAccount).directPaymentsCurrency) {
                    if (Objects.equals(phase.getPriceCurrencyCode(), cur)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Boolean betaApp;
    public static boolean isBetaApp() {
        if (betaApp == null) {
            betaApp = ApplicationLoader.applicationContext != null && "org.telegram.messenger.beta".equals(ApplicationLoader.applicationContext.getPackageName());
        }
        return betaApp;
    }


    public static boolean isHuaweiStoreApp() {
        return ApplicationLoader.isHuaweiStoreBuild();
    }

    public static String getSmsHash() {
        return ApplicationLoader.isStandaloneBuild() ? "w0lkcmTZkKh" : (DEBUG_VERSION ? "O2P2z+/jBpJ" : "oLeq9AcOZkT");
    }
}
