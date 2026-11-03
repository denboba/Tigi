package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;

/**
 * Nilak: reads a PDF from a post inside the app. Pages are rendered one by one as they scroll into view (on
 * TigiPdf's queue, at screen width) and kept in a small cache; "Open in..." hands the file to another app, for
 * zooming, printing or signing. Password-protected or damaged files say so and offer the same button.
 */
public class TigiPdfViewerActivity extends BaseFragment {

    private final MessageObject message;
    private final File file;
    private volatile TigiPdf.Document document;
    private int pageCount;
    private float pageAspect = 1.414f;
    private boolean destroyed;

    private RecyclerView list;
    private TextView pageLabel;
    private TextView errorView;
    private PagesAdapter adapter;
    private int pageWidth;

    private int renderWidth;
    private ZoomFrame zoomFrame;

    private final HashSet<Integer> rendering = new HashSet<>();
    /** Rendered pages; sized to the phone's memory (a fifth of what the app may use, at most 64 MB). */
    private final LruCache<Integer, Bitmap> pages = new LruCache<Integer, Bitmap>((int) Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 5)) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Runnable hideLabel = () -> {
        if (pageLabel != null) {
            pageLabel.animate().alpha(0f).setDuration(250).start();
        }
    };

    public TigiPdfViewerActivity(MessageObject message, File file) {
        this.message = message;
        this.file = file;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.isCurrentThemeDark() ? 0xFF111111 : 0xFFE9E9EE);

        pageWidth = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) - dp(16);
        // a little sharper than the screen, so text stays readable when zoomed in
        renderWidth = Math.min(Math.round(pageWidth * 1.5f), 1600);
        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setClipToPadding(false);
        list.setPadding(dp(8), AndroidUtilities.statusBarHeight + dp(64), dp(8), dp(72));
        adapter = new PagesAdapter();
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updatePageLabel();
            }
        });
        zoomFrame = new ZoomFrame(context);
        zoomFrame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(zoomFrame, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // top bar: back, file name, open in another app
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        bar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> finishFragment());
        if (LocaleController.isRTL) {
            back.setScaleX(-1f);
        }
        bar.addView(back, LayoutHelper.createLinear(48, 56));
        final TextView title = new TextView(context);
        final String name = message != null ? FileLoader.getDocumentFileName(message.getDocument()) : file.getName();
        title.setText(TextUtils.isEmpty(name) ? "PDF" : name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(TigiStyle.displayBold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        bar.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));
        final ImageView openIn = new ImageView(context);
        openIn.setScaleType(ImageView.ScaleType.CENTER);
        openIn.setImageResource(R.drawable.msg_openin);
        openIn.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        openIn.setContentDescription(LocaleController.getString(R.string.OpenInExternalApp));
        openIn.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        openIn.setOnClickListener(v -> openExternally());
        bar.addView(openIn, LayoutHelper.createLinear(48, 56));
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // "3 / 12" at the bottom
        pageLabel = new TextView(context);
        pageLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pageLabel.setTypeface(TigiStyle.displayBold());
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setPadding(dp(14), dp(6), dp(14), dp(6));
        pageLabel.setBackground(Theme.createRoundRectDrawable(dp(16), 0xB3111111));
        pageLabel.setVisibility(View.GONE);
        root.addView(pageLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));

        // shown instead of the pages when the file can't be drawn here
        errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        errorView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(32), 0, dp(32), 0);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = root;
        openDocument();
        return fragmentView;
    }

    private void openDocument() {
        TigiPdf.queue.postRunnable(() -> {
            int count = 0;
            float aspect = 1.414f;
            int error = 0;
            try {
                document = TigiPdf.Document.open(file);
                if (document == null) {
                    error = R.string.NilakPdfCantShow;
                } else {
                    count = document.pageCount();
                    if (count > 0) {
                        aspect = Math.min(3f, document.aspect(0));
                    }
                }
            } catch (SecurityException e) {
                error = R.string.NilakPdfLocked; // password-protected
            } catch (Throwable e) {
                FileLog.e(e);
                error = R.string.NilakPdfCantShow;
            }
            final int pagesFound = count;
            final float firstAspect = aspect;
            final int errorText = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) {
                    return;
                }
                if (errorText != 0 || pagesFound == 0) {
                    errorView.setText(LocaleController.getString(errorText != 0 ? errorText : R.string.NilakPdfCantShow));
                    errorView.setVisibility(View.VISIBLE);
                    return;
                }
                pageCount = pagesFound;
                pageAspect = firstAspect;
                adapter.notifyDataSetChanged();
                updatePageLabel();
            });
        });
    }

    private void requestPage(int index) {
        if (document == null || rendering.contains(index) || pages.get(index) != null) {
            return;
        }
        rendering.add(index);
        final int width = renderWidth;
        TigiPdf.queue.postRunnable(() -> {
            Bitmap bitmap = null;
            try {
                if (!destroyed && document != null) {
                    bitmap = document.render(index, width);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                rendering.remove(index);
                if (destroyed || result == null) {
                    return;
                }
                pages.put(index, result);
                adapter.notifyItemChanged(index);
            });
        });
    }

    private void updatePageLabel() {
        if (pageLabel == null || pageCount == 0) {
            return;
        }
        final LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
        int position = manager != null ? manager.findFirstVisibleItemPosition() : 0;
        if (manager != null && manager.findLastCompletelyVisibleItemPosition() == pageCount - 1) {
            position = pageCount - 1;
        }
        pageLabel.setText(String.format(Locale.getDefault(), "%d / %d", Math.max(0, position) + 1, pageCount));
        pageLabel.setVisibility(View.VISIBLE);
        pageLabel.animate().cancel();
        pageLabel.setAlpha(1f);
        AndroidUtilities.cancelRunOnUIThread(hideLabel);
        AndroidUtilities.runOnUIThread(hideLabel, 1500);
    }

    private void openExternally() {
        if (getParentActivity() == null) {
            return;
        }
        try {
            if (message != null) {
                AndroidUtilities.openForView(message, getParentActivity(), getResourceProvider(), false);
            } else {
                AndroidUtilities.openForView(file, file.getName(), "application/pdf", getParentActivity(), getResourceProvider(), false);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(hideLabel);
        final TigiPdf.Document open = document;
        document = null;
        TigiPdf.queue.postRunnable(() -> {
            if (open != null) {
                open.close();
            }
        });
        pages.evictAll();
    }

    /** One page: the rendered bitmap scaled to the width, or a white sheet with its number while it renders. */
    private class PageView extends View {
        private final android.graphics.Paint bitmapPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint numberPaint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Rect destination = new android.graphics.Rect();
        private Bitmap bitmap;
        private int number;

        PageView(Context context) {
            super(context);
            numberPaint.setColor(0xFFB0B0B8);
            numberPaint.setTextSize(dp(28));
            numberPaint.setTypeface(TigiStyle.displayBold());
            numberPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        void set(Bitmap bitmap, int number) {
            this.bitmap = bitmap;
            this.number = number;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            if (bitmap != null && !bitmap.isRecycled()) {
                destination.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(bitmap, null, destination, bitmapPaint);
            } else {
                canvas.drawText(String.valueOf(number), getWidth() / 2f, getHeight() / 2f, numberPaint);
            }
        }
    }

    /**
     * Pinch or double-tap to zoom (1x to 3x) around the fingers; when zoomed, dragging sideways pans the page while
     * up and down still scroll the pages.
     */
    private class ZoomFrame extends FrameLayout {
        private static final float MAX_SCALE = 3f;
        private float scale = 1f;
        private float panX;
        private float lastX;
        private boolean panning;
        private final android.view.ScaleGestureDetector scaleDetector;
        private final android.view.GestureDetector tapDetector;

        ZoomFrame(Context context) {
            super(context);
            scaleDetector = new android.view.ScaleGestureDetector(context, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(android.view.ScaleGestureDetector detector) {
                    zoomTo(scale * detector.getScaleFactor(), detector.getFocusX());
                    return true;
                }
            });
            tapDetector = new android.view.GestureDetector(context, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(android.view.MotionEvent e) {
                    animateZoom(scale > 1.05f ? 1f : 2.5f, e.getX());
                    return true;
                }
            });
        }

        private void zoomTo(float target, float focusX) {
            final float next = Math.max(1f, Math.min(MAX_SCALE, target));
            // keep the point under the fingers where it is
            final float center = getWidth() / 2f;
            final float contentX = (focusX - center - panX) / scale;
            panX = focusX - center - next * contentX;
            scale = next;
            apply();
        }

        private void animateZoom(float target, float focusX) {
            final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(scale, target);
            animator.setDuration(220);
            animator.setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.DEFAULT);
            animator.addUpdateListener(a -> zoomTo((Float) a.getAnimatedValue(), focusX));
            animator.start();
        }

        private void apply() {
            final float limit = (scale - 1f) * getWidth() / 2f;
            panX = Math.max(-limit, Math.min(limit, panX));
            list.setPivotX(getWidth() / 2f);
            list.setPivotY(getHeight() / 2f);
            list.setScaleX(scale);
            list.setScaleY(scale);
            list.setTranslationX(panX);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            tapDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    panning = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (scale > 1.01f && Math.abs(event.getX() - lastX) > dp(8)) {
                        panning = true;
                        lastX = event.getX();
                        return true;
                    }
                    break;
            }
            return event.getPointerCount() > 1 || scaleDetector.isInProgress();
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            tapDetector.onTouchEvent(event);
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && panning) {
                panX += event.getX() - lastX;
                lastX = event.getX();
                apply();
            } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
            } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                panning = false;
            }
            return true;
        }
    }

    private class PagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final PageView page = new PageView(parent.getContext());
            final RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (pageWidth * pageAspect));
            params.bottomMargin = dp(8);
            page.setLayoutParams(params);
            return new RecyclerView.ViewHolder(page) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final PageView page = (PageView) holder.itemView;
            final Bitmap bitmap = pages.get(position);
            final RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) page.getLayoutParams();
            final int height = bitmap != null ? (int) (pageWidth * (bitmap.getHeight() / (float) bitmap.getWidth())) : (int) (pageWidth * pageAspect);
            if (params.height != height) {
                params.height = height;
                page.setLayoutParams(params);
            }
            page.set(bitmap, position + 1);
            page.setContentDescription(String.format(Locale.getDefault(), "%d / %d", position + 1, pageCount));
            if (bitmap == null) {
                requestPage(position);
            }
            // the next page too, so scrolling down rarely shows a blank one
            if (position + 1 < pageCount) {
                requestPage(position + 1);
            }
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }
    }
}
