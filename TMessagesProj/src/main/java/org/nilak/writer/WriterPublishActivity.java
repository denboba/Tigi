package org.nilak.writer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.TigiCreateSheet;
import org.telegram.ui.TigiLilac;
import org.telegram.ui.TigiStyle;

/** Publish: how the article will look in the feed, a topic, who can find it, then one Publish button. */
public class WriterPublishActivity extends BaseFragment {

    private static final int[] TOPICS = {
        R.string.NilakInterestNews, R.string.NilakInterestTech, R.string.NilakTopicMarkets, R.string.NilakTopicPolitics,
        R.string.NilakInterestSports, R.string.NilakInterestFaith, R.string.NilakInterestJobs, R.string.NilakWriterTopicLife
    };

    private final WriterActivity writer;
    private final WriterDoc doc;
    private MessageObject editing;
    private int topic = -1;
    private boolean showInRead = true;
    private boolean notifyFollowers;
    private LinearLayout chips;
    private TextView publishButton;
    private boolean publishing;

    public WriterPublishActivity(WriterActivity writer, WriterDoc doc, MessageObject editing) {
        super();
        this.writer = writer;
        this.doc = doc;
        this.editing = editing;
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
        final int ink = TigiLilac.on() ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int meta = TigiLilac.on() ? TigiLilac.meta() : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        final int card = TigiLilac.on() ? TigiLilac.card() : Theme.getColor(Theme.key_windowBackgroundWhite);

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, AndroidUtilities.statusBarHeight, 0, dp(100));

