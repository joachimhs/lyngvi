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

class StackCommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PS_RUNNING = "[{\"Names\":[\"sb-proxy\"],\"State\":\"running\",\"Status\":\"Up 1 minute (healthy)\",\"Image\":\"squid\"}]";

    @TempDir Path tmp;
    Path sb;
    Platform platform;
    FakeRunner runner;

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
    void setUp() throws Exception {
        sb = tmp.resolve("sb");
        platform = new Platform(Platform.Os.MACOS, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 501, 20);
        runner = new FakeRunner()
                .ok("podman --version", "podman version 5.3.1\n")
                .ok("podman compose version", "1.2.0\n")
                .ok("podman compose", "")
                .ok("git --version", "git version 2.50.1\n")
                .ok("openssl version", "OpenSSL 3\n")
                .ok("openssl req", "").ok("openssl x509", "")
                .ok("podman network ls", "[]")
                .ok("podman ps", PS_RUNNING)
                .ok("podman exec", "")
                .ok("podman image exists", "")
                .ok("podman build", "STEP 1/3\nCOMMIT localhost/lyngvi-base\n")
                .ok("podman logs", "1757000000.000 1 172.31.240.20 TCP_DENIED/403 1 CONNECT blocked.example:443 - HIER_NONE/- -\n")
                .ok("podman machine inspect", "[{\"Name\":\"m\",\"State\":\"running\",\"Resources\":{\"CPUs\":8,\"DiskSize\":90,\"Memory\":16384}}]");
        assertThat(run("--json", "init").exit()).isZero();
    }

    private Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = Main.commandLine(platform, runner);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        String[] full = new String[args.length + 2];
        full[0] = "--sandbox"; full[1] = sb.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new Run(cmd.execute(full), out.toString(), err.toString());
    }

    @Test
    void rebuildConfigRendersAndReportsThePrefix() {
        Run r = run("--json", "rebuild-config");

        assertThat(r.exit()).isZero();
        assertThat(r.json().get("subnetPrefix").asText()).isEqualTo("172.31.240");
        assertThat(r.json().get("written").findValuesAsText("")).isEmpty();
        assertThat(sb.resolve("docker-compose.yml")).isRegularFile();

        Run text = run("rebuild-config");
        assertThat(text.out()).contains("172.31.240").contains("docker-compose.yml");
    }

    @Test
    void upFailsBeforeRebuildConfigAndSucceedsAfter() {
        Run early = run("--json", "up");
        assertThat(early.exit()).isEqualTo(4);

        run("rebuild-config");
        Run r = run("--json", "up");

        assertThat(r.exit()).isZero();
        assertThat(r.json().get("services").get(0).get("name").asText()).isEqualTo("proxy");
        assertThat(r.json().get("services").get(0).get("state").asText()).isEqualTo("running");
        assertThat(runner.calls()).contains(List.of("podman", "compose", "-p", "sb", "-f", "docker-compose.yml", "up", "-d"));

        Run down = run("down");
        assertThat(down.exit()).isZero();
        assertThat(runner.calls()).contains(List.of("podman", "compose", "-p", "sb", "-f", "docker-compose.yml", "down"));
    }

    @Test
    void buildStreamsOutputAndReportsTheImage() {
        run("rebuild-config");

        Run r = run("build");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("STEP 1/3").contains("localhost/lyngvi-base:");

        Run j = run("--json", "build", "--no-cache");
        assertThat(j.exit()).isZero();
        // Two scripted log lines per image: base, claude-code, opencode.
        assertThat(j.out().lines().filter(l -> l.contains("\"type\":\"log\"")).count()).isEqualTo(6);
        assertThat(j.json().get("image").asText()).startsWith("localhost/lyngvi-base:");
        assertThat(runner.calls().stream().anyMatch(c -> c.contains("--no-cache"))).isTrue();
    }

    @Test
    void logsStreamTheServiceLog() {
        run("rebuild-config");

        Run r = run("logs", "proxy", "--tail", "50");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("TCP_DENIED");
        assertThat(runner.calls()).contains(List.of("podman", "logs", "--tail", "50", "sb-proxy"));

        Run j = run("--json", "logs", "proxy");
        assertThat(j.out().lines().findFirst().orElseThrow()).contains("\"service\":\"proxy\"").contains("\"line\":");

        assertThat(run("logs", "database").exit()).isEqualTo(2);
    }

    @Test
    void domainAddListRemoveReload() throws Exception {
        run("rebuild-config");

        Run add = run("--json", "domain", "add", "example.org", "--note", "docs");
        assertThat(add.exit()).isZero();
        assertThat(add.json().get("domain").asText()).isEqualTo("example.org");
        assertThat(add.json().get("reloaded").asBoolean()).isTrue();

        Run list = run("--json", "domain", "list");
        assertThat(list.json().get("domains").findValuesAsText("domain")).startsWith("registry.npmjs.org").endsWith("example.org");
        assertThat(list.json().get("domains").findValuesAsText("source")).contains("profile", "agent:claude-code", "user");

        Run text = run("domain", "list");
        assertThat(text.out()).contains("example.org").contains("(user)").contains("(profile)");

        Run bad = run("--json", "domain", "add", "registry.npmjs.org");
        assertThat(bad.exit()).isEqualTo(4);

        Run remove = run("--json", "domain", "remove", "example.org");
        assertThat(remove.exit()).isZero();
        assertThat(Files.readString(sb.resolve("proxy/allowed-domains.txt"))).doesNotContain("example.org");

        Run reload = run("--json", "domain", "reload");
        assertThat(reload.json().get("reloaded").asBoolean()).isTrue();
    }

    @Test
    void domainBlockedListsDeniedHosts() {
        run("rebuild-config");

        Run r = run("--json", "domain", "blocked");
        assertThat(r.exit()).isZero();
        assertThat(r.json().get("blocked").get(0).get("host").asText()).isEqualTo("blocked.example");
        assertThat(r.json().get("blocked").get(0).get("count").asInt()).isEqualTo(1);

        Run text = run("domain", "blocked");
        assertThat(text.out()).contains("blocked.example");
    }

    @Test
    void resourcesMachineReportsTheVmAndAdvice() {
        Run r = run("--json", "resources", "machine");

        assertThat(r.exit()).isZero();
        assertThat(r.json().get("machine").get("cpus").asInt()).isEqualTo(8);
        assertThat(r.json().get("machine").get("memoryBytes").asLong()).isEqualTo(16384L * 1024 * 1024);
        assertThat(r.json().get("advice")).isEmpty();

        run("--json", "resources", "set", "--mem", "64g");
        Run warn = run("resources", "machine");
        assertThat(warn.out()).contains("MEM_EXCEEDS_MACHINE").contains("16 GiB");
    }

    @Test
    void doctorIncludesPodmanChecksAndMachineOnMacos() {
        run("rebuild-config");

        Run r = run("--json", "doctor");

        assertThat(r.json().get("checks").findValuesAsText("id")).contains("machine", "generated", "subnet", "compose-mounts", "image");
    }

    @Test
    void upgradeRefreshesTheBundledFiles() throws Exception {
        Files.writeString(sb.resolve("image/base.Containerfile"), "FROM old\n");
        Run r = run("--json", "upgrade");
        assertThat(r.exit()).isZero();
        assertThat(r.json().get("to").asText()).isNotBlank();
        assertThat(r.json().get("updated").toString()).contains("image/base.Containerfile");
        assertThat(Files.readString(sb.resolve("image/base.Containerfile"))).contains("getent group");
        Run text = run("upgrade");
        assertThat(text.exit()).isZero();
        assertThat(text.out()).contains("up to date");
    }
}
