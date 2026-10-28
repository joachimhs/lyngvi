package no.syntakserror.lyngvi.gui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProblemReportTest {

    @Test
    void reportContainsDiagnosticsButNoSecrets(@TempDir Path tmp) throws Exception {
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1000, 1000);
        FakeRunner runner = new FakeRunner().ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "").ok("podman ps", "[]");
        SandboxContext ctx = new SandboxContext(platform, runner);
        ctx.init(tmp.resolve("sb"), Optional.empty(), List.of(), Optional.empty());
        ctx.sandbox().sandboxService().rebuildConfig();
        Files.writeString(tmp.resolve("sb/.env"), "SECRET_TOKEN=hunter2\n");
        Files.createDirectories(tmp.resolve("sb/gateway/secrets"));
        Files.writeString(tmp.resolve("sb/gateway/secrets/wiki.env"), "WIKI_TOKEN=hunter2\n");
        AppLog log = new AppLog();
        log.info("hello");

        Path zip = ProblemReport.create(tmp.resolve("out/report.zip"), ctx, log);

        List<String> names = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                names.add(e.getName());
                all.append(new String(in.readAllBytes()));
            }
        }
        assertThat(names).contains("app.log", "status.json", "doctor.json", "sandbox/profile.json", "sandbox/docker-compose.yml");
        assertThat(names).noneMatch(n -> n.contains(".env") || n.contains("secrets"));
        assertThat(all).contains("hello").doesNotContain("hunter2");
    }
}
