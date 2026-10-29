package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * Tigi: skeleton placeholders with a moving shimmer while a feed loads. STYLE_CARDS draws post cards (avatar, name,
 * photo, actions, caption), STYLE_REEL a full screen dark reel with its side actions.
 */
public class TigiShimmerView extends View {

    public static final int STYLE_CARDS = 0;
    public static final int STYLE_REEL = 1;

    private final int style;
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private LinearGradient gradient;
    private int gradientColor;
    private int topPadding;

    public TigiShimmerView(Context context, int style) {
        super(context);
        this.style = style;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setTopPadding(int topPadding) {
        this.topPadding = topPadding;
        invalidate();
    }

    private void updatePaints() {
        final int base;
        final int shine;
        if (style == STYLE_REEL) {
            base = 0xFF1C1C1E;
            shine = 0xFF2C2C2E;
        } else {
            final int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
            final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            base = ColorUtils.blendARGB(bg, text, 0.07f);
            shine = ColorUtils.blendARGB(bg, text, 0.15f);
        }
        basePaint.setColor(base);
        if (gradient == null || gradientColor != shine) {
            gradientColor = shine;
            gradient = new LinearGradient(0, 0, dp(240), 0, new int[]{0x00000000, shine, 0x00000000}, null, Shader.TileMode.CLAMP);
            shinePaint.setShader(gradient);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        updatePaints();
        final long now = SystemClock.elapsedRealtime();
        final int width = getWidth();
        final float travel = width + dp(480);
        final float x = (now % 1400) / 1400f * travel - dp(240);
        matrix.setTranslate(x, 0);
        gradient.setLocalMatrix(matrix);
        if (style == STYLE_REEL) {
            drawReel(canvas, basePaint);
            drawReel(canvas, shinePaint);
        } else {
            drawCards(canvas, basePaint);
            drawCards(canvas, shinePaint);
        }
        if (isAttachedToWindow() && getVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        }
    }

    private void drawCards(Canvas canvas, Paint paint) {
        final int width = getWidth();
        final int cardSide = dp(8);
        int y = topPadding + dp(5);
        final float radius = dp(6);
        while (y < getHeight()) {
            final int left = cardSide + dp(12);
            // avatar + name + time
            canvas.drawCircle(left + dp(16), y + dp(26), dp(16), paint);
            rect.set(left + dp(42), y + dp(16), left + dp(42) + width * 0.38f, y + dp(26));
            canvas.drawRoundRect(rect, radius, radius, paint);
            rect.set(left + dp(42), y + dp(31), left + dp(42) + width * 0.2f, y + dp(39));
            canvas.drawRoundRect(rect, radius, radius, paint);
            // media
            final int mediaTop = y + dp(52);
            final int mediaHeight = (int) ((width - cardSide * 2) * 0.9f);
            rect.set(cardSide, mediaTop, width - cardSide, mediaTop + mediaHeight);
            canvas.drawRect(rect, paint);
            // actions
            final int actionsY = mediaTop + mediaHeight + dp(24);
            for (int i = 0; i < 3; i++) {
                canvas.drawCircle(left + dp(10) + i * dp(48), actionsY, dp(11), paint);
            }
            // caption
            rect.set(left, actionsY + dp(24), width - cardSide - dp(40), actionsY + dp(34));
            canvas.drawRoundRect(rect, radius, radius, paint);
            rect.set(left, actionsY + dp(42), left + width * 0.45f, actionsY + dp(52));
            canvas.drawRoundRect(rect, radius, radius, paint);
            y = actionsY + dp(76);
        }
    }

    private void drawReel(Canvas canvas, Paint paint) {
        final int width = getWidth();
        final int height = getHeight();
        final float radius = dp(6);
        final int bottom = height - dp(110);
        // side actions
        final int cx = width - dp(36);
        canvas.drawCircle(cx, bottom - dp(250), dp(22), paint);
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(cx, bottom - dp(180) + i * dp(56), dp(16), paint);
        }
        // name and caption
        rect.set(dp(16), bottom - dp(40), dp(16) + width * 0.4f, bottom - dp(26));
        canvas.drawRoundRect(rect, radius, radius, paint);
        rect.set(dp(16), bottom - dp(16), width - dp(90), bottom - dp(4));
        canvas.drawRoundRect(rect, radius, radius, paint);
        rect.set(dp(16), bottom + dp(6), dp(16) + width * 0.5f, bottom + dp(18));
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}
