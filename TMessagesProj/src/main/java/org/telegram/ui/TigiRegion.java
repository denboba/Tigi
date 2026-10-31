package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Nilak: the country and language whose content comes first in suggestions, Explore and Reels "For you".
 * Defaults from the phone (SIM or network country, system language); chosen on first run and changeable from the
 * profile. Telegram has no per-channel language field, so a channel counts as matching when most letters of its
 * title are in the language's script (Ethiopic, Arabic, Cyrillic, ...). Matching channels move up, nothing is hidden.
 */
public final class TigiRegion {

    private static final String KEY_COUNTRY = "nilak_country";
    private static final String KEY_LANGUAGE = "nilak_language";

    /** Languages offered in the picker; each shows under its own name. */
    private static final String[] LANGUAGES = {
        "en", "am", "ti", "om", "so", "ar", "sw", "fr", "es", "pt", "de", "it", "ru", "uk", "tr", "fa", "hi", "ur", "id", "zh"
    };

    private static final int LATIN = 0, ETHIOPIC = 1, ARABIC = 2, CYRILLIC = 3, HEBREW = 4, DEVANAGARI = 5, GREEK = 6,
        THAI = 7, HANGUL = 8, HAN = 9, OTHER = 10;

    private TigiRegion() {
    }

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    /** Whether the user picked a country and language themselves. */
    public static boolean chosen() {
        return prefs().contains(KEY_COUNTRY) && prefs().contains(KEY_LANGUAGE);
    }

    public static void set(String country, String language) {
        prefs().edit().putString(KEY_COUNTRY, country).putString(KEY_LANGUAGE, language).apply();
    }

    /** ISO country code, upper case; the phone's when none was picked; may be empty. */
    public static String country() {
        final String stored = prefs().getString(KEY_COUNTRY, null);
        if (!TextUtils.isEmpty(stored)) {
            return stored;
        }
        String code = null;
        try {
            final TelephonyManager phone = (TelephonyManager) ApplicationLoader.applicationContext.getSystemService(Context.TELEPHONY_SERVICE);
            if (phone != null) {
                code = phone.getSimCountryIso();
                if (TextUtils.isEmpty(code)) {
                    code = phone.getNetworkCountryIso();
                }
            }
        } catch (Exception ignore) {
        }
        if (TextUtils.isEmpty(code)) {
            code = systemLocale().getCountry();
        }
        return code == null ? "" : code.toUpperCase(Locale.US);
    }

    /** Language code; the system language when none was picked, or the country's main language when that is English. */
    public static String language() {
        final String stored = prefs().getString(KEY_LANGUAGE, null);
        if (!TextUtils.isEmpty(stored)) {
            return stored;
        }
        final String system = systemLocale().getLanguage();
        if (!TextUtils.isEmpty(system) && !"en".equals(system)) {
            return system;
        }
        final String local = countryLanguage(country());
        return local != null ? local : "en";
    }

