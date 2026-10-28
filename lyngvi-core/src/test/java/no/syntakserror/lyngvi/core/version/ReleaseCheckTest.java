package no.syntakserror.lyngvi.core.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.SandboxException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReleaseCheckTest {

    private HttpServer server;

    private URI serve(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest", ex -> {
            byte[] b = body.getBytes();
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void reportsANewerReleaseWithItsAssets() throws Exception {
        URI api = serve(200, "{\"tag_name\":\"v2.0.0-M10\",\"html_url\":\"https://example.test/rel\",\"assets\":[{\"browser_download_url\":\"https://example.test/lyngvi-cli-2.0.0-M10-macos-arm64.tar.gz\"}]}");
        ReleaseCheck.ReleaseInfo info = new ReleaseCheck().check(Version.parse("2.0.0-M9"), api);
        assertThat(info.latest()).isEqualTo("2.0.0-M10");
        assertThat(info.upToDate()).isFalse();
        assertThat(info.releaseUrl()).isEqualTo("https://example.test/rel");
        assertThat(info.assets()).hasSize(1);
        assertThat(new ReleaseCheck().check(Version.parse("2.0.0-M10"), api).upToDate()).isTrue();
        assertThat(new ReleaseCheck().check(Version.parse("2.0.0"), api).upToDate()).isTrue();
    }

    @Test
    void failuresAreReportedNotThrownAsRawExceptions() throws Exception {
        URI api = serve(404, "{}");
        assertThatThrownBy(() -> new ReleaseCheck().check(Version.parse("2.0.0-M9"), api)).isInstanceOf(SandboxException.class).hasMessageContaining("404");
        assertThat(ReleaseCheck.apiFrom(Map.of())).isEqualTo(URI.create(ReleaseCheck.DEFAULT_API));
        assertThat(ReleaseCheck.apiFrom(Map.of("LYNGVI_RELEASE_API", "http://x/y"))).isEqualTo(URI.create("http://x/y"));
    }
}
