package org.telegram.ui;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.voip.VoIPHelper;

import java.util.ArrayList;

/**
 * Tigi Live, built only on what Telegram offers: a channel's video chat / live stream (GroupCallActivity + VoIPService).
 * Starting goes through Telegram's own dialog (start now, schedule, or "start with" another app over RTMP); in the call
 * the host turns the camera on, sees the viewer count and ends it with Telegram's confirmation. Telegram has no
 * one-to-many live video for personal accounts, so Live is offered for channels the user can manage calls in.
 */
public class TigiLive {

    /** Channels where the user may start a live stream (owner, or admin with the "manage video chats" right). */
    public static ArrayList<TLRPC.Chat> liveChannels(int account) {
        final MessagesController controller = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Chat> out = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> dialogs = controller.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog == null || !DialogObject.isChatDialog(dialog.id)) {
                continue;
            }
            final TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !ChatObject.isNotInChat(chat) && ChatObject.canManageCalls(chat) && !out.contains(chat)) {
                out.add(chat);
            }
        }
        // the channel the user posts to most recently comes first
        final long last = TigiPosting.lastChannel(account);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).id == last) {
                out.add(0, out.remove(i));
                break;
            }
        }
        return out;
    }

    public static void start(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null || !BuildVars.TIGI_LIVE) {
            return;
        }
        TigiCreateSheet.pickFrom(fragment, liveChannels(fragment.getCurrentAccount()), chat -> goLive(fragment, chat));
    }

    static void goLive(BaseFragment fragment, TLRPC.Chat chat) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null) {
            return;
        }
        if (!ChatObject.canManageCalls(chat)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiLiveNoRights)).show();
            return;
        }
        final AccountInstance accountInstance = AccountInstance.getInstance(fragment.getCurrentAccount());
        final ChatObject.Call call = MessagesController.getInstance(fragment.getCurrentAccount()).getGroupCall(chat.id, false);
        if (call != null) {
            // already live: go back into it
            VoIPHelper.startCall(chat, null, null, false, fragment.getParentActivity(), fragment, accountInstance);
        } else {
            VoIPHelper.showGroupCallAlert(fragment, chat, null, false, accountInstance);
        }
    }

    /** True while the channel has a live stream / video chat going on. */
    public static boolean isLive(TLRPC.Chat chat) {
        return BuildVars.TIGI_LIVE && chat != null && chat.call_active && chat.call_not_empty;
    }

    /** Joins a running live as a viewer (Telegram's join flow: permission, ongoing-call and offline checks). */
    public static void join(BaseFragment fragment, TLRPC.Chat chat) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null) {
            return;
        }
        VoIPHelper.startCall(chat, null, null, false, fragment.getParentActivity(), fragment, AccountInstance.getInstance(fragment.getCurrentAccount()));
    }

    /** "LIVE" or "LIVE · 1.2K" when the viewer count is known. */
    public static String badgeText(int account, TLRPC.Chat chat) {
        final ChatObject.Call call = MessagesController.getInstance(account).getGroupCall(chat.id, false);
        final int viewers = call != null && call.call != null ? call.call.participants_count : 0;
        final String live = LocaleController.getString(R.string.TigiLiveBadge);
        return viewers > 0 ? live + " · " + LocaleController.formatShortNumber(viewers, null) : live;
    }
}
