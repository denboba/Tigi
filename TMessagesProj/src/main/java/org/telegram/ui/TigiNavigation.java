package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;

import android.os.Bundle;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;

import java.util.List;

/**
 * Tigi: one place that decides which screen a tap on an avatar or name opens, and where the screens that left the
 * tab bar (contacts, calls, settings) live now. Everything here falls back to Telegram's own screens.
 */
public class TigiNavigation {

    /**
     * Opens the profile of a user (dialogId > 0) or chat/channel (dialogId < 0).
     * Own profile: the Profile tab when the caller is one of the main tabs, otherwise Telegram's own profile screen.
     * Broadcast channels and people with a personal channel: the Tigi profile (posts grid; Telegram's info is one tap
     * away there). Everything else: ProfileActivity.
     */
    public static boolean openProfile(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        final int account = fragment.getCurrentAccount();
        if (dialogId > 0) {
            if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
                final MainTabsActivity tabs = findMainTabs(fragment);
                if (tabs != null && tabs == fragment.getParentLayout().getLastFragment() && tabs.openProfileTab()) {
                    return true;
                }
            }
            final TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            if (user == null) {
                return false;
            }
            // a person with a personal channel gets the posts grid of that channel
            final TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(dialogId);
            if (BuildVars.TIGI_HOME && full == null && !user.bot && !user.self) {
                // not known yet whether they have one: ask first, then open the right profile
                MessagesController.getInstance(account).loadFullUser(user, 0, true, loaded -> AndroidUtilities.runOnUIThread(() -> {
                    if (fragment.getParentActivity() == null) {
                        return;
                    }
                    if (loaded != null && MessagesController.getInstance(account).getUserFull(dialogId) != null) {
                        openProfile(fragment, dialogId);
                    } else {
                        final Bundle fallback = new Bundle();
                        fallback.putLong("user_id", dialogId);
                        fragment.presentFragment(new ProfileActivity(fallback));
                    }
                }));
                return true;
            }
            if (BuildVars.TIGI_HOME && full != null && full.personal_channel_id != 0) {
                final TLRPC.Chat personal = MessagesController.getInstance(account).getChat(full.personal_channel_id);
                if (personal != null && !personal.restricted) {
                    final Bundle args = new Bundle();
                    args.putLong("chat_id", personal.id);
                    args.putLong("user_id", dialogId);
                    if (fragment.presentFragment(new TigiProfileActivity(args))) {
                        return true;
                    }
                }
            }
            final Bundle args = new Bundle();
            args.putLong("user_id", dialogId);
            return fragment.presentFragment(new ProfileActivity(args));
        }
        final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat == null) {
            return false;
        }
        if (BuildVars.TIGI_HOME && ChatObject.isChannelAndNotMegaGroup(chat) && !chat.forum && !chat.monoforum && !chat.restricted) {
            final Bundle args = new Bundle();
            args.putLong("chat_id", chat.id);
            if (fragment.presentFragment(new TigiProfileActivity(args))) {
                return true;
            }
        }
        return openChatInfo(fragment, chat.id);
    }

    /** Telegram's own info screen of a chat or channel (members, admin tools, notifications, shared media). */
    public static boolean openChatInfo(BaseFragment fragment, long chatId) {
        if (fragment == null || chatId == 0) {
            return false;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        return fragment.presentFragment(new ProfileActivity(args));
    }

    public static boolean openContacts(BaseFragment fragment) {
        if (fragment == null) {
            return false;
        }
        final Bundle args = new Bundle();
        args.putBoolean("needPhonebook", true);
        args.putBoolean("needFinishFragment", false);
        return fragment.presentFragment(new ContactsActivity(args));
    }

    public static boolean openCalls(BaseFragment fragment) {
        if (fragment == null) {
            return false;
        }
        final Bundle args = new Bundle();
        args.putBoolean("needFinishFragment", false);
        return fragment.presentFragment(new CallLogActivity(args));
    }

    public static boolean openSettings(BaseFragment fragment) {
        return fragment != null && fragment.presentFragment(new SettingsActivity());
    }

    /** The MainTabsActivity at the bottom of the fragment's stack, if any. */
    public static MainTabsActivity findMainTabs(BaseFragment fragment) {
        if (fragment == null) {
            return null;
        }
        final INavigationLayout layout = fragment.getParentLayout();
        if (layout == null) {
            return null;
        }
        final List<BaseFragment> stack = layout.getFragmentStack();
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i) instanceof MainTabsActivity) {
                return (MainTabsActivity) stack.get(i);
            }
        }
        return null;
    }

    /**
     * Space a tab page must keep free at the bottom, in px: the floating tab bar plus the system navigation bar
     * below it (the tab pages run behind both).
     */
    public static int tabsBottomSpace() {
        return AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
    }
}
