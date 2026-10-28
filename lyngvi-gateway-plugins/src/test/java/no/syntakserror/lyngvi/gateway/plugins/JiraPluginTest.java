package no.syntakserror.lyngvi.gateway.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.JacksonJson;
import no.syntakserror.lyngvi.gateway.plugins.jira.JiraPlugin;
import no.syntakserror.lyngvi.gateway.testing.GatewayHarness;
import no.syntakserror.lyngvi.gateway.testing.StubUpstream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JiraPluginTest {

    @TempDir Path tmp;

    static final String MANIFEST = """
            {"schemaVersion":1,"instances":[{"id":"jira","plugin":"jira","label":"Jira","hostname":"jira.gateway.local",
             "baseUrlEnv":"JIRA_BASE_URL","secret":{"env":"JIRA_TOKEN","scheme":"basic"},"allowlist":{"file":"jira.txt"}}]}
            """;

    static final String ISSUE = """
            {"key":"OPS-7","fields":{"summary":"Rotate keys","status":{"name":"In Progress"},"issuetype":{"name":"Task"},
             "priority":{"name":"High"},"assignee":{"displayName":"Ada"},"reporter":{"displayName":"Bob"},
             "created":"2026-01-01T00:00:00.000+0000","updated":"2026-01-02T00:00:00.000+0000",
             "labels":["security"],"description":"h2. Why\\n* because [docs|http://x]",
             "project":{"key":"OPS","name":"Operations"}}}
            """;

    private StubUpstream upstream() {
        return new StubUpstream("https://jira.example.com", "/rest/api/2")
                .json("/issue/OPS-7", ISSUE)
                .json("/issue/SEC-1", ISSUE.replace("OPS-7", "SEC-1").replace("\"OPS\"", "\"SEC\""))
                .json("/issue/OPS-7/comment", "{\"comments\":[{\"id\":\"1\",\"author\":{\"displayName\":\"Ada\"},\"created\":\"2026-01-03T00:00:00.000+0000\",\"body\":\"*bold* note\"}],\"total\":1}")
                .json("/search", "{\"issues\":[{\"key\":\"OPS-7\",\"fields\":{\"summary\":\"Rotate keys\",\"status\":{\"name\":\"In Progress\"}}}],\"total\":1}")
                .json("/myself", "{\"name\":\"svc\",\"displayName\":\"Service\"}");
    }

    private GatewayHarness harness(String allowlist) throws Exception {
        return new GatewayHarness(tmp).plugin(new JiraPlugin()).manifest(MANIFEST)
                .env("JIRA_BASE_URL", "https://jira.example.com").env("JIRA_TOKEN", "u:p")
                .upstream("jira", upstream()).allowlist("jira.txt", allowlist).start();
    }

    @Test
    void issueIsReturnedAsTrimmedJsonWithText() throws Exception {
        try (GatewayHarness h = harness("OPS-7\n")) {
            HttpResponse<String> r = h.get("jira.gateway.local", "/issue/OPS-7");
            assertThat(r.statusCode()).isEqualTo(200);
            Map<String, Object> body = new JacksonJson().readObject(r.body());
            assertThat(body).containsEntry("key", "OPS-7").containsEntry("summary", "Rotate keys").containsEntry("status", "In Progress")
                    .containsEntry("assignee", "Ada").containsEntry("project", "OPS").containsEntry("description", "Why\n- because docs")
                    .containsEntry("url", "https://jira.example.com/browse/OPS-7");
        }
    }

    @Test
    void projectEntryAllowsEveryIssueInTheProject() throws Exception {
        try (GatewayHarness h = harness("ops\n")) {
            assertThat(h.get("jira.gateway.local", "/issue/OPS-7").statusCode()).isEqualTo(200);
            assertThat(h.get("jira.gateway.local", "/issue/ops-7").statusCode()).isEqualTo(200);
            HttpResponse<String> r = h.get("jira.gateway.local", "/issue/SEC-1");
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.body()).contains("SEC-1");
        }
    }

    @Test
    void browserUrlsWork() throws Exception {
        try (GatewayHarness h = harness("https://jira.example.com/browse/OPS-7\n")) {
            assertThat(h.get("jira.gateway.local", "/browse/OPS-7").statusCode()).isEqualTo(200);
            assertThat(h.get("jira.gateway.local", "/browse/SEC-1").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void commentsAndProjectSearch() throws Exception {
        try (GatewayHarness h = harness("OPS\n")) {
            HttpResponse<String> c = h.get("jira.gateway.local", "/issue/OPS-7/comments");
            assertThat(c.statusCode()).isEqualTo(200);
            assertThat(c.body()).contains("\"author\":\"Ada\"").contains("\"text\":\"bold note\"");
            HttpResponse<String> s = h.get("jira.gateway.local", "/project/OPS/issues?jql=status%20%3D%20Open&limit=3");
            assertThat(s.statusCode()).isEqualTo(200);
            assertThat(s.body()).contains("\"key\":\"OPS-7\"");
            StubUpstream.Call call = ((StubUpstream) h.gateway().instances().getFirst().upstream()).calls.getLast();
            assertThat(call.params().get("jql")).isEqualTo("project = \"OPS\" AND (status = Open)");
            assertThat(call.params().get("maxResults")).isEqualTo("3");
            assertThat(h.get("jira.gateway.local", "/project/SEC/issues").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void deepHealthProbesMyself() throws Exception {
        try (GatewayHarness h = harness("OPS\n")) {
            assertThat(h.get("jira.gateway.local", "/healthz?deep=1").body()).contains("\"user\":\"Service\"");
        }
    }
}