    private static Locale systemLocale() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                return android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
            }
            return android.content.res.Resources.getSystem().getConfiguration().locale;
        } catch (Exception e) {
            return Locale.getDefault();
        }
    }

    private static String countryLanguage(String country) {
        switch (country) {
            case "ET": return "am";
            case "ER": return "ti";
            case "SO": return "so";
            case "SA": case "AE": case "EG": case "IQ": case "JO": case "KW": case "LB": case "LY": case "MA": case "OM":
            case "QA": case "SD": case "SY": case "TN": case "YE": case "DZ": case "BH": return "ar";
            case "RU": case "BY": case "KZ": case "KG": return "ru";
            case "UA": return "uk";
            case "IR": return "fa";
            case "TR": return "tr";
            case "PK": return "ur";
            case "IL": return "he";
            case "KE": case "TZ": return "sw";
            default: return null;
        }
    }

    public static String countryName(String code) {
        if (TextUtils.isEmpty(code)) {
            return "";
        }
        return new Locale("", code).getDisplayCountry(LocaleController.getInstance().getCurrentLocale());
    }

    /** The country's name in English plus in the chosen language, for searching public channels from there. */
    public static ArrayList<String> countryQueries() {
        final ArrayList<String> out = new ArrayList<>();
        final String code = country();
        if (TextUtils.isEmpty(code)) {
            return out;
        }
        final Locale place = new Locale("", code);
        final String english = place.getDisplayCountry(Locale.ENGLISH);
        if (!TextUtils.isEmpty(english) && !english.equals(code)) {
            out.add(english);
        }
        final String local = place.getDisplayCountry(new Locale(language()));
        if (!TextUtils.isEmpty(local) && !local.equals(code) && !out.contains(local)) {
            out.add(local);
        }
        return out;
    }

    public static String languageName(String code) {
        final Locale locale = new Locale(code);
        final String name = locale.getDisplayLanguage(locale);
        if (TextUtils.isEmpty(name) || name.equals(code)) {
            return code;
        }
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    /** "Ethiopia · አማርኛ" */
    public static String summary() {
        final String place = countryName(country());
        final String lang = languageName(language());
        return TextUtils.isEmpty(place) ? lang : place + " · " + lang;
    }

    /* ranking */

    private static int scriptOfLanguage(String language) {
        switch (language == null ? "" : language) {
            case "am": case "ti": return ETHIOPIC;
            case "ar": case "fa": case "ur": case "ps": case "ckb": return ARABIC;
            case "ru": case "uk": case "be": case "bg": case "sr": case "kk": case "ky": case "mn": case "tg": case "mk": return CYRILLIC;
            case "he": case "yi": return HEBREW;
            case "hi": case "mr": case "ne": return DEVANAGARI;
            case "el": return GREEK;
            case "th": return THAI;
            case "ko": return HANGUL;
            case "zh": case "ja": return HAN;
            default: return LATIN;
        }
    }

    private static int scriptOf(int cp) {
        if (cp >= 'A' && cp <= 'Z' || cp >= 'a' && cp <= 'z' || cp >= 0x00C0 && cp <= 0x024F) return LATIN;
        if (cp >= 0x1200 && cp <= 0x139F || cp >= 0x2D80 && cp <= 0x2DDF || cp >= 0xAB00 && cp <= 0xAB2F) return ETHIOPIC;
        if (cp >= 0x0600 && cp <= 0x06FF || cp >= 0x0750 && cp <= 0x077F || cp >= 0x08A0 && cp <= 0x08FF
            || cp >= 0xFB50 && cp <= 0xFDFF || cp >= 0xFE70 && cp <= 0xFEFF) return ARABIC;
        if (cp >= 0x0400 && cp <= 0x052F) return CYRILLIC;
        if (cp >= 0x0590 && cp <= 0x05FF) return HEBREW;
        if (cp >= 0x0900 && cp <= 0x097F) return DEVANAGARI;
        if (cp >= 0x0370 && cp <= 0x03FF) return GREEK;
        if (cp >= 0x0E00 && cp <= 0x0E7F) return THAI;
        if (cp >= 0xAC00 && cp <= 0xD7AF || cp >= 0x1100 && cp <= 0x11FF) return HANGUL;
        if (cp >= 0x3040 && cp <= 0x30FF || cp >= 0x4E00 && cp <= 0x9FFF) return HAN;
        return Character.isLetter(cp) ? OTHER : -1;
    }

    /** Whether most letters of the text are in the script of the chosen language. */
    static boolean matchesScript(String text, String language) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        final int wanted = scriptOfLanguage(language);
        int letters = 0, hits = 0;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            final int script = scriptOf(cp);
            if (script < 0) {
                continue;
            }
            letters++;
            if (script == wanted) {
                hits++;
            }
        }
        return letters > 0 && hits * 2 >= letters;
    }

    /** Stable: channels in the chosen language's script first, otherwise the order stays. */
    public static void rank(ArrayList<TLRPC.Chat> chats) {
        if (chats == null || chats.size() < 2) {
            return;
        }
        final String language = language();
        if (scriptOfLanguage(language) == LATIN) {
            // Latin titles say little about the language; leave the order to Telegram's ranking
            return;
        }
        final java.util.HashMap<Long, Boolean> match = new java.util.HashMap<>();
        for (int i = 0; i < chats.size(); i++) {
            final TLRPC.Chat chat = chats.get(i);
            match.put(chat.id, matchesScript(chat.title, language));
        }
        Collections.sort(chats, (a, b) -> Boolean.compare(Boolean.TRUE.equals(match.get(b.id)), Boolean.TRUE.equals(match.get(a.id))));
    }

    /* picker */

    /** Country (Telegram's country list) then language; onDone runs after both were picked. */
    public static void showPicker(BaseFragment fragment, Runnable onDone) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final CountrySelectActivity countries = new CountrySelectActivity(false);
        countries.setCountrySelectActivityDelegate(country -> {
            if (country == null || TextUtils.isEmpty(country.shortname)) {
                return;
            }
            final String code = country.shortname.toUpperCase(Locale.US);
            // after the country list has closed, so the dialog shows on the screen underneath
            AndroidUtilities.runOnUIThread(() -> pickLanguage(fragment, code, onDone), 350);
        });
        fragment.presentFragment(countries);
    }

    private static void pickLanguage(BaseFragment fragment, String country, Runnable onDone) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final ArrayList<String> codes = new ArrayList<>();
        final String suggested = countryLanguage(country);
        if (suggested != null) {
            codes.add(suggested);
        }
        for (String code : LANGUAGES) {
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        final CharSequence[] names = new CharSequence[codes.size()];
        for (int i = 0; i < codes.size(); i++) {
            names[i] = languageName(codes.get(i));
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NilakContentLanguage));
        builder.setItems(names, (dialog, which) -> {
            if (which >= 0 && which < codes.size()) {
                set(country, codes.get(which));
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
        fragment.showDialog(builder.create());
    }
}
