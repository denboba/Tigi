package org.nilak.writer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.TigiStyle;

import java.util.ArrayList;

/**
 * Tables in the Writer: the model (rows of cells, the first row is the header), the grid drawn in the editor, and a
 * sheet to edit it cell by cell, with rows and columns added, moved and removed like in a modern editor.
 * Stored in the block as "a | b" lines, published as a Markdown table.
 */
public final class WriterTable {

    public static final int MAX_ROWS = 40;
    public static final int MAX_COLUMNS = 8;

    private WriterTable() {
    }

    /* ---------- model ---------- */

    public static ArrayList<ArrayList<String>> parse(String text) {
        final ArrayList<ArrayList<String>> rows = new ArrayList<>();
        int columns = 0;
        if (text != null) {
            for (String line : text.split("\n")) {
                String row = line.trim();
                if (row.isEmpty()) {
                    continue;
                }
                if (row.startsWith("|")) {
                    row = row.substring(1);
                }
                if (row.endsWith("|")) {
                    row = row.substring(0, row.length() - 1);
                }
                final ArrayList<String> cells = new ArrayList<>();
                for (String cell : row.split("\\|", -1)) {
                    cells.add(cell.trim());
                }
                columns = Math.max(columns, cells.size());
                rows.add(cells);
            }
        }
        for (ArrayList<String> row : rows) {
            while (row.size() < columns) {
                row.add("");
            }
        }
        return rows;
    }

