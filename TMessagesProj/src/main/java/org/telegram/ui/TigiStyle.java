package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Nilak brand: one violet used sparingly (buttons, active tab, links), lilac only for decoration (story rings),
 * and one colour per job for likes and online. The display font is Plus Jakarta Sans (SIL OFL, bundled).
 */
public class TigiStyle {

    public static final int PRIMARY = 0xFF7138D5;
    public static final int PRIMARY_PRESSED = 0xFF5B2BB5;
    public static final int LILAC = 0xFFB996F0;
    public static final int LIKE = 0xFFF0446B;

    // story rings and other decoration: primary into lilac
    public static final int RING_START = PRIMARY;
    public static final int RING_END = LILAC;

    private static Typeface displayBold;
    private static Typeface displayExtraBold;

    /** The theme's accent (the Nilak violet in the default themes, the user's accent otherwise). */
    public static int accent() {
        return Theme.getColor(Theme.key_featuredStickers_addButton);
    }

    /** Primary button: a rounded pill in the accent with a touch ripple. */
    public static Drawable primaryPill(int radiusDp) {
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(accent());
        fill.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, null);
    }

    /** Secondary button: a rounded pill tinted with the accent. */
    public static Drawable tintedPill(int radiusDp) {
        final GradientDrawable fill = new GradientDrawable();
        fill.setColor(Theme.multAlpha(accent(), 0.12f));
        fill.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(Theme.multAlpha(accent(), 0.25f)), fill, null);
    }

    /** Plus Jakarta Sans Bold for names, buttons and counts; the app's bold font when the file is missing. */
    public static Typeface displayBold() {
        if (displayBold == null) {
            displayBold = load("fonts/PlusJakartaSans-Bold.ttf");
        }
        return displayBold;
    }

    /** Plus Jakarta Sans ExtraBold for screen titles and big numbers. */
    public static Typeface displayExtraBold() {
        if (displayExtraBold == null) {
            displayExtraBold = load("fonts/PlusJakartaSans-ExtraBold.ttf");
        }
        return displayExtraBold;
    }

    private static Typeface load(String asset) {
        try {
            final Typeface typeface = AndroidUtilities.getTypeface(asset);
            if (typeface != null) {
                return typeface;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return AndroidUtilities.bold();
    }
}
