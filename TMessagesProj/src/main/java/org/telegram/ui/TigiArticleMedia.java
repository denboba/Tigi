package org.telegram.ui;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where an article's photos and videos live: not inside the article file, but as ordinary Telegram messages in the
 * writer's media channel, a public channel made quietly for this (archived, never shown in feeds), and referenced
 * from the article as https://t.me/&lt;channel&gt;/&lt;id&gt;. Readers then download only the text; photos load as they
 * scroll and videos stream, and media stay deduplicated and cached by Telegram like any other post.
 *
 * Each upload carries a one-off caption token, so the composer can match the server's answer to the photo it
 * inserted, even after the app was closed mid-upload ({@link #resolvePending}).
 */
public final class TigiArticleMedia {

    private static final String KEY_STORE = "nilak_article_media_store";
    private static final String TOKEN_PREFIX = "nilak-media:";
    private static final Pattern TME = Pattern.compile("^https?://t\\.me/([A-Za-z0-9_]{4,32})/(\\d+)$");

    private static boolean creating;
    private static final ArrayList<Utilities.Callback<TLRPC.Chat>> waiting = new ArrayList<>();
    /** token → callback with the t.me link (or null when the upload failed) */
    private static final HashMap<String, Utilities.Callback<String>> pending = new HashMap<>();
    private static NotificationCenter.NotificationCenterDelegate observer;
    private static int observerAccount = -1;

    private TigiArticleMedia() {
    }

    private static SharedPreferences prefs(int account) {
        return MessagesController.getMainSettings(account);
    }

    /** The writer's media channel, if made and public. */
    public static TLRPC.Chat store(int account) {
        final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(prefs(account).getLong(KEY_STORE, 0));
        return chat != null && TigiPosting.canPostTo(chat) && !TextUtils.isEmpty(ChatObject.getPublicUsername(chat)) ? chat : null;
    }

    /** Makes the media channel once (public, archived); onDone gets null when that is not possible. */
    public static void ensureStore(int account, Utilities.Callback<TLRPC.Chat> onDone) {
        final TLRPC.Chat existing = store(account);
        if (existing != null) {
            onDone.run(existing);
            return;
        }
        waiting.add(onDone);
        if (creating) {
            return;
        }
        creating = true;
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        final String name = self != null ? UserObject.getUserName(self) : "Nilak";
        final TLRPC.TL_channels_createChannel req = new TLRPC.TL_channels_createChannel();
        req.broadcast = true;
        req.title = LocaleController.formatString(R.string.NilakMediaStoreTitle, name);
        req.about = LocaleController.getString(R.string.NilakMediaStoreAbout);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
            }
            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.Chat chat = null;
                if (response instanceof TLRPC.Updates && ((TLRPC.Updates) response).chats != null) {
                    MessagesController.getInstance(account).putChats(((TLRPC.Updates) response).chats, false);
                    for (TLRPC.Chat c : ((TLRPC.Updates) response).chats) {
                        if (c.broadcast) {
                            chat = c;
                        }
                    }
                }
                if (chat == null) {
                    finishCreate(account, null);
                    return;
                }
                prefs(account).edit().putLong(KEY_STORE, chat.id).apply();
                // a storage room, not something to read: out of the chat list
                MessagesController.getInstance(account).addDialogToFolder(-chat.id, 1, -1, 0);
                final ArrayList<String> names = new ArrayList<>();
                final String base = TigiPosting.suggestedUsername(account);
                final String root = base != null ? base.replace(TigiPosting.NILAK_SUFFIX, "") : "nilak";
                for (String candidate : new String[]{root + "_media", root + "_nilakmedia", "nilakmedia_" + (self != null ? self.id : System.currentTimeMillis() % 100000)}) {
                    if (candidate.length() >= 5 && candidate.length() <= 32) {
                        names.add(candidate.toLowerCase(Locale.US));
                    }
                }
                final TLRPC.Chat made = chat;
                claim(account, made, names, 0);
            });
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void claim(int account, TLRPC.Chat chat, ArrayList<String> names, int index) {
        if (index >= names.size()) {
            finishCreate(account, null);
            return;
        }
        final TLRPC.TL_channels_updateUsername req = new TLRPC.TL_channels_updateUsername();
        req.channel = MessagesController.getInputChannel(chat);
        req.username = names.get(index);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_boolTrue) {
                final TLRPC.Chat current = MessagesController.getInstance(account).getChat(chat.id);
                if (current != null) {
                    current.username = names.get(index);
                    final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                    chats.add(current);
                    MessagesController.getInstance(account).putChats(chats, false);
                }
                finishCreate(account, current);
            } else if (error != null && ("USERNAME_OCCUPIED".equals(error.text) || "USERNAME_PURCHASE_AVAILABLE".equals(error.text) || "USERNAME_INVALID".equals(error.text))) {
                claim(account, chat, names, index + 1);
            } else {
                // e.g. too many public channels: no public media channel, articles carry their media inside
                finishCreate(account, null);
            }
        }));
    }

    private static void finishCreate(int account, TLRPC.Chat chat) {
        creating = false;
        final ArrayList<Utilities.Callback<TLRPC.Chat>> callbacks = new ArrayList<>(waiting);
        waiting.clear();
        for (Utilities.Callback<TLRPC.Chat> callback : callbacks) {
            callback.run(chat);
        }
    }

    public static String newToken() {
        return TOKEN_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static boolean isPending(String ref) {
        return ref != null && ref.startsWith(TOKEN_PREFIX);
    }

    /** Uploads a photo or video (local file) to the media channel; onLink gets the t.me link. */
    public static void upload(int account, TLRPC.Chat store, String path, boolean video, String token, Utilities.Callback<String> onLink) {
        listen(account);
        pending.put(token, onLink);
        final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = path;
        info.isVideo = video;
        info.caption = token;
        final ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
        media.add(info);
        SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), media, -store.id, null, null, null, null,
            false, false, null, false, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
    }

    private static void listen(int account) {
        if (observer != null && observerAccount == account) {
            return;
        }
        if (observer != null) {
            NotificationCenter.getInstance(observerAccount).removeObserver(observer, NotificationCenter.messageReceivedByServer);
        }
        observerAccount = account;
        observer = (id, acc, args) -> {
            if (id != NotificationCenter.messageReceivedByServer || args.length < 3 || !(args[2] instanceof TLRPC.Message)) {
                return;
            }
            final TLRPC.Message message = (TLRPC.Message) args[2];
            final String caption = message.message;
            if (caption == null || !caption.startsWith(TOKEN_PREFIX)) {
                return;
            }
            final Utilities.Callback<String> callback = pending.remove(caption.trim());
            final TLRPC.Chat store = store(acc);
            if (callback != null && store != null) {
                callback.run("https://t.me/" + ChatObject.getPublicUsername(store) + "/" + message.id);
            }
        };
        NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.messageReceivedByServer);
    }

    /** After a restart: finds an upload by its token in the media channel; onLink gets the link or null. */
    public static void resolvePending(int account, String token, Utilities.Callback<String> onLink) {
        final TLRPC.Chat store = store(account);
        if (store == null) {
            onLink.run(null);
            return;
        }
        final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(account).getInputPeer(-store.id);
        req.q = token;
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        req.limit = 1;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages && !((TLRPC.messages_Messages) response).messages.isEmpty()) {
                final TLRPC.Message message = ((TLRPC.messages_Messages) response).messages.get(0);
                if (token.equals(message.message != null ? message.message.trim() : null)) {
                    onLink.run("https://t.me/" + ChatObject.getPublicUsername(store) + "/" + message.id);
                    return;
                }
            }
            onLink.run(null);
        }));
    }

    /* reading */

    public static boolean isTelegramRef(String ref) {
        return ref != null && TME.matcher(ref).matches();
    }

    public static boolean isWebImage(String ref) {
        if (ref == null || !(ref.startsWith("https://") || ref.startsWith("http://")) || isTelegramRef(ref)) {
            return false;
        }
        final String lower = ref.toLowerCase(Locale.ROOT);
        final int query = lower.indexOf('?');
        final String path = query > 0 ? lower.substring(0, query) : lower;
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".gif");
    }

    /**
     * Loads the Telegram messages behind t.me links (public channels, no joining needed): resolves each channel
     * once and fetches its messages in one call. onDone gets ref → message for those found.
     */
    public static void loadMessages(int account, ArrayList<String> refs, Utilities.Callback<HashMap<String, MessageObject>> onDone) {
        final HashMap<String, ArrayList<Integer>> byChannel = new HashMap<>();
        for (String ref : refs) {
            final Matcher m = TME.matcher(ref);
            if (m.matches()) {
                final String username = m.group(1).toLowerCase(Locale.ROOT);
                ArrayList<Integer> ids = byChannel.get(username);
                if (ids == null) {
                    byChannel.put(username, ids = new ArrayList<>());
                }
                ids.add(Utilities.parseInt(m.group(2)));
            }
        }
        final HashMap<String, MessageObject> result = new HashMap<>();
        final ArrayList<String> names = new ArrayList<>(byChannel.keySet());
        if (names.isEmpty()) {
            onDone.run(result);
            return;
        }
        final int[] left = {names.size()};
        for (String username : names) {
            resolve(account, username, chat -> {
                if (chat == null) {
                    if (--left[0] == 0) {
                        onDone.run(result);
                    }
                    return;
                }
                final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
                req.channel = MessagesController.getInputChannel(chat);
                req.id.addAll(byChannel.get(username));
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (response instanceof TLRPC.messages_Messages) {
                        final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                        MessagesController.getInstance(account).putUsers(res.users, false);
                        MessagesController.getInstance(account).putChats(res.chats, false);
                        for (TLRPC.Message message : res.messages) {
                            if (message instanceof TLRPC.TL_messageEmpty) {
                                continue;
                            }
                            result.put("https://t.me/" + username + "/" + message.id, new MessageObject(account, message, false, true));
                        }
                    }
                    if (--left[0] == 0) {
                        onDone.run(result);
                    }
                }));
            });
        }
    }

    private static void resolve(int account, String username, Utilities.Callback<TLRPC.Chat> onDone) {
        final org.telegram.tgnet.TLObject cached = MessagesController.getInstance(account).getUserOrChat(username);
        if (cached instanceof TLRPC.Chat) {
            onDone.run((TLRPC.Chat) cached);
            return;
        }
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                final TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                onDone.run(res.chats.isEmpty() ? null : res.chats.get(0));
            } else {
                onDone.run(null);
            }
        }));
    }

    /** Normalised key for a t.me ref (lower-case channel name), matching {@link #loadMessages} keys. */
    public static String key(String ref) {
        final Matcher m = TME.matcher(ref);
        return m.matches() ? "https://t.me/" + m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2) : ref;
    }
}
