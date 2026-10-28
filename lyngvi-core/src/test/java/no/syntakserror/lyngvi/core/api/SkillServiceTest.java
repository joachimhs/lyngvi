package no.syntakserror.lyngvi.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.contract.SkillInfo;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The skills of a sandbox, and the copy of them each agent that takes skills gets (spec §3.6). */
class SkillServiceTest {

    /** A sandbox whose runner answers what {@code upgrade} needs: a subnet to allocate and a certificate to write. */
    private static Sandbox sandbox(Path tmp) {
        Path sb = tmp.resolve("sb");
        no.syntakserror.lyngvi.core.runtime.Platform platform = new no.syntakserror.lyngvi.core.runtime.Platform(
                no.syntakserror.lyngvi.core.runtime.Platform.Os.LINUX, tmp.resolve("home"),
                java.util.Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        FakeRunner runner = new FakeRunner().ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "");
        Sandbox.init(sb, new InitOptions(Optional.empty(), List.of(), Optional.empty(), false), platform, runner);
        return Sandbox.open(sb, platform, runner);
    }

    /** Where Claude Code reads skills from: {@code skillsDir} under the configuration directory it mounts. */
    private static Path claudeSkills(Sandbox sandbox) {
        return sandbox.paths().agentState("claude-code").resolve("skills");
    }

    @Test
    void initLaysDownTheSkillsThatShipWithLyngvi(@TempDir Path tmp) {
        Sandbox sandbox = sandbox(tmp);

        List<SkillInfo> skills = sandbox.skillService().list();

        assertThat(skills).extracting(SkillInfo::name).contains("blocked-host", "deliver-change");
        assertThat(skills).allSatisfy(s -> {
            assertThat(s.enabled()).isTrue();
            assertThat(s.problem()).isNull();
            assertThat(s.description()).isNotBlank();
        });
    }

    @Test
    void initDoesNotOverwriteSkillsTheSandboxAlreadyHas(@TempDir Path tmp) throws Exception {
        Sandbox sandbox = sandbox(tmp);
        Path file = sandbox.paths().skillFile("deliver-change");
        Files.writeString(file, "---\nname: deliver-change\ndescription: mine\n---\nmine\n");

        // What 'init --yes' does to a sandbox that is already there: the user's skills are not bundled files.
        Sandbox.init(sandbox.paths().root(), new InitOptions(Optional.empty(), List.of(), Optional.empty(), true),
                SandboxTest.PLATFORM, new FakeRunner());

        assertThat(Files.readString(file)).contains("description: mine");
    }

    @Test
    void upgradeOffersTheStarterSkillsOnceAndNeverBringsADeletedOneBack(@TempDir Path tmp) {
        Sandbox sandbox = sandbox(tmp);
        sandbox.skillService().remove("deliver-change");

        sandbox.sandboxService().upgrade();

        assertThat(sandbox.skillService().list()).extracting(SkillInfo::name)
                .doesNotContain("deliver-change")
                .contains("blocked-host");
    }

    @Test
    void aSandboxThatPredatesSkillsGetsTheStarterSkillsOnUpgrade(@TempDir Path tmp) {
        Sandbox sandbox = sandbox(tmp);
        // As an older version left it: no skills folder, and nothing recorded as offered.
        no.syntakserror.lyngvi.core.skills.SkillStore store = new no.syntakserror.lyngvi.core.skills.SkillStore(sandbox.paths());
        for (SkillInfo s : store.list()) store.remove(s.name());
        new java.io.File(sandbox.paths().skillsOffered().toString()).delete();

        sandbox.sandboxService().upgrade();

        assertThat(sandbox.skillService().list()).extracting(SkillInfo::name).contains("deliver-change", "blocked-host");
    }

    @Test
    void aSwitchedOnSkillIsCopiedToTheAgentAndASwitchedOffOneIsTakenAway(@TempDir Path tmp) throws Exception {
        Sandbox sandbox = sandbox(tmp);

        sandbox.skillService().syncAll();
        assertThat(claudeSkills(sandbox).resolve("deliver-change/SKILL.md")).isRegularFile();

        sandbox.skillService().setEnabled("deliver-change", false);

        assertThat(claudeSkills(sandbox).resolve("deliver-change")).doesNotExist();
        assertThat(claudeSkills(sandbox).resolve("blocked-host/SKILL.md")).isRegularFile();
        assertThat(sandbox.skillService().show("deliver-change").enabled()).isFalse();
        // Switched off, not deleted: the folder in the sandbox is untouched.
        assertThat(sandbox.paths().skillFile("deliver-change")).isRegularFile();
    }

    @Test
    void savingASkillRewritesTheCopyTheAgentReads(@TempDir Path tmp) throws Exception {
        Sandbox sandbox = sandbox(tmp);
        String content = "---\nname: deliver-change\ndescription: Edited by the user.\n---\n\nSteps.\n";

        SkillInfo saved = sandbox.skillService().save("deliver-change", content);

        assertThat(saved.description()).isEqualTo("Edited by the user.");
        assertThat(Files.readString(claudeSkills(sandbox).resolve("deliver-change/SKILL.md"))).isEqualTo(content);
    }

