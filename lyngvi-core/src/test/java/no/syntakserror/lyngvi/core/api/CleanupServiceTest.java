package no.syntakserror.lyngvi.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import no.syntakserror.lyngvi.core.contract.DiskReport;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.session.ConversationStore;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git and the volumes run for real; podman's listings are scripted. */
class CleanupServiceTest {

    @TempDir Path tmp;
    HybridRunner runner;
    Sandbox sandbox;

    @BeforeEach
    void setUp() throws Exception {
        runner = new HybridRunner(tmp.resolve("volumes"));
        runner.fake.ok("podman network ls", "[]").ok("podman ps", "[]").ok("podman image exists", "")
                .ok("podman images", "[]").ok("podman system df", "").ok("podman rm", "").ok("podman rmi", "");
        Path repo = Files.createDirectories(tmp.resolve("api"));
        git(runner.real, repo, "init", "-q", "-b", "main");
        git(runner.real, repo, "config", "user.email", "h@example.org");
        git(runner.real, repo, "config", "user.name", "h");
        Files.writeString(repo.resolve("README.md"), "hi\n");
        git(runner.real, repo, "add", "-A");
        git(runner.real, repo, "commit", "-q", "-m", "init");
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(repo), Optional.empty(), false), platform, runner);
        sandbox = Sandbox.open(tmp.resolve("sb"), platform, runner);
        new Renderer(sandbox).render();
    }

    private static void git(no.syntakserror.lyngvi.core.runtime.Runner r, Path repo, String... args) {
        java.util.List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        r.run(cmd, repo, Duration.ofSeconds(30)).requireSuccess("git");
    }

    private Conversation session() {
        return sandbox.sessionService().create(new NewSessionOptions(Optional.of("Fix login"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false));
    }

    private static DiskReport.Entry entry(DiskReport report, String kind, String name) {
        return report.entries().stream().filter(e -> e.kind().equals(kind) && e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aLiveSessionIsListedButNeverReclaimable() {
        Conversation c = session();

        DiskReport report = sandbox.cleanupService().report(CleanOptions.report());

        DiskReport.Entry volume = entry(report, "volume", c.container() + "-work");
        assertThat(volume.sessionId()).isEqualTo(c.id());
        assertThat(volume.reclaimable()).isFalse();
        assertThat(volume.reason()).isEqualTo("live");
        assertThat(report.reclaimable()).isEmpty();
    }

    @Test
    void aVolumeWhoseConversationIsGoneIsAnOrphanAndGoesFirst() {
        Conversation c = session();
        new ConversationStore(sandbox.paths()).delete(c.id());

        DiskReport report = sandbox.cleanupService().report(CleanOptions.report());
        assertThat(entry(report, "volume", c.container() + "-work").reason()).isEqualTo("orphan");

        CleanupService.CleanResult dry = sandbox.cleanupService().clean(CleanOptions.report());
        assertThat(dry.dryRun()).isTrue();
        assertThat(runner.volumes.dir(c.container() + "-work")).isDirectory();

        CleanupService.CleanResult done = sandbox.cleanupService().clean(
                new CleanOptions(Set.of(CleanOptions.WORK), 0, false, false));
        assertThat(done.removed()).contains(c.container() + "-work");
        assertThat(runner.volumes.dir(c.container() + "-work")).doesNotExist();
    }

    /** The rule that matters most: an old session that never delivered keeps its work unless asked twice. */
    @Test
    void anOldSessionWithNothingDeliveredKeepsItsWorkUnlessTheCallerInsists() {
        Conversation c = session();
        ConversationStore store = new ConversationStore(sandbox.paths());
        store.save(c.withLastActiveAt(System.currentTimeMillis() - Duration.ofDays(30).toMillis()));

        CleanOptions old = new CleanOptions(Set.of(CleanOptions.WORK), 7, false, true);
        assertThat(entry(sandbox.cleanupService().report(old), "volume", c.container() + "-work").reclaimable()).isFalse();

        CleanOptions insisted = new CleanOptions(Set.of(CleanOptions.WORK), 7, true, true);
        DiskReport.Entry e = entry(sandbox.cleanupService().report(insisted), "volume", c.container() + "-work");
        assertThat(e.reclaimable()).isTrue();
        assertThat(e.reason()).isEqualTo("undelivered");
    }

    @Test
    void anOldSessionThatHasDeliveredIsFinishedAndMayGo() {
        Conversation c = session();
        ConversationStore store = new ConversationStore(sandbox.paths());
        Conversation delivered = new Conversation(c.schemaVersion(), c.id(), c.title(), c.createdAt(),
                c.updatedAt(), System.currentTimeMillis() - Duration.ofDays(30).toMillis(), c.agent(), c.agentSessionId(),
                c.model(), c.repos(), c.resources(), c.container(), c.base(),
                List.of(new Conversation.Delivery(1L, "agent/x", "msg", "commit", Map.of())), c.messages(), null);
        store.save(delivered);

        DiskReport.Entry e = entry(sandbox.cleanupService().report(new CleanOptions(Set.of(CleanOptions.WORK), 7, false, true)),
                "volume", c.container() + "-work");
        assertThat(e.reclaimable()).isTrue();
        assertThat(e.reason()).isEqualTo("finished");
    }

    /** A working copy an older version left on the host is cleaned too, and it is listed with its real size. */
    @Test
    void aWorkingCopyLeftOnTheHostIsFoundAndRemoved() throws Exception {
        Path work = Files.createDirectories(sandbox.paths().sessionMeta("8ac3-gone").resolve("work/api"));
        Files.writeString(work.resolve("big.txt"), "x".repeat(4096));

        DiskReport report = sandbox.cleanupService().report(CleanOptions.report());
        DiskReport.Entry e = entry(report, "workdir", work.getParent().toString());
        assertThat(e.reason()).isEqualTo("orphan");
        assertThat(e.bytes()).isEqualTo(4096);

        sandbox.cleanupService().clean(new CleanOptions(Set.of(CleanOptions.WORK), 0, false, false));
        assertThat(work.getParent()).doesNotExist();
    }

    @Test
    void imagesOfOtherVersionsAreReclaimableAndTheCurrentOneIsNot() {
        String version = no.syntakserror.lyngvi.core.version.Version.current().toString();
        runner.fake.ok("podman images", "[{\"Names\":[\"localhost/lyngvi-base:" + version + "\"],\"Size\":900000000},"
                + "{\"Names\":[\"localhost/lyngvi-base:1.9.0\"],\"Size\":800000000}]");

        DiskReport report = sandbox.cleanupService().report(CleanOptions.report());

        assertThat(report.entries()).noneMatch(e -> e.name().equals("localhost/lyngvi-base:" + version));
        DiskReport.Entry old = entry(report, "image", "localhost/lyngvi-base:1.9.0");
        assertThat(old.reclaimable()).isTrue();
        assertThat(old.bytes()).isEqualTo(800000000);

        sandbox.cleanupService().clean(new CleanOptions(Set.of(CleanOptions.IMAGES), 0, false, false));
        assertThat(runner.fake.calls()).contains(List.of("podman", "rmi", "localhost/lyngvi-base:1.9.0"));
    }

    /** The leftovers have to be visible without being looked for, so doctor counts them. */
    @Test
    void doctorReportsWhatCanBeReclaimed() {
        Conversation c = session();
        new ConversationStore(sandbox.paths()).delete(c.id());
        // the Podman-side checks only run on a host that has Podman and a rendered stack
        runner.fake.ok("podman --version", "podman version 5.3.1\n").ok("podman compose version", "podman-compose 1.2.0\n")
                .ok("git --version", "git version 2.50.1\n").ok("openssl version", "OpenSSL 3.6.3\n").ok("git -C", "true\n");

        var checks = sandbox.sandboxService().doctor().checks();

        assertThat(checks).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("disk");
            assertThat(check.message()).contains("1 object(s) can be reclaimed");
            // under the warning threshold it is an ok line, and then the way out is in the message itself
            assertThat(check.message() + check.hint()).contains("setup.sh clean");
        });
    }

    /** Nothing outside this sandbox is ever in the report, whatever podman reports. */
    @Test
    void objectsOfOtherProjectsAndOtherProductsAreLeftAlone() {
        runner.fake.ok("podman ps", "[{\"Names\":[\"lyngvi-other-11111111\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"},"
                        + "{\"Names\":[\"someone-elses-container\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"}]")
                .ok("podman images", "[{\"Names\":[\"docker.io/library/debian:bookworm\"],\"Size\":100000000}]");

        DiskReport report = sandbox.cleanupService().report(CleanOptions.report());

        assertThat(report.entries()).noneMatch(e -> e.name().contains("someone-elses"));
        assertThat(report.entries()).noneMatch(e -> e.name().contains("lyngvi-other-"));
        assertThat(report.entries()).noneMatch(e -> e.name().contains("debian"));
    }
}
