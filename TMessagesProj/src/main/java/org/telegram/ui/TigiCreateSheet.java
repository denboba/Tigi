package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.util.ArrayList;

/**
 * Nilak Create: opens the user's channel as the compose screen (Telegram's message bar with everything it
 * offers; posts drawn as cards), making the channel quietly on first use; and the channel picker live uses.
 */
public class TigiCreateSheet {

    /**
     * Create: the user's channel opened as Nilak's compose screen, Telegram's full message bar (emoji, GIFs,
     * stickers, photos, videos, files, music, polls, location, articles, scheduling) with the posts drawn as cards.
     * The channel is made quietly on first use. initialText (optional) is put in the message field.
     */
    public static void openCompose(BaseFragment fragment) {
        openCompose(fragment, null);
    }

    public static void openCompose(BaseFragment fragment, String initialText) {
        openCompose(fragment, initialText, 0);
    }

    /** action: ChatActivity.TIGI_ACTION_* to open a tool right away (attach menu, poll, location). */
    public static void openCompose(BaseFragment fragment, String initialText, int action) {
        pickChannel(fragment, chat -> {
            final Bundle args = new Bundle();
            args.putLong("chat_id", chat.id);
            args.putInt("tigi_compose", ChatActivity.TIGI_COMPOSE_FEED);
            if (initialText != null) {
                args.putString("tigi_text", initialText);
            }
            if (action != 0) {
                args.putInt("tigi_action", action);
            }
            fragment.presentFragment(new ChatActivity(args));
        });
    }

