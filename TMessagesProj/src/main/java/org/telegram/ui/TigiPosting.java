package org.telegram.ui;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Tigi: Telegram has no personal posts, a post is a message in a channel. This finds the channels the user may post
 * to, remembers the last one, and creates a personal channel on the first post.
 */
public class TigiPosting {

    private static final String KEY_LAST = "tigi_post_channel";
    private static final String KEY_OWN = "tigi_own_channel";
    private static final String KEY_ARCHIVED = "tigi_own_channel_archived";

    private static boolean creating;

    public static boolean isCreating() {
        return creating;
    }

    private static SharedPreferences prefs(int account) {
        return MessagesController.getMainSettings(account);
    }

    /** Broadcast channels from the chat list where the user can post: owner, or admin with the post right. */
    public static ArrayList<TLRPC.Chat> postableChannels(int account) {
        final MessagesController controller = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Chat> out = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> dialogs = controller.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog == null || !DialogObject.isChatDialog(dialog.id)) {
                continue;
            }
            final TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (canPostTo(chat) && !out.contains(chat)) {
                out.add(chat);
            }
        }
        final TLRPC.Chat own = controller.getChat(prefs(account).getLong(KEY_OWN, 0));
        if (canPostTo(own) && !out.contains(own)) {
            out.add(0, own);
        }
        // the last used channel first
        final long last = prefs(account).getLong(KEY_LAST, 0);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).id == last) {
                out.add(0, out.remove(i));
                break;
            }
        }
        return out;
    }

    public static boolean canPostTo(TLRPC.Chat chat) {
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !chat.left && !chat.kicked
            && !ChatObject.isNotInChat(chat) && ChatObject.canPost(chat) && !chat.monoforum;
    }

    /** The user's own Tigi channel (made on the first post), if it still exists and accepts posts. */
    public static TLRPC.Chat ownChannel(int account) {
        final TLRPC.Chat own = MessagesController.getInstance(account).getChat(prefs(account).getLong(KEY_OWN, 0));
        return canPostTo(own) ? own : null;
    }

    /** Where a post goes without asking: the own Tigi channel, else the last channel used for a post. */
    public static TLRPC.Chat defaultChannel(int account) {
        final TLRPC.Chat own = ownChannel(account);
        if (own != null) {
            return own;
        }
        final TLRPC.Chat last = MessagesController.getInstance(account).getChat(prefs(account).getLong(KEY_LAST, 0));
        return canPostTo(last) ? last : null;
    }

    /**
     * The own channel is how posts work, not a chat the user made: it goes to the archive once, so "Channel created"
     * does not sit in the chat list. Done only once, so a user who takes it out of the archive keeps it there.
     */
    public static void archiveOwnChannelOnce(int account) {
        final long ownId = prefs(account).getLong(KEY_OWN, 0);
        if (ownId == 0 || prefs(account).getLong(KEY_ARCHIVED, 0) == ownId) {
            return;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.Dialog dialog = controller.dialogs_dict.get(-ownId);
        if (dialog == null) {
            // not in the loaded chat list yet; try again next time
            return;
        }
        prefs(account).edit().putLong(KEY_ARCHIVED, ownId).apply();
        if (dialog.folder_id == 0) {
            controller.addDialogToFolder(-ownId, 1, -1, 0);
        }
    }

    public static void setLastChannel(int account, long chatId) {
        prefs(account).edit().putLong(KEY_LAST, chatId).apply();
    }

    public static long lastChannel(int account) {
        return prefs(account).getLong(KEY_LAST, 0);
    }

    public static String suggestedTitle(int account) {
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        final String name = self != null ? UserObject.getFirstName(self) : null;
        return LocaleController.formatString(R.string.TigiOwnChannelTitle, TextUtils.isEmpty(name) ? LocaleController.getString(R.string.AppName) : name);
    }

    /** A public link derived from the user's username: letters, digits and _, 5 to 32 characters. */
    public static String suggestedUsername(int account) {
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        String base = self != null ? UserObject.getPublicUsername(self) : null;
        if (TextUtils.isEmpty(base)) {
            return null;
        }
        base = base.toLowerCase(Locale.US).replaceAll("[^a-z0-9_]", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            return null;
        }
        String candidate = base + "_tigi";
        if (candidate.length() > 32) {
            candidate = candidate.substring(0, 32);
        }
        while (candidate.endsWith("_")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate.length() >= 5 ? candidate : null;
    }

    /**
     * Creates the user's personal broadcast channel (private unless publicUsername is given), remembers it and
     * returns it on the UI thread. Errors come back as a user-readable message.
     */
    public static void createPersonalChannel(int account, String title, String publicUsername, Utilities.Callback<TLRPC.Chat> onDone, Utilities.Callback<String> onError) {
        final TLRPC.Chat existing = MessagesController.getInstance(account).getChat(prefs(account).getLong(KEY_OWN, 0));
        if (canPostTo(existing)) {
            // already made on an earlier try (maybe the answer got lost); never make a second one
            onDone.run(existing);
            return;
        }
        if (creating) {
            return;
        }
        creating = true;
        final TLRPC.TL_channels_createChannel req = new TLRPC.TL_channels_createChannel();
        req.broadcast = true;
        req.title = TextUtils.isEmpty(title) ? suggestedTitle(account) : title.trim();
        req.about = LocaleController.getString(R.string.TigiOwnChannelAbout);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
            }
            AndroidUtilities.runOnUIThread(() -> {
                creating = false;
                if (error != null || !(response instanceof TLRPC.Updates)) {
                    onError.run(errorText(error));
                    return;
                }
                final TLRPC.Updates updates = (TLRPC.Updates) response;
                MessagesController.getInstance(account).putUsers(updates.users, false);
                MessagesController.getInstance(account).putChats(updates.chats, false);
                if (updates.chats == null || updates.chats.isEmpty()) {
                    onError.run(LocaleController.getString(R.string.TigiPostErrorGeneric));
                    return;
                }
                final TLRPC.Chat chat = updates.chats.get(0);
                prefs(account).edit().putLong(KEY_OWN, chat.id).putLong(KEY_LAST, chat.id).apply();
                if (TextUtils.isEmpty(publicUsername)) {
                    onDone.run(chat);
                    return;
                }
                setPublicUsername(account, chat, publicUsername, () -> onDone.run(chat), message -> {
                    // the channel exists and works, only the link failed: say so and go on
                    onError.run(message);
                    onDone.run(chat);
                });
            });
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void setPublicUsername(int account, TLRPC.Chat chat, String username, Runnable onDone, Utilities.Callback<String> onError) {
        final TLRPC.TL_channels_updateUsername req = new TLRPC.TL_channels_updateUsername();
        req.channel = MessagesController.getInputChannel(chat);
        req.username = username;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_boolTrue) {
                final TLRPC.Chat current = MessagesController.getInstance(account).getChat(chat.id);
                if (current != null) {
                    current.username = username;
                    final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                    chats.add(current);
                    MessagesController.getInstance(account).putChats(chats, false);
                }
                onDone.run();
            } else {
                final String text = error != null ? error.text : null;
                if (text != null && (text.equals("USERNAME_OCCUPIED") || text.equals("USERNAME_PURCHASE_AVAILABLE"))) {
                    onError.run(LocaleController.formatString(R.string.TigiPostLinkTaken, username));
                } else if (text != null && text.equals("CHANNELS_ADMIN_PUBLIC_TOO_MUCH")) {
                    onError.run(LocaleController.getString(R.string.TigiPostTooManyPublic));
                } else {
                    onError.run(LocaleController.getString(R.string.TigiPostLinkFailed));
                }
            }
        }));
    }

    static String errorText(TLRPC.TL_error error) {
        final String text = error != null ? error.text : null;
        if (text == null) {
            return LocaleController.getString(R.string.TigiPostErrorGeneric);
        }
        if (text.startsWith("FLOOD_WAIT")) {
            int seconds = Utilities.parseInt(text);
            final int minutes = Math.max(1, (seconds + 59) / 60);
            return LocaleController.formatPluralString("TigiTryAgainMinutes", minutes);
        }
        if (text.equals("CHANNELS_TOO_MUCH")) {
            return LocaleController.getString(R.string.TigiPostTooManyChannels);
        }
        if (text.equals("USER_RESTRICTED") || text.equals("PEER_FLOOD")) {
            return LocaleController.getString(R.string.TigiPostRestricted);
        }
        return LocaleController.getString(R.string.TigiPostErrorGeneric);
    }
}
