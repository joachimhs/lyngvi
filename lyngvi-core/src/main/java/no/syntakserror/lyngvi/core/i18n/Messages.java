package no.syntakserror.lyngvi.core.i18n;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * User-facing text for both front-ends, from {@code messages.properties} (English) and {@code messages_<lang>.properties}.
 *
 * <p>English is the base bundle; an unsupported locale falls back to it. A
 * missing key is returned as {@code [key]} so a typo never crashes a command.
 */
public final class Messages {

    private static final String BASE = "no.syntakserror.lyngvi.core.i18n.messages";
    /** Languages with their own bundle; anything else resolves to the base (English) bundle, never to the JVM default locale. */
    private static final Set<String> TRANSLATED = Set.of("nb");

    private final ResourceBundle bundle;

    private Messages(ResourceBundle bundle) {
        this.bundle = bundle;
    }

    public static Messages of(Locale locale) {
        return new Messages(load(locale));
    }

    /**
     * {@code ResourceBundle.Control} is not allowed in named modules (the GUI runs on the module path), so the
     * locale is resolved here: a translated language gets its bundle, everything else the root bundle.
     */
    private static ResourceBundle load(Locale locale) {
        Locale l = locale != null && TRANSLATED.contains(locale.getLanguage()) ? Locale.of(locale.getLanguage()) : Locale.ROOT;
        return ResourceBundle.getBundle(BASE, l, Messages.class.getModule());
    }

    /** Accepts a language code such as {@code en} or {@code nb}; null means English. */
    public static Messages forLanguage(String languageCode) {
        return of(languageCode == null || languageCode.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(languageCode));
    }

    public String get(String key, Object... args) {
        try {
            String pattern = bundle.getString(key);
            if (args.length == 0) return pattern;
            // Identifiers such as UIDs must not get digit grouping ("1,234"), so integers are passed as text.
            Object[] plain = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                plain[i] = args[i] instanceof Integer || args[i] instanceof Long ? String.valueOf(args[i]) : args[i];
            }
            return new MessageFormat(pattern, locale()).format(plain);
        } catch (MissingResourceException e) {
            return "[" + key + "]";
        }
    }

    /** The underlying bundle (for FXML {@code %key} lookups in the GUI). */
    public ResourceBundle bundle() {
        return bundle;
    }

    /** The bundle's locale; English for the root bundle. */
    public Locale locale() {
        return bundle.getLocale().getLanguage().isEmpty() ? Locale.ENGLISH : bundle.getLocale();
    }

    /** All keys defined for the locale's own bundle (used to verify translations are complete). */
    public static Set<String> keys(Locale locale) {
        ResourceBundle b = load(locale);
        return Collections.unmodifiableSet(new HashSet<>(Collections.list(b.getKeys())));
    }
}
