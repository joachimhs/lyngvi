package no.syntakserror.lyngvi.core.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.contract.MigrateReport;
import no.syntakserror.lyngvi.core.gateway.ManifestIo;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec §12 against a synthetic 1.1.2 sandbox (no organisation-specific values, G6). */
class Migrate112Test {

    private static final Path FIXTURE = Path.of("src/test/resources/migrate/112");
    private static final String CONV = "3f2a1b0c-1111-2222-3333-444455556666";

    @TempDir Path tmp;
    Path sb;
    Platform platform;
    FakeRunner runner;

    @BeforeEach
    void copyFixture() throws Exception {
        sb = tmp.resolve("sb");
        try (Stream<Path> files = Files.walk(FIXTURE)) {
            for (Path p : files.toList()) {
                Path target = sb.resolve(FIXTURE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
        Files.createDirectories(tmp.resolve("home/.cache/cplt-sndbx"));
        Files.writeString(tmp.resolve("home/.cache/cplt-sndbx/subnets.txt"), "172.31.240\t" + sb + "\n172.31.241\t/somewhere/else\n");
        platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 501, 20);
        runner = new FakeRunner().ok("podman network ls", "[]").ok("podman ps", "[]").ok("openssl req", "").ok("openssl x509", "");
    }

    @Test
    void aSchemaOneSandboxIsReportedAsNeedingMigration() {
        assertThatThrownBy(() -> Sandbox.open(sb, platform, runner))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> {
                    assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.NOT_INITIALIZED);
                    assertThat(((SandboxException) e).data()).containsEntry("code", "MIGRATION_REQUIRED");
                });
        assertThat(Migrate112.needsMigration(sb)).isTrue();
        assertThat(Migrate112.needsMigration(tmp.resolve("nowhere"))).isFalse();
    }

