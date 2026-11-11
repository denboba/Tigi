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
        // the corner sprigs were retired after testing: the page glows and the floret pattern carry the look
        return false;
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

    /** Cards: white on the calm page, graphite at night. */
    public static int card() {
        return dark() ? 0xFF1D1C23 : 0xFFFFFFFF;
    }

    /** The thin edge around cards, so they stand apart from the page without heavy shadows. */
    public static int hairline() {
        return dark() ? 0xFF2C2A34 : 0xFFE6E4EC;
    }

    /** Blocks inside a card (poll options, music, files): a shade of the card, never the window colour. */
    public static int inset() {
        if (!on()) {
            return Theme.getColor(Theme.key_windowBackgroundGray);
        }
        return dark() ? 0xFF27252F : 0xFFF4F3F7;
    }

    /** Icons and strong text on cards and the page. */
    public static int ink() {
        return dark() ? 0xFFECEAF2 : 0xFF1E1C24;
    }

    /** Small grey text (times, views, "View all comments"): 4.5:1 or better on both the card and the page. */
    public static int meta() {
        return dark() ? 0xFFA6A2B2 : 0xFF6A6775;
    }

    /** Behind small round buttons on the page (search, bell). */
    public static int chip() {
        return dark() ? 0xF2262430 : 0xF5FFFFFF;
    }

    /** The page colour at the top, for bars that sit over the scrolling list. */
    public static int pageTop() {
        return dark() ? PAGE_TOP_DARK : PAGE_TOP_LIGHT;
    }

    // A calm, nearly neutral page: soft grey-white by day, graphite at night, with only a hint of violet at the top
    private static final int PAGE_TOP_LIGHT = 0xFFF7F6FA;
    private static final int PAGE_BOTTOM_LIGHT = 0xFFF3F2F6;
    private static final int PAGE_TOP_DARK = 0xFF131217;
    private static final int PAGE_BOTTOM_DARK = 0xFF15141A;

    /**
     * Nilak night for Telegram's own dark themes: graphite surfaces, violet outgoing bubbles, and the blue or
     * green accents inside your messages turned lilac. Text colours stay as the theme made them (they are light).
     */
    public static void tintNight(android.util.SparseIntArray colors) {
        final android.util.SparseIntArray fixed = new android.util.SparseIntArray();
        fixed.put(Theme.key_windowBackgroundWhite, 0xFF17161C);
        fixed.put(Theme.key_windowBackgroundGray, 0xFF111015);
        fixed.put(Theme.key_actionBarDefault, 0xFF17161C);
        fixed.put(Theme.key_actionBarDefaultSubmenuBackground, 0xFF201F27);
        fixed.put(Theme.key_dialogBackground, 0xFF1E1D24);
        fixed.put(Theme.key_dialogBackgroundGray, 0xFF17161C);
        fixed.put(Theme.key_windowBackgroundWhiteGrayText, 0xFFA09CAB);
        fixed.put(Theme.key_chat_inBubble, 0xFF23222A);
        fixed.put(Theme.key_chat_inBubbleSelected, 0xFF2C2A34);
        fixed.put(Theme.key_chat_outBubble, 0xFF46387A);
        fixed.put(Theme.key_chat_outBubbleSelected, 0xFF51428A);
        final float[] hsv = new float[3];
        for (int i = 0; i < colors.size(); i++) {
            final int key = colors.keyAt(i);
            final int fixedColor = fixed.get(key, 0);
            if (fixedColor != 0) {
                colors.put(key, fixedColor);
                continue;
            }
            final String name = org.telegram.ui.ActionBar.ThemeColors.getStringName(key);
            if (name == null || !name.startsWith("chat_out") || name.startsWith("chat_outGreenCall") || name.startsWith("chat_outPollCorrect")) {
                continue;
            }
            final int color = colors.valueAt(i);
            android.graphics.Color.colorToHSV(color, hsv);
            if (hsv[1] > 0.15f && hsv[0] >= 80 && hsv[0] <= 250) {
                hsv[0] = 268;
                colors.put(key, android.graphics.Color.HSVToColor(android.graphics.Color.alpha(color), hsv));
            }
        }
        for (int i = 0; i < fixed.size(); i++) {
            if (colors.indexOfKey(fixed.keyAt(i)) < 0) {
                colors.put(fixed.keyAt(i), fixed.valueAt(i));
            }
        }
    }

    /** The page: a calm, nearly neutral gradient with one faint violet glow at the top. */
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
        // the top margin cancels its height, so the sprig never makes the header taller; it is moved into place
        // with a translation, hanging from the top edge and peeking in from the side
        parent.addView(sprig, 0, LayoutHelper.createFrame(sizeDp, sizeDp, Gravity.TOP | side, 0, -sizeDp, -sizeDp * 0.22f, 0));
        sprig.setTranslationY(dp(sizeDp * 0.72f));
        return sprig;
    }

    /** Empty screens: a small lilac sprig above the message, so an empty page still looks finished. */
    public static void decorateEmpty(android.widget.TextView view) {
        if (!flowers() || view == null) {
            return;
        }
        final Drawable sprig = view.getContext().getResources().getDrawable(R.drawable.nilak_lilac_sprig).mutate();
        sprig.setBounds(0, 0, dp(96), dp(96));
        sprig.setAlpha(dark() ? 150 : 255);
        view.setCompoundDrawables(null, sprig, null, null);
        view.setCompoundDrawablePadding(dp(14));
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
            final int top = builtDark ? PAGE_TOP_DARK : PAGE_TOP_LIGHT;
            final int bottom = builtDark ? PAGE_BOTTOM_DARK : PAGE_BOTTOM_LIGHT;
            // the bar uses the page's colours over the first screen height, so both meet without a seam
            base.setShader(new LinearGradient(0, b.top, 0, b.top + (bar ? Math.max(h, dp(800)) : h), top, bottom, Shader.TileMode.CLAMP));
            // top-right glow sits at a fixed distance from the top on every screen size
            final float rTop = w * 0.62f;
            glowTop.setShader(new RadialGradient(b.left + w * 0.88f, b.top + dp(40), rTop,
                builtDark ? 0x247B5BC4 : 0x33D8C8F2, 0x00000000, Shader.TileMode.CLAMP));
            if (!bar) {
                // No coloured side glows: the page stays quiet so cards and photos carry the colour
                glowLeft.setShader(null);
                glowBottom.setShader(new RadialGradient(b.left + w * 0.9f, b.top + h * 0.84f, w * 0.8f,
                    builtDark ? 0x127B5BC4 : 0x1AD8C8F2, 0x00000000, Shader.TileMode.CLAMP));
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
