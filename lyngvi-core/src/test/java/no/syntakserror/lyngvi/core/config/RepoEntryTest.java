package no.syntakserror.lyngvi.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepoEntryTest {

    @Test
    void parsesPathOnlyAndDefaultsNameToBasename() {
        RepoEntry e = RepoEntry.parse("/Users/alice/src/web");

        assertThat(e.path()).isEqualTo(Path.of("/Users/alice/src/web"));
        assertThat(e.name()).isEqualTo("web");
        assertThat(e.history()).isEmpty();
        assertThat(e.format()).isEqualTo("/Users/alice/src/web");
    }

    @Test
    void parsesCustomNameAndHistory() {
        RepoEntry e = RepoEntry.parse("/Users/alice/src/legacy-billing|billing|history=none");

        assertThat(e.name()).isEqualTo("billing");
        assertThat(e.history()).contains(History.NONE);
        assertThat(e.format()).isEqualTo("/Users/alice/src/legacy-billing|billing|history=none");
    }

    @Test
    void historyWithoutCustomNameKeepsTheBasename() {
        RepoEntry e = RepoEntry.parse("/srv/api||history=full");

        assertThat(e.name()).isEqualTo("api");
        assertThat(e.history()).contains(History.FULL);
        assertThat(e.format()).isEqualTo("/srv/api||history=full");
    }

    @Test
    void rejectsInvalidNames() {
        assertThatThrownBy(() -> new RepoEntry(Path.of("/x"), "bad name", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepoEntry(Path.of("/x"), "output", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> RepoEntry.parse("/x|ok|history=deep"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepoEntry.parse("/x|ok|colour=red"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservedNamesAreExactAndCaseSensitive() {
        assertThat(ReservedNames.isReserved("output")).isTrue();
        assertThat(ReservedNames.isReserved("_attachments")).isTrue();
        assertThat(ReservedNames.isReserved("AGENTS.md")).isTrue();
        assertThat(ReservedNames.isReserved("CLAUDE.md")).isTrue();
        assertThat(ReservedNames.isReserved("Output")).isFalse();
        assertThat(ReservedNames.isReserved("outputs")).isFalse();
    }
}
