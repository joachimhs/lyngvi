package no.syntakserror.lyngvi.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiTest {

    @Test
    void maskNeverRevealsShortSecretsAndKeepsEdgesOfLongOnes() {
        assertThat(Secrets.mask(null)).isEqualTo("(not set)");
        assertThat(Secrets.mask("")).isEqualTo("(not set)");
        assertThat(Secrets.mask("abcdefgh")).isEqualTo("********");
        assertThat(Secrets.mask("ghp_1234567890abcd")).isEqualTo("ghp…cd (18 chars)");
    }

    @Test
    void resolversMatchAsDocumented() {
        assertThat(AllowlistResolver.exact().matches("A", Set.of("A"))).isTrue();
        assertThat(AllowlistResolver.exact().matches("a", Set.of("A"))).isFalse();
        assertThat(AllowlistResolver.exactIgnoreCase().matches("a", Set.of("a"))).isTrue();
        assertThat(AllowlistResolver.pathPrefix().matches("/api/v1/pages/12", Set.of("/api/v1/pages/"))).isTrue();
        assertThat(AllowlistResolver.pathPrefix().matches("/api/v2", Set.of("/api/v1/"))).isFalse();
        assertThat(AllowlistResolver.regex().matches("/api/v1/pages/12", Set.of("^/api/v1/pages/1[0-9]$"))).isTrue();
        assertThat(AllowlistResolver.exact().matches(null, Set.of("A"))).isFalse();
    }

    @Test
    void manifestCollectsHostnamesAndInstanceDefaults() {
        Instance i = new Instance("docs", "http", "Docs", "docs.internal.example", List.of("docs"), "DOCS_BASE_URL",
                new SecretSpec("DOCS_TOKEN", "bearer"), new AllowlistSpec("docs.txt", "path-prefix", "documents"), null, null);
        Manifest m = new Manifest(2, 10, List.of(i));

        assertThat(m.hostnames()).containsExactly("docs.internal.example", "docs");
        assertThat(i.capabilities()).containsExactly("read");
        assertThat(i.canWrite()).isFalse();
        assertThat(i.urlEnvName()).isEqualTo("DOCS_GATEWAY_URL");
        assertThat(Instance.isValidId("docs-2")).isTrue();
        assertThat(Instance.isValidId("Docs")).isFalse();
        assertThat(m.instance("docs")).isSameAs(i);
        assertThat(Manifest.empty().instances()).isEmpty();
    }

    @Test
    void requestHelpersReadQueryAndParams() {
        Request r = new Request("GET", "/x", Map.of("id", "1"), Map.of("q", List.of("a", "b")), Map.of(), new byte[0]);

        assertThat(r.param("id")).isEqualTo("1");
        assertThat(r.query("q")).isEqualTo("a");
        assertThat(r.query("missing", "d")).isEqualTo("d");
    }
}
