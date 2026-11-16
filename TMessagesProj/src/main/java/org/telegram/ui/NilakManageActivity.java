package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * "Your channels, groups and bots": everything the person created or administers, on Telegram's own list cells, with
 * New channel, New group and New bot. A tap offers Open, Settings (Telegram's edit screen) and Delete or Leave
 * (Telegram's own confirmation); bots are created and deleted with @BotFather, as in Telegram.
 */
public class NilakManageActivity extends UniversalFragment {

    private static final int BUTTON_NEW_CHANNEL = 1;
    private static final int BUTTON_NEW_GROUP = 2;
    private static final int BUTTON_NEW_BOT = 3;
    private static final String BOT_FATHER_LINK = "https://t.me/BotFather";

    private final ArrayList<TLRPC.User> bots = new ArrayList<>();
    private boolean areBotsRequested;

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        loadBots();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // A chat created, deleted or left meanwhile shows up here
        update();
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.NilakManage);
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void loadBots() {
        if (areBotsRequested) {
            return;
        }
        areBotsRequested = true;
        ConnectionsManager.getInstance(currentAccount).sendRequest(new TL_bots.getAdminedBots(), (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(res instanceof Vector)) {
                return;
            }
            bots.clear();
            for (Object object : ((Vector) res).objects) {
                if (object instanceof TLRPC.User) {
                    bots.add((TLRPC.User) object);
                }
            }
            getMessagesController().putUsers(bots, false);
            update();
        }));
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(BUTTON_NEW_CHANNEL, R.drawable.msg_channel_create, LocaleController.getString(R.string.NewChannel)).accent());
        items.add(UItem.asButton(BUTTON_NEW_GROUP, R.drawable.msg_groups_create, LocaleController.getString(R.string.NewGroup)).accent());
        items.add(UItem.asButton(BUTTON_NEW_BOT, R.drawable.msg_bots, LocaleController.getString(R.string.NilakNewBot)).accent());
        items.add(UItem.asShadow(null));

        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
        final ArrayList<TLRPC.Chat> groups = new ArrayList<>();
        final ArrayList<TLRPC.Chat> communities = new ArrayList<>();
        final MessagesController controller = getMessagesController();
        for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
            if (dialog.id >= 0) {
                continue;
            }
            final TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (!isManaged(chat)) {
                continue;
            }
            if (ChatObject.isCommunity(chat)) {
                communities.add(chat);
            } else if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                channels.add(chat);
            } else {
                groups.add(chat);
            }
        }
        addSection(items, R.string.FilterChannels, channels);
        addSection(items, R.string.FilterGroups, groups);
        addSection(items, R.string.NilakCommunities, communities);
        if (!bots.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.FilterBots)));
            for (TLRPC.User bot : bots) {
                items.add(UItem.asFilterChat(true, bot.id));
            }
            items.add(UItem.asShadow(null));
        }
    }

    /** How many channels, groups and communities the person runs, from the loaded chat list */
    public static int countManaged(int account) {
        final MessagesController controller = MessagesController.getInstance(account);
        int count = 0;
        for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
            if (dialog.id < 0 && isManaged(controller.getChat(-dialog.id))) {
                count++;
            }
        }
        return count;
    }

    /** Chats the person created or administers and is still in */
    private static boolean isManaged(TLRPC.Chat chat) {
        return chat != null && !ChatObject.isNotInChat(chat) && chat.migrated_to == null && !chat.deactivated
            && !ChatObject.isMonoForum(chat) && (chat.creator || chat.admin_rights != null);
    }

    private static void addSection(ArrayList<UItem> items, int title, ArrayList<TLRPC.Chat> chats) {
        if (chats.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(title)));
        for (TLRPC.Chat chat : chats) {
            items.add(UItem.asFilterChat(true, -chat.id));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_NEW_CHANNEL) {
            openNewChannel();
        } else if (item.id == BUTTON_NEW_GROUP) {
            presentFragment(new GroupCreateActivity(new Bundle()));
        } else if (item.id == BUTTON_NEW_BOT) {
            Browser.openUrl(getParentActivity(), BOT_FATHER_LINK);
        } else if (item.viewType == UniversalAdapter.VIEW_TYPE_FILTER_CHAT) {
            if (item.dialogId > 0) {
                showBotOptions(view, getMessagesController().getUser(item.dialogId));
            } else {
                showChatOptions(view, getMessagesController().getChat(-item.dialogId));
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** Telegram's New Channel flow: its introduction the first time, then the channel form */
    private void openNewChannel() {
        final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        if (!BuildVars.DEBUG_VERSION && preferences.getBoolean("channel_intro", false)) {
            final Bundle args = new Bundle();
            args.putInt("step", 0);
            presentFragment(new ChannelCreateActivity(args));
        } else {
            presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE));
            preferences.edit().putBoolean("channel_intro", true).apply();
        }
    }

    private void showChatOptions(View view, TLRPC.Chat chat) {
        if (chat == null) {
            return;
        }
        final boolean isCommunity = ChatObject.isCommunity(chat);
        final boolean isChannel = ChatObject.isChannelAndNotMegaGroup(chat);
        final int leaveText;
        if (chat.creator) {
            leaveText = isChannel ? R.string.ChannelDeleteMenu : R.string.DeleteMegaMenu;
        } else {
            leaveText = isChannel ? R.string.LeaveChannelMenu : R.string.LeaveMegaMenu;
        }
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_openin, LocaleController.getString(R.string.Open), () -> openChat(chat))
            .addIf(ChatObject.canChangeChatInfo(chat) || isCommunity, R.drawable.msg_settings, LocaleController.getString(R.string.ChannelSettings), () -> openSettings(chat))
            .addIf(!isCommunity, R.drawable.msg_delete, LocaleController.getString(leaveText), true, () -> confirmLeave(chat))
            .show();
    }

    private void openChat(TLRPC.Chat chat) {
        if (ChatObject.isCommunity(chat)) {
            NilakCommunitySheets.openCommunity(this, chat.id);
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        presentFragment(new ChatActivity(args));
    }

    private void openSettings(TLRPC.Chat chat) {
        final Bundle args = new Bundle();
        if (ChatObject.isCommunity(chat)) {
            args.putLong("community_id", chat.id);
            presentFragment(new org.telegram.ui.community.CommunityEditActivity(args));
            return;
        }
        args.putLong("chat_id", chat.id);
        presentFragment(new ChatEditActivity(args));
    }

    /** Telegram's own Delete or Leave confirmation; the creator deletes the chat for everyone. */
    private void confirmLeave(TLRPC.Chat chat) {
        AlertsCreator.createClearOrDeleteDialogAlert(this, false, chat, null, false, chat.creator, chat.creator, false, revoke -> {
            getMessagesController().deleteParticipantFromChat(chat.id, getUserConfig().getCurrentUser(), null, revoke, revoke);
            update();
        });
    }

    private void showBotOptions(View view, TLRPC.User bot) {
        if (bot == null) {
            return;
        }
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_openin, LocaleController.getString(R.string.Open), () -> {
                final Bundle args = new Bundle();
                args.putLong("user_id", bot.id);
                presentFragment(new ChatActivity(args));
            })
            .add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> {
                final Bundle args = new Bundle();
                args.putLong("user_id", bot.id);
                presentFragment(new ChatEditActivity(args));
            })
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.DeleteBot), true, () ->
                Browser.openUrl(getParentActivity(), BOT_FATHER_LINK + "?start=deletebot"))
            .show();
    }
}
