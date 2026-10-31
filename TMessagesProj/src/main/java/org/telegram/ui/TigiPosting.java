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

    /** Channels the user follows (joined broadcast channels from the chat list), without the own Tigi channel. */
    public static ArrayList<TLRPC.Chat> followedChannels(int account) {
        final MessagesController controller = MessagesController.getInstance(account);
        final long ownId = prefs(account).getLong(KEY_OWN, 0);
        final ArrayList<TLRPC.Chat> out = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> dialogs = controller.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog == null || !DialogObject.isChatDialog(dialog.id)) {
                continue;
            }
            final TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (chat != null && chat.id != ownId && ChatObject.isChannelAndNotMegaGroup(chat) && !ChatObject.isNotInChat(chat) && !chat.monoforum && !out.contains(chat)) {
                out.add(chat);
            }
        }
        return out;
    }

    private static final ArrayList<TLRPC.Chat>[] suggested = new ArrayList[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] suggestedTime = new long[UserConfig.MAX_ACCOUNT_COUNT];

    /**
     * Public channels Telegram recommends to this account (channels.getChannelRecommendations without a channel),
     * for people who follow none yet. Cached for 30 minutes; an empty list when there are none or on error.
     */
    /** After the content country or language changed: the next suggestions are fetched and ranked again. */
    public static void clearSuggestedCache(int account) {
        suggested[account] = null;
        suggestedTime[account] = 0;
    }

    public static void loadSuggestedChannels(int account, Utilities.Callback<ArrayList<TLRPC.Chat>> onDone) {
        if (suggested[account] != null && System.currentTimeMillis() - suggestedTime[account] < 30 * 60 * 1000L) {
            onDone.run(new ArrayList<>(suggested[account]));
            return;
        }
        final TLRPC.TL_channels_getChannelRecommendations req = new TLRPC.TL_channels_getChannelRecommendations();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            final ArrayList<TLRPC.Chat> out = new ArrayList<>();
            if (response instanceof TLRPC.messages_Chats) {
                final ArrayList<TLRPC.Chat> chats = ((TLRPC.messages_Chats) response).chats;
                MessagesController.getInstance(account).putChats(chats, false);
                for (int i = 0; i < chats.size(); i++) {
                    final TLRPC.Chat chat = chats.get(i);
                    if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !chat.restricted) {
                        out.add(chat);
                    }
                }
                // the chosen language's channels first
                TigiRegion.rank(out);
                suggested[account] = new ArrayList<>(out);
                suggestedTime[account] = System.currentTimeMillis();
            }
            onDone.run(out);
        }));
    }

    /**
     * "For you": channels Telegram recommends next to each seed channel (channels.getChannelRecommendations with a
     * channel, one request at a time), then the general suggestions. Without channels the user already follows.
     */
    public static void loadRecommendedChannels(int account, ArrayList<TLRPC.Chat> seeds, Utilities.Callback<ArrayList<TLRPC.Chat>> onDone) {
        final ArrayList<TLRPC.Chat> out = new ArrayList<>();
        final java.util.HashSet<Long> ids = new java.util.HashSet<>();
        final Utilities.Callback<ArrayList<TLRPC.Chat>> add = chats -> {
            for (int i = 0; i < chats.size(); i++) {
                final TLRPC.Chat chat = chats.get(i);
                if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !chat.restricted && ChatObject.isNotInChat(chat) && ids.add(chat.id)) {
                    out.add(chat);
                }
            }
        };
        final int[] next = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (next[0] >= seeds.size()) {
                loadSuggestedChannels(account, suggested -> {
                    add.run(suggested);
                    TigiRegion.rank(out);
                    onDone.run(out);
                });
                return;
            }
            final TLRPC.Chat seed = seeds.get(next[0]++);
            final TLRPC.TL_channels_getChannelRecommendations req = new TLRPC.TL_channels_getChannelRecommendations();
            req.flags |= 1;
            req.channel = MessagesController.getInputChannel(seed);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Chats) {
                    final ArrayList<TLRPC.Chat> chats = ((TLRPC.messages_Chats) response).chats;
                    MessagesController.getInstance(account).putChats(chats, false);
                    add.run(chats);
                }
                if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                    next[0] = seeds.size();
                }
                step[0].run();
            }));
        };
        step[0].run();
    }

    /** Story posting is not switched off for the account (it may still need Premium; the server decides). */
    public static boolean storiesOffered(int account) {
        return !"disabled".equals(MessagesController.getInstance(account).storiesPosting);
    }

    /**
     * Opens Telegram's story camera after asking the server whether this account may post a story now, so the
     * answer is the real one (Telegram's local "premium only" setting can be stale). A refusal is explained.
     */
    public static void openStoryRecorder(org.telegram.ui.ActionBar.BaseFragment fragment) {
        final int account = fragment.getCurrentAccount();
        if (fragment.getParentActivity() == null) {
            return;
        }
        final org.telegram.tgnet.tl.TL_stories.TL_stories_canSendStory req = new org.telegram.tgnet.tl.TL_stories.TL_stories_canSendStory();
        req.peer = MessagesController.getInstance(account).getInputPeer(UserConfig.getInstance(account).getClientUserId());
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (error == null) {
                org.telegram.ui.Stories.recorder.StoryRecorder.getInstance(fragment.getParentActivity(), account).open(null);
                return;
            }
            final String text;
            if ("PREMIUM_ACCOUNT_REQUIRED".equalsIgnoreCase(error.text)) {
                text = LocaleController.getString(R.string.TigiStoryNeedsPremium);
            } else if (error.text != null && error.text.startsWith("STORIES_TOO_MUCH")) {
                text = LocaleController.getString(R.string.TigiStoryTooMany);
            } else {
                text = errorText(error);
            }
            org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, text).show();
        }), ConnectionsManager.RequestFlagDoNotWaitFloodWait);
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

    private static final String KEY_PERSONAL = "nilak_personal_channel_set";
    private static final String KEY_IDENTITY = "nilak_identity_done";
    /** Profile channels made by Nilak end with this, so Nilak can tell a person's profile from other channels. */
    public static final String NILAK_SUFFIX = "_nilak";

    public static boolean isNilakProfile(TLRPC.Chat chat) {
        final String username = chat != null ? ChatObject.getPublicUsername(chat) : null;
        return username != null && username.toLowerCase(Locale.US).endsWith(NILAK_SUFFIX);
    }

    /**
     * The profile channel as the person's identity, quietly: their name as its title and a public link ending in
     * _nilak (abdi_nilak, then abdi_nilak123, then nilak_<id>) so Nilak can recognise profiles. Done once.
     */
    public static void ensureNilakIdentity(int account) {
        final TLRPC.Chat own = ownChannel(account);
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (own == null || self == null || prefs(account).getLong(KEY_IDENTITY, 0) == own.id) {
            return;
        }
        prefs(account).edit().putLong(KEY_IDENTITY, own.id).apply();
        final String name = UserObject.getUserName(self);
        if (!TextUtils.isEmpty(name) && !name.equals(own.title)) {
            MessagesController.getInstance(account).changeChatTitle(own.id, name);
        }
        if (TextUtils.isEmpty(ChatObject.getPublicUsername(own))) {
            final ArrayList<String> candidates = new ArrayList<>();
            final String base = suggestedUsername(account);
            if (base != null) {
                candidates.add(base);
                final String numbered = base + (self.id % 1000);
                if (numbered.length() <= 32) {
                    candidates.add(numbered);
                }
            }
            candidates.add("nilak_" + self.id);
            claimUsername(account, own, candidates, 0);
        }
    }

    private static void claimUsername(int account, TLRPC.Chat chat, ArrayList<String> candidates, int index) {
        if (index >= candidates.size()) {
            return;
        }
        setPublicUsername(account, chat, candidates.get(index), () -> {}, message -> {
            // taken: the next one; other refusals (too many public channels) keep the profile private
            if (message.equals(LocaleController.formatString(R.string.TigiPostLinkTaken, candidates.get(index)))) {
                claimUsername(account, chat, candidates, index + 1);
            }
        });
    }

    /**
     * The own channel shown on the user's Telegram profile as their personal channel, so other people opening the
     * profile see the posts and can follow. Done once, and never over a personal channel the user chose themselves.
     */
    public static void setPersonalChannelOnce(int account) {
        final TLRPC.Chat own = ownChannel(account);
        if (own == null || prefs(account).getLong(KEY_PERSONAL, 0) == own.id) {
            return;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null) {
            return;
        }
        final TLRPC.UserFull full = controller.getUserFull(self.id);
        if (full == null) {
            controller.loadFullUser(self, 0, true, loaded -> AndroidUtilities.runOnUIThread(() -> {
                if (loaded != null) {
                    setPersonalChannelOnce(account);
                }
            }));
            return;
        }
        prefs(account).edit().putLong(KEY_PERSONAL, own.id).apply();
        if (full.personal_channel_id != 0) {
            // the user picked one already
            return;
        }
        final org.telegram.tgnet.tl.TL_account.updatePersonalChannel req = new org.telegram.tgnet.tl.TL_account.updatePersonalChannel();
        req.channel = MessagesController.getInputChannel(own);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error == null) {
                full.flags |= 64;
                full.personal_channel_id = own.id;
                full.personal_channel_message = 0;
            } else {
                prefs(account).edit().remove(KEY_PERSONAL).apply();
            }
        }));
    }

    public static void setLastChannel(int account, long chatId) {
        prefs(account).edit().putLong(KEY_LAST, chatId).apply();
    }

    public static long lastChannel(int account) {
        return prefs(account).getLong(KEY_LAST, 0);
    }

    public static String suggestedTitle(int account) {
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        // the profile channel carries the person's own name
        final String name = self != null ? UserObject.getUserName(self) : null;
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
        String candidate = base + NILAK_SUFFIX;
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
                // comments on from the first post; a failure here only means no comments yet
                createDiscussionGroup(account, chat, group -> {}, message -> {});
                setPersonalChannelOnce(account);
                if (TextUtils.isEmpty(publicUsername)) {
                    ensureNilakIdentity(account);
                }
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

    /**
     * Comments for a channel the user owns: a discussion group made quietly next to it, linked with
     * channels.setDiscussionGroup and moved to the archive. Posts published from now on get comments.
     */
    public static void createDiscussionGroup(int account, TLRPC.Chat channel, Utilities.Callback<TLRPC.Chat> onDone, Utilities.Callback<String> onError) {
        final TLRPC.TL_channels_createChannel req = new TLRPC.TL_channels_createChannel();
        req.megagroup = true;
        req.title = LocaleController.formatString(R.string.NilakCommentsGroupTitle, channel.title);
        req.about = LocaleController.getString(R.string.NilakCommentsGroupAbout);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (error != null || !(response instanceof TLRPC.Updates) || ((TLRPC.Updates) response).chats.isEmpty()) {
                    onError.run(errorText(error));
                    return;
                }
                final TLRPC.Updates updates = (TLRPC.Updates) response;
                MessagesController.getInstance(account).putChats(updates.chats, false);
                TLRPC.Chat group = null;
                for (int i = 0; i < updates.chats.size(); i++) {
                    if (updates.chats.get(i).megagroup) {
                        group = updates.chats.get(i);
                    }
                }
                if (group == null) {
                    onError.run(LocaleController.getString(R.string.TigiPostErrorGeneric));
                    return;
                }
                final TLRPC.Chat finalGroup = group;
                final TLRPC.TL_channels_setDiscussionGroup link = new TLRPC.TL_channels_setDiscussionGroup();
                link.broadcast = MessagesController.getInputChannel(channel);
                link.group = MessagesController.getInputChannel(group);
                ConnectionsManager.getInstance(account).sendRequest(link, (linked, linkError) -> AndroidUtilities.runOnUIThread(() -> {
                    if (!(linked instanceof TLRPC.TL_boolTrue)) {
                        onError.run(errorText(linkError));
                        return;
                    }
                    // it is how comments work, not a group the user made: keep it out of the chat list
                    MessagesController.getInstance(account).addDialogToFolder(-finalGroup.id, 1, -1, 0);
                    MessagesController.getInstance(account).loadFullChat(channel.id, 0, true);
                    onDone.run(finalGroup);
                }));
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