    @Test
    void aSkillWithoutFrontMatterIsListedWithItsProblemAndNeverCopied(@TempDir Path tmp) throws Exception {
        Sandbox sandbox = sandbox(tmp);
        Files.createDirectories(sandbox.paths().skill("half-written"));
        Files.writeString(sandbox.paths().skillFile("half-written"), "# started, no front matter\n");

        sandbox.skillService().syncAll();

        assertThat(sandbox.skillService().show("half-written").problem()).contains("front matter");
        assertThat(claudeSkills(sandbox).resolve("half-written")).doesNotExist();
    }

    @Test
    void extraFilesInTheFolderTravelWithTheSkill(@TempDir Path tmp) throws Exception {
        Sandbox sandbox = sandbox(tmp);
        Files.createDirectories(sandbox.paths().skill("deliver-change").resolve("references"));
        Files.writeString(sandbox.paths().skill("deliver-change").resolve("references/checklist.md"), "one\n");

        sandbox.skillService().syncAll();

        assertThat(claudeSkills(sandbox).resolve("deliver-change/references/checklist.md")).isRegularFile();
        assertThat(sandbox.skillService().show("deliver-change").extraFiles()).isEqualTo(1);
    }

    @Test
    void aSkillInstalledInTheAgentsOwnWayIsLeftAlone(@TempDir Path tmp) throws Exception {
        Sandbox sandbox = sandbox(tmp);
        Path theirs = claudeSkills(sandbox).resolve("installed-by-hand");
        Files.createDirectories(theirs);
        Files.writeString(theirs.resolve("SKILL.md"), "---\nname: installed-by-hand\ndescription: d\n---\n");

        sandbox.skillService().syncAll();
        sandbox.skillService().setEnabled("deliver-change", false);

        assertThat(theirs.resolve("SKILL.md")).isRegularFile();
    }

    @Test
    void newCreatesAFolderWithAUsableFileAndRemoveTakesTheWholeFolder(@TempDir Path tmp) {
        Sandbox sandbox = sandbox(tmp);

        SkillInfo created = sandbox.skillService().create("my-skill");

        assertThat(created.problem()).isNull();
        assertThat(created.enabled()).isTrue();
        assertThat(sandbox.paths().skillFile("my-skill")).isRegularFile();
        assertThat(claudeSkills(sandbox).resolve("my-skill/SKILL.md")).isRegularFile();

        sandbox.skillService().remove("my-skill");

        assertThat(sandbox.paths().skill("my-skill")).doesNotExist();
        assertThat(claudeSkills(sandbox).resolve("my-skill")).doesNotExist();
        assertThat(sandbox.skillService().list()).extracting(SkillInfo::name).doesNotContain("my-skill");
    }

    @Test
    void aNameThatIsNotASkillNameIsRefused(@TempDir Path tmp) {
        Sandbox sandbox = sandbox(tmp);

        assertThatThrownBy(() -> sandbox.skillService().create("Not A Name"))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("is not a skill name");
        assertThatThrownBy(() -> sandbox.skillService().create("../escape"))
                .isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> sandbox.skillService().create("deliver-change"))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void anUnknownSkillIsAnError(@TempDir Path tmp) {
        Sandbox sandbox = sandbox(tmp);

        assertThatThrownBy(() -> sandbox.skillService().read("no-such-skill"))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("No skill named");
    }

    @Test
    void bothReferenceAgentsGetTheSkillsInTheirOwnLocation(@TempDir Path tmp) {
        Sandbox sandbox = sandbox(tmp);

        assertThat(sandbox.skillService().syncAll()).allSatisfy(r -> assertThat(r.supported()).isTrue())
                .extracting(r -> r.agent()).containsExactlyInAnyOrder("claude-code", "opencode");
        assertThat(sandbox.skillService().agentsTakingSkills()).containsExactlyInAnyOrder("Claude Code", "OpenCode");
        // Claude Code reads ~/.claude/skills; opencode reads $XDG_CONFIG_HOME/opencode/skills, which is why its
        // skillsDir starts with config/ (verified against the pinned CLI with 'opencode debug skill').
        assertThat(claudeSkills(sandbox).resolve("deliver-change/SKILL.md")).isRegularFile();
        assertThat(sandbox.paths().agentState("opencode").resolve("config/opencode/skills/deliver-change/SKILL.md"))
                .isRegularFile();
    }

    @Test
    void anAgentWithoutSkillsIsLeftOutOfTheSync(@TempDir Path tmp) {
        Sandbox sandbox = sandbox(tmp);

        // mock-sh declares no configDir and no skillsDir: skills are not part of how it works.
        no.syntakserror.lyngvi.core.skills.SkillSync.Result result =
                new no.syntakserror.lyngvi.core.skills.SkillSync(sandbox.paths()).sync("mock-sh", sandbox.skillService().list());

        assertThat(result.supported()).isFalse();
        assertThat(result.copied()).isEmpty();
        assertThat(sandbox.paths().agentState("mock-sh")).doesNotExist();
    }
}
