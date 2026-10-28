package no.syntakserror.lyngvi.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessagesTest {

    @Test
    void englishIsTheDefault() {
        Messages m = Messages.of(Locale.forLanguageTag("xx"));

        assertThat(m.get("version.line", "2.0.0")).isEqualTo("lyngvi 2.0.0");
    }

    @Test
    void norwegianIsAvailable() {
        Messages m = Messages.of(Locale.forLanguageTag("nb"));

        assertThat(m.get("init.done", "/sb")).isEqualTo("Sandkassen er klar i /sb");
    }

    @Test
    void norwegianHasEveryEnglishKey() {
        Set<String> en = Messages.keys(Locale.ENGLISH);
        Set<String> nb = Messages.keys(Locale.forLanguageTag("nb"));

        assertThat(en).isNotEmpty();
        assertThat(nb).containsAll(en);
    }

    @Test
    void missingKeyIsReturnedInBracketsRatherThanThrowing() {
        assertThat(Messages.of(Locale.ENGLISH).get("no.such.key")).isEqualTo("[no.such.key]");
    }

    @Test
    void languageCodeResolvesToALocale() {
        assertThat(Messages.forLanguage("nb").get("init.done", "/x")).isEqualTo("Sandkassen er klar i /x");
        assertThat(Messages.forLanguage(null).get("version.line", "1")).isEqualTo("lyngvi 1");
    }
}
