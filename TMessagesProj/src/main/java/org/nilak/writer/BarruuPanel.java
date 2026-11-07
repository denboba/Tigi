package org.nilak.writer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.TigiArticle;
import org.telegram.ui.TigiArticleReaderActivity;
import org.telegram.ui.TigiStyle;

import java.util.ArrayList;

/**
 * Barruu (Nilak's own articles) from a chat's attach menu: write a new one for this chat, continue a draft, or
 * read, edit and delete the ones already published here. Works in channels, groups and private chats.
 */
public final class BarruuPanel {

    private final BaseFragment fragment;
    private final long dialogId;
    private final int account;
    private final Context context;
    private final boolean dark = Theme.isCurrentThemeDark();
    private BottomSheet sheet;
    private LinearLayout publishedList;
    private TextView publishedHeader;

    private BarruuPanel(BaseFragment fragment, long dialogId) {
        this.fragment = fragment;
        this.dialogId = dialogId;
        this.account = fragment.getCurrentAccount();
        this.context = fragment.getParentActivity();
    }

    public static void show(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0) {
            return;
        }
        new BarruuPanel(fragment, dialogId).open();
    }

    private void open() {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(4), 0, dp(20));

        final LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(8), dp(20), dp(12));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakBarruu));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        head.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView kind = new TextView(context);
        kind.setText(LocaleController.getString(R.string.NilakBarruuKind));
        kind.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        kind.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        head.addView(kind);
        column.addView(head);

        // write a new one, for this chat
        final LinearLayout write = new LinearLayout(context);
        write.setOrientation(LinearLayout.HORIZONTAL);
        write.setGravity(Gravity.CENTER_VERTICAL);
        write.setPadding(dp(16), dp(16), dp(16), dp(16));
        write.setBackground(TigiStyle.primaryPill(20));
        final TextView pen = new TextView(context);
        pen.setText(org.telegram.messenger.Emoji.replaceEmoji("✍️", pen.getPaint().getFontMetricsInt(), false));
        pen.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        pen.setGravity(Gravity.CENTER);
        pen.setBackground(Theme.createRoundRectDrawable(dp(15), 0x38FFFFFF));
        write.addView(pen, LayoutHelper.createLinear(46, 46));
        final LinearLayout writeTexts = new LinearLayout(context);
        writeTexts.setOrientation(LinearLayout.VERTICAL);
        final TextView writeTitle = new TextView(context);
        writeTitle.setText(LocaleController.getString(R.string.NilakBarruuWrite));
        writeTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        writeTitle.setTypeface(TigiStyle.displayExtraBold());
        writeTitle.setTextColor(0xFFFFFFFF);
        writeTexts.addView(writeTitle);
        final TextView writeHint = new TextView(context);
        writeHint.setText(LocaleController.getString(R.string.NilakBarruuWriteHint));
        writeHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        writeHint.setTextColor(0xE6FFFFFF);
        writeTexts.addView(writeHint);
        write.addView(writeTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
        write.setContentDescription(writeTitle.getText());
        write.setOnClickListener(v -> {
            dismiss();
            final WriterDoc doc = new WriterDoc();
            doc.targetDialogId = dialogId;
            fragment.presentFragment(new WriterActivity(doc, null));
        });
        column.addView(write, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 14));

        // drafts
        final ArrayList<WriterDoc> drafts = WriterDrafts.list(account);
        if (!drafts.isEmpty()) {
            column.addView(section(LocaleController.getString(R.string.NilakWriterDrafts)));
            for (int i = 0; i < drafts.size() && i < 5; i++) {
                final WriterDoc doc = drafts.get(i);
                final String when = LocaleController.formatString(R.string.NilakBarruuDraftLine,
                    DateUtils.getRelativeTimeSpanString(doc.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), doc.minutes());
                final View row = row(TextUtils.isEmpty(doc.title.trim()) ? LocaleController.getString(R.string.NilakWriterUntitled) : doc.title.trim(), when, "📝", null, null);
                row.setOnClickListener(v -> {
                    dismiss();
                    if (doc.targetDialogId == 0 && doc.editMessageId == 0) {
                        doc.targetDialogId = dialogId;
                    }
                    fragment.presentFragment(new WriterActivity(doc, null));
                });
                column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 74, 16, 0, 16, 10));
            }
        }

        // published here
        publishedHeader = section(LocaleController.getString(R.string.NilakBarruuPublishedHere));
        publishedHeader.setVisibility(View.GONE);
        column.addView(publishedHeader);
        publishedList = new LinearLayout(context);
        publishedList.setOrientation(LinearLayout.VERTICAL);
        column.addView(publishedList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        // the lilac page inside the sheet, white cards on it
        scroll.setBackground(Theme.createRoundRectDrawable(dp(18), 0, dark ? 0xFF1A1326 : 0xFFF7F2FD));
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
        builder.setCustomView(scroll);
        sheet = builder.create();
        fragment.showDialog(sheet);
        loadPublished();
    }

    private void dismiss() {
        if (sheet != null) {
            sheet.dismiss();
        }
    }

    private TextView section(String text) {
        final TextView view = new TextView(context);
        view.setText(text.toUpperCase());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTypeface(TigiStyle.displayExtraBold());
        view.setLetterSpacing(0.05f);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        view.setPadding(dp(20), dp(4), dp(20), dp(8));
        return view;
    }

    /** A row: a cover (or an emoji), a serif title, a line of details and ⋯. */
    private LinearLayout row(String title, String meta, String emoji, MessageObject message, View.OnClickListener onMore) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), dark ? 0xFF241B34 : 0xFFFFFFFF, Theme.multAlpha(TigiStyle.accent(), 0.15f)));
        row.setElevation(dp(1));
        final TLRPC.Document document = message != null ? message.getDocument() : null;
        final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320) : null;
        if (thumb != null && !(thumb instanceof TLRPC.TL_photoStrippedSize)) {
            final BackupImageView cover = new BackupImageView(context);
            cover.setRoundRadius(dp(18), 0, dp(18), 0);
            cover.setImage(ImageLocation.getForDocument(thumb, document), "160_160", (android.graphics.drawable.Drawable) null, message);
            row.addView(cover, LayoutHelper.createLinear(74, 74));
        } else {
            final TextView icon = new TextView(context);
            icon.setText(org.telegram.messenger.Emoji.replaceEmoji(emoji, icon.getPaint().getFontMetricsInt(), false));
            icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Theme.createRoundRectDrawable(dp(18), 0, 0, dp(18), Theme.multAlpha(TigiStyle.accent(), dark ? 0.2f : 0.1f)));
            row.addView(icon, LayoutHelper.createLinear(74, 74));
        }
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        name.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD));
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        final TextView details = new TextView(context);
        details.setText(meta);
        details.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        details.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        details.setSingleLine(true);
        details.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(details, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 8, 0));
        if (onMore != null) {
            final TextView more = new TextView(context);
            more.setText("⋯");
            more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            more.setGravity(Gravity.CENTER);
            more.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            more.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
            more.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            more.setOnClickListener(onMore);
            row.addView(more, LayoutHelper.createLinear(44, 44, 0, 0, 6, 0));
        }
        return row;
    }

    /** The Barruu files in this chat (yours in groups and private chats), newest first. */
    private void loadPublished() {
        final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = fragment.getMessagesController().getInputPeer(dialogId);
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterDocument();
        req.limit = 60;
        final TLRPC.Chat chat = dialogId < 0 ? fragment.getMessagesController().getChat(-dialogId) : null;
        if (chat == null || !org.telegram.messenger.ChatObject.isChannelAndNotMegaGroup(chat)) {
            req.from_id = fragment.getMessagesController().getInputPeer(UserConfig.getInstance(account).getClientUserId());
            req.flags |= 1;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Messages) || publishedList == null || sheet == null || !sheet.isShowing()) {
                return;
            }
            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            fragment.getMessagesController().putUsers(res.users, false);
            fragment.getMessagesController().putChats(res.chats, false);
            publishedList.removeAllViews();
            int shown = 0;
            for (TLRPC.Message raw : res.messages) {
                final MessageObject message = new MessageObject(account, raw, false, false);
                if (!TigiArticle.isArticle(message)) {
                    continue;
                }
                final int minutes = TigiArticle.minutesOf(message);
                final StringBuilder meta = new StringBuilder();
                if (minutes > 0) {
                    meta.append(LocaleController.formatString(R.string.NilakReadMinutes, minutes)).append(" · ");
                }
                meta.append(LocaleController.formatDateAudio(raw.date, false));
                final View row = row(TigiArticle.titleOf(message), meta.toString(), "📖", message, v -> menu(message));
                row.setOnClickListener(v -> {
                    dismiss();
                    TigiArticleReaderActivity.open(fragment, message);
                });
                publishedList.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 74, 16, 0, 16, 10));
                shown++;
            }
            publishedHeader.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        }));
    }

    private void menu(MessageObject message) {
        final boolean mine = message.isOutOwner() || message.messageOwner.post && dialogId < 0
            && org.telegram.messenger.ChatObject.canPost(fragment.getMessagesController().getChat(-dialogId));
        final ArrayList<CharSequence> items = new ArrayList<>();
        final ArrayList<Integer> icons = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        items.add(LocaleController.getString(R.string.NilakBarruuRead));
        icons.add(R.drawable.msg_openin);
        actions.add(() -> {
            dismiss();
            TigiArticleReaderActivity.open(fragment, message);
        });
        if (mine) {
            items.add(LocaleController.getString(R.string.NilakWriterEdit));
            icons.add(R.drawable.msg_edit);
            actions.add(() -> edit(message));
            items.add(LocaleController.getString(R.string.Delete));
            icons.add(R.drawable.msg_delete);
            actions.add(() -> confirmDelete(message));
        }
        final int[] iconIds = new int[icons.size()];
        for (int i = 0; i < iconIds.length; i++) {
            iconIds[i] = icons.get(i);
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setItems(items.toArray(new CharSequence[0]), iconIds, (dialog, which) -> actions.get(which).run());
        fragment.showDialog(builder.create());
    }

    private void edit(MessageObject message) {
        final WriterDoc doc = WriterBridge.importArticle(message);
        if (doc != null) {
            dismiss();
            fragment.presentFragment(new WriterActivity(doc, message));
            return;
        }
        // the file is not on the phone yet: fetch it, the next tap opens the editor
        if (message.getDocument() != null) {
            FileLoader.getInstance(account).loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.NilakWriterDownloading)).show();
    }

    private void confirmDelete(MessageObject message) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.NilakBarruuDelete));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            final ArrayList<Integer> ids = new ArrayList<>();
            ids.add(message.getId());
            fragment.getMessagesController().deleteMessages(ids, null, null, dialogId, 0, true, 0);
            loadPublished();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        dialog.redPositive();
    }
}
