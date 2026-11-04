package org.telegram.ui;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Nilak: the personal channel is part of the person, never a separate thing. This keeps it in step with the profile
 * (the channel's name, photo and description follow the person's name, photo and bio) and carries the opt-in
 * "Following" count other Nilak apps can read: the number only, written invisibly at the end of the description.
 */
public final class TigiIdentity {

    /** Invisible marker: U+2063, the count in base 4 as zero-width characters, U+2063. Telegram shows nothing. */
    private static final char MARK = '⁣';
    private static final char[] DIGITS = {'​', '‌', '‍', '⁠'};
    private static final String KEY_SHARE_FOLLOWING = "nilak_share_following";
    private static final String KEY_SYNC_TIME = "nilak_identity_sync";

    private TigiIdentity() {
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("nilak_identity_" + account, 0);
    }

    public static boolean sharesFollowing(int account) {
        return prefs(account).getBoolean(KEY_SHARE_FOLLOWING, false);
    }

    public static void setSharesFollowing(int account, boolean value) {
        prefs(account).edit().putBoolean(KEY_SHARE_FOLLOWING, value).remove(KEY_SYNC_TIME).apply();
        sync(account, true);
    }

    static String encode(int count) {
        final StringBuilder out = new StringBuilder().append(MARK);
        final String base4 = Integer.toString(Math.max(0, count), 4);
        for (int i = 0; i < base4.length(); i++) {
            out.append(DIGITS[base4.charAt(i) - '0']);
        }
        return out.append(MARK).toString();
    }

    /** The shared Following count in a description, or -1 when there is none. */
    public static int decode(String about) {
        if (TextUtils.isEmpty(about)) {
            return -1;
        }
        final int end = about.lastIndexOf(MARK);
        final int start = end > 0 ? about.lastIndexOf(MARK, end - 1) : -1;
        if (start < 0 || end - start < 2) {
            return -1;
        }
        int value = 0;
        for (int i = start + 1; i < end; i++) {
            int digit = -1;
            for (int d = 0; d < DIGITS.length; d++) {
                if (about.charAt(i) == DIGITS[d]) {
                    digit = d;
                }
            }
            if (digit < 0) {
                return -1;
            }
            value = value * 4 + digit;
        }
        return value;
    }

    /** The description without the invisible marker. */
    public static String strip(String about) {
        if (TextUtils.isEmpty(about)) {
            return about;
        }
        final int end = about.lastIndexOf(MARK);
        final int start = end > 0 ? about.lastIndexOf(MARK, end - 1) : -1;
        return start >= 0 ? about.substring(0, start).trim() : about;
    }

    /**
     * Brings the own personal channel in line with the profile: name, bio (plus the Following marker when shared) and
     * photo. Cheap when nothing changed; at most every 10 minutes unless forced.
     */
    public static void sync(int account, boolean force) {
        final SharedPreferences prefs = prefs(account);
        final long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong(KEY_SYNC_TIME, 0) < 10 * 60 * 1000L) {
            return;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        final TLRPC.Chat channel = TigiPosting.ownChannel(account);
        if (me == null || channel == null || !channel.creator) {
            return;
        }
        prefs.edit().putLong(KEY_SYNC_TIME, now).apply();
        final ConnectionsManager connections = ConnectionsManager.getInstance(account);

        final String name = UserObject.getUserName(me);
        if (!TextUtils.isEmpty(name) && !name.equals(channel.title)) {
            final TLRPC.TL_channels_editTitle req = new TLRPC.TL_channels_editTitle();
            req.channel = MessagesController.getInputChannel(channel);
            req.title = name;
            connections.sendRequest(req, (response, error) -> {
                if (response instanceof TLRPC.Updates) {
                    controller.processUpdates((TLRPC.Updates) response, false);
                }
            });
        }

        final TLRPC.UserFull meFull = controller.getUserFull(me.id);
        final TLRPC.ChatFull channelFull = controller.getChatFull(channel.id);
        if (meFull != null && channelFull != null) {
            String about = meFull.about != null ? meFull.about : "";
            if (sharesFollowing(account)) {
                about = about + encode(TigiPosting.followedChannels(account).size());
            }
            if (!about.equals(channelFull.about != null ? channelFull.about : "")) {
                final TLRPC.TL_messages_editChatAbout req = new TLRPC.TL_messages_editChatAbout();
                req.peer = controller.getInputPeer(-channel.id);
                req.about = about;
                final String written = about;
                connections.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (response instanceof TLRPC.TL_boolTrue) {
                        channelFull.about = written;
                    }
                }));
            }
        }

        // the profile photo, reused as is (no upload) when the channel shows a different one
        if (meFull != null && meFull.profile_photo instanceof TLRPC.TL_photo
            && (channel.photo == null || channel.photo.photo_id != meFull.profile_photo.id)) {
            final TLRPC.TL_inputPhoto photo = new TLRPC.TL_inputPhoto();
            photo.id = meFull.profile_photo.id;
            photo.access_hash = meFull.profile_photo.access_hash;
            photo.file_reference = meFull.profile_photo.file_reference;
            final TLRPC.TL_inputChatPhoto chatPhoto = new TLRPC.TL_inputChatPhoto();
            chatPhoto.id = photo;
            final TLRPC.TL_channels_editPhoto req = new TLRPC.TL_channels_editPhoto();
            req.channel = MessagesController.getInputChannel(channel);
            req.photo = chatPhoto;
            connections.sendRequest(req, (response, error) -> {
                if (response instanceof TLRPC.Updates) {
                    controller.processUpdates((TLRPC.Updates) response, false);
                }
            });
        }
    }
}
