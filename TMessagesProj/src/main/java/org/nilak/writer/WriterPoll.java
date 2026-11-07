package org.nilak.writer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.TigiStyle;

import java.util.ArrayList;

/**
 * Polls in a Barruu. In the editor a poll is a question and its options; when the Barruu is published each poll is
 * posted as a real Telegram poll in the same chat and the Barruu keeps a link to it ("tgpoll:dialog:message"), so
 * readers vote inside the Barruu and everyone sees live results.
 */
public final class WriterPoll {

    public static final String PREFIX = "tgpoll:";
    public static final int MAX_OPTIONS = 10;

    private WriterPoll() {
    }

    public static boolean isRef(String ref) {
        return ref != null && ref.startsWith(PREFIX);
    }

    public static String ref(long dialogId, int messageId) {
        return PREFIX + dialogId + ":" + messageId;
    }

    /** {dialogId, messageId}, or null. */
    public static long[] parseRef(String ref) {
        if (!isRef(ref)) {
            return null;
        }
        try {
            final String[] parts = ref.substring(PREFIX.length()).split(":");
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- model: the first line is the question, then one option per line ---------- */

    public static String question(String text) {
        if (text == null) {
            return "";
        }
        final int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).trim();
    }

    public static ArrayList<String> options(String text) {
        final ArrayList<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        final String[] lines = text.split("\n");
        for (int i = 1; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    static String format(String question, ArrayList<String> options) {
        final StringBuilder out = new StringBuilder(question.replace('\n', ' ').trim());
        for (String option : options) {
            final String clean = option.replace('\n', ' ').trim();
            if (!clean.isEmpty()) {
                out.append('\n').append(clean);
            }
        }
        return out.toString();
    }

    /* ---------- the poll drawn in the editor ---------- */

    static final class Card {
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint questionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint optionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout questionLayout;
        private final ArrayList<StaticLayout> optionLayouts = new ArrayList<>();
        private String laidOut;
        private int laidWidth;
        private boolean live;
        int height;

        Card() {
            label.setTypeface(TigiStyle.displayExtraBold());
            label.setTextSize(dp(12));
            questionPaint.setTypeface(TigiStyle.displayBold());
            questionPaint.setTextSize(dp(17));
            optionPaint.setTextSize(dp(15));
        }

        void layout(String text, boolean live, int width, int ink) {
            questionPaint.setColor(ink);
            optionPaint.setColor(ink);
            label.setColor(TigiStyle.accent());
            if (text.equals(laidOut) && width == laidWidth && live == this.live) {
                return;
            }
            laidOut = text;
            laidWidth = width;
            this.live = live;
            final int inner = Math.max(dp(80), width - dp(32));
            String q = question(text);
            if (q.isEmpty()) {
                q = LocaleController.getString(R.string.NilakPollQuestionHint);
            }
            questionLayout = StaticLayout.Builder.obtain(q, 0, q.length(), questionPaint, inner).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            optionLayouts.clear();
            height = dp(16) + dp(18) + dp(6) + questionLayout.getHeight() + dp(12);
            final ArrayList<String> options = options(text);
            if (options.isEmpty() && !live) {
                options.add(LocaleController.getString(R.string.NilakPollOptionHint) + " 1");
                options.add(LocaleController.getString(R.string.NilakPollOptionHint) + " 2");
            }
            for (String option : options) {
                final StaticLayout layout = StaticLayout.Builder.obtain(option, 0, option.length(), optionPaint, inner - dp(44))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
                optionLayouts.add(layout);
                height += Math.max(dp(44), layout.getHeight() + dp(20)) + dp(8);
            }
            height += dp(8);
        }

        void draw(Canvas canvas, float left, float top, int width, boolean dark) {
            final RectF box = new RectF(left, top, left + width, top + height);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(dark ? 0xFF241B34 : 0xFFFFFFFF);
            canvas.drawRoundRect(box, dp(18), dp(18), fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1, dp(1)));
            fill.setColor(dark ? 0xFF3A2E50 : 0xFFE6DCF4);
            canvas.drawRoundRect(box, dp(18), dp(18), fill);
            fill.setStyle(Paint.Style.FILL);
            float y = top + dp(16);
            final String kind = "📊  " + LocaleController.getString(live ? R.string.NilakPollLive : R.string.NilakWriterPoll).toUpperCase();
            canvas.drawText(kind, left + dp(16), y + dp(13), label);
            y += dp(18) + dp(6);
            canvas.save();
            canvas.translate(left + dp(16), y);
            questionLayout.draw(canvas);
            canvas.restore();
            y += questionLayout.getHeight() + dp(12);
            for (StaticLayout option : optionLayouts) {
                final int rowHeight = Math.max(dp(44), option.getHeight() + dp(20));
                final RectF row = new RectF(left + dp(12), y, left + width - dp(12), y + rowHeight);
                fill.setColor(dark ? 0xFF2F2542 : 0xFFF4EEFC);
                canvas.drawRoundRect(row, dp(14), dp(14), fill);
                fill.setStyle(Paint.Style.STROKE);
                fill.setColor(Theme.multAlpha(TigiStyle.accent(), 0.7f));
                canvas.drawCircle(row.left + dp(20), row.centerY(), dp(8), fill);
                fill.setStyle(Paint.Style.FILL);
                canvas.save();
                canvas.translate(row.left + dp(40), row.centerY() - option.getHeight() / 2f);
                option.draw(canvas);
                canvas.restore();
                y += rowHeight + dp(8);
            }
        }
    }

    /* ---------- the editing sheet ---------- */

    /** Edit a poll that is not published yet; a published one can only be removed (Telegram polls do not change). */
    public static void edit(BaseFragment fragment, String text, boolean live, Utilities.Callback<String> onDone, Runnable onDelete) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final boolean dark = Theme.isCurrentThemeDark();
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18), dp(6), dp(18), dp(16));

