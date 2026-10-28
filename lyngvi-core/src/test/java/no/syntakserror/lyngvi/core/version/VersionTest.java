package no.syntakserror.lyngvi.core.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void parsesMajorMinorPatchAndPreRelease() {
        Version v = Version.parse("2.0.0-M3");

        assertThat(v.major()).isEqualTo(2);
        assertThat(v.minor()).isZero();
        assertThat(v.patch()).isZero();
        assertThat(v.preRelease()).isEqualTo("M3");
        assertThat(v.toString()).isEqualTo("2.0.0-M3");
    }

    @Test
    void parsesReleaseWithoutPreRelease() {
        Version v = Version.parse("2.1.7");

        assertThat(v.preRelease()).isNull();
        assertThat(v.toString()).isEqualTo("2.1.7");
    }

    @Test
    void ordersMilestonesNumericallyAndBelowTheRelease() {
        assertThat(Version.parse("2.0.0-M1")).isLessThan(Version.parse("2.0.0-M2"));
        assertThat(Version.parse("2.0.0-M2")).isLessThan(Version.parse("2.0.0-M10"));
        assertThat(Version.parse("2.0.0-M10")).isLessThan(Version.parse("2.0.0"));
        assertThat(Version.parse("2.0.0")).isLessThan(Version.parse("2.1.0"));
        assertThat(Version.parse("2.1.0")).isLessThan(Version.parse("3.0.0"));
        assertThat(Version.parse("1.9.9")).isLessThan(Version.parse("2.0.0-M1"));
    }

    @Test
    void equalVersionsCompareAsZero() {
        assertThat(Version.parse("2.0.0-M1")).isEqualByComparingTo(Version.parse("2.0.0-M1"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> Version.parse("2.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Version.parse("v2.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Version.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Version.parse("2.0.0-")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(Version.parse(" 2.0.0-M0\n")).isEqualTo(Version.parse("2.0.0-M0"));
    }

    @Test
    void currentVersionMatchesTheRootVersionFile() throws Exception {
        String expected = Files.readString(Path.of("..", "VERSION")).trim();

        assertThat(Version.current().toString()).isEqualTo(expected);
    }
}
