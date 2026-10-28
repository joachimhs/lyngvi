package no.syntakserror.lyngvi.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class GatewayCommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;
    Path sb;
    Platform platform;
    FakeRunner runner;
    String stdin = "";

    record Run(int exit, String out, String err) {
        JsonNode json() {
            try {
                return JSON.readTree(out.lines().reduce((a, b) -> b).orElse(""));
            } catch (Exception e) {
                throw new AssertionError("Not JSON: " + out, e);
            }
        }
    }

    @BeforeEach
    void setUp() {
        sb = tmp.resolve("sb");
        platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1000, 1000);
        runner = new FakeRunner()
                .ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "")
                .ok("podman ps", "[{\"Names\":[\"sb-gateway\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"gw\"}]")
                .ok("podman exec", "{\"ok\":true,\"version\":\"test\",\"instances\":[]}\n")
                .ok("podman restart", "").ok("podman compose", "").ok("podman logs", "gateway listening on http://0.0.0.0:8080\n");
        assertThat(run("--json", "init").exit()).isZero();
    }

    private Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = Main.commandLine(platform, runner, new java.io.ByteArrayInputStream(stdin.getBytes()));
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        String[] full = new String[args.length + 2];
        full[0] = "--sandbox"; full[1] = sb.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new Run(cmd.execute(full), out.toString(), err.toString());
    }

    @Test
    void instanceLifecycleThroughTheCli() throws Exception {
        Run add = run("--json", "gateway", "instance", "add", "docs", "--plugin", "http", "--hostname", "docs.gateway.local",
                "--alias", "docs.internal", "--label", "Docs", "--config", "{\"restBase\":\"/api\",\"pathAllow\":[\"^/pages\"]}");
        assertThat(add.exit()).isZero();
        assertThat(add.json().get("id").asText()).isEqualTo("docs");
        assertThat(add.json().get("status").asText()).isEqualTo("no-secret");
        assertThat(add.json().get("urlEnvName").asText()).isEqualTo("DOCS_GATEWAY_URL");

        Run list = run("--json", "gateway", "list");
        assertThat(list.json().get("instances").get(0).get("hostname").asText()).isEqualTo("docs.gateway.local");
        Run text = run("gateway", "list");
        assertThat(text.out()).contains("docs").contains("docs.gateway.local").contains("no-secret");

        Run show = run("--json", "gateway", "instance", "show", "docs");
        assertThat(show.json().get("plugin").asText()).isEqualTo("http");

        Run dup = run("--json", "gateway", "instance", "add", "docs", "--hostname", "x.gateway.local");
        assertThat(dup.exit()).isEqualTo(2);
        assertThat(dup.json().get("error").get("message").asText()).contains("already exists");

        Run rm = run("--json", "gateway", "instance", "remove", "docs");
        assertThat(rm.exit()).isZero();
        assertThat(run("--json", "gateway", "list").json().get("instances")).isEmpty();
    }

    @Test
    void secretsAndAllowlists() throws Exception {
        run("gateway", "instance", "add", "docs", "--hostname", "docs.gateway.local");
        stdin = "tok-from-stdin\n";
        Run set = run("--json", "gateway", "secret", "set", "docs", "--url", "https://docs.example.com", "--token-stdin");
        assertThat(set.exit()).isZero();
        assertThat(set.json().get("present").asBoolean()).isTrue();
        assertThat(set.json().get("masked").asText()).isEqualTo("****tdin");
        assertThat(set.out()).doesNotContain("tok-from-stdin");
        assertThat(Files.readString(sb.resolve("gateway/secrets/docs.env"))).contains("DOCS_TOKEN=tok-from-stdin");

        Run status = run("gateway", "secret", "status", "docs");
        assertThat(status.out()).contains("****tdin").contains("https://docs.example.com").doesNotContain("tok-from-stdin");

        Run add = run("--json", "gateway", "allow", "docs", "add", "/pages");
        assertThat(add.exit()).isZero();
        assertThat(add.json().get("added").asBoolean()).isTrue();
        assertThat(run("--json", "gateway", "allow", "docs", "add", "/pages").json().get("added").asBoolean()).isFalse();
        Run listed = run("--json", "gateway", "allow", "docs", "list");
        assertThat(listed.json().get("entries").get(0).asText()).isEqualTo("/pages");
        assertThat(run("gateway", "allow", "docs", "list").out()).contains("/pages");
        assertThat(run("--json", "gateway", "allow", "docs", "remove", "/pages").json().get("removed").asBoolean()).isTrue();
        assertThat(run("--json", "gateway", "list").json().get("instances").get(0).get("status").asText()).isEqualTo("no-allowlist");

        Run clear = run("--json", "gateway", "secret", "clear", "docs");
        assertThat(clear.exit()).isZero();
        assertThat(sb.resolve("gateway/secrets/docs.env")).isEmptyFile();
    }

    @Test
    void pluginsStatusLogsRestartRebuildAndCert() throws Exception {
        Run plugins = run("--json", "gateway", "plugin", "list");
        assertThat(plugins.exit()).isZero();
        assertThat(plugins.json().get("plugins")).hasSize(3);
        assertThat(plugins.json().get("plugins").get(1).get("type").asText()).isEqualTo("confluence");
        assertThat(run("gateway", "plugin", "list").out()).contains("http").contains("confluence.jar").contains("jira.jar");

        Path copy = tmp.resolve("custom-jira.jar");
        Files.copy(sb.resolve("gateway/plugins/jira.jar"), copy);
        assertThat(run("--json", "gateway", "plugin", "remove", "jira.jar").exit()).isZero();
        Run install = run("--json", "gateway", "plugin", "install", copy.toString());
        assertThat(install.exit()).isZero();
        assertThat(install.json().get("type").asText()).isEqualTo("jira");
        assertThat(sb.resolve("gateway/plugins/custom-jira.jar")).isRegularFile();

        Run status = run("--json", "gateway", "status");
        assertThat(status.exit()).isZero();
        assertThat(status.json().get("ok").asBoolean()).isTrue();
        assertThat(run("gateway", "status", "--deep").exit()).isZero();
        assertThat(runner.calls()).contains(List.of("podman", "exec", "sb-gateway", "/opt/jre/bin/java", "-jar", "/app/gateway.jar", "--health", "--deep"));

        Run logs = run("gateway", "logs", "--tail", "5");
        assertThat(logs.exit()).isZero();
        assertThat(logs.out()).contains("listening");

        run("rebuild-config");
        assertThat(run("--json", "gateway", "restart").exit()).isZero();
        assertThat(runner.calls()).contains(List.of("podman", "compose", "-p", "sb", "-f", "docker-compose.yml", "up", "-d", "--force-recreate", "--no-deps", "gateway"));

        Run rebuild = run("--json", "gateway", "rebuild");
        assertThat(rebuild.exit()).as(rebuild.out() + rebuild.err()).isZero();
        assertThat(runner.calls()).contains(List.of("podman", "compose", "-p", "sb", "-f", "docker-compose.yml", "build", "gateway"));

        int before = runner.calls().size();
        Run cert = run("--json", "gateway", "cert", "renew");
        assertThat(cert.exit()).isZero();
        assertThat(cert.json().get("certificate").asText()).endsWith("gateway.crt");
        assertThat(runner.calls().subList(before, runner.calls().size())).anySatisfy(c -> assertThat(c).startsWith("openssl", "req"));
    }
}
