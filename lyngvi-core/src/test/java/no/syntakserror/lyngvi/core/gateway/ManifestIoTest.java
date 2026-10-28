package no.syntakserror.lyngvi.core.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.AllowlistSpec;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.gateway.api.SecretSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestIoTest {

    @Test
    void roundTripsThroughTheSharedRecords(@TempDir Path tmp) throws Exception {
        Instance i = new Instance("docs", "http", "Docs", "docs.gateway.local", List.of("docs.internal"), "DOCS_BASE_URL",
                new SecretSpec("DOCS_TOKEN", "bearer"), new AllowlistSpec("docs.txt", "path-prefix", "paths"), List.of("read"),
                Map.of("restBase", "/api", "pathAllow", List.of("^/pages")));
        Manifest m = new Manifest(1, 10, List.of(i));
        Path f = tmp.resolve("gateway.manifest.json");
        ManifestIo.write(f, m);
        assertThat(Files.readString(f)).contains("\"schemaVersion\" : 1").contains("\"hostname\" : \"docs.gateway.local\"");
        assertThat(ManifestIo.read(f)).isEqualTo(m);
    }

    @Test
    void missingFileIsTheEmptyManifest() {
        assertThat(ManifestIo.read(Path.of("/nonexistent/gateway.manifest.json"))).isEqualTo(Manifest.empty());
    }
}
