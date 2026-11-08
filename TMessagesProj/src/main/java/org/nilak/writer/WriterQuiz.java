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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.TigiStyle;

import java.util.ArrayList;

/**
 * A quiz inside a Barruu: a question, options, the right answer and an optional explanation. Everything lives in
 * the article itself (nothing is sent to Telegram): readers pick an answer and see at once whether it was right.
 *
 * In the editor block the text is: the question on the first line, then "+ option" for the right answer and
 * "- option" for the others, and "> explanation". Published as a ```quiz fenced block in the same form.
 */
public final class WriterQuiz {

    public static final int MAX_OPTIONS = 10;

    public String question = "";
    public final ArrayList<String> options = new ArrayList<>();
    public int correct = -1;
    public String explanation = "";

    public static WriterQuiz parse(String text) {
        final WriterQuiz quiz = new WriterQuiz();
        if (text == null) {
            return quiz;
        }
        final String[] lines = text.split("\n");
        boolean first = true;
        for (String raw : lines) {
            final String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (first) {
                quiz.question = line;
                first = false;
            } else if (line.startsWith("+ ")) {
                quiz.correct = quiz.options.size();
                quiz.options.add(line.substring(2).trim());
            } else if (line.startsWith("- ")) {
                quiz.options.add(line.substring(2).trim());
            } else if (line.startsWith("> ")) {
                quiz.explanation = line.substring(2).trim();
            } else {
                quiz.options.add(line); // older poll drafts: one option per line
            }
        }
        return quiz;
    }

    public String format() {
        final StringBuilder out = new StringBuilder(clean(question));
        for (int i = 0; i < options.size(); i++) {
            final String option = clean(options.get(i));
            if (!option.isEmpty()) {
                out.append('\n').append(i == correct ? "+ " : "- ").append(option);
            }
        }
        if (!clean(explanation).isEmpty()) {
            out.append("\n> ").append(clean(explanation));
        }
        return out.toString();
    }

    public boolean isComplete() {
        int filled = 0;
        for (String option : options) {
            if (!clean(option).isEmpty()) {
                filled++;
            }
        }
        return !clean(question).isEmpty() && filled >= 2;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace("```", "'''").trim();
    }

    /* ---------- the quiz drawn in the editor ---------- */

    static final class Card {
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint questionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint optionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout questionLayout;
        private final ArrayList<StaticLayout> optionLayouts = new ArrayList<>();
        private WriterQuiz quiz;
        private String laidOut;
        private int laidWidth;
        int height;

        Card() {
            label.setTypeface(TigiStyle.displayExtraBold());
            label.setTextSize(dp(12));
            questionPaint.setTypeface(TigiStyle.displayBold());
            questionPaint.setTextSize(dp(17));
            optionPaint.setTextSize(dp(15));
        }

        void layout(String text, int width, int ink) {
            questionPaint.setColor(ink);
            optionPaint.setColor(ink);
            label.setColor(TigiStyle.accent());
            if (text.equals(laidOut) && width == laidWidth) {
                return;
            }
            laidOut = text;
            laidWidth = width;
            quiz = parse(text);
            final int inner = Math.max(dp(80), width - dp(32));
            final String q = quiz.question.isEmpty() ? LocaleController.getString(R.string.NilakQuizQuestionHint) : quiz.question;
            questionLayout = StaticLayout.Builder.obtain(q, 0, q.length(), questionPaint, inner).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            optionLayouts.clear();
            height = dp(16) + dp(24) + questionLayout.getHeight() + dp(12);
            final ArrayList<String> options = new ArrayList<>(quiz.options);
            if (options.isEmpty()) {
                options.add(LocaleController.getString(R.string.NilakQuizOptionHint) + " 1");
                options.add(LocaleController.getString(R.string.NilakQuizOptionHint) + " 2");
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
            canvas.drawText("❓  " + LocaleController.getString(R.string.NilakWriterQuiz).toUpperCase(), left + dp(16), y + dp(13), label);
            y += dp(24);
            canvas.save();
            canvas.translate(left + dp(16), y);
            questionLayout.draw(canvas);
            canvas.restore();
            y += questionLayout.getHeight() + dp(12);
            for (int i = 0; i < optionLayouts.size(); i++) {
                final StaticLayout option = optionLayouts.get(i);
                final boolean right = quiz != null && i == quiz.correct;
                final int rowHeight = Math.max(dp(44), option.getHeight() + dp(20));
                final RectF row = new RectF(left + dp(12), y, left + width - dp(12), y + rowHeight);
                fill.setColor(right ? Theme.multAlpha(0xFF22C55E, dark ? 0.22f : 0.14f) : (dark ? 0xFF2F2542 : 0xFFF4EEFC));
                canvas.drawRoundRect(row, dp(14), dp(14), fill);
                if (right) {
                    fill.setColor(0xFF22C55E);
                    canvas.drawCircle(row.left + dp(20), row.centerY(), dp(9), fill);
                    label.setColor(0xFFFFFFFF);
                    final float was = label.getTextSize();
                    label.setTextSize(dp(12));
                    canvas.drawText("✓", row.left + dp(16), row.centerY() + dp(4.5f), label);
                    label.setTextSize(was);
                    label.setColor(TigiStyle.accent());
                } else {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setColor(Theme.multAlpha(TigiStyle.accent(), 0.7f));
                    canvas.drawCircle(row.left + dp(20), row.centerY(), dp(8), fill);
                    fill.setStyle(Paint.Style.FILL);
                }
                canvas.save();
                canvas.translate(row.left + dp(40), row.centerY() - option.getHeight() / 2f);
                option.draw(canvas);
                canvas.restore();
                y += rowHeight + dp(8);
            }
        }
    }

