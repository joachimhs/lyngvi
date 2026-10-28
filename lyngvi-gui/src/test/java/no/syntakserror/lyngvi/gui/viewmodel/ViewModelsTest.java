package no.syntakserror.lyngvi.gui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import no.syntakserror.lyngvi.core.api.ApplyOptions;
import no.syntakserror.lyngvi.core.api.ExportOptions;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.contract.ApplyResult;
import no.syntakserror.lyngvi.core.contract.ExportResult;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The viewmodels against the real core: temp sandbox, real git, scripted podman, the mock agent (spec §9.6). */
class ViewModelsTest {

    @TempDir Path tmp;
    HybridRunner runner;
    SandboxContext ctx;
    Async async;
    AppLog log;
    List<Throwable> errors;
    Path repo;

    private static void git(Runner r, Path repo, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        r.run(cmd, repo, java.time.Duration.ofMinutes(1)).requireSuccess("git");
    }

    @BeforeEach
    void setUp() throws Exception {
        runner = new HybridRunner(tmp.resolve("volumes"));
        runner.fake.ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "")
                .ok("podman ps", "[{\"Names\":[\"sb-proxy\"],\"State\":\"running\",\"Status\":\"Up (healthy)\",\"Image\":\"squid\"}]")
                .ok("podman image exists", "").ok("podman compose version", "1.2\n").ok("podman compose", "")
                .ok("podman --version", "podman version 5.3.1\n").ok("git --version", "git version 2.50\n").ok("openssl version", "OpenSSL 3\n")
                .ok("podman build", "STEP 1\n").ok("podman exec", "").ok("podman stop", "").ok("podman rm", "")
                .ok("podman logs", "1757000000.000 1 172.31.240.20 TCP_DENIED/403 1 CONNECT blocked.example:443 - HIER_NONE/- -\n")
                .ok("podman machine inspect", "[{\"Name\":\"m\",\"State\":\"running\",\"Resources\":{\"CPUs\":8,\"DiskSize\":90,\"Memory\":16384}}]");
        repo = Files.createDirectories(tmp.resolve("api"));
        git(runner.real, repo, "init", "-q", "-b", "main");
        git(runner.real, repo, "config", "user.email", "h@example.org");
        git(runner.real, repo, "config", "user.name", "h");
        Files.writeString(repo.resolve("README.md"), "hi\n");
        git(runner.real, repo, "add", "-A");
        git(runner.real, repo, "commit", "-q", "-m", "init");
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        ctx = new SandboxContext(platform, runner);
        ctx.init(tmp.resolve("sb"), Optional.empty(), List.of(repo), Optional.empty());
        Path profile = tmp.resolve("sb/profile.json");
        Files.writeString(profile, Files.readString(profile).replace("\"enabled\": [\"claude-code\", \"opencode\"]", "\"enabled\": [\"claude-code\", \"opencode\", \"mock\"]"));
        ctx.reload();
        ctx.sandbox().sandboxService().rebuildConfig();
        errors = new ArrayList<>();
        log = new AppLog();
        async = new Async(UiThread.direct(), Runnable::run);
    }

    @Test
    void stackViewModelRendersStartsAndReportsStatus() {
        StackViewModel vm = new StackViewModel(ctx, async, log);
        vm.setErrorHandler(errors::add);
        vm.rebuildConfig();
        vm.up();
        assertThat(errors).isEmpty();
        assertThat(vm.prefixProperty().get()).isEqualTo("172.31.240");
        assertThat(vm.services()).extracting(s -> s.name()).containsExactly("proxy", "gateway");
        assertThat(vm.services().getFirst().state()).isEqualTo("running");
        assertThat(vm.busyProperty().get()).isFalse();

        vm.build(false);
        assertThat(log.text()).contains("STEP 1");
        AtomicReference<no.syntakserror.lyngvi.core.contract.DoctorReport> report = new AtomicReference<>();
        vm.doctor(report::set);
        assertThat(report.get().checks()).isNotEmpty();
        AtomicReference<no.syntakserror.lyngvi.core.stack.MachineInfo> machine = new AtomicReference<>();
        vm.machine(machine::set);
        assertThat(machine.get().cpus()).isGreaterThan(0);

        List<String> lines = new ArrayList<>();
        vm.startLogs("proxy", lines::add);
        assertThat(lines).hasSize(1);
        vm.stopLogs();
        assertThat(vm.logsRunningProperty().get()).isFalse();
    }

    @Test
    void stackViewModelListsSessionContainersAndCleansUpOrphans() {
        StackViewModel vm = new StackViewModel(ctx, async, log);
        vm.setErrorHandler(errors::add);
        SessionViewModel sessions = new SessionViewModel(ctx, async, log);
        sessions.setErrorHandler(errors::add);
        AtomicReference<Conversation> created = new AtomicReference<>();
        sessions.create(new NewSessionOptions(Optional.of("Known"), Optional.empty(), Optional.of("mock"), Optional.empty(),
                Optional.of(History.NONE), Optional.empty(), Optional.empty(), false), created::set);
        String orphan = "lyngvi-" + ctx.sandbox().paths().projectName() + "-deadbeef";
        runner.fake.ok("podman ps", "[{\"Names\":[\"sb-proxy\"],\"State\":\"running\",\"Status\":\"Up (healthy)\",\"Image\":\"squid\"},"
                + "{\"Names\":[\"" + created.get().container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"localhost/lyngvi-mock:2.0.0\"},"
                + "{\"Names\":[\"" + orphan + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"localhost/lyngvi-mock:2.0.0-M9\"}]");
        vm.refresh();
        assertThat(vm.services()).extracting(s -> s.name()).contains("proxy");
        assertThat(vm.sessionContainers()).extracting(c -> c.container()).containsExactly(created.get().container(), orphan);
        assertThat(vm.sessionContainers().getFirst().title()).isEqualTo("Known");
        assertThat(vm.sessionContainers().getLast().orphan()).isTrue();

        // the profile's idle limit is 60 minutes: the fresh session stays, the orphan goes
        AtomicReference<no.syntakserror.lyngvi.core.contract.CleanupReport> report = new AtomicReference<>();
        vm.cleanup(report::set);
        assertThat(report.get().stopped()).isEmpty();
        assertThat(report.get().removed()).containsExactly(orphan);
        assertThat(runner.fake.calls()).contains(List.of("podman", "rm", "-f", orphan));
        assertThat(log.text()).contains(orphan);

        // what is left over, and the GUI's own way of reclaiming it: the live session is never in the list
        AtomicReference<no.syntakserror.lyngvi.core.contract.DiskReport> disk = new AtomicReference<>();
        vm.diskReport(disk::set);
        assertThat(disk.get().entries()).extracting(e -> e.name())
                .contains(created.get().container() + "-work");
        assertThat(disk.get().reclaimable()).extracting(e -> e.name())
                .doesNotContain(created.get().container() + "-work");

        vm.removeOrphan(orphan);
        vm.stopSession(created.get().id());
        assertThat(runner.fake.calls()).contains(List.of("podman", "stop", created.get().container()));
        assertThat(errors).isEmpty();
    }

    @Test
    void configViewModelEditsReposDomainsResourcesAndToolchains() throws Exception {
        ConfigViewModel vm = new ConfigViewModel(ctx, async, log);
        vm.setErrorHandler(errors::add);
        vm.refreshAll();
        assertThat(vm.repos()).extracting(r -> r.name()).containsExactly("api");
        assertThat(vm.domains()).isNotEmpty();
        assertThat(vm.profile().get().name()).isEqualTo("default");

        Path other = Files.createDirectories(tmp.resolve("web"));
        git(runner.real, other, "init", "-q");
        vm.addRepo(other, Optional.empty(), Optional.of(History.NONE));
        assertThat(vm.repos()).extracting(r -> r.name()).containsExactly("api", "web");
        vm.removeRepo("web");
        assertThat(vm.repos()).hasSize(1);

        vm.addDomain("example.org", Optional.of("docs"));
        assertThat(vm.domains()).anySatisfy(d -> assertThat(d.domain()).isEqualTo("example.org"));
        vm.removeDomain("example.org");
        assertThat(vm.domains()).noneSatisfy(d -> assertThat(d.domain()).isEqualTo("example.org"));

        vm.saveResources(new ResourceLimits("16g", null, null, null));
        assertThat(vm.resources().get().mem()).isEqualTo("16g");

        vm.refreshBlocked();
        assertThat(vm.blocked()).extracting(b -> b.host()).containsExactly("blocked.example");
        vm.allowBlocked("blocked.example");
        assertThat(vm.blocked()).isEmpty();
        assertThat(vm.domains()).anySatisfy(d -> assertThat(d.domain()).isEqualTo("blocked.example"));

        assertThat(vm.availableToolchains()).contains("node22", "java25", "python312");
        assertThat(vm.toolchains()).containsExactly("node22");
        vm.setToolchains(List.of("node22", "java25"));
        assertThat(vm.toolchains()).containsExactly("node22", "java25");
        assertThat(Files.readString(tmp.resolve("sb/profile.json"))).contains("\"java25\"");
        assertThat(errors).isEmpty();
    }

    @Test
    void sessionViewModelRunsAConversationWithTheMockAgentAndDeliversIt() throws Exception {
        SessionViewModel vm = new SessionViewModel(ctx, async, log);
        vm.setErrorHandler(errors::add);
        AtomicReference<List<no.syntakserror.lyngvi.core.contract.AgentInfo>> agents = new AtomicReference<>();
        vm.agents(agents::set);
        assertThat(agents.get()).extracting(a -> a.id()).contains("mock", "claude-code");

        AtomicReference<Conversation> created = new AtomicReference<>();
        vm.create(new NewSessionOptions(Optional.of("Fix login"), Optional.empty(), Optional.of("mock"), Optional.empty(),
                Optional.of(History.NONE), Optional.empty(), Optional.empty(), false), created::set);
        assertThat(created.get()).isNotNull();
        String id = created.get().id();
        assertThat(vm.sessions()).extracting(s -> s.id()).containsExactly(id);

        CountDownLatch done = new CountDownLatch(1);
        List<String> texts = new ArrayList<>();
        List<Integer> workSizes = new ArrayList<>();
        AtomicReference<AgentRunBinder.Outcome> outcome = new AtomicReference<>();
        AgentRunBinder.Listener listener = new AgentRunBinder.Listener() {
            @Override public void onText(String full) { texts.add(full); }
            @Override public void onWork(List<Conversation.WorkItem> work) { workSizes.add(work.size()); }
            @Override public void onDone(AgentRunBinder.Outcome o) { outcome.set(o); done.countDown(); }
        };
        AgentRunBinder binder = vm.ask(id, "add a file please", List.of(), Optional.empty(), listener);
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(binder).isNotNull();
        assertThat(texts.getLast()).isEqualTo("I will look at the repository.\n\nDone: wrote MOCK.md in api.");
        assertThat(workSizes).contains(1);
        assertThat(outcome.get().stopReason()).isEqualTo("end_turn");
        assertThat(outcome.get().usage().contextUsed()).isEqualTo(30L);
        assertThat(vm.activity().unreadDone()).isEqualTo(1);
        assertThat(vm.activity().isRunning(id)).isFalse();

        AtomicReference<Conversation> shown = new AtomicReference<>();
        vm.show(id, shown::set);
        assertThat(shown.get().messages()).hasSize(2);
        assertThat(shown.get().messages().getLast().role()).isEqualTo("assistant");

        AtomicReference<String> diff = new AtomicReference<>();
        vm.diff(id, Optional.empty(), Optional.empty(), Optional.empty(), diff::set);
        assertThat(diff.get()).contains("MOCK.md");
        AtomicReference<ReviewResult> review = new AtomicReference<>();
        vm.review(id, Optional.empty(), review::set, errors::add);
        assertThat(review.get().repos().get("api").files()).extracting(f -> f.path()).containsExactly("MOCK.md");
        AtomicReference<ApplyResult> applied = new AtomicReference<>();
        vm.apply(id, new ApplyOptions("agent/fix-login", Optional.of("Add MOCK.md"), "commit", List.of(), List.of(), Optional.empty()), applied::set, errors::add);
        assertThat(applied.get().repos().get("api").files()).isEqualTo(1);
        assertThat(runner.real.run(List.of("git", "branch", "--list", "agent/fix-login"), repo, java.time.Duration.ofSeconds(10)).stdout()).contains("agent/fix-login");

        AtomicReference<List<String>> branches = new AtomicReference<>();
        vm.branches(id, branches::set);
        // the host repo was switched to the working branch by the delivery above, so that one comes first
        assertThat(branches.get()).containsExactly("agent/fix-login", "main");

        // main is protected by the default profile; this sandbox may allow it, and the next delivery lands there
        ConfigViewModel cfg = new ConfigViewModel(ctx, async, log);
        cfg.setErrorHandler(errors::add);
        cfg.refreshProtectedBranches();
        assertThat(cfg.protectedBranches()).contains("main");
        Files.writeString(runner.volumes.dir(created.get().container() + "-work").resolve("api/EXTRA.md"), "more\n");
        List<Throwable> refused = new ArrayList<>();
        vm.apply(id, new ApplyOptions("main", Optional.of("On main"), "commit", List.of(), List.of(), Optional.of("last")), r -> {}, refused::add);
        assertThat(refused).hasSize(1);
        assertThat(Errors.map(refused.getFirst(), ctx.messages()).kind()).isEqualTo(Errors.Kind.PROTECTED_BRANCH);
        cfg.setProtectedBranches(List.of("master", "develop"), () -> {});
        AtomicReference<ApplyResult> onMain = new AtomicReference<>();
        vm.apply(id, new ApplyOptions("main", Optional.of("On main"), "commit", List.of(), List.of(), Optional.of("last")), onMain::set, errors::add);
        assertThat(onMain.get().repos().get("api").branch()).isEqualTo("main");

        vm.rename(id, "Login fixed");
        assertThat(vm.sessions().getFirst().title()).isEqualTo("Login fixed");
        vm.remove(id, false);
        assertThat(vm.sessions()).isEmpty();
        assertThat(errors).isEmpty();
    }

    /** «Export ZIP»: the last resort, which hands the files over without delivering anything (spec §5.9). */
    @Test
    void sessionViewModelExportsTheWholeSessionAsAZip() throws Exception {
        SessionViewModel vm = new SessionViewModel(ctx, async, log);
        vm.setErrorHandler(errors::add);
        AtomicReference<Conversation> created = new AtomicReference<>();
        vm.create(new NewSessionOptions(Optional.of("Fix login"), Optional.empty(), Optional.of("mock"), Optional.empty(),
                Optional.of(History.NONE), Optional.empty(), Optional.empty(), false), created::set);
        Path volume = runner.volumes.dir(created.get().container() + "-work");
        Files.writeString(volume.resolve("api/NEW.md"), "new\n");
        Path zip = tmp.resolve("rescue.zip");

        AtomicReference<ExportResult> exported = new AtomicReference<>();
        vm.export(created.get().id(), ExportOptions.to(zip), exported::set, errors::add);

        assertThat(errors).isEmpty();
        assertThat(exported.get().path()).isEqualTo(zip.toString());
        assertThat(exported.get().files()).isPositive();
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(zip.toFile())) {
            assertThat(archive.stream().map(java.util.zip.ZipEntry::getName)).contains("EXPORT.txt", "api/NEW.md");
        }
        // nothing was delivered: the changes are still waiting
        AtomicReference<ReviewResult> review = new AtomicReference<>();
        vm.review(created.get().id(), Optional.empty(), review::set, errors::add);
        assertThat(review.get().repos().get("api").files()).extracting(f -> f.path()).containsExactly("NEW.md");
    }

    @Test
    void sessionViewModelStopCancelsARunningAsk() throws Exception {
        SessionViewModel vm = new SessionViewModel(ctx, async, log);
        vm.setErrorHandler(errors::add);
        AtomicReference<Conversation> created = new AtomicReference<>();
        vm.create(new NewSessionOptions(Optional.of("Hang"), Optional.empty(), Optional.of("mock"), Optional.empty(),
                Optional.of(History.NONE), Optional.empty(), Optional.empty(), true), created::set);
        String id = created.get().id();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AgentRunBinder.Outcome> outcome = new AtomicReference<>();
        vm.ask(id, "please hang", List.of(), Optional.empty(), new AgentRunBinder.Listener() {
            @Override public void onDone(AgentRunBinder.Outcome o) { outcome.set(o); done.countDown(); }
        });
        assertThat(vm.activity().isRunning(id)).isTrue();
        vm.stop(id);
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(outcome.get().stopReason()).isEqualTo("cancelled");
        assertThat(errors).isEmpty();
    }

    @Test
    void sessionViewModelRunsTwoConversationsInParallelAndKeepsALiveTurnPerConversation() throws Exception {
        SessionViewModel vm = new SessionViewModel(ctx, async, log);
        vm.setErrorHandler(errors::add);
        AtomicReference<Conversation> a = new AtomicReference<>();
        AtomicReference<Conversation> b = new AtomicReference<>();
        vm.create(new NewSessionOptions(Optional.of("Hang A"), Optional.empty(), Optional.of("mock"), Optional.empty(),
                Optional.of(History.NONE), Optional.empty(), Optional.empty(), true), a::set);
        vm.create(new NewSessionOptions(Optional.of("Hang B"), Optional.empty(), Optional.of("mock"), Optional.empty(),
                Optional.of(History.NONE), Optional.empty(), Optional.empty(), true), b::set);
        CountDownLatch doneB = new CountDownLatch(1);
        Path shot = Files.writeString(tmp.resolve("shot.png"), "png");
        vm.ask(a.get().id(), "please hang", List.of(), Optional.empty(), new AgentRunBinder.Listener() {});
        vm.ask(b.get().id(), "please hang", List.of(shot), Optional.of("mock-2"), new AgentRunBinder.Listener() {
            @Override public void onDone(AgentRunBinder.Outcome o) { doneB.countDown(); }
        });
        assertThat(vm.runningCount()).isEqualTo(2);
        assertThat(vm.isRunning(a.get().id())).isTrue();
        assertThat(vm.isRunning(b.get().id())).isTrue();
        assertThat(vm.liveTurn(a.get().id())).isPresent();
        assertThat(vm.liveTurn(b.get().id()).orElseThrow().prompt()).isEqualTo("please hang");
        assertThat(vm.liveTurn(b.get().id()).orElseThrow().attachmentNames()).containsExactly("shot.png");
        assertThat(vm.liveTurn(b.get().id()).orElseThrow().agent()).isEqualTo("mock");

        // The view that shows a running conversation attaches its own listener; the earlier one is replaced.
        CountDownLatch doneA = new CountDownLatch(1);
        AtomicReference<AgentRunBinder.Outcome> outcomeA = new AtomicReference<>();
        vm.watch(a.get().id(), new AgentRunBinder.Listener() {
            @Override public void onDone(AgentRunBinder.Outcome o) { outcomeA.set(o); doneA.countDown(); }
        });
        vm.stop(a.get().id());
        vm.stop(b.get().id());
        assertThat(doneA.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(doneB.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(outcomeA.get().stopReason()).isEqualTo("cancelled");
        assertThat(vm.runningCount()).isZero();
        assertThat(vm.liveTurn(a.get().id())).isEmpty();
        assertThat(vm.liveTurn(b.get().id())).isEmpty();
        assertThat(errors).isEmpty();
    }

    @Test
    void agentRunBinderBatchesEventsAndTreatsAnEndedStreamWithoutDoneAsError() {
        List<String> texts = new ArrayList<>();
        AtomicReference<AgentRunBinder.Outcome> outcome = new AtomicReference<>();
        AgentRunBinder binder = new AgentRunBinder(async, 0, new AgentRunBinder.Listener() {
            @Override public void onText(String full) { texts.add(full); }
            @Override public void onDone(AgentRunBinder.Outcome o) { outcome.set(o); }
        });
        binder.consumer().accept(no.syntakserror.lyngvi.core.ask.AgentEvent.text("Hel"));
        binder.consumer().accept(no.syntakserror.lyngvi.core.ask.AgentEvent.text("lo"));
        assertThat(texts).containsExactly("Hel", "Hello");
        binder.streamEnded();
        assertThat(outcome.get().stopReason()).isEqualTo("error");
        assertThat(outcome.get().error()).isNotBlank();
    }

    @Test
    void gatewayAndAgentsViewModels() throws Exception {
        GatewayViewModel gw = new GatewayViewModel(ctx, async, log);
        gw.setErrorHandler(errors::add);
        gw.refresh();
        assertThat(gw.plugins()).extracting(p -> p.type()).containsExactly("http", "confluence", "jira");
        gw.addInstance("wiki", "confluence", "Wiki", "wiki.gateway.local", List.of(), null, null, Map.of());
        assertThat(gw.instances()).extracting(i -> i.id()).containsExactly("wiki");
        gw.secretSet("wiki", "https://wiki.example.com", "tok");
        gw.allowAdd("wiki", "space:OPS");
        assertThat(gw.allowlist("wiki")).containsExactly("space:OPS");
        gw.refresh();
        assertThat(gw.instances().getFirst().status()).isEqualTo("ready");
        gw.removeInstance("wiki");
        assertThat(gw.instances()).isEmpty();

        AgentsViewModel ag = new AgentsViewModel(ctx, async, log);
        ag.setErrorHandler(errors::add);
        ag.refresh();
        assertThat(ag.agents()).extracting(a -> a.id()).contains("mock");
        AtomicReference<List<no.syntakserror.lyngvi.core.ask.Model>> models = new AtomicReference<>();
        ag.models("mock", models::set);
        assertThat(models.get()).isNotEmpty();
        assertThat(errors).isEmpty();
    }

    @Test
    void skillsViewModelListsEditsAndSwitchesTheSandboxesSkills() {
        SkillsViewModel vm = new SkillsViewModel(ctx, async, log);
        vm.setErrorHandler(errors::add);

        vm.refresh();
        assertThat(vm.skills()).extracting(s -> s.name()).contains("deliver-change", "blocked-host");
        assertThat(vm.agentsProperty().get()).contains("Claude Code");

        AtomicReference<String> content = new AtomicReference<>();
        vm.load("deliver-change", content::set);
        assertThat(content.get()).contains("name: deliver-change");

        List<String> done = new ArrayList<>();
        vm.save("deliver-change", "---\nname: deliver-change\ndescription: Edited.\n---\nbody\n", () -> done.add("saved"));
        assertThat(done).containsExactly("saved");
        assertThat(vm.skills()).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo("deliver-change");
            assertThat(s.description()).isEqualTo("Edited.");
        });

        vm.setEnabled("deliver-change", false, () -> done.add("switched"));
        assertThat(vm.skills()).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo("deliver-change");
            assertThat(s.enabled()).isFalse();
        });

        AtomicReference<no.syntakserror.lyngvi.core.contract.SkillInfo> created = new AtomicReference<>();
        vm.create("from-the-gui", created::set);
        assertThat(created.get().problem()).isNull();
        vm.remove("from-the-gui", () -> done.add("removed"));
        assertThat(vm.skills()).extracting(s -> s.name()).doesNotContain("from-the-gui");

        vm.clear();
        assertThat(vm.skills()).isEmpty();
        assertThat(vm.agentsProperty().get()).isEmpty();
        assertThat(errors).isEmpty();
    }
}
