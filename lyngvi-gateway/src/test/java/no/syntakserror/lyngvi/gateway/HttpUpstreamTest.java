package no.syntakserror.lyngvi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.syntakserror.lyngvi.gateway.api.AllowlistSpec;
import no.syntakserror.lyngvi.gateway.api.GatewayException;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.SecretSpec;
import no.syntakserror.lyngvi.gateway.api.UpstreamResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpUpstreamTest {

    private HttpServer server;
    private volatile String seenAuth;
    private volatile String seenPath;

    private HttpUpstream upstream(String scheme, Set<String> methods) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seenAuth = ex.getRequestHeaders().getFirst("Authorization");
            seenPath = ex.getRequestURI().toString();
            byte[] b = "{\"ok\":true}".getBytes();
            ex.getResponseHeaders().set("content-type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        Instance inst = new Instance("t", "http", "t", "t.local", List.of(), "T_URL", new SecretSpec("T_TOKEN", scheme),
                new AllowlistSpec("t.txt", null, "entries"), List.of("read"), Map.of());
        Config cfg = Config.fromEnv(Map.of(), "test");
        return new HttpUpstream(HttpClient.newHttpClient(), inst, "http://127.0.0.1:" + server.getAddress().getPort() + "/base/",
                "/rest", "secret", cfg, 1_000_000, 5000, methods);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void bearerSecretAndRestBaseAreApplied() throws Exception {
        UpstreamResponse r = upstream("bearer", Set.of("GET")).get("/items/1", Map.of("q", "a b"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(seenAuth).isEqualTo("Bearer secret");
        assertThat(seenPath).isEqualTo("/base/rest/items/1?q=a+b");
    }

    @Test
    void basicAndHeaderAndQuerySchemes() throws Exception {
        upstream("basic", Set.of("GET")).get("/x", Map.of());
        assertThat(seenAuth).isEqualTo("Basic c2VjcmV0");
        upstream("header:X-Api-Key", Set.of("GET")).get("/x", Map.of());
        assertThat(seenAuth).isNull();
        upstream("query:key", Set.of("GET")).get("/x", Map.of("a", "1"));
        assertThat(seenPath).isEqualTo("/base/rest/x?a=1&key=secret");
    }

    @Test
    void pathsCannotEscapeTheBase() throws Exception {
        HttpUpstream u = upstream("bearer", Set.of("GET"));
        assertThatThrownBy(() -> u.get("/../../etc", Map.of())).isInstanceOf(GatewayException.class).hasMessageContaining("escape");
        assertThatThrownBy(() -> u.get("http://evil/", Map.of())).isInstanceOf(GatewayException.class).hasMessageContaining("relative");
        assertThatThrownBy(() -> u.get("//evil/", Map.of())).isInstanceOf(GatewayException.class);
    }

    @Test
    void writeMethodsRequireExplicitAllowance() throws Exception {
        HttpUpstream u = upstream("bearer", Set.of("GET"));
        assertThatThrownBy(() -> u.request("POST", "/x", Map.of(), new byte[0], Map.of())).isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).status()).isEqualTo(405));
        assertThat(upstream("bearer", Set.of("GET", "POST")).request("POST", "/x", Map.of(), "{}".getBytes(), Map.of()).status()).isEqualTo(200);
    }

    @Test
    void callerAuthorizationHeaderIsNeverForwarded() throws Exception {
        upstream("bearer", Set.of("GET")).request("GET", "/x", Map.of(), null, Map.of("Authorization", "Bearer caller"));
        assertThat(seenAuth).isEqualTo("Bearer secret");
    }

    @Test
    void pseudoHeadersFromHttp2UpstreamsAreNotCopied() throws Exception {
        UpstreamResponse r = upstream("bearer", Set.of("GET")).get("/x", Map.of());
        assertThat(r.headers().keySet()).noneMatch(k -> k.startsWith(":"));
        assertThat(r.headers()).containsKey("content-type");
    }
}
