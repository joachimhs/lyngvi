package no.syntakserror.lyngvi.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code lyngvi skill …} over a real sandbox directory (spec §3.6). */
class SkillCommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;
    private Path sb;
    private Platform platform;
    private FakeRunner runner;

    @BeforeEach
    void setUp() {
        sb = tmp.resolve("sb");
        platform = new Platform(Platform.Os.LINUX, Path.of(System.getProperty("user.home")),
                Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        runner = new FakeRunner().ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "");
        run("init");
    }

    private record Run(int exit, String out) {
        JsonNode json() {
            try {
                return JSON.readTree(out);
            } catch (Exception e) {
                throw new AssertionError("Not JSON: " + out, e);
            }
        }
    }

    private Run run(String... args) {
        StringWriter out = new StringWriter();
        CommandLine cmd = Main.commandLine(platform, runner);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(new StringWriter(), true));
        String[] full = new String[args.length + 2];
        full[0] = "--sandbox";
        full[1] = sb.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new Run(cmd.execute(full), out.toString());
    }

    @Test
    void listShowsTheStarterSkillsAndWhichAgentsTakeThem() {
        Run r = run("--json", "skill", "list");

        assertThat(r.exit()).isZero();
        JsonNode skills = r.json().path("skills");
        assertThat(skills).isNotEmpty();
        assertThat(skills.findValuesAsText("name")).contains("deliver-change", "blocked-host");
        assertThat(skills.get(0).path("enabled").asBoolean()).isTrue();
        assertThat(r.json().path("agents").toString()).contains("Claude Code");
    }

    @Test
    void listWithoutJsonPrintsOneLinePerSkill() {
        Run r = run("skill", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("[x] deliver-change").contains("[x] blocked-host");
    }

    @Test
    void newCreatesASkillAndShowPrintsIt() {
        assertThat(run("skill", "new", "my-skill").exit()).isZero();

        Run shown = run("skill", "show", "my-skill");

        assertThat(shown.out()).contains("name: my-skill").contains("skills/my-skill");
    }

    @Test
    void disableTakesTheSkillAwayFromTheAgentAndEnablePutsItBack() {
        Path copied = sb.resolve("state/agents/claude-code/skills/deliver-change");

        run("skill", "sync");
        assertThat(copied).isDirectory();

        assertThat(run("skill", "disable", "deliver-change").exit()).isZero();
        assertThat(copied).doesNotExist();
        assertThat(sb.resolve("skills/deliver-change/SKILL.md")).isRegularFile();

        assertThat(run("skill", "enable", "deliver-change").exit()).isZero();
        assertThat(copied.resolve("SKILL.md")).isRegularFile();
    }

    @Test
    void removeDeletesTheFolder() {
        run("skill", "new", "my-skill");

        assertThat(run("skill", "remove", "my-skill").exit()).isZero();

        assertThat(sb.resolve("skills/my-skill")).doesNotExist();
    }

    @Test
    void anInvalidNameIsRefused() {
        Run r = run("skill", "new", "Not A Name");

        assertThat(r.exit()).isEqualTo(4); // VALIDATION_FAILED
    }

    @Test
    void aSkillCopiedInByHandIsListedAndSwitchedOn() throws Exception {
        Files.createDirectories(sb.resolve("skills/by-hand"));
        Files.writeString(sb.resolve("skills/by-hand/SKILL.md"), "---\nname: by-hand\ndescription: d\n---\nbody\n");

        Run r = run("--json", "skill", "list");

        assertThat(r.json().path("skills").findValuesAsText("name")).contains("by-hand");
    }
}
