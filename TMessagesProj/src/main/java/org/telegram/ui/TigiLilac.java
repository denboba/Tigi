package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Nilak Lilac: the design language of the Nilak screens. A pale lavender page with soft lilac and spring-green
 * glows (a lilac night in dark mode), white rounded cards, ink-violet icons, one lilac sprig in the top corner.
 * Everything here is drawn once per size change: no blur, no images to decode beyond one vector.
 */
public final class TigiLilac {

    private static final String KEY_FLOWERS = "nilak_flowers";

    private TigiLilac() {
    }

    /** The Lilac look is on (build flag). */
    public static boolean on() {
        return BuildVars.NILAK_LILAC;
    }

    /** The lilac sprigs: on by default, the person can turn them off in Settings. */
    public static boolean flowers() {
        return on() && prefs().getBoolean(KEY_FLOWERS, true);
    }

    public static void setFlowers(boolean value) {
        prefs().edit().putBoolean(KEY_FLOWERS, value).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nilak_design", Context.MODE_PRIVATE);
    }

    public static boolean dark() {
        return Theme.isCurrentThemeDark();
    }

    /** Cards: white on the lilac page, deep violet at night. */
    public static int card() {
        return dark() ? 0xFF2A1F3C : 0xFFFFFFFF;
    }

    /** Blocks inside a card (poll options, music, files): a shade of the card, never the window colour. */
    public static int inset() {
        if (!on()) {
            return Theme.getColor(Theme.key_windowBackgroundGray);
        }
        return dark() ? 0xFF372A4D : 0xFFF2ECFA;
    }

    /** Icons and strong text on cards and the page. */
    public static int ink() {
        return dark() ? 0xFFE9DFF8 : 0xFF2A1D40;
    }

    /** Small grey text (times, views, "View all comments"): 4.5:1 or better on both the card and the page. */
    public static int meta() {
        return dark() ? 0xFFB3A6C9 : 0xFF6A5E80;
    }

    /** Behind small round buttons on the page (search, bell). */
    public static int chip() {
        return dark() ? 0xD9322646 : 0xC7FFFFFF;
    }

    /** The page colour at the top, for bars that sit over the scrolling list. */
    public static int pageTop() {
        return dark() ? 0xFF1A1326 : 0xFFF5EFFD;
    }

    /** The lilac page: a gentle vertical gradient with three soft glows. */
    public static Drawable page() {
        return new PageDrawable(false);
    }

    /** The same page for a bar at the top of the screen: only the top glow, so it meets the page without a seam. */
    public static Drawable pageBar() {
        return new PageDrawable(true);
    }

    /**
     * The sprig in the top-right corner (top-left in RTL), added to a FrameLayout under its buttons. Returns null
     * when flowers are off. Never covers content: it sits inside the header only.
     */
    public static ImageView addSprig(FrameLayout parent, int sizeDp) {
        if (!flowers()) {
            return null;
        }
        final ImageView sprig = new ImageView(parent.getContext());
        sprig.setImageResource(R.drawable.nilak_lilac_sprig);
        sprig.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sprig.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sprig.setAlpha(dark() ? 0.55f : 1f);
        if (LocaleController.isRTL) {
            sprig.setScaleX(-1f);
        }
        final int side = LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT;
        parent.addView(sprig, 0, LayoutHelper.createFrame(sizeDp, sizeDp, Gravity.TOP | side, 0, -sizeDp * 0.30f, -sizeDp * 0.12f, 0));
        return sprig;
    }

    /** Small round "glass" circle behind header buttons so they read over the sprig. */
    public static Drawable roundButton() {
        return Theme.createSimpleSelectorCircleDrawable(dp(40), chip(), Theme.multAlpha(TigiStyle.accent(), 0.2f));
    }

    private static final class PageDrawable extends Drawable {
        private final boolean bar;
        private final Paint base = new Paint();
        private final Paint glowTop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowLeft = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowBottom = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean builtDark;

        PageDrawable(boolean bar) {
            this.bar = bar;
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            build(bounds);
        }

        private void build(Rect b) {
            final int w = b.width();
            final int h = b.height();
            if (w <= 0 || h <= 0) {
                return;
            }
            builtDark = dark();
            final int top = builtDark ? 0xFF1A1326 : 0xFFF5EFFD;
            final int bottom = builtDark ? 0xFF21172F : 0xFFF1EAFA;
            // the bar uses the page's colours over the first screen height, so both meet without a seam
            base.setShader(new LinearGradient(0, b.top, 0, b.top + (bar ? Math.max(h, dp(800)) : h), top, bottom, Shader.TileMode.CLAMP));
            // top-right glow sits at a fixed distance from the top on every screen size
            final float rTop = w * 0.62f;
            glowTop.setShader(new RadialGradient(b.left + w * 0.88f, b.top + dp(40), rTop,
                builtDark ? 0x80764ABE : 0xF2D6BCF6, 0x00000000, Shader.TileMode.CLAMP));
            if (!bar) {
                glowLeft.setShader(new RadialGradient(b.left + w * 0.08f, b.top + h * 0.55f, w * 0.7f,
                    builtDark ? 0x383C6946 : 0xBFE2F0DA, 0x00000000, Shader.TileMode.CLAMP));
                glowBottom.setShader(new RadialGradient(b.left + w * 0.9f, b.top + h * 0.84f, w * 0.8f,
                    builtDark ? 0x40683FA8 : 0xF2EAD6FA, 0x00000000, Shader.TileMode.CLAMP));
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            if (builtDark != dark()) {
                build(b);
            }
            canvas.drawRect(b, base);
            if (!bar) {
                canvas.drawRect(b, glowLeft);
                canvas.drawRect(b, glowBottom);
            }
            canvas.drawRect(b, glowTop);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
