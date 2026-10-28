package no.syntakserror.lyngvi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.testing.GatewayHarness;
import no.syntakserror.lyngvi.gateway.testing.StubUpstream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouterTest {

    @TempDir Path tmp;

    private GatewayHarness docs(String mode, Map<String, Object> cfg) throws Exception {
        return new GatewayHarness(tmp)
                .manifest(GatewayHarness.httpManifest("docs", "docs.gateway.local", mode, cfg))
                .env("DOCS_BASE_URL", "https://docs.example.com")
                .env("DOCS_TOKEN", "s3cr3t-token")
                .upstream("docs", new StubUpstream("https://docs.example.com", "/api")
                        .json("/pages/1", "{\"id\":1,\"title\":\"Hello\"}")
                        .json("/pages", "[]"));
    }

    private static Map<String, Object> readCfg() {
        return Map.of("restBase", "/api", "pathAllow", List.of("^/pages(/.*)?$"), "queryAllow", List.of("limit"));
    }

    @Test
    void unknownHostIs404WithHint() throws Exception {
        try (GatewayHarness h = docs("path-prefix", readCfg()).allowlist("docs.txt", "/pages\n").start()) {
            HttpResponse<String> r = h.get("nope.gateway.local", "/pages/1");
            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(r.body()).contains("unknown gateway host").contains("\"adapters\":[\"docs\"]");
        }
    }

    @Test
    void pathPrefixFallbackWhenHostIsUnknown() throws Exception {
        try (GatewayHarness h = docs("path-prefix", readCfg()).allowlist("docs.txt", "/pages\n").start()) {
            HttpResponse<String> r = h.get("127.0.0.1", "/docs/pages/1");
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).isEqualTo("{\"id\":1,\"title\":\"Hello\"}");
        }
    }

    @Test
    void writeMethodsAre405UnlessAllowWrite() throws Exception {
        try (GatewayHarness h = docs("path-prefix", readCfg()).allowlist("docs.txt", "/pages\n").start()) {
            HttpResponse<String> r = h.send("POST", "docs.gateway.local", "/pages", "{}", Map.of());
            assertThat(r.statusCode()).isEqualTo(405);
            assertThat(r.body()).contains("read-only");
        }
    }

    @Test
    void missingSecretIs503WithVariableName() throws Exception {
        try (GatewayHarness h = new GatewayHarness(tmp)
                .manifest(GatewayHarness.httpManifest("docs", "docs.gateway.local", null, readCfg()))
                .env("DOCS_BASE_URL", "https://docs.example.com")
                .allowlist("docs.txt", "/pages\n").start()) {
            HttpResponse<String> r = h.get("docs.gateway.local", "/pages/1");
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("gateway not configured: missing DOCS_TOKEN");
        }
    }

    @Test
    void missingBaseUrlIs503NamingTheBaseUrlVariable() throws Exception {
        try (GatewayHarness h = new GatewayHarness(tmp)
                .manifest(GatewayHarness.httpManifest("docs", "docs.gateway.local", null, readCfg()))
                .env("DOCS_TOKEN", "t").allowlist("docs.txt", "/pages\n").start()) {
            HttpResponse<String> r = h.get("docs.gateway.local", "/pages/1");
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("missing DOCS_BASE_URL").contains("gateway secret set docs");
        }
    }

    @Test
    void tokenGateWhenConfigured() throws Exception {
        try (GatewayHarness h = docs("path-prefix", readCfg()).env("GATEWAY_TOKEN", "tok123").allowlist("docs.txt", "/pages\n").start()) {
            assertThat(h.get("docs.gateway.local", "/pages/1").statusCode()).isEqualTo(401);
            assertThat(h.get("docs.gateway.local", "/pages/1", Map.of("Authorization", "Bearer tok123")).statusCode()).isEqualTo(200);
            assertThat(h.get("docs.gateway.local", "/pages/1", Map.of("X-Gateway-Token", "tok123")).statusCode()).isEqualTo(200);
            assertThat(h.get("docs.gateway.local", "/healthz").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void allowlistDeniesWithLabelAndHint() throws Exception {
        try (GatewayHarness h = docs("path-prefix", readCfg()).allowlist("docs.txt", "# nothing\n").start()) {
            HttpResponse<String> r = h.get("docs.gateway.local", "/pages/1");
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.body()).contains("not on allowlist").contains("gateway allow docs add");
        }
    }

    @Test
    void allowlistFileIsReloadedWhenChanged() throws Exception {
        try (GatewayHarness h = docs("path-prefix", readCfg()).allowlist("docs.txt", "").start()) {
            assertThat(h.get("docs.gateway.local", "/pages/1").statusCode()).isEqualTo(403);
            Thread.sleep(1100);
            h.allowlist("docs.txt", "/pages\n");
            assertThat(h.get("docs.gateway.local", "/pages/1").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void pathAllowGuardsEvenWhenModeIsNone() throws Exception {
        try (GatewayHarness h = docs("none", readCfg()).start()) {
            assertThat(h.get("docs.gateway.local", "/pages/1").statusCode()).isEqualTo(200);
            assertThat(h.get("docs.gateway.local", "/admin").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void queryModeUsesTheParameterValueAndForwardsOnlyAllowedQuery() throws Exception {
        StubUpstream up = new StubUpstream("https://docs.example.com", "/api").json("/pages", "[]");
        try (GatewayHarness h = new GatewayHarness(tmp)
                .manifest(GatewayHarness.httpManifest("docs", "docs.gateway.local", "query:space", Map.of("restBase", "/api", "pathAllow", List.of("^/pages$"), "queryAllow", List.of("space", "limit"))))
                .env("DOCS_BASE_URL", "https://docs.example.com").env("DOCS_TOKEN", "t")
                .upstream("docs", up).allowlist("docs.txt", "ENG\n").start()) {
            assertThat(h.get("docs.gateway.local", "/pages?space=OPS").statusCode()).isEqualTo(403);
            assertThat(h.get("docs.gateway.local", "/pages?space=ENG&limit=5&secret=x").statusCode()).isEqualTo(200);
            assertThat(up.calls.getLast().params()).containsExactly(Map.entry("space", "ENG"), Map.entry("limit", "5"));
        }
    }

    @Test
    void regexModeMatchesKeys() throws Exception {
        try (GatewayHarness h = docs("path-regex", readCfg()).allowlist("docs.txt", "^/pages/\\d+$\n").start()) {
            assertThat(h.get("docs.gateway.local", "/pages/1").statusCode()).isEqualTo(200);
            assertThat(h.get("docs.gateway.local", "/pages").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void writeAllowedWhenCapabilityAndAllowWriteSet() throws Exception {
        StubUpstream up = new StubUpstream("https://docs.example.com", "/api").on("/pages", c -> new no.syntakserror.lyngvi.gateway.api.UpstreamResponse(201, Map.of(), c.body()));
        String manifest = GatewayHarness.httpManifest("docs", "docs.gateway.local", "path-prefix",
                Map.of("restBase", "/api", "methods", List.of("GET", "POST"), "pathAllow", List.of("^/pages$")))
                .replace("\"config\"", "\"capabilities\":[\"read\",\"write\"],\"config\"");
        try (GatewayHarness h = new GatewayHarness(tmp).manifest(manifest)
                .env("DOCS_BASE_URL", "https://docs.example.com").env("DOCS_TOKEN", "t").env("ALLOW_WRITE", "docs")
                .upstream("docs", up).allowlist("docs.txt", "/pages\n").start()) {
            HttpResponse<String> r = h.send("POST", "docs.gateway.local", "/pages", "{\"title\":\"x\"}", Map.of("content-type", "application/json"));
            assertThat(r.statusCode()).isEqualTo(201);
            assertThat(r.body()).isEqualTo("{\"title\":\"x\"}");
            assertThat(up.calls.getLast().method()).isEqualTo("POST");
        }
    }

    @Test
    void healthzListsInstancesRoutesAndAllowlist() throws Exception {
        try (GatewayHarness h = docs("path-prefix", readCfg()).allowlist("docs.txt", "/pages\n/x\n").start()) {
            HttpResponse<String> r = h.get("any", "/healthz");
            assertThat(r.statusCode()).isEqualTo(200);
            Map<String, Object> body = new JacksonJson().readObject(r.body());
            assertThat(body.get("ok")).isEqualTo(true);
            List<?> instances = (List<?>) body.get("instances");
            Map<?, ?> docs = (Map<?, ?>) instances.getFirst();
            assertThat(docs.get("id")).isEqualTo("docs");
            assertThat(docs.get("configured")).isEqualTo(true);
            assertThat(docs.get("secretPresent")).isEqualTo(true);
            assertThat(docs.get("write")).isEqualTo(false);
            assertThat(String.valueOf(docs.get("routes"))).contains("GET /**");
            assertThat(((Map<?, ?>) docs.get("allowlist")).get("entries")).isEqualTo(2);
            assertThat(((Map<?, ?>) body.get("plugins")).get("http")).isEqualTo("builtin");
        }
    }

    @Test
    void unknownPluginProducesWarningNotCrash() throws Exception {
        try (GatewayHarness h = new GatewayHarness(tmp)
                .manifest("{\"schemaVersion\":1,\"instances\":[{\"id\":\"x\",\"plugin\":\"nope\",\"hostname\":\"x.gateway.local\"}]}").start()) {
            assertThat(h.gateway().warnings()).anySatisfy(w -> assertThat(w).contains("unknown plugin 'nope'"));
            assertThat(h.get("x.gateway.local", "/").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void secretsAreMaskedInLogs() throws Exception {
        try (GatewayHarness h = docs("path-prefix", readCfg()).allowlist("docs.txt", "/pages\n").start()) {
            h.gateway().instances().getFirst().log().info("token is s3cr3t-token here");
            assertThat(h.logs()).contains("token is ****** here").doesNotContain("s3cr3t-token");
        }
    }

    @Test
    void responseCarriesNoStoreAndStripsHopHeaders() throws Exception {
        StubUpstream up = new StubUpstream("https://docs.example.com", "/api")
                .on("/pages/1", c -> new no.syntakserror.lyngvi.gateway.api.UpstreamResponse(200,
                        Map.of("content-type", "application/json", "set-cookie", "a=b", "x-custom", "1", ":status", "200"), "{}".getBytes()));
        try (GatewayHarness h = docs("path-prefix", readCfg()).upstream("docs", up).allowlist("docs.txt", "/pages\n").start()) {
            HttpResponse<String> r = h.get("docs.gateway.local", "/pages/1");
            assertThat(r.headers().firstValue("cache-control")).contains("no-store");
            assertThat(r.headers().firstValue("set-cookie")).isEmpty();
            assertThat(r.headers().firstValue("x-custom")).contains("1");
            assertThat(r.headers().map().keySet()).noneMatch(k -> k.startsWith(":"));
        }
    }
}