        final LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(8), 0, dp(10));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakWriterPoll));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        head.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView done = pill(context, LocaleController.getString(R.string.Done), true, dark);
        head.addView(done, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        column.addView(head);

        final BottomSheet[] sheet = new BottomSheet[1];
        final boolean[] finished = new boolean[1];
        final EditText question = field(context, LocaleController.getString(R.string.NilakPollQuestionHint), true, dark);
        question.setText(question(text));
        final LinearLayout optionsBox = new LinearLayout(context);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<EditText> optionFields = new ArrayList<>();
        final TextView add = pill(context, "＋ " + LocaleController.getString(R.string.NilakPollAddOption), false, dark);
        final Runnable[] addOption = new Runnable[1];
        addOption[0] = () -> {
            if (optionFields.size() >= MAX_OPTIONS) {
                return;
            }
            final EditText option = field(context, LocaleController.getString(R.string.NilakPollOptionHint) + " " + (optionFields.size() + 1), false, dark);
            optionFields.add(option);
            optionsBox.addView(option, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
            add.setVisibility(optionFields.size() >= MAX_OPTIONS ? View.GONE : View.VISIBLE);
        };
        if (live) {
            // a published poll: shown, not editable
            final TextView note = new TextView(context);
            note.setText(LocaleController.getString(R.string.NilakPollLiveNote));
            note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            note.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            note.setPadding(dp(2), 0, dp(2), dp(12));
            column.addView(note);
            question.setEnabled(false);
        }
        column.addView(question, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        if (!live) {
            final TextView optionsLabel = new TextView(context);
            optionsLabel.setText(LocaleController.getString(R.string.NilakPollOptions).toUpperCase());
            optionsLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            optionsLabel.setTypeface(TigiStyle.displayExtraBold());
            optionsLabel.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            optionsLabel.setPadding(dp(2), 0, 0, dp(8));
            column.addView(optionsLabel);
            column.addView(optionsBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            final ArrayList<String> existing = options(text);
            for (int i = 0; i < Math.max(2, existing.size()); i++) {
                addOption[0].run();
                if (i < existing.size()) {
                    optionFields.get(i).setText(existing.get(i));
                }
            }
            add.setOnClickListener(v -> {
                addOption[0].run();
                final EditText last = optionFields.get(optionFields.size() - 1);
                last.requestFocus();
                AndroidUtilities.showKeyboard(last);
            });
            column.addView(add, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, Gravity.LEFT, 0, 4, 0, 0));
        }
        final TextView delete = pill(context, LocaleController.getString(R.string.NilakPollRemove), false, dark);
        delete.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        column.addView(delete, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, Gravity.RIGHT, 0, 14, 0, 0));

        final Runnable save = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            if (live) {
                return;
            }
            final ArrayList<String> values = new ArrayList<>();
            for (EditText option : optionFields) {
                values.add(option.getText().toString());
            }
            onDone.run(format(question.getText().toString(), values));
        };
        done.setOnClickListener(v -> {
            save.run();
            sheet[0].dismiss();
        });
        delete.setOnClickListener(v -> {
            finished[0] = true;
            sheet[0].dismiss();
            onDelete.run();
        });
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, true);
        builder.setCustomView(scroll);
        sheet[0] = builder.create();
        sheet[0].setOnDismissListener(d -> save.run());
        fragment.showDialog(sheet[0]);
        if (!live && question.length() == 0) {
            question.postDelayed(() -> {
                question.requestFocus();
                AndroidUtilities.showKeyboard(question);
            }, 250);
        }
    }

    private static EditText field(Context context, String hint, boolean bold, boolean dark) {
        final EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, bold ? 17 : 15.5f);
        if (bold) {
            field.setTypeface(TigiStyle.displayBold());
        }
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setBackground(Theme.createRoundRectDrawable(dp(14), dark ? 0xFF2A2236 : 0xFFF4EEFC));
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        return field;
    }

    private static TextView pill(Context context, String text, boolean primary, boolean dark) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(TigiStyle.displayBold());
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setTextColor(primary ? 0xFFFFFFFF : (dark ? 0xFFE2DAEE : 0xFF4A3A66));
        view.setBackground(primary ? TigiStyle.primaryPill(17)
            : Theme.createSimpleSelectorRoundRectDrawable(dp(18), dark ? 0xFF2A2533 : 0xFFF2EDF9, Theme.multAlpha(TigiStyle.accent(), 0.2f)));
        return view;
    }

    /* ---------- publishing: each new poll becomes a Telegram poll in the chat ---------- */

    /**
     * Posts the Barruu's polls that are not posted yet into dialogId and writes their links into the blocks, then
     * runs done on the UI thread. A poll that could not be posted within the time limit is left out.
     */
    static void postPolls(int account, long dialogId, WriterDoc doc, Runnable done) {
        final ArrayList<WriterDoc.Block> pending = new ArrayList<>();
        for (WriterDoc.Block block : doc.blocks) {
            if (block.type == WriterDoc.POLL && !isRef(block.path) && !question(block.text.toString()).isEmpty()
                && options(block.text.toString()).size() >= 2) {
                pending.add(block);
            }
        }
        if (pending.isEmpty()) {
            done.run();
            return;
        }
        final int[] left = {pending.size()};
        final boolean[] over = {false};
        final NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        final Runnable finish = () -> {
            if (over[0]) {
                return;
            }
            over[0] = true;
            NotificationCenter.getInstance(account).removeObserver(observer[0], NotificationCenter.messageReceivedByServer);
            done.run();
        };
        observer[0] = (id, acc, args) -> {
            if (id != NotificationCenter.messageReceivedByServer || args.length < 4 || !(args[2] instanceof TLRPC.Message)) {
                return;
            }
            final TLRPC.Message message = (TLRPC.Message) args[2];
            if (!(args[3] instanceof Long) || (Long) args[3] != dialogId || !(message.media instanceof TLRPC.TL_messageMediaPoll) || ((TLRPC.TL_messageMediaPoll) message.media).poll == null) {
                return;
            }
            final String q = ((TLRPC.TL_messageMediaPoll) message.media).poll.question != null ? ((TLRPC.TL_messageMediaPoll) message.media).poll.question.text : "";
            for (WriterDoc.Block block : pending) {
                if (!isRef(block.path) && question(block.text.toString()).equals(q)) {
                    block.path = ref(dialogId, message.id);
                    left[0]--;
                    break;
                }
            }
            if (left[0] <= 0) {
                finish.run();
            }
        };
        NotificationCenter.getInstance(account).addObserver(observer[0], NotificationCenter.messageReceivedByServer);
        AndroidUtilities.runOnUIThread(finish, 30_000);
        for (WriterDoc.Block block : pending) {
            try {
                final TLRPC.TL_messageMediaPoll media = new TLRPC.TL_messageMediaPoll();
                media.poll = new TLRPC.TL_poll();
                media.poll.question = new TLRPC.TL_textWithEntities();
                media.poll.question.text = question(block.text.toString());
                for (String option : options(block.text.toString())) {
                    final TLRPC.PollAnswer answer = new TLRPC.TL_pollAnswer();
                    answer.text = new TLRPC.TL_textWithEntities();
                    answer.text.text = option;
                    answer.option = new byte[]{(byte) (48 + media.poll.answers.size())};
                    media.poll.answers.add(answer);
                    if (media.poll.answers.size() >= MAX_OPTIONS) {
                        break;
                    }
                }
                media.poll.creator = true;
                media.results = new TLRPC.TL_pollResults();
                SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(media, dialogId, null, null, null, null, false, 0, 0));
            } catch (Throwable e) {
                FileLog.e(e);
                left[0]--;
            }
        }
        if (left[0] <= 0) {
            finish.run();
        }
    }

    /* ---------- the poll in the reader: vote and see results, live ---------- */

    public static View readerView(BaseFragment fragment, String ref, String fallbackQuestion, int ink, int gray) {
        final Context context = fragment.getParentActivity();
        final long[] parts = parseRef(ref);
        if (context == null || parts == null) {
            return null;
        }
        final int account = fragment.getCurrentAccount();
        final boolean dark = Theme.isCurrentThemeDark();
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        final android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
        frame.setCornerRadius(dp(18));
        frame.setColor(dark ? 0xFF241B34 : 0xFFFFFFFF);
        frame.setStroke(Math.max(1, dp(1)), dark ? 0xFF3A2E50 : 0xFFE6DCF4);
        card.setBackground(frame);
        final Runnable[] load = new Runnable[1];
        final MessageObject[] current = new MessageObject[1];
        final Utilities.Callback<MessageObject> bind = message -> {
            card.removeAllViews();
            final TextView label = new TextView(context);
            label.setText("📊  " + LocaleController.getString(R.string.NilakWriterPoll).toUpperCase());
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            label.setTypeface(TigiStyle.displayExtraBold());
            label.setTextColor(TigiStyle.accent());
            card.addView(label);
            final TLRPC.TL_messageMediaPoll media = message != null && message.messageOwner.media instanceof TLRPC.TL_messageMediaPoll
                ? (TLRPC.TL_messageMediaPoll) message.messageOwner.media : null;
            final TextView questionView = new TextView(context);
            questionView.setText(media != null && media.poll.question != null ? media.poll.question.text : fallbackQuestion);
            questionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            questionView.setTypeface(TigiStyle.displayBold());
            questionView.setTextColor(ink);
            card.addView(questionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 10));
            if (media == null) {
                final TextView info = new TextView(context);
                info.setText(LocaleController.getString(R.string.NilakPollLoading));
                info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                info.setTextColor(gray);
                card.addView(info);
                return;
            }
            int total = 0;
            boolean voted = false;
            if (media.results != null) {
                total = media.results.total_voters;
                for (TLRPC.PollAnswerVoters voters : media.results.results) {
                    voted |= voters.chosen;
                }
            }
            final boolean showResults = voted || media.poll.closed;
            for (TLRPC.PollAnswer answer : media.poll.answers) {
                int count = 0;
                boolean chosen = false;
                if (media.results != null) {
                    for (TLRPC.PollAnswerVoters voters : media.results.results) {
                        if (java.util.Arrays.equals(voters.option, answer.option)) {
                            count = voters.voters;
                            chosen = voters.chosen;
                        }
                    }
                }
                final float share = total > 0 ? count / (float) total : 0f;
                card.addView(optionRow(context, answer.text != null ? answer.text.text : "", showResults, share, chosen, ink, dark, v -> {
                    if (showResults || current[0] == null) {
                        return;
                    }
                    final ArrayList<TLRPC.PollAnswer> answers = new ArrayList<>();
                    answers.add(answer);
                    SendMessagesHelper.getInstance(account).sendVote(current[0], answers, () -> AndroidUtilities.runOnUIThread(load[0], 400));
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 0, 0, 8));
            }
            final TextView footer = new TextView(context);
            footer.setText(LocaleController.formatPluralString("Vote", total));
            footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            footer.setTextColor(gray);
            card.addView(footer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        };
        load[0] = () -> {
            final long dialogId = parts[0];
            final int messageId = (int) parts[1];
            final TLRPC.Chat chat = dialogId < 0 ? MessagesController.getInstance(account).getChat(-dialogId) : null;
            final org.telegram.tgnet.TLObject req;
            if (chat != null && ChatObject.isChannel(chat)) {
                final TLRPC.TL_channels_getMessages channelReq = new TLRPC.TL_channels_getMessages();
                channelReq.channel = MessagesController.getInputChannel(chat);
                channelReq.id.add(messageId);
                req = channelReq;
            } else {
                final TLRPC.TL_messages_getMessages plainReq = new TLRPC.TL_messages_getMessages();
                plainReq.id.add(messageId);
                req = plainReq;
            }
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages && !((TLRPC.messages_Messages) response).messages.isEmpty()) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    MessagesController.getInstance(account).putUsers(res.users, false);
                    MessagesController.getInstance(account).putChats(res.chats, false);
                    final TLRPC.Message message = res.messages.get(0);
                    if (message.media instanceof TLRPC.TL_messageMediaPoll) {
                        current[0] = new MessageObject(account, message, false, false);
                        bind.run(current[0]);
                        return;
                    }
                }
                bind.run(null);
            }));
        };
        bind.run(null);
        load[0].run();
        return card;
    }

    private static View optionRow(Context context, String text, boolean results, float share, boolean chosen, int ink, boolean dark, View.OnClickListener onClick) {
        final FrameLayout row = new FrameLayout(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(Canvas canvas) {
                final RectF box = new RectF(0, 0, getWidth(), getHeight());
                paint.setColor(dark ? 0xFF2F2542 : 0xFFF4EEFC);
                canvas.drawRoundRect(box, dp(14), dp(14), paint);
                if (results && share > 0f) {
                    paint.setColor(Theme.multAlpha(TigiStyle.accent(), chosen ? 0.32f : 0.18f));
                    canvas.save();
                    canvas.clipRect(0, 0, getWidth() * share, getHeight());
                    canvas.drawRoundRect(box, dp(14), dp(14), paint);
                    canvas.restore();
                }
                super.dispatchDraw(canvas);
            }
        };
        row.setWillNotDraw(false);
        final TextView label = new TextView(context);
        label.setText((results && chosen ? "✓ " : "") + text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTextColor(ink);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTypeface(chosen ? TigiStyle.displayBold() : android.graphics.Typeface.DEFAULT);
        row.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 14, 0, results ? 56 : 14, 0));
        if (results) {
            final TextView percent = new TextView(context);
            percent.setText(Math.round(share * 100) + "%");
            percent.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            percent.setTypeface(TigiStyle.displayBold());
            percent.setTextColor(TigiStyle.accent());
            row.addView(percent, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 14, 0));
        } else {
            row.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(TigiStyle.accent(), 0.15f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            row.setOnClickListener(onClick);
        }
        row.setContentDescription(text + (results ? ", " + Math.round(share * 100) + "%" : ""));
        return row;
    }
}
