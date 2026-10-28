package no.syntakserror.lyngvi.gui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Titles the user did not write: recognised, and replaced by the first thing they did write. */
class SessionTitlesTest {

    @Test
    void onlyTheCoresGeneratedTitleCountsAsGenerated() {
        assertThat(SessionTitles.isGenerated("Session 2026-09-14 15:13")).isTrue();
        assertThat(SessionTitles.isGenerated("Session 2026-09-14 15:13 ")).isFalse();
        assertThat(SessionTitles.isGenerated("Invoice-api multi tenant")).isFalse();
        assertThat(SessionTitles.isGenerated("Session about 2026")).isFalse();
        assertThat(SessionTitles.isGenerated(null)).isFalse();
    }

    @Test
    void theTitleIsTheFirstLineOfThePrompt() {
        assertThat(SessionTitles.fromPrompt("  Rydd opp i auth-modulen  \n\nog kjør testene")).isEqualTo("Rydd opp i auth-modulen");
        assertThat(SessionTitles.fromPrompt("\n\nfix   the    spacing")).isEqualTo("fix the spacing");
    }

    @Test
    void aLongPromptIsCutOnAWordBoundary() {
        String title = SessionTitles.fromPrompt("legg til SAF-T-eksport i InvoiceExportService og dekk den med tester for tre fakturatyper");
        assertThat(title).endsWith("…").doesNotContain("  ");
        assertThat(title.length()).isLessThanOrEqualTo(SessionTitles.MAX + 1);
        assertThat(title).startsWith("legg til SAF-T-eksport i InvoiceExportService");
    }

    @Test
    void oneLongWordIsCutWhereItIs() {
        String title = SessionTitles.fromPrompt("a".repeat(120));
        assertThat(title).hasSize(SessionTitles.MAX + 1).endsWith("…");
    }

    @Test
    void anEmptyPromptKeepsTheGeneratedTitle() {
        assertThat(SessionTitles.fromPrompt("")).isEmpty();
        assertThat(SessionTitles.fromPrompt("   \n  ")).isEmpty();
        assertThat(SessionTitles.fromPrompt(null)).isEmpty();
    }
}
