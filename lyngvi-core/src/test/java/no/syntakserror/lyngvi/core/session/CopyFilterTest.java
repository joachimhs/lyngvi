package no.syntakserror.lyngvi.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopyFilterTest {

    private final CopyFilter filter = new CopyFilter(List.of("node_modules", "build", "*.egg-info", ".idea"), 1L << 20)
            .withIgnoreRules(List.of("secrets/", "*.bin", "docs/generated/**", "!docs/generated/keep.md"));

    @Test
    void excludesMatchAnyPathSegment() {
        assertThat(filter.excluded(Path.of("node_modules"), true)).isTrue();
        assertThat(filter.excluded(Path.of("a/b/node_modules"), true)).isTrue();
        assertThat(filter.excluded(Path.of("pkg.egg-info"), true)).isTrue();
        assertThat(filter.excluded(Path.of("src/main.js"), false)).isFalse();
        assertThat(filter.excluded(Path.of(".git"), true)).isTrue();
    }

    @Test
    void ignoreRulesFollowGitignoreConventions() {
        assertThat(filter.excluded(Path.of("secrets"), true)).isTrue();
        assertThat(filter.excluded(Path.of("secrets"), false)).isFalse();
        assertThat(filter.excluded(Path.of("x/blob.bin"), false)).isTrue();
        assertThat(filter.excluded(Path.of("docs/generated/a/b.md"), false)).isTrue();
        assertThat(filter.excluded(Path.of("docs/generated/keep.md"), false)).isFalse();
        assertThat(filter.excluded(Path.of("docs/other.md"), false)).isFalse();
    }

    @Test
    void sizeLimitIsExposed() {
        assertThat(filter.maxFileBytes()).isEqualTo(1L << 20);
    }
}