    public static String format(ArrayList<ArrayList<String>> rows) {
        final StringBuilder out = new StringBuilder();
        for (ArrayList<String> row : rows) {
            boolean any = false;
            for (String cell : row) {
                any |= !cell.trim().isEmpty();
            }
            if (!any && out.length() > 0) {
                continue; // empty body rows are dropped; the header stays
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) {
                    out.append(" | ");
                }
                out.append(clean(row.get(c)));
            }
        }
        return out.toString();
    }

    /** A cell is one line, and | would split it. */
    static String clean(String cell) {
        return cell == null ? "" : cell.replace('\n', ' ').replace('|', '/').trim();
    }

    public static String starter() {
        return LocaleController.getString(R.string.NilakTableColumn) + " 1 | " + LocaleController.getString(R.string.NilakTableColumn) + " 2 | "
            + LocaleController.getString(R.string.NilakTableColumn) + " 3\n | | \n | | ";
    }

    /* ---------- the grid in the editor ---------- */

    /** Lays out and draws the table read-only inside a block: a tinted header, thin rules, a rounded frame. */
    static final class Grid {
        private final TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint cellPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<StaticLayout[]> layouts = new ArrayList<>();
        private final ArrayList<Integer> heights = new ArrayList<>();
        private String laidOut;
        private int laidWidth;
        private int columns;
        int height;

        Grid() {
            headerPaint.setTypeface(TigiStyle.displayBold());
            headerPaint.setTextSize(dp(14));
            cellPaint.setTextSize(dp(15));
        }

        void layout(String text, int width, int ink) {
            headerPaint.setColor(TigiStyle.accent());
            cellPaint.setColor(ink);
            if (text.equals(laidOut) && width == laidWidth) {
                return;
            }
            laidOut = text;
            laidWidth = width;
            layouts.clear();
            heights.clear();
            final ArrayList<ArrayList<String>> rows = parse(text);
            columns = rows.isEmpty() ? 0 : rows.get(0).size();
            height = 0;
            if (columns == 0 || width <= 0) {
                return;
            }
            final int cellWidth = Math.max(dp(40), width / columns - dp(20));
            for (int r = 0; r < rows.size(); r++) {
                final StaticLayout[] line = new StaticLayout[columns];
                int rowHeight = dp(22);
                for (int c = 0; c < columns; c++) {
                    final String cell = rows.get(r).get(c);
                    line[c] = StaticLayout.Builder.obtain(cell, 0, cell.length(), r == 0 ? headerPaint : cellPaint, cellWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build();
                    rowHeight = Math.max(rowHeight, line[c].getHeight());
                }
                layouts.add(line);
                heights.add(rowHeight + dp(20));
                height += rowHeight + dp(20);
            }
        }

        void draw(Canvas canvas, float left, float top, int width, boolean dark) {
            if (columns == 0) {
                return;
            }
            final int border = dark ? 0xFF3A3346 : 0xFFE3D9F2;
            final float radius = dp(14);
            canvas.save();
            final android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(left, top, left + width, top + height, radius, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(dark ? 0xFF1B1820 : 0xFFFFFFFF);
            canvas.drawRect(left, top, left + width, top + height, fill);
            float y = top;
            final float columnWidth = width / (float) columns;
            for (int r = 0; r < layouts.size(); r++) {
                final int rowHeight = heights.get(r);
                if (r == 0) {
                    fill.setColor(Theme.multAlpha(TigiStyle.accent(), dark ? 0.22f : 0.10f));
                    canvas.drawRect(left, y, left + width, y + rowHeight, fill);
                } else if (r % 2 == 0) {
                    fill.setColor(dark ? 0x0CFFFFFF : 0xFFFAF7FE);
                    canvas.drawRect(left, y, left + width, y + rowHeight, fill);
                }
                final StaticLayout[] line = layouts.get(r);
                for (int c = 0; c < columns; c++) {
                    canvas.save();
                    canvas.translate(left + columnWidth * c + dp(10), y + dp(10));
                    line[c].draw(canvas);
                    canvas.restore();
                }
                y += rowHeight;
                if (r < layouts.size() - 1) {
                    fill.setColor(border);
                    canvas.drawRect(left, y - Math.max(1, dp(1) / 2f), left + width, y, fill);
                }
            }
            fill.setColor(border);
            for (int c = 1; c < columns; c++) {
                final float x = left + columnWidth * c;
                canvas.drawRect(x - Math.max(1, dp(1) / 2f), top, x, top + height, fill);
            }
            canvas.restore();
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1, dp(1)));
            canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, fill);
            fill.setStyle(Paint.Style.FILL);
        }
    }

    /* ---------- the editing sheet ---------- */

    /** Opens the table to edit; onDone gets the new text, onDelete removes the block. */
    public static void edit(BaseFragment fragment, String text, Utilities.Callback<String> onDone, Runnable onDelete) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        new Editor(fragment, context, parse(TextUtils.isEmpty(text) ? starter() : text), onDone, onDelete).show();
    }

    private static final class Editor {
        private final BaseFragment fragment;
        private final Context context;
        private final ArrayList<ArrayList<String>> rows;
        private final Utilities.Callback<String> onDone;
        private final Runnable onDelete;
        private final boolean dark = Theme.isCurrentThemeDark();
        private LinearLayout grid;
        private BottomSheet sheet;
        private boolean finished;

        Editor(BaseFragment fragment, Context context, ArrayList<ArrayList<String>> rows, Utilities.Callback<String> onDone, Runnable onDelete) {
            this.fragment = fragment;
            this.context = context;
            this.rows = rows;
            this.onDone = onDone;
            this.onDelete = onDelete;
            if (rows.isEmpty()) {
                rows.addAll(parse(starter()));
            }
        }

        void show() {
            final LinearLayout page = new LinearLayout(context);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(0, dp(4), 0, dp(12));

            final LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(20), dp(10), dp(14), dp(6));
            final TextView title = new TextView(context);
            title.setText(LocaleController.getString(R.string.NilakWriterTable));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
            title.setTypeface(TigiStyle.displayExtraBold());
            title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final TextView done = pill(LocaleController.getString(R.string.Done), true);
            done.setOnClickListener(v -> finish(true));
            header.addView(done, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
            page.addView(header);

            final TextView hint = new TextView(context);
            hint.setText(LocaleController.getString(R.string.NilakTableHint));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            hint.setPadding(dp(20), 0, dp(20), dp(10));
            page.addView(hint);

            final ScrollView vertical = new ScrollView(context);
            final HorizontalScrollView horizontal = new HorizontalScrollView(context);
            horizontal.setHorizontalScrollBarEnabled(false);
            grid = new LinearLayout(context);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(dp(16), 0, dp(16), 0);
            horizontal.addView(grid);
            vertical.addView(horizontal);
            page.addView(vertical, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

            final LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(dp(16), dp(12), dp(16), 0);
            final TextView addRow = pill("＋ " + LocaleController.getString(R.string.NilakTableRow), false);
            addRow.setOnClickListener(v -> {
                collect();
                if (rows.size() < MAX_ROWS) {
                    rows.add(emptyRow());
                    rebuild(rows.size() - 1, 0);
                }
            });
            actions.addView(addRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
            final TextView addColumn = pill("＋ " + LocaleController.getString(R.string.NilakTableColumn), false);
            addColumn.setOnClickListener(v -> {
                collect();
                if (columns() < MAX_COLUMNS) {
                    for (ArrayList<String> row : rows) {
                        row.add("");
                    }
                    rebuild(0, columns() - 1);
                }
            });
            actions.addView(addColumn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
            actions.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
            final TextView delete = pill(LocaleController.getString(R.string.Delete), false);
            delete.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            delete.setOnClickListener(v -> {
                finished = true;
                sheet.dismiss();
                if (onDelete != null) {
                    onDelete.run();
                }
            });
            actions.addView(delete, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
            page.addView(actions);

            final int heightDp = Math.round(AndroidUtilities.displaySize.y * 0.62f / AndroidUtilities.density);
            final FrameLayout holder = new FrameLayout(context);
            holder.addView(page, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, heightDp));
            final BottomSheet.Builder builder = new BottomSheet.Builder(context, true);
            builder.setCustomView(holder);
            sheet = builder.create();
            sheet.setOnDismissListener(d -> finish(false));
            rebuild(-1, -1);
            fragment.showDialog(sheet);
        }

        private int columns() {
            return rows.isEmpty() ? 0 : rows.get(0).size();
        }

        private ArrayList<String> emptyRow() {
            final ArrayList<String> row = new ArrayList<>();
            for (int c = 0; c < columns(); c++) {
                row.add("");
            }
            return row;
        }

        /** The cells into the model before the grid changes. */
        private void collect() {
            for (int r = 0; r < grid.getChildCount() && r < rows.size(); r++) {
                final LinearLayout line = (LinearLayout) grid.getChildAt(r);
                for (int c = 0; c < line.getChildCount() && c < rows.get(r).size(); c++) {
                    final View child = line.getChildAt(c);
                    if (child instanceof EditText) {
                        rows.get(r).set(c, clean(((EditText) child).getText().toString()));
                    }
                }
            }
        }

        private void rebuild(int focusRow, int focusColumn) {
            grid.removeAllViews();
            final int columns = columns();
            final int border = dark ? 0xFF3A3346 : 0xFFE3D9F2;
            for (int r = 0; r < rows.size(); r++) {
                final LinearLayout line = new LinearLayout(context);
                line.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < columns; c++) {
                    final EditText cell = new EditText(context);
                    cell.setText(rows.get(r).get(c));
                    cell.setSingleLine(true);
                    cell.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES);
                    cell.setImeOptions(EditorInfo.IME_ACTION_NEXT);
                    cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, r == 0 ? 14 : 15);
                    cell.setTypeface(r == 0 ? TigiStyle.displayBold() : android.graphics.Typeface.DEFAULT);
                    cell.setTextColor(r == 0 ? TigiStyle.accent() : Theme.getColor(Theme.key_dialogTextBlack));
                    cell.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
                    cell.setHint(r == 0 ? LocaleController.getString(R.string.NilakTableHeader) : "");
                    cell.setPadding(dp(12), dp(10), dp(12), dp(10));
                    final GradientDrawable box = new GradientDrawable();
                    box.setColor(r == 0 ? Theme.multAlpha(TigiStyle.accent(), dark ? 0.22f : 0.10f) : (dark ? 0xFF1B1820 : 0xFFFFFFFF));
                    box.setStroke(Math.max(1, dp(1) / 2), border);
                    cell.setBackground(box);
                    cell.setPadding(dp(12), dp(10), dp(12), dp(10));
                    final int row = r;
                    final int column = c;
                    cell.setOnLongClickListener(v -> {
                        menu(v, row, column);
                        return true;
                    });
                    line.addView(cell, LayoutHelper.createLinear(132, LayoutHelper.WRAP_CONTENT));
                    if (r == focusRow && c == focusColumn) {
                        cell.post(() -> {
                            cell.requestFocus();
                            AndroidUtilities.showKeyboard(cell);
                        });
                    }
                }
                // a handle at the end of each row: its menu
                final TextView more = new TextView(context);
                more.setText("⋯");
                more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
                more.setGravity(Gravity.CENTER);
                more.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
                more.setContentDescription(LocaleController.getString(R.string.NilakTableRowMenu));
                final int row = r;
                more.setOnClickListener(v -> menu(v, row, -1));
                line.addView(more, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
                grid.addView(line);
            }
        }

        /** Insert or remove the row (and the column, when a cell was long-pressed). */
        private void menu(View anchor, int row, int column) {
            collect();
            final ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context);
            final ActionBarPopupWindow[] window = new ActionBarPopupWindow[1];
            final ArrayList<Object[]> items = new ArrayList<>();
            items.add(new Object[]{R.string.NilakTableRowAbove, (Runnable) () -> {
                if (row > 0 && rows.size() < MAX_ROWS) {
                    rows.add(row, emptyRow());
                    rebuild(row, 0);
                }
            }});
            items.add(new Object[]{R.string.NilakTableRowBelow, (Runnable) () -> {
                if (rows.size() < MAX_ROWS) {
                    rows.add(row + 1, emptyRow());
                    rebuild(row + 1, 0);
                }
            }});
            if (row > 0) {
                items.add(new Object[]{R.string.NilakTableRowDelete, (Runnable) () -> {
                    rows.remove(row);
                    rebuild(-1, -1);
                }});
            }
            if (column >= 0) {
                items.add(new Object[]{R.string.NilakTableColumnLeft, (Runnable) () -> {
                    if (columns() < MAX_COLUMNS) {
                        for (ArrayList<String> r : rows) {
                            r.add(column, "");
                        }
                        rebuild(0, column);
                    }
                }});
                items.add(new Object[]{R.string.NilakTableColumnRight, (Runnable) () -> {
                    if (columns() < MAX_COLUMNS) {
                        for (ArrayList<String> r : rows) {
                            r.add(column + 1, "");
                        }
                        rebuild(0, column + 1);
                    }
                }});
                if (columns() > 1) {
                    items.add(new Object[]{R.string.NilakTableColumnDelete, (Runnable) () -> {
                        for (ArrayList<String> r : rows) {
                            r.remove(column);
                        }
                        rebuild(-1, -1);
                    }});
                }
            }
            for (Object[] item : items) {
                final ActionBarMenuSubItem sub = new ActionBarMenuSubItem(context, false, false);
                sub.setText(LocaleController.getString((Integer) item[0]));
                final Runnable action = (Runnable) item[1];
                sub.setOnClickListener(v -> {
                    if (window[0] != null) {
                        window[0].dismiss();
                    }
                    action.run();
                });
                layout.addView(sub);
            }
            window[0] = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
            window[0].setOutsideTouchable(true);
            window[0].setFocusable(true);
            window[0].setAnimationStyle(R.style.PopupContextAnimation);
            window[0].showAsDropDown(anchor, 0, -dp(8));
        }

        private void finish(boolean save) {
            if (finished) {
                return;
            }
            finished = true;
            collect();
            if (sheet != null && sheet.isShowing()) {
                sheet.dismiss();
            }
            // closing the sheet keeps the edits too, like every other change in the Writer
            onDone.run(format(rows));
        }

        private TextView pill(String text, boolean primary) {
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
    }
}