        final TextView back = new TextView(context);
        back.setText("‹  " + LocaleController.getString(R.string.NilakWriterKeepEditing));
        back.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        back.setTypeface(TigiStyle.displayBold());
        back.setTextColor(TigiLilac.on() ? 0xFF4A3A66 : ink);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(15), 0, dp(15), 0);
        back.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(17), Theme.multAlpha(TigiStyle.accent(), 0.10f), Theme.multAlpha(TigiStyle.accent(), 0.2f)));
        back.setOnClickListener(v -> finishFragment());
        column.addView(back, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 14, 10, 0, 12));

        // the card as it will appear in the feed
        final LinearLayout preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(16), dp(14), dp(16), dp(14));
        preview.setBackground(Theme.createRoundRectDrawable(dp(20), card));
        preview.setElevation(dp(1));
        final TextView kicker = new TextView(context);
        kicker.setText(LocaleController.getString(R.string.NilakArticleLabel).toUpperCase());
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        kicker.setTypeface(TigiStyle.displayExtraBold());
        kicker.setTextColor(TigiStyle.accent());
        preview.addView(kicker);
        final TextView title = new TextView(context);
        title.setText(TextUtils.isEmpty(doc.title.trim()) ? LocaleController.getString(R.string.NilakWriterUntitled) : doc.title.trim());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(ink);
        preview.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));
        final TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        final TextView by = new TextView(context);
        by.setText((me != null ? UserObject.getUserName(me) + " · " : "") + "📖 " + LocaleController.formatString(R.string.NilakReadMinutes, doc.minutes()));
        by.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        by.setTextColor(meta);
        preview.addView(by);
        column.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 16));

        final TextView topicHeader = new TextView(context);
        topicHeader.setText(LocaleController.getString(R.string.NilakWriterTopic));
        topicHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        topicHeader.setTypeface(TigiStyle.displayExtraBold());
        topicHeader.setTextColor(ink);
        column.addView(topicHeader, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 18, 0, 18, 8));
        final HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(14), 0, dp(14), 0);
        for (int i = 0; i < TOPICS.length; i++) {
            final int index = i;
            final TextView chip = new TextView(context);
            chip.setText(LocaleController.getString(TOPICS[i]));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            chip.setTypeface(TigiStyle.displayBold());
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setOnClickListener(v -> {
                topic = topic == index ? -1 : index;
                bindChips();
            });
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        }
        chipScroll.addView(chips);
        column.addView(chipScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));
        bindChips();

        final LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setBackground(Theme.createRoundRectDrawable(dp(18), card));
        final TextCheckCell read = new TextCheckCell(context);
        read.setTextAndValueAndCheck(LocaleController.getString(R.string.NilakWriterShowInRead), LocaleController.getString(R.string.NilakWriterShowInReadInfo), showInRead, true, true);
        read.setBackground(null);
        read.setOnClickListener(v -> {
            showInRead = !showInRead;
            read.setChecked(showInRead);
        });
        options.addView(read);
        final TextCheckCell notify = new TextCheckCell(context);
        notify.setTextAndCheck(LocaleController.getString(R.string.NilakWriterNotify), notifyFollowers, false);
        notify.setBackground(null);
        notify.setOnClickListener(v -> {
            notifyFollowers = !notifyFollowers;
            notify.setChecked(notifyFollowers);
        });
        options.addView(notify);
        column.addView(options, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        publishButton = new TextView(context);
        publishButton.setText(LocaleController.getString(doc.editMessageId != 0 ? R.string.NilakWriterUpdate : R.string.NilakWriterPublish));
        publishButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        publishButton.setTypeface(TigiStyle.displayBold());
        publishButton.setTextColor(0xFFFFFFFF);
        publishButton.setGravity(Gravity.CENTER);
        publishButton.setBackground(TigiStyle.primaryPill(25));
        publishButton.setOnClickListener(v -> publish());
        root.addView(publishButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.BOTTOM, 16, 0, 16, 22));
        fragmentView = root;
        return fragmentView;
    }

    private void bindChips() {
        if (chips == null) {
            return;
        }
        final int ink = TigiLilac.on() ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        for (int i = 0; i < chips.getChildCount(); i++) {
            final TextView chip = (TextView) chips.getChildAt(i);
            final boolean on = i == topic;
            chip.setSelected(on);
            chip.setTextColor(on ? 0xFFFFFFFF : ink);
            chip.setBackground(on ? TigiStyle.primaryPill(18)
                : Theme.createSimpleSelectorRoundRectDrawable(dp(18), TigiLilac.on() ? TigiLilac.card() : Theme.getColor(Theme.key_windowBackgroundWhite), Theme.multAlpha(TigiStyle.accent(), 0.15f)));
        }
    }

    private void publish() {
        if (publishing) {
            return;
        }
        if (editing == null && doc.editMessageId != 0) {
            // a draft of an edit: find the published post first
            publishing = true;
            final TLRPC.Chat chat = getMessagesController().getChat(-doc.editDialogId);
            if (chat == null) {
                publishing = false;
                doc.editMessageId = 0;
                publish();
                return;
            }
            final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = MessagesController.getInputChannel(chat);
            req.id.add(doc.editMessageId);
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                publishing = false;
                if (response instanceof TLRPC.messages_Messages && !((TLRPC.messages_Messages) response).messages.isEmpty()
                    && !(((TLRPC.messages_Messages) response).messages.get(0) instanceof TLRPC.TL_messageEmpty)) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putChats(res.chats, false);
                    getMessagesController().putUsers(res.users, false);
                    editing = new MessageObject(currentAccount, res.messages.get(0), false, false);
                } else {
                    // the post is gone: publish as a new article
                    doc.editMessageId = 0;
                }
                publish();
            }));
            return;
        }
        if (editing != null) {
            final TLRPC.Chat chat = getMessagesController().getChat(-editing.getDialogId());
            if (chat != null && ChatObject.canPost(chat)) {
                done(chat);
                return;
            }
        }
        TigiCreateSheet.pickChannel(this, this::done);
    }

    private void done(TLRPC.Chat channel) {
        if (channel == null || getParentActivity() == null) {
            return;
        }
        publishing = true;
        final String topicName = topic >= 0 ? LocaleController.getString(TOPICS[topic]) : null;
        // progress and errors show on the screen the writer was opened from, which stays after these two close
        BaseFragment target = this;
        if (getParentLayout() != null && writer != null) {
            final java.util.List<BaseFragment> stack = getParentLayout().getFragmentStack();
            final int index = stack.indexOf(writer);
            if (index > 0) {
                target = stack.get(index - 1);
            }
        }
        WriterHomeActivity.justPublished = target instanceof WriterHomeActivity;
        WriterBridge.publish(target, channel, doc, topicName, showInRead, notifyFollowers, editing);
        if (writer != null) {
            writer.published();
        }
        finishFragment();
    }
}
