package org.telegram.ui;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.voip.VoIPHelper;

/**
 * Nilak Live, built only on what Telegram offers. Going live is Telegram's live story (its camera's Live tab: live as
 * the person or one of their channels, who can watch, comments); watching is Telegram's story viewer. A channel's
 * live stream / video chat (GroupCallActivity + VoIPService) counts as live too, and joins through Telegram's call.
 */
public class TigiLive {

    /** Go live: Telegram's camera on its Live tab, live as the person or one of their channels */
    public static void start(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null || !BuildVars.TIGI_LIVE) {
            return;
        }
        TigiPosting.openStoryRecorder(fragment, true);
    }

    /** True while the person or channel streams a live story */
    public static boolean hasLiveStory(int account, long dialogId) {
        return BuildVars.TIGI_LIVE && dialogId != 0 && MessagesController.getInstance(account).getStoriesController().hasLiveStory(dialogId);
    }

    /** Live in any way: a live story, or a channel's live stream / video chat */
    public static boolean isLive(int account, long dialogId) {
        if (hasLiveStory(account, dialogId)) {
            return true;
        }
        return dialogId < 0 && isLive(MessagesController.getInstance(account).getChat(-dialogId));
    }

    /** Opens what is live: the live story in Telegram's story viewer, else the channel's live stream */
    public static void watch(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (hasLiveStory(fragment.getCurrentAccount(), dialogId)) {
            fragment.getOrCreateStoryViewer().open(fragment.getParentActivity(), dialogId, (did, messageId, storyId, type, holder) -> false);
            return;
        }
        final TLRPC.Chat chat = dialogId < 0 ? MessagesController.getInstance(fragment.getCurrentAccount()).getChat(-dialogId) : null;
        if (isLive(chat)) {
            join(fragment, chat);
        }
    }

    /** Telegram's live colour, the same as on its chat list and story rings */
    public static int color() {
        return Theme.getColor(Theme.key_stories_circle_live2);
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
        final ChatObject.Call call = isLive(chat) ? MessagesController.getInstance(account).getGroupCall(chat.id, false) : null;
        final int viewers = call != null && call.call != null ? call.call.participants_count : 0;
        final String live = LocaleController.getString(R.string.TigiLiveBadge);
        return viewers > 0 ? live + " · " + LocaleController.formatShortNumber(viewers, null) : live;
    }
}