    /* ---------- editing: question, options (tap the circle to mark the right one), explanation ---------- */

    public static void edit(BaseFragment fragment, String text, Utilities.Callback<String> onDone, Runnable onDelete) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final boolean dark = Theme.isCurrentThemeDark();
        final WriterQuiz quiz = parse(text);
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18), dp(6), dp(18), dp(16));
        column.setBackground(Theme.createRoundRectDrawable(dp(18), 0, dark ? 0xFF1A1326 : 0xFFF7F2FD));

        final LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(8), 0, dp(4));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakWriterQuiz));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        head.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView done = pill(context, LocaleController.getString(R.string.Done), true, dark);
        head.addView(done, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        column.addView(head);
        final TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.NilakQuizHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        hint.setPadding(dp(2), 0, dp(2), dp(12));
        column.addView(hint);

        final EditText question = field(context, LocaleController.getString(R.string.NilakQuizQuestionHint), true, dark);
        question.setText(quiz.question);
        column.addView(question, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final LinearLayout optionsBox = new LinearLayout(context);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        column.addView(optionsBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final ArrayList<EditText> fields = new ArrayList<>();
        final ArrayList<TextView> marks = new ArrayList<>();
        final int[] correct = {quiz.correct};
        final Runnable refreshMarks = () -> {
            for (int i = 0; i < marks.size(); i++) {
                final boolean on = i == correct[0];
                marks.get(i).setText(on ? "✓" : "");
                marks.get(i).setBackground(on ? Theme.createCircleDrawable(dp(28), 0xFF22C55E)
                    : Theme.createCircleDrawable(dp(28), Theme.multAlpha(TigiStyle.accent(), 0.14f)));
            }
        };
        final TextView add = pill(context, "＋ " + LocaleController.getString(R.string.NilakQuizAddOption), false, dark);
        final Utilities.Callback<String> addOption = value -> {
            if (fields.size() >= MAX_OPTIONS) {
                return;
            }
            final int index = fields.size();
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final TextView mark = new TextView(context);
            mark.setGravity(Gravity.CENTER);
            mark.setTextColor(0xFFFFFFFF);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            mark.setTypeface(TigiStyle.displayExtraBold());
            mark.setContentDescription(LocaleController.getString(R.string.NilakQuizMarkCorrect));
            mark.setOnClickListener(v -> {
                correct[0] = index;
                refreshMarks.run();
            });
            row.addView(mark, LayoutHelper.createLinear(28, 28, 0, 0, 10, 0));
            final EditText field = field(context, LocaleController.getString(R.string.NilakQuizOptionHint) + " " + (index + 1), false, dark);
            field.setText(value);
            row.addView(field, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            fields.add(field);
            marks.add(mark);
            optionsBox.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
            add.setVisibility(fields.size() >= MAX_OPTIONS ? View.GONE : View.VISIBLE);
            refreshMarks.run();
        };
        for (int i = 0; i < Math.max(3, quiz.options.size()); i++) {
            addOption.run(i < quiz.options.size() ? quiz.options.get(i) : "");
        }
        add.setOnClickListener(v -> {
            addOption.run("");
            final EditText last = fields.get(fields.size() - 1);
            last.requestFocus();
            AndroidUtilities.showKeyboard(last);
        });
        column.addView(add, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, Gravity.LEFT, 38, 2, 0, 12));

        final EditText explanation = field(context, LocaleController.getString(R.string.NilakQuizExplanationHint), false, dark);
        explanation.setSingleLine(false);
        explanation.setMaxLines(4);
        explanation.setText(quiz.explanation);
        column.addView(explanation, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView delete = pill(context, LocaleController.getString(R.string.NilakQuizRemove), false, dark);
        delete.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        column.addView(delete, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, Gravity.RIGHT, 0, 14, 0, 0));

        final BottomSheet[] sheet = new BottomSheet[1];
        final boolean[] finished = new boolean[1];
        final Runnable save = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            final WriterQuiz out = new WriterQuiz();
            out.question = question.getText().toString();
            for (int i = 0; i < fields.size(); i++) {
                final String value = fields.get(i).getText().toString().trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (i == correct[0]) {
                    out.correct = out.options.size();
                }
                out.options.add(value);
            }
            out.explanation = explanation.getText().toString();
            onDone.run(out.format());
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
        if (question.length() == 0) {
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
        field.setBackground(Theme.createRoundRectDrawable(dp(14), dark ? 0xFF2A2236 : 0xFFFFFFFF));
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

    /* ---------- in the reader: pick an answer, see right away ---------- */

    public static View readerView(Context context, String question, ArrayList<CharSequence> options, int correct, String explanation, int ink, int gray) {
        final boolean dark = Theme.isCurrentThemeDark();
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        final android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
        frame.setCornerRadius(dp(18));
        frame.setColor(dark ? 0xFF241B34 : 0xFFFFFFFF);
        frame.setStroke(Math.max(1, dp(1)), dark ? 0xFF3A2E50 : 0xFFE6DCF4);
        card.setBackground(frame);
        final TextView label = new TextView(context);
        label.setText("❓  " + LocaleController.getString(R.string.NilakWriterQuiz).toUpperCase());
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        label.setTypeface(TigiStyle.displayExtraBold());
        label.setTextColor(TigiStyle.accent());
        card.addView(label);
        final TextView questionView = new TextView(context);
        questionView.setText(question);
        questionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        questionView.setTypeface(TigiStyle.displayBold());
        questionView.setTextColor(ink);
        card.addView(questionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 10));
        final ArrayList<TextView> rows = new ArrayList<>();
        final TextView result = new TextView(context);
        result.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        result.setTextColor(ink);
        result.setLineSpacing(0, 1.35f);
        result.setVisibility(View.GONE);
        final boolean[] answered = new boolean[1];
        for (int i = 0; i < options.size(); i++) {
            final int index = i;
            final TextView row = new TextView(context);
            row.setText(options.get(i));
            row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            row.setTextColor(ink);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(46));
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), dark ? 0xFF2F2542 : 0xFFF4EEFC, Theme.multAlpha(TigiStyle.accent(), 0.2f)));
            row.setOnClickListener(v -> {
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
                for (int j = 0; j < rows.size(); j++) {
                    final TextView other = rows.get(j);
                    if (j == correct) {
                        other.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.multAlpha(0xFF22C55E, dark ? 0.30f : 0.18f)));
                        other.setText("✓  " + options.get(j));
                        other.setTypeface(TigiStyle.displayBold());
                    } else if (j == index) {
                        other.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.multAlpha(0xFFF0446B, dark ? 0.30f : 0.16f)));
                        other.setText("✕  " + options.get(j));
                    } else {
                        other.setAlpha(0.6f);
                    }
                }
                final boolean right = correct < 0 || index == correct;
                final String verdict = LocaleController.getString(correct < 0 ? R.string.NilakQuizThanks : right ? R.string.NilakQuizRight : R.string.NilakQuizWrong);
                result.setText(TextUtils.isEmpty(explanation) ? verdict : verdict + "\n" + explanation);
                result.setVisibility(View.VISIBLE);
            });
            rows.add(row);
            card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
        card.addView(result, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 4, 2, 0));
        return card;
    }
}
