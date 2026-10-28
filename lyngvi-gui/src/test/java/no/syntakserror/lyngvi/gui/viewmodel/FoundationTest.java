package no.syntakserror.lyngvi.gui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationTest {

    @TempDir Path tmp;

    private Platform platform() {
        return new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1000, 1000);
    }

    @Test
    void asyncRunsWorkOffTheUiThreadAndDeliversResultOnIt() throws Exception {
        List<String> uiCalls = new ArrayList<>();
        Async async = new Async(r -> { uiCalls.add("ui"); r.run(); }, Runnable::run);
        AtomicReference<String> result = new AtomicReference<>();
        async.call(() -> "done", result::set, e -> result.set("error " + e.getMessage()));
        assertThat(result.get()).isEqualTo("done");
        assertThat(uiCalls).containsExactly("ui");

        async.call(() -> { throw new IllegalStateException("boom"); }, r -> result.set("no"), e -> result.set("error " + e.getMessage()));
        assertThat(result.get()).isEqualTo("error boom");
    }

    @Test
    void sandboxContextReportsNotInitialisedThenOpensAfterInit() {
        FakeRunner runner = new FakeRunner().ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "");
        SandboxContext ctx = new SandboxContext(platform(), runner);
        assertThat(ctx.isOpen()).isFalse();
        assertThat(ctx.open(tmp.resolve("nowhere"))).isFalse();
        assertThat(ctx.lastError()).isNotNull();
        assertThat(ctx.lastError().code()).isEqualTo(ErrorCode.NOT_INITIALIZED);

        ctx.init(tmp.resolve("sb"), Optional.empty(), List.of(), Optional.empty());
        assertThat(ctx.isOpen()).isTrue();
        assertThat(ctx.sandbox().paths().root()).isEqualTo(tmp.resolve("sb"));
        assertThat(ctx.messages().get("status.header")).isEqualTo("lyngvi status");
    }

    @Test
    void appLogKeepsALimitedRingAndNotifiesListeners() {
        AppLog log = new AppLog(3);
        List<AppLog.Entry> seen = new ArrayList<>();
        log.addListener(seen::add);
        log.info("a");
        log.warn("b");
        log.error("c", new RuntimeException("x"));
        log.info("d");
        assertThat(log.entries()).extracting(AppLog.Entry::message).containsExactly("b", "c: x", "d");
        assertThat(seen).hasSize(4);
        assertThat(log.entries().get(0).level()).isEqualTo(AppLog.Level.WARN);
        assertThat(log.text()).contains("WARN  b").contains("ERROR c: x");
    }

    @Test
    void settingsRoundTripThroughAPropertiesFile() throws Exception {
        Path file = tmp.resolve("gui.properties");
        Settings s = new Settings(file);
        assertThat(s.theme()).isEqualTo(Settings.Theme.LIGHT);
        assertThat(s.sandboxDir()).isEmpty();
        assertThat(s.sidebarWidth()).isEmpty();
        assertThat(s.filesWindowOpen()).isFalse();
        s.setTheme(Settings.Theme.DARK);
        s.setSidebarWidth(318.6);
        s.setSandboxDir(tmp.resolve("sb"));
        s.setWindowBounds(new Settings.WindowBounds(10, 20, 800, 600, true));
        s.setWelcomeShown(true);
        s.setLastChangelogSeen("2.0.0-M8");
        s.setFilesWindowOpen(true);
        s.setFilesWindowBounds(new Settings.WindowBounds(900, 40, 700, 900, false));
        s.setFavouriteModels("opencode", List.of("anthropic/claude-sonnet-4-5", "openai/gpt-5"));
        s.setFavouriteModels("claude-code", List.of());
        s.setLastModel("opencode", "openai/gpt-5");
        s.setLastRepos(tmp.resolve("sb"), List.of("api", "web"));
        s.setLastRepos(tmp.resolve("other"), List.of());
        s.save();

        Settings again = new Settings(file);
        assertThat(again.theme()).isEqualTo(Settings.Theme.DARK);
        assertThat(again.sandboxDir()).contains(tmp.resolve("sb"));
        assertThat(again.windowBounds()).contains(new Settings.WindowBounds(10, 20, 800, 600, true));
        assertThat(again.welcomeShown()).isTrue();
        assertThat(again.lastChangelogSeen()).isEqualTo("2.0.0-M8");
        // the files window is a window of its own: whether it was open, and where it was, are kept apart
        assertThat(again.filesWindowOpen()).isTrue();
        assertThat(again.filesWindowBounds()).contains(new Settings.WindowBounds(900, 40, 700, 900, false));
        // a fractional drag is stored as whole pixels: a pref width is set in pixels and the file stays readable
        assertThat(again.sidebarWidth()).hasValue(319);
        assertThat(again.favouriteModels("opencode")).containsExactly("anthropic/claude-sonnet-4-5", "openai/gpt-5");
        assertThat(again.favouriteModels("claude-code")).isEmpty();
        assertThat(again.lastModel("opencode")).contains("openai/gpt-5");
        assertThat(again.lastModel("claude-code")).isEmpty();
        assertThat(again.lastRepos(tmp.resolve("sb"))).contains(List.of("api", "web"));
        assertThat(again.lastRepos(tmp.resolve("other"))).contains(List.of());
        assertThat(again.lastRepos(tmp.resolve("never"))).isEmpty();

        // a width nobody can parse is no width at all, and the chat falls back to its default
        Path handEdited = tmp.resolve("hand-edited.properties");
        Files.writeString(handEdited, "chatSidebarWidth=wide\n");
        assertThat(new Settings(handEdited).sidebarWidth()).isEmpty();
    }

    /**
     * The sandboxes the toolbar's switcher offers: most recently opened first, each one once, and no more of them
     * than the cap — a sandbox opened twice must not take two lines in the menu or push the others off the list.
     */
    @Test
    void recentSandboxesAreKeptNewestFirstWithoutDuplicates() {
        Path file = tmp.resolve("recent.properties");
        Settings s = new Settings(file);
        assertThat(s.recentSandboxes()).isEmpty();
        s.addRecentSandbox(tmp.resolve("one"));
        s.addRecentSandbox(tmp.resolve("two"));
        s.addRecentSandbox(tmp.resolve("three"));
        s.addRecentSandbox(tmp.resolve("sub/../one")); // the same sandbox, written another way
        assertThat(s.recentSandboxes()).containsExactly(tmp.resolve("one"), tmp.resolve("three"), tmp.resolve("two"));
        s.removeRecentSandbox(tmp.resolve("three"));
        assertThat(s.recentSandboxes()).containsExactly(tmp.resolve("one"), tmp.resolve("two"));
        s.save();
        assertThat(new Settings(file).recentSandboxes()).containsExactly(tmp.resolve("one"), tmp.resolve("two"));

        for (int i = 0; i < Settings.MAX_RECENT_SANDBOXES + 5; i++) s.addRecentSandbox(tmp.resolve("sb" + i));
        assertThat(s.recentSandboxes()).hasSize(Settings.MAX_RECENT_SANDBOXES);
        assertThat(s.recentSandboxes().getFirst()).isEqualTo(tmp.resolve("sb" + (Settings.MAX_RECENT_SANDBOXES + 4)));
        assertThat(s.recentSandboxes()).doesNotContain(tmp.resolve("one"));
    }

    @Test
    void changelogParsesVersionsAndFindsEntriesNewerThanAVersion() {
        String md = """
                # Changelog

                ## 2.0.0-M8 - 2026-09-05

                ### Added
                - Gateway in Java.

                ## 2.0.0-M7 - 2026-09-04

                ### Added
                - Adapters.
                """;
        Changelog c = Changelog.parse(md);
        assertThat(c.entries()).hasSize(2);
        assertThat(c.entries().get(0).version()).isEqualTo("2.0.0-M8");
        assertThat(c.entries().get(0).date()).isEqualTo("2026-09-05");
        assertThat(c.entries().get(0).body()).contains("Gateway in Java");
        assertThat(c.since("2.0.0-M7")).extracting(Changelog.Entry::version).containsExactly("2.0.0-M8");
        assertThat(c.since("2.0.0-M8")).isEmpty();
        assertThat(Changelog.bundled().entries()).isNotEmpty();
    }

    @Test
    void errorsAreMappedByCodeWithDialogText() {
        Messages m = Messages.forLanguage("en");
        SandboxException busy = new SandboxException(ErrorCode.VALIDATION_FAILED, "Session x is busy", Map.of("id", "x", "code", "SESSION_BUSY"));
        Errors.UiError e = Errors.map(busy, m);
        assertThat(e.kind()).isEqualTo(Errors.Kind.SESSION_BUSY);
        assertThat(e.title()).isEqualTo("The agent is still working");

        SandboxException noChanges = new SandboxException(ErrorCode.NO_CHANGES, "No changes since start", Map.of("from", "start",
                "alternatives", List.of(Map.of("from", "last", "describe", "since last delivery", "files", 3))));
        Errors.UiError n = Errors.map(noChanges, m);
        assertThat(n.kind()).isEqualTo(Errors.Kind.NO_CHANGES);
        assertThat(n.alternatives()).hasSize(1);
        assertThat(n.alternatives().get(0).from()).isEqualTo("last");
        assertThat(n.alternatives().get(0).files()).isEqualTo(3);

        SandboxException prot = new SandboxException(ErrorCode.VALIDATION_FAILED, "protected", Map.of("branch", "main", "code", "PROTECTED_BRANCH"));
        assertThat(Errors.map(prot, m).kind()).isEqualTo(Errors.Kind.PROTECTED_BRANCH);
        assertThat(Errors.map(new SandboxException(ErrorCode.NOT_INITIALIZED, "no"), m).kind()).isEqualTo(Errors.Kind.NOT_INITIALIZED);
        Errors.UiError generic = Errors.map(new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "podman failed", Map.of("stderr", "boom")), m);
        assertThat(generic.kind()).isEqualTo(Errors.Kind.GENERIC);
        assertThat(generic.details()).contains("boom");
        assertThat(Errors.map(new IllegalStateException("npe"), m).kind()).isEqualTo(Errors.Kind.GENERIC);
    }

    @Test
    void chatActivityCountsRunningAndUnreadAndPhrasesTheTooltip() {
        Messages m = Messages.forLanguage("en");
        ChatActivity a = new ChatActivity();
        assertThat(a.isIdle()).isTrue();
        a.started("s1");
        a.started("s2");
        assertThat(a.running()).isEqualTo(2);
        a.finished("s1", true);
        a.finished("s2", false);
        assertThat(a.running()).isZero();
        assertThat(a.unreadDone()).isEqualTo(1);
        assertThat(a.unreadErrors()).isEqualTo(1);
        assertThat(a.isIdle()).isFalse();
        assertThat(a.isUnreadDone("s1")).isTrue();
        assertThat(a.isUnreadError("s1")).isFalse();
        assertThat(a.isUnreadDone("s2")).isFalse();
        assertThat(a.isUnreadError("s2")).isTrue();
        assertThat(a.tooltip(m)).isEqualTo("1 conversation finished, 1 conversation failed");
        a.started("s3");
        assertThat(a.tooltip(m)).isEqualTo("1 conversation running, 1 conversation finished, 1 conversation failed");
        a.seen("s1");
        a.seen("s2");
        assertThat(a.isUnreadDone("s1")).isFalse();
        assertThat(a.isUnreadError("s2")).isFalse();
        a.finished("s3", true);
        a.finished("s4", true);
        assertThat(a.tooltip(m)).isEqualTo("2 conversations finished");
        assertThat(a.unreadDone()).isEqualTo(2);
    }

    @Test
    void liveTurnKeepsTheLatestFullStateOfARunningTurn() {
        LiveTurn t = new LiveTurn("mock", "please hang", java.util.List.of("shot.png"), 1234L);
        assertThat(t.model()).isNull();
        t.onStart("mock", "mock-2");
        t.onText("Hello");
        t.onText("Hello world");
        t.onReasoning("thinking");
        t.onWork(java.util.List.of(new no.syntakserror.lyngvi.core.session.Conversation.WorkItem("tool", "read_file", "/work/x", null, false)));
        t.onNotice("warn", "slow");
        no.syntakserror.lyngvi.core.session.Conversation.Usage usage = new no.syntakserror.lyngvi.core.session.Conversation.Usage(1, 2, 0, 0, null, 10L, 100L);
        t.onUsage(usage);
        assertThat(t.agent()).isEqualTo("mock");
        assertThat(t.model()).isEqualTo("mock-2");
        assertThat(t.prompt()).isEqualTo("please hang");
        assertThat(t.attachmentNames()).containsExactly("shot.png");
        assertThat(t.startedAt()).isEqualTo(1234L);
        assertThat(t.text()).isEqualTo("Hello world");
        assertThat(t.reasoning()).isEqualTo("thinking");
        assertThat(t.work()).hasSize(1);
        assertThat(t.notices()).containsExactly(new LiveTurn.Notice("warn", "slow"));
        assertThat(t.usage()).isEqualTo(usage);
    }

    @Test
    void draftsAreKeptPerConversation() {
        Drafts d = new Drafts();
        assertThat(d.restore("a").text()).isEmpty();
        assertThat(d.restore("a").attachments()).isEmpty();
        d.save("a", "  half a thought ", java.util.List.of(java.nio.file.Path.of("shot.png")));
        d.save("b", "", java.util.List.of());
        assertThat(d.restore("a").text()).isEqualTo("  half a thought ");
        assertThat(d.restore("a").attachments()).containsExactly(java.nio.file.Path.of("shot.png"));
        assertThat(d.restore("b").text()).isEmpty();
        assertThat(d.restore("a").text()).as("restore does not consume the draft").isEqualTo("  half a thought ");
        d.clear("a");
        assertThat(d.restore("a").text()).isEmpty();
    }

    @Test
    void lenientBundleShowsTheKeyInsteadOfCrashingWhenARuntimeLacksIt() {
        AppLog log = new AppLog();
        java.util.ResourceBundle real = Messages.forLanguage("en").bundle();
        LenientBundle b = new LenientBundle(real, log);
        assertThat(b.getString("gui.chat.you")).isEqualTo(real.getString("gui.chat.you"));
        assertThat(b.containsKey("gui.chat.you")).isTrue();
        assertThat(b.getString("gui.no.such.key")).isEqualTo("!gui.no.such.key!");
        assertThat(b.getString("gui.no.such.key")).isEqualTo("!gui.no.such.key!");
        assertThat(log.text()).contains("gui.no.such.key");
        assertThat(log.text().split("gui.no.such.key").length - 1).as("logged once, not per lookup").isEqualTo(1);
        assertThat(b.keySet()).contains("gui.chat.you");
        assertThat(b.getLocale()).isEqualTo(real.getLocale());
    }
}
