package org.nilak.writer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.TigiArticle;
import org.telegram.ui.TigiArticleReaderActivity;
import org.telegram.ui.TigiLilac;
import org.telegram.ui.TigiPosting;
import org.telegram.ui.TigiStyle;

import java.util.ArrayList;

/** Your articles: what you published (open, edit, delete) and your drafts (continue, delete), and Write. */
public class WriterHomeActivity extends BaseFragment {

    private boolean draftsTab;
    private LinearLayout list;
    private TextView publishedChip;
    private TextView draftsChip;
    private final ArrayList<MessageObject> published = new ArrayList<>();
    private ArrayList<WriterDoc> drafts = new ArrayList<>();
    private boolean loadingPublished;
    /** Set by the publish screen when an article went out: shown once, then Published is refreshed. */
    static String publishedMessage;

    private int ink() {
        return TigiLilac.on() ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
    }

    private int meta() {
        return TigiLilac.on() ? TigiLilac.meta() : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
    }

    private int card() {
        return TigiLilac.on() ? TigiLilac.card() : Theme.getColor(Theme.key_windowBackgroundWhite);
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        if (TigiLilac.on()) {
            root.setBackground(TigiLilac.page());
        } else {
            root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        }
        final LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        final TextView back = new TextView(context);
        back.setText(LocaleController.isRTL ? "›" : "‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        back.setTextColor(ink());
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        back.setOnClickListener(v -> finishFragment());
        bar.addView(back, LayoutHelper.createLinear(48, 54));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakWriterYourArticles));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(ink());
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        bar.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView write = new TextView(context);
        write.setText("✎  " + LocaleController.getString(R.string.NilakWriterWrite));
        write.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        write.setTypeface(TigiStyle.displayBold());
        write.setTextColor(0xFFFFFFFF);
        write.setGravity(Gravity.CENTER);
        write.setPadding(dp(16), 0, dp(16), 0);
        write.setBackground(TigiStyle.primaryPill(17));
        write.setOnClickListener(v -> presentFragment(new WriterActivity()));
        bar.addView(write, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 14, 0));
        page.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        publishedChip = chip(context, v -> setTab(false));
        draftsChip = chip(context, v -> setTab(true));
        tabs.addView(publishedChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        tabs.addView(draftsChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        page.addView(tabs, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 10));

        final ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);
        page.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(page, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (publishedMessage != null) {
            final String message = publishedMessage;
            publishedMessage = null;
            draftsTab = false;
            // a clear result that stays long enough to read
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, message).setDuration(org.telegram.ui.Components.Bulletin.DURATION_PROLONG).show();
            // the article is packed and uploaded in the background: look again a few times
            loadPublished();
            AndroidUtilities.runOnUIThread(this::loadPublished, 3000);
            AndroidUtilities.runOnUIThread(this::loadPublished, 8000);
            AndroidUtilities.runOnUIThread(this::loadPublished, 15000);
        } else {
            loadPublished();
        }
        // a draft may have been written, finished or published meanwhile
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<WriterDoc> loaded = WriterDrafts.list(currentAccount);
            AndroidUtilities.runOnUIThread(() -> {
                drafts = loaded;
                bind();
            });
        });
    }

    private TextView chip(Context context, View.OnClickListener onClick) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(TigiStyle.displayBold());
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setOnClickListener(onClick);
        return view;
    }

    private void setTab(boolean drafts) {
        draftsTab = drafts;
        bind();
    }

    private void loadPublished() {
        final TLRPC.Chat channel = TigiPosting.defaultChannel(currentAccount);
        if (channel == null || isFinishing() || getParentActivity() == null) {
            return;
        }
        loadingPublished = true;
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = getMessagesController().getInputPeer(-channel.id);
        req.limit = 100;
        getConnectionsManager().bindRequestToGuid(getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loadingPublished = false;
            published.clear();
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                for (TLRPC.Message message : res.messages) {
                    final MessageObject object = new MessageObject(currentAccount, message, false, false);
                    if (WriterBridge.isArticle(object)) {
                        published.add(object);
                    }
                }
            }
            bind();
        })), classGuid);
    }

    private void bind() {
        if (list == null) {
            return;
        }
        publishedChip.setText(LocaleController.getString(R.string.NilakWriterPublished) + (published.isEmpty() ? "" : "  " + published.size()));
        draftsChip.setText(LocaleController.getString(R.string.NilakWriterDrafts) + (drafts.isEmpty() ? "" : "  " + drafts.size()));
        styleChip(publishedChip, !draftsTab);
        styleChip(draftsChip, draftsTab);
        list.removeAllViews();
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        if (draftsTab) {
            for (WriterDoc doc : drafts) {
                final String when = DateUtils.getRelativeTimeSpanString(doc.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
                final View row = row(context, titleOf(doc.title),
                    LocaleController.formatString(R.string.NilakWriterDraftLine, when, doc.wordCount()), doc.editMessageId != 0);
                row.setOnClickListener(v -> presentFragment(new WriterActivity(doc, null)));
                row.setOnLongClickListener(v -> {
                    confirmDeleteDraft(doc);
                    return true;
                });
                list.addView(row);
            }
            if (drafts.isEmpty()) {
                list.addView(empty(context, R.string.NilakWriterNoDrafts));
            }
        } else {
            for (MessageObject message : published) {
                final String date = LocaleController.formatDateAudio(message.messageOwner.date, false);
                final String line = date + (message.isEdited() ? " · " + LocaleController.getString(R.string.EditedMessage) : "")
                    + (message.messageOwner.views > 0 ? " · " + LocaleController.formatPluralString("Views", message.messageOwner.views) : "");
                final View row = row(context, titleOf(articleTitle(message)), line, false);
                row.setOnClickListener(v -> showPublishedMenu(message));
                list.addView(row);
            }
            if (published.isEmpty() && !loadingPublished) {
                list.addView(empty(context, R.string.NilakWriterNoPublished));
            }
        }
    }

    private String articleTitle(MessageObject message) {
        if (message.messageOwner.rich_message != null) {
            for (org.telegram.tgnet.tl.TL_iv.PageBlock block : message.messageOwner.rich_message.blocks) {
                if (block.text != null) {
                    final String text = WriterBridge.toSpans(block.text).toString().trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            }
            return "";
        }
        return TigiArticle.titleOf(message);
    }

    private String titleOf(String title) {
        return TextUtils.isEmpty(title == null ? null : title.trim()) ? LocaleController.getString(R.string.NilakWriterUntitled) : title.trim();
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setTextColor(on ? 0xFFFFFFFF : ink());
        chip.setBackground(on ? TigiStyle.primaryPill(18)
            : Theme.createSimpleSelectorRoundRectDrawable(dp(18), card(), Theme.multAlpha(TigiStyle.accent(), 0.15f)));
    }

    private View row(Context context, String title, String line, boolean editingDraft) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), card(), Theme.multAlpha(TigiStyle.accent(), 0.10f)));
        box.setElevation(dp(1));
        final TextView name = new TextView(context);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTypeface(TigiStyle.displayExtraBold());
        name.setTextColor(ink());
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name);
        final TextView sub = new TextView(context);
        sub.setText((editingDraft ? LocaleController.getString(R.string.NilakWriterEditing) + " · " : "") + line);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setTextColor(meta());
        box.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        box.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10));
        return box;
    }

    private View empty(Context context, int text) {
        final TextView view = new TextView(context);
        view.setText(LocaleController.getString(text));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(meta());
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(32), dp(48), dp(32), dp(48));
        return view;
    }

    private void confirmDeleteDraft(WriterDoc doc) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NilakWriterDeleteDraft));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            WriterDrafts.delete(currentAccount, doc.id);
            drafts.remove(doc);
            bind();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.redPositive();
    }

    private void showPublishedMenu(MessageObject message) {
        final CharSequence[] items = {
            LocaleController.getString(R.string.NilakReadArticle),
            LocaleController.getString(R.string.NilakWriterEdit),
            LocaleController.getString(R.string.Delete)
        };
        final int[] icons = {R.drawable.msg_openin, R.drawable.msg_edit, R.drawable.msg_delete};
        final BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
        builder.setItems(items, icons, (dialog, which) -> {
            if (which == 0) {
                read(message);
            } else if (which == 1) {
                edit(message);
            } else {
                confirmDeletePublished(message);
            }
        });
        showDialog(builder.create());
    }

    private void read(MessageObject message) {
        if (message.messageOwner.rich_message == null) {
            TigiArticleReaderActivity.open(this, message);
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", -message.getDialogId());
        args.putInt("message_id", message.getId());
        presentFragment(new ChatActivity(args));
    }

    private void edit(MessageObject message) {
        final WriterDoc doc = WriterBridge.importArticle(message);
        if (doc != null) {
            presentFragment(new WriterActivity(doc, message));
            return;
        }
        // the article file is not on the phone yet: fetch it, then the next tap opens the editor
        if (message.getDocument() != null) {
            FileLoader.getInstance(currentAccount).loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.NilakWriterDownloading)).show();
    }

    private void confirmDeletePublished(MessageObject message) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NilakWriterDeleteArticle));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            final ArrayList<Integer> ids = new ArrayList<>();
            ids.add(message.getId());
            getMessagesController().deleteMessages(ids, null, null, message.getDialogId(), 0, true, 0);
            published.remove(message);
            bind();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.redPositive();
    }
}
