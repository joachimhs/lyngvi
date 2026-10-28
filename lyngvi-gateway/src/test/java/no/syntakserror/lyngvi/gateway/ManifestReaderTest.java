package no.syntakserror.lyngvi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import org.junit.jupiter.api.Test;

class ManifestReaderTest {
    @Test
    void roundTripsThroughJson() {
        String json = GatewayHarness_httpManifest();
        Manifest m = ManifestReader.read(json);
        assertThat(m.instances()).hasSize(1);
        assertThat(m.instance("docs").hostname()).isEqualTo("docs.gateway.local");
        assertThat(m.instance("docs").config()).containsEntry("restBase", "/api");
        Manifest again = ManifestReader.read(ManifestReader.write(m));
        assertThat(again).isEqualTo(m);
    }

    private static String GatewayHarness_httpManifest() {
        return no.syntakserror.lyngvi.gateway.testing.GatewayHarness.httpManifest("docs", "docs.gateway.local", "path-prefix",
                Map.of("restBase", "/api", "pathAllow", List.of("^/pages")));
    }

    @Test
    void missingFileIsEmptyManifest() {
        assertThat(ManifestReader.read(java.nio.file.Path.of("/nonexistent/manifest.json"))).isEqualTo(Manifest.empty());
    }
}
