package org.telegram.utils.localization;

import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Turns Telegram's own wording (bundled and downloaded translations) into Tigi wording.
 */
public class TigiBranding {

    // Messages that really describe the official Telegram app (where login codes arrive).
    private static final HashSet<Integer> PROTECTED = new HashSet<>();

    static {
        PROTECTED.add("SentAppCode".hashCode());
        PROTECTED.add("SentAppCodeWithPhone".hashCode());
        PROTECTED.add("SentAppCodeTitle".hashCode());
    }

    // The word Telegram, but not inside links, mentions or the names of Telegram's other apps.
    private static final Pattern WORD = Pattern.compile("(?<![/@.\\w])Telegram(?!\\.org)(?!\\s+(?:Desktop|Web)\\b)(?!\\w)");

    public static boolean isProtectedKey(int resNameHash) {
        return PROTECTED.contains(resNameHash);
    }

    public static String apply(String value) {
        if (value == null || value.indexOf("Telegram") < 0) {
            return value;
        }
        String result = value;
        result = result.replace("Telegram Premium", "Premium");
        result = result.replace("Telegram Stars", "Stars");
        result = result.replace("Telegram Business", "Business");
        result = result.replace("Telegram Passport", "Passport");
        result = result.replace("Telegram Ads", "Ads");
        result = result.replace("Telegram Ad", "Ad");
        result = result.replace("Telegram FAQ", "FAQ");
        result = result.replace("Telegram Support", "Support");
        return WORD.matcher(result).replaceAll("Tigi");
    }
}
