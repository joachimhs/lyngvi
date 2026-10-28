package no.syntakserror.lyngvi.core.agents;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.ask.AgentAdapter;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import no.syntakserror.lyngvi.core.ask.AgentRunHandle;
import no.syntakserror.lyngvi.core.ask.AgentUsage;
import no.syntakserror.lyngvi.core.ask.AskRequest;
import no.syntakserror.lyngvi.core.ask.AuthStatus;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.ask.Suggestion;
import no.syntakserror.lyngvi.core.ask.UsageLimit;
import no.syntakserror.lyngvi.core.stack.SessionVolumes;

/**
 * The built-in deterministic agent used by tests and for trying the product without a real agent.
 * It writes {@code MOCK.md} into the first repository and emits a fixed event sequence.
 * A prompt containing "fail" produces an error; one containing "hang" waits until cancelled.
 */
public final class MockAdapter implements AgentAdapter {

    public static final String ID = "mock";
    private static final AgentDescriptor DESCRIPTOR = AgentDescriptor.builtIn(ID, "Mock agent");

    private final SessionVolumes volumes;

    public MockAdapter(SessionVolumes volumes) {
        this.volumes = volumes;
    }

    @Override
    public AgentDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean needsContainer() {
        return false;
    }

    @Override
    public AgentRunHandle ask(AskRequest req, Consumer<AgentEvent> onEvent) {
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Thread t = Thread.ofVirtual().unstarted(() -> {
            try {
                run(req, onEvent, cancelled);
            } finally {
                finished.countDown();
            }
        });
        t.start();
        return new AgentRunHandle() {
            @Override public void cancel() { cancelled.countDown(); }
            @Override public int waitFor() {
                try {
                    finished.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
        };
    }

    private void run(AskRequest req, Consumer<AgentEvent> onEvent, CountDownLatch cancelled) {
        String prompt = readPrompt(req);
        String session = req.agentSessionId() != null ? req.agentSessionId() : "mock-" + UUID.randomUUID();
        onEvent.accept(AgentEvent.start(ID, session, req.model() == null ? "mock-1" : req.model()));
        if (prompt.contains("fail")) {
            onEvent.accept(AgentEvent.error("MOCK_FAILURE", "the prompt asked for a failure"));
            onEvent.accept(AgentEvent.done("error"));
            return;
        }
        if (prompt.contains("hang")) {
            try {
                cancelled.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            onEvent.accept(AgentEvent.done("cancelled"));
            return;
        }
        onEvent.accept(AgentEvent.text("I will look at the repository."));
        if (!req.repos().isEmpty()) {
            String repo = req.repos().getFirst();
            onEvent.accept(AgentEvent.toolStart("t1", "write_file", "/work/" + repo + "/MOCK.md"));
            boolean ok = writeMockFile(req, repo);
            onEvent.accept(AgentEvent.toolEnd("t1", ok ? "wrote MOCK.md" : "could not write MOCK.md", !ok));
            onEvent.accept(AgentEvent.text(" Done: wrote MOCK.md in " + repo + "."));
        }
        onEvent.accept(AgentEvent.usage(10, 20, 0, 0, null, 30L, 200_000L));
        onEvent.accept(AgentEvent.done("end_turn"));
    }

    /** Into the session's volume, where the working copy is — the mock agent writes no host file either. */
    private boolean writeMockFile(AskRequest req, String repo) {
        byte[] content = "Written by the built-in mock agent.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            volumes.of(req.container(), req.sessionId()).write(repo + "/MOCK.md", content);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String readPrompt(AskRequest req) {
        try {
            return req.promptFile() == null ? "" : Files.readString(req.promptFile());
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public Suggestion suggest(AskRequest req, String diff) {
        String prompt = readPrompt(req).lines().findFirst().orElse("change").strip();
        String slug = prompt.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) slug = "change";
        if (slug.length() > 40) slug = slug.substring(0, 40).replaceAll("-$", "");
        return new Suggestion("agent/" + slug, "Mock: " + prompt);
    }

    @Override
    public List<Model> models() {
        return List.of(new Model("mock-1", "Mock model", true, false));
    }

    @Override
    public AuthStatus authStatus() {
        return new AuthStatus(true, "none", "mock", null);
    }

    /** A fixed account, so the usage view can be tried without a real agent; the weekly quota is deliberately near its ceiling. */
    @Override
    public AgentUsage usage() {
        return new AgentUsage(true, "Mock Pro", null, null, List.of(
                new UsageLimit("session", "Session quota", 18, 40.0, "requests", null),
                new UsageLimit("weekly", "Weekly quota", 380, 400.0, "requests", null)), Map.of());
    }
}
