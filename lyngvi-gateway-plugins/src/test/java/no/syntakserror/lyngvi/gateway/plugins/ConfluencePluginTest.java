package no.syntakserror.lyngvi.gateway.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.JacksonJson;
import no.syntakserror.lyngvi.gateway.plugins.confluence.ConfluencePlugin;
import no.syntakserror.lyngvi.gateway.testing.GatewayHarness;
import no.syntakserror.lyngvi.gateway.testing.StubUpstream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfluencePluginTest {

    @TempDir Path tmp;

    static final String MANIFEST = """
            {"schemaVersion":1,"instances":[{"id":"wiki","plugin":"confluence","label":"Wiki","hostname":"wiki.gateway.local",
             "baseUrlEnv":"WIKI_BASE_URL","secret":{"env":"WIKI_TOKEN","scheme":"bearer"},"allowlist":{"file":"wiki.txt"}}]}
            """;

    static final String PAGE_42 = """
            {"id":"42","type":"page","title":"Runbook","space":{"key":"OPS","name":"Operations"},
             "version":{"number":7,"when":"2026-01-02T03:04:05Z","by":{"displayName":"Ada"}},
             "body":{"storage":{"value":"<h1>Steps</h1><p>First &amp; second</p>"}},
             "_links":{"webui":"/display/OPS/Runbook"}}
            """;

    private StubUpstream upstream() {
        return new StubUpstream("https://wiki.example.com", "/rest/api")
                .json("/content/42", PAGE_42)
                .json("/content/43", PAGE_42.replace("\"42\"", "\"43\"").replace("OPS", "SEC"))
                .json("/content/42/child/page", "{\"results\":[{\"id\":\"99\",\"title\":\"Child\",\"_links\":{\"webui\":\"/x\"}}],\"size\":1}")
                .json("/content/search", "{\"results\":[{\"id\":\"42\",\"title\":\"Runbook\",\"space\":{\"key\":\"OPS\"},\"excerpt\":\"hit\"}],\"totalSize\":1}")
                .json("/user/current", "{\"username\":\"svc\",\"displayName\":\"Service\"}");
    }

    private GatewayHarness harness(String allowlist) throws Exception {
        return new GatewayHarness(tmp).plugin(new ConfluencePlugin()).manifest(MANIFEST)
                .env("WIKI_BASE_URL", "https://wiki.example.com").env("WIKI_TOKEN", "t")
                .upstream("wiki", upstream()).allowlist("wiki.txt", allowlist).start();
    }

    @Test
    void pageIsReturnedAsTrimmedJsonWithText() throws Exception {
        try (GatewayHarness h = harness("42\n")) {
            HttpResponse<String> r = h.get("wiki.gateway.local", "/page/42");
            assertThat(r.statusCode()).isEqualTo(200);
            Map<String, Object> body = new JacksonJson().readObject(r.body());
            assertThat(body).containsEntry("id", "42").containsEntry("title", "Runbook").containsEntry("space", "OPS")
                    .containsEntry("text", "Steps\nFirst & second").containsEntry("url", "https://wiki.example.com/display/OPS/Runbook");
            assertThat(((Map<?, ?>) body.get("version")).get("number")).isEqualTo(7);
        }
    }

    @Test
    void pageNotAllowedByIdIsDeniedWithoutAnUpstreamCallWhenNoSpaceIsAllowed() throws Exception {
        StubUpstream up = upstream();
        try (GatewayHarness h = new GatewayHarness(tmp).plugin(new ConfluencePlugin()).manifest(MANIFEST)
                .env("WIKI_BASE_URL", "https://wiki.example.com").env("WIKI_TOKEN", "t")
                .upstream("wiki", up).allowlist("wiki.txt", "42\n").start()) {
            assertThat(h.get("wiki.gateway.local", "/page/43").statusCode()).isEqualTo(403);
            assertThat(up.calls).isEmpty();
        }
    }

    @Test
    void spaceEntryAllowsEveryPageInThatSpace() throws Exception {
        try (GatewayHarness h = harness("space:OPS\n")) {
            assertThat(h.get("wiki.gateway.local", "/page/42").statusCode()).isEqualTo(200);
            HttpResponse<String> r = h.get("wiki.gateway.local", "/page/43");
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.body()).contains("\"page\":\"43\"").contains("space:SEC");
        }
    }

    @Test
    void browserUrlsAreAcceptedAsAllowlistEntriesAndRequests() throws Exception {
        try (GatewayHarness h = harness("https://wiki.example.com/pages/viewpage.action?pageId=42\n")) {
            assertThat(h.get("wiki.gateway.local", "/pages/viewpage.action?pageId=42").statusCode()).isEqualTo(200);
            assertThat(h.get("wiki.gateway.local", "/wiki/spaces/OPS/pages/42/Runbook").statusCode()).isEqualTo(200);
            assertThat(h.get("wiki.gateway.local", "/page/43").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void childrenAndSearchAreScopedToAllowedSpaces() throws Exception {
        try (GatewayHarness h = harness("space:OPS\n")) {
            assertThat(h.get("wiki.gateway.local", "/page/42/children").body()).contains("\"id\":\"99\"");
            HttpResponse<String> r = h.get("wiki.gateway.local", "/search?q=runbook&space=OPS&limit=5");
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("\"id\":\"42\"");
            StubUpstream.Call call = ((StubUpstream) h.gateway().instances().getFirst().upstream()).calls.getLast();
            assertThat(call.params().get("cql")).isEqualTo("space = \"OPS\" AND type = page AND text ~ \"runbook\"");
            assertThat(call.params().get("limit")).isEqualTo("5");
            assertThat(h.get("wiki.gateway.local", "/search?q=x&space=SEC").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void deepHealthProbesCurrentUser() throws Exception {
        try (GatewayHarness h = harness("")) {
            String body = h.get("wiki.gateway.local", "/healthz?deep=1").body();
            assertThat(body).contains("\"user\":\"Service\"").contains("\"status\":\"no-allowlist\"");
        }
    }
}
