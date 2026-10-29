package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;

import org.telegram.ui.ActionBar.Theme;

/** Tigi: the brand gradient and accent used on buttons, rings and links across the Tigi screens. */
public class TigiStyle {

    public static final int GRADIENT_START = 0xFF8B5CF6;
    public static final int GRADIENT_END = 0xFFEC4899;

    /** The theme's accent (violet in the Tigi theme, the user's accent otherwise). */
    public static int accent() {
        return Theme.getColor(Theme.key_featuredStickers_addButton);
    }

    /** A rounded pill with the brand gradient and a touch ripple. */
    public static Drawable gradientPill(int radiusDp) {
        final GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{GRADIENT_START, GRADIENT_END});
        fill.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, null);
    }

    /** A rounded pill tinted with the accent, for secondary buttons. */
    public static Drawable tintedPill(int radiusDp) {
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(Theme.multAlpha(accent(), 0.12f));
        fill.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(Theme.multAlpha(accent(), 0.25f)), fill, null);
    }
}