    /**
     * Nilak Create: one sheet with every way to post, so nothing is hidden behind the channel screen.
     * Post and the tools open the user's channel (made quietly on first use); Article opens Nilak Writer.
     */
    public static void show(BaseFragment fragment) {
        final Context context = fragment != null ? fragment.getParentActivity() : null;
        if (context == null) {
            return;
        }
        final boolean dark = Theme.isCurrentThemeDark();
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(14), dp(6), dp(14), dp(18));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.TigiCreate));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setPadding(dp(8), dp(8), dp(8), dp(2));
        column.addView(title);
        final TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.NilakCreateSubtitle));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        subtitle.setPadding(dp(8), 0, dp(8), dp(14));
        column.addView(subtitle);

        final BottomSheet[] sheet = new BottomSheet[1];
        final ArrayList<Object[]> items = new ArrayList<>();
        items.add(new Object[]{"✏️", R.string.NilakCreatePost, R.string.NilakCreatePostHint, (Runnable) () -> openCompose(fragment, null, 0)});
        items.add(new Object[]{"📖", R.string.NilakCreateArticle, R.string.NilakCreateArticleHint, (Runnable) () -> fragment.presentFragment(new org.nilak.writer.WriterActivity())});
        items.add(new Object[]{"🖼", R.string.NilakCreateMedia, R.string.NilakCreateMediaHint, (Runnable) () -> openCompose(fragment, null, ChatActivity.TIGI_ACTION_ATTACH)});
        items.add(new Object[]{"📊", R.string.NilakCreatePoll, R.string.NilakCreatePollHint, (Runnable) () -> openCompose(fragment, null, ChatActivity.TIGI_ACTION_POLL)});
        items.add(new Object[]{"📍", R.string.NilakCreateLocation, R.string.NilakCreateLocationHint, (Runnable) () -> openCompose(fragment, null, ChatActivity.TIGI_ACTION_LOCATION)});
        items.add(new Object[]{"📎", R.string.NilakCreateFile, R.string.NilakCreateFileHint, (Runnable) () -> openCompose(fragment, null, ChatActivity.TIGI_ACTION_ATTACH)});
        if (BuildVars.TIGI_LIVE) {
            items.add(new Object[]{"🔴", R.string.NilakCreateLive, R.string.NilakCreateLiveHint, (Runnable) () -> TigiLive.start(fragment)});
        }
        items.add(new Object[]{"🗂", R.string.NilakCreateArticles, R.string.NilakCreateArticlesHint, (Runnable) () -> fragment.presentFragment(new org.nilak.writer.WriterHomeActivity())});
        items.add(new Object[]{"🏘", R.string.NilakCreateCommunity, R.string.NilakCreateCommunityHint, (Runnable) () -> NilakCommunitySheets.showNewCommunity(fragment)});

        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
            }
            final Object[] item = items.get(i);
            final Runnable action = (Runnable) item[3];
            final LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.HORIZONTAL);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(12), dp(12), dp(10), dp(12));
            tile.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), dark ? 0xFF221E29 : 0xFFF6F1FD, Theme.multAlpha(TigiStyle.accent(), 0.18f)));
            final TextView icon = new TextView(context);
            icon.setText(org.telegram.messenger.Emoji.replaceEmoji((String) item[0], icon.getPaint().getFontMetricsInt(), false));
            icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Theme.createRoundRectDrawable(dp(14), dark ? 0xFF2F2840 : 0xFFFFFFFF));
            tile.addView(icon, LayoutHelper.createLinear(42, 42));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView name = new TextView(context);
            name.setText(LocaleController.getString((Integer) item[1]));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTypeface(TigiStyle.displayBold());
            name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            name.setSingleLine(true);
            texts.addView(name);
            final TextView hint = new TextView(context);
            hint.setText(LocaleController.getString((Integer) item[2]));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            hint.setMaxLines(2);
            texts.addView(hint);
            tile.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
            tile.setContentDescription(name.getText() + ", " + hint.getText());
            tile.setOnClickListener(v -> {
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
                action.run();
            });
            row.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i % 2 == 0 ? 0 : 5, 0, i % 2 == 0 ? 5 : 0, 0));
        }
        if (items.size() % 2 == 1 && row != null) {
            row.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f, 5, 0, 0, 0));
        }
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
        builder.setCustomView(scroll);
        sheet[0] = builder.create();
        fragment.showDialog(sheet[0]);
    }

    /**
     * One channel for everything, without asking: the user's Tigi channel or the last one used. On the very first
     * post a private personal channel is made quietly; only if that fails does the user see a choice or an error.
     */
    public static void pickChannel(BaseFragment fragment, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onPicked) {
        final int account = fragment.getCurrentAccount();
        final TLRPC.Chat target = TigiPosting.defaultChannel(account);
        if (target != null) {
            onPicked.run(target);
            return;
        }
        createQuietly(fragment, onPicked);
    }

    private static void createQuietly(BaseFragment fragment, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onPicked) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        if (TigiPosting.isCreating()) {
            return; // a second tap while the channel is being made
        }
        final AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.showDelayed(300);
        TigiPosting.createPersonalChannel(account, TigiPosting.suggestedTitle(account), null, chat -> {
            progress.dismiss();
            if (fragment.getParentActivity() != null) {
                onPicked.run(chat);
            }
        }, message -> {
            progress.dismiss();
            if (fragment.getParentActivity() == null) {
                return;
            }
            final ArrayList<TLRPC.Chat> channels = TigiPosting.postableChannels(account);
            if (!channels.isEmpty()) {
                // could not make one (too many channels, limits): post in one the user already runs
                pickFrom(fragment, channels, onPicked);
            } else {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, message).show();
            }
        });
    }

    /** The "Post as" picker over the given channels (also used to choose where to go live). */
    static void pickFrom(BaseFragment fragment, ArrayList<TLRPC.Chat> channels, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onPicked) {
        final int account = fragment.getCurrentAccount();
        if (channels.isEmpty()) {
            askCreatePersonalChannel(fragment, onPicked);
            return;
        }
        if (channels.size() == 1) {
            onPicked.run(channels.get(0));
            return;
        }
        final Context context = fragment.getParentActivity();
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
        final BottomSheet[] sheet = new BottomSheet[1];
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        final long last = TigiPosting.lastChannel(account);
        for (int i = 0; i < channels.size(); i++) {
            final TLRPC.Chat chat = channels.get(i);
            final String subtitle = chat.id == last ? LocaleController.getString(R.string.TigiPostLastUsed)
                : (chat.creator ? LocaleController.getString(R.string.ChannelCreator) : LocaleController.getString(R.string.ChannelAdmin));
            list.addView(channelRow(context, account, chat, subtitle, v -> {
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
                onPicked.run(chat);
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));
        }
        final TLRPC.Chat own = MessagesController.getInstance(account).getChat(MessagesController.getMainSettings(account).getLong("tigi_own_channel", 0));
        if (!TigiPosting.canPostTo(own)) {
            list.addView(actionRow(context, LocaleController.getString(R.string.TigiPostNewChannel), v -> {
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
                askCreatePersonalChannel(fragment, onPicked);
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        }
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        builder.setTitle(LocaleController.getString(R.string.TigiPostAs), true);
        builder.setCustomView(scroll);
        sheet[0] = builder.create();
        fragment.showDialog(sheet[0]);
    }

    private static View channelRow(Context context, int account, TLRPC.Chat chat, String subtitle, View.OnClickListener onClick) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(onClick);
        row.setContentDescription(chat.title + ", " + subtitle);
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(20));
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(account, chat);
        avatar.setForUserOrChat(chat, avatarDrawable);
        row.addView(avatar, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 0, 16, 0));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(chat.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        final TextView sub = new TextView(context);
        sub.setText(subtitle);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        sub.setSingleLine(true);
        sub.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        texts.addView(title);
        texts.addView(sub);
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 16 : 72, 0, LocaleController.isRTL ? 72 : 16, 0));
        return row;
    }

    private static View actionRow(Context context, String text, View.OnClickListener onClick) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(onClick);
        final ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.msg_channel_create);
        icon.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2), android.graphics.PorterDuff.Mode.SRC_IN));
        row.addView(icon, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 0, 16, 0));
        final TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
        label.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        row.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 16 : 72, 0, LocaleController.isRTL ? 72 : 16, 0));
        return row;
    }

    /** First post without a channel: explain, let the user name it, private unless they tick "public link". */
    static void askCreatePersonalChannel(BaseFragment fragment, org.telegram.messenger.Utilities.Callback<TLRPC.Chat> onCreated) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        final TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.TigiOwnChannelInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));

        final EditTextBoldCursor titleField = new EditTextBoldCursor(context);
        titleField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleField.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleField.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        titleField.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        titleField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleField.setBackground(null);
        titleField.setSingleLine(true);
        titleField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleField.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        titleField.setHint(LocaleController.getString(R.string.EnterChannelName));
        titleField.setText(TigiPosting.suggestedTitle(account));
        titleField.setPadding(0, dp(8), 0, dp(8));
        content.addView(titleField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));

        final String username = TigiPosting.suggestedUsername(account);
        final CheckBoxCell publicCell;
        if (username != null) {
            publicCell = new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, fragment.getResourceProvider());
            publicCell.setBackground(Theme.getSelectorDrawable(false));
            publicCell.setText(LocaleController.formatString(R.string.TigiOwnChannelPublic, username), "", false, false);
            publicCell.setOnClickListener(v -> publicCell.setChecked(!publicCell.isChecked(), true));
            content.addView(publicCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 0));
        } else {
            publicCell = null;
        }

        new AlertDialog.Builder(context, fragment.getResourceProvider())
            .setTitle(LocaleController.getString(R.string.TigiOwnChannelTitleDialog))
            .setView(content)
            .setPositiveButton(LocaleController.getString(R.string.Create), (dialog, which) -> {
                final String title = titleField.getText() != null ? titleField.getText().toString().trim() : "";
                final String link = publicCell != null && publicCell.isChecked() ? username : null;
                final AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
                progress.setCanCancel(false);
                progress.show();
                TigiPosting.createPersonalChannel(account, title, link, chat -> {
                    progress.dismiss();
                    if (fragment.getParentActivity() != null) {
                        onCreated.run(chat);
                    }
                }, message -> {
                    progress.dismiss();
                    if (fragment.getParentActivity() != null) {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, message).show();
                    }
                });
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show();
    }

}
