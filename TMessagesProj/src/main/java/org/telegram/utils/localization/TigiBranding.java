package org.telegram.utils.localization;

import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Turns Telegram's own wording (bundled and downloaded translations) into Nilak wording.
 */
public class TigiBranding {

    // Messages that really describe the official Telegram app (where login codes arrive).
    private static final HashSet<Integer> PROTECTED = new HashSet<>();

    static {
        PROTECTED.add("SentAppCode".hashCode());
        PROTECTED.add("SentAppCodeWithPhone".hashCode());
        PROTECTED.add("SentAppCodeTitle".hashCode());
        // These mean the official app or another Telegram client, renaming them would say something false
        PROTECTED.add("OtherLoginCode".hashCode());
        PROTECTED.add("SessionsListInfo".hashCode());
        PROTECTED.add("SubscribeToPremiumOfficialAppNeededDescription".hashCode());
        PROTECTED.add("ProfileUnofficialSecurityRisk".hashCode());
        PROTECTED.add("AuthAnotherClientInfo1".hashCode());
        PROTECTED.add("AdHidden".hashCode());
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
        // "Telegram Premium", "Telegram Stars", "Telegram FAQ" ... become "Nilak Premium", "Nilak Stars", "Nilak FAQ" ...
        return WORD.matcher(value).replaceAll("Nilak");
    }
}