    @Test
    void migratesConfigProfileGatewayConversationsSessionsAndSubnets() throws Exception {
        MigrateReport report = Migrate112.run(sb, platform, runner);

        String config = Files.readString(sb.resolve("sandbox.config"));
        assertThat(config).contains("SCHEMA_VERSION=2").contains("UID=501").contains("GID=20").contains("MEM_LIMIT=6g").contains("CPUS=3.0")
                .contains("PIDS_LIMIT=512").contains("PROFILE=" + sb.resolve("profiles/migrated")).contains("DEFAULT_AGENT=claude-code").contains("LANG_UI=nb")
                .contains("REPO=/tmp/migrate-fixture/api").contains("REPO=/tmp/migrate-fixture/web|frontend").doesNotContain("ENTERPRISE_URL");
        Sandbox sandbox = Sandbox.open(sb, platform, runner);
        assertThat(sandbox.config().repos()).extracting(r -> r.name()).containsExactly("api", "frontend");

        assertThat(sandbox.profile().name()).isEqualTo("migrated");
        assertThat(sandbox.profile().language()).isEqualTo("nb");
        assertThat(sandbox.profile().egress().allow()).containsExactly("registry.npmjs.org", ".example.test", "repo.maven.example.test");
        assertThat(sandbox.profile().toolchains()).containsExactly("node22", "java17", "java21", "java25", "kotlin", "maven", "gradle");
        assertThat(Files.readString(sb.resolve("profiles/migrated/copilot-cli.env"))).contains("COPILOT_ENTERPRISE_URL=https://github.example.test/acme");
        assertThat(Files.readString(sb.resolve("profiles/migrated/README.md"))).contains("1.1.2");

        Manifest m = ManifestIo.read(sb.resolve("gateway/gateway.manifest.json"));
        assertThat(m.schemaVersion()).isEqualTo(Manifest.CURRENT_SCHEMA_VERSION);
        assertThat(m.ipSuffix()).isEqualTo(10);
        assertThat(m.instances()).extracting(i -> i.id()).containsExactly("confluence", "jira");
        var wiki = m.instance("confluence");
        assertThat(wiki.plugin()).isEqualTo("confluence");
        assertThat(wiki.label()).isEqualTo("Confluence Wiki");
        assertThat(wiki.hostname()).isEqualTo("wiki.example.test");
        assertThat(wiki.hostAliases()).containsExactly("wiki-gateway");
        assertThat(wiki.baseUrlEnv()).isEqualTo("CONFLUENCE_BASE_URL");
        assertThat(wiki.secret().env()).isEqualTo("CONFLUENCE_PAT");
        assertThat(wiki.allowlist().file()).isEqualTo("confluence.txt");
        assertThat(sb.resolve("gateway/allowlists/confluence.txt")).hasContent("12345\n67890\n");
        assertThat(sb.resolve("gateway/allowlists/jira.txt")).hasContent("ACME-1\nACME\n");
        assertThat(Files.readString(sb.resolve("gateway/secrets/confluence.env"))).contains("CONFLUENCE_PAT=old-secret-value").contains("CONFLUENCE_BASE_URL=https://wiki.example.test");
        assertThat(Files.readString(sb.resolve("gateway/secrets/jira.env.example"))).contains("JIRA_BASE_URL=").contains("JIRA_PAT=");

        Conversation c = sandbox.sessionService().show(CONV);
        assertThat(c.schemaVersion()).isEqualTo(2);
        assertThat(c.title()).isEqualTo("Fix login bug");
        assertThat(c.agent()).isEqualTo("copilot-cli");
        assertThat(c.agentSessionId()).isEqualTo("cp-9999");
        assertThat(c.model()).isEqualTo("gpt-4");
        assertThat(c.repos()).containsExactly("api");
        assertThat(c.base().get("api").baseCommit()).isEqualTo("abc123abc123abc123abc123abc123abc123abc1");
        assertThat(c.base().get("api").hostSha()).isNull();
        assertThat(c.base().get("api").history()).isEqualTo(History.NONE);
        assertThat(c.deliveries()).hasSize(1);
        assertThat(c.deliveries().getFirst().branch()).isEqualTo("copilot-fix-1");
        assertThat(c.deliveries().getFirst().repos().get("api").files()).isEqualTo(3);
        assertThat(c.messages()).extracting(Conversation.Message::role).containsExactly("user", "assistant");
        assertThat(c.messages().get(1).text()).isEqualTo("Done: changed LoginController.");
        assertThat(c.messages().get(1).stopReason()).isEqualTo("end_turn");
        assertThat(c.container()).startsWith("lyngvi-sb-3f2a1b0c");

        Path session = sb.resolve(".sessions").resolve(CONV);
        assertThat(session.resolve("work/api/README.md")).hasContent("# api\n");
        assertThat(session.resolve("patches/changes.patch")).exists();
        assertThat(session.resolve("out")).doesNotExist();
        assertThat(session.resolve("output/notes.txt")).hasContent("notes from the agent\n");
        assertThat(session.resolve("attachments")).isDirectory();

        assertThat(sb.resolve("state/agents/copilot-cli/config.json")).exists();
        assertThat(sb.resolve("copilot-config")).doesNotExist();

        assertThat(Files.readString(platform.stateDir().resolve("subnets.txt"))).contains("172.31.240\t" + sb);
        assertThat(sandbox.config().subnetPrefix()).contains("172.31.240");

        assertThat(Files.readString(sb.resolve("Containerfile"))).doesNotContain("FROM old");
        assertThat(sb.resolve("setup.sh")).exists();
        assertThat(sb.resolve("VERSION")).exists();

        assertThat(report.profilePack()).isEqualTo(sb.resolve("profiles/migrated").toString());
        assertThat(report.migratedConversations()).isEqualTo(1);
        assertThat(report.migratedSessions()).isEqualTo(1);
        assertThat(report.gatewayInstances()).isEqualTo(2);
        assertThat(report.manualSteps()).anySatisfy(s -> assertThat(s).contains("build")).anySatisfy(s -> assertThat(s).contains("login"));
        assertThat(sb.resolve("state/.migrated-from-1.1.2")).exists();
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        Migrate112.run(sb, platform, runner);
        MigrateReport again = Migrate112.run(sb, platform, runner);
        assertThat(again.alreadyMigrated()).isTrue();
        assertThat(again.migratedConversations()).isZero();
        assertThat(Sandbox.open(sb, platform, runner).sessionService().list()).hasSize(1);
    }

    @Test
    void conversationsFromTheOldMasterFileAreMigratedToo() throws Exception {
        Files.delete(sb.resolve("copilot-config/conversations/" + CONV + ".json"));
        Path master = Files.createDirectories(tmp.resolve("home/.agentsandbox-conversations"));
        Files.writeString(master.resolve("conversations.json"), "{\"version\":\"1.0.0\",\"conversations\":[" + Files.readString(FIXTURE.resolve("copilot-config/conversations/" + CONV + ".json")) + "]}");

        MigrateReport report = Migrate112.run(sb, platform, runner);

        assertThat(report.migratedConversations()).isEqualTo(1);
        assertThat(Sandbox.open(sb, platform, runner).sessionService().show(CONV).title()).isEqualTo("Fix login bug");
    }
}
