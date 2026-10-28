package no.syntakserror.lyngvi.core.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.ask.AgentContext;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import no.syntakserror.lyngvi.core.ask.AskRequest;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.stack.SessionVolumes;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerCliAdapterTest {

    private static final Path REPO_AGENTS = Path.of("..", "agents").toAbsolutePath().normalize();

    private static AgentContext context(Path tmp, FakeRunner runner) throws Exception {
        return context(tmp, runner, Platform.Os.MACOS);
    }

    private static AgentContext context(Path tmp, FakeRunner runner, Platform.Os os) throws Exception {
        Path sb = Files.createDirectories(tmp.resolve("sb"));
        Files.createDirectories(sb.resolve("state/agents"));
        Platform platform = new Platform(os, tmp.resolve("home"), Map.of(), 501, 20);
        return new AgentContext(Paths.of(sb), platform, runner, "172.31.240", "2.0.0-M7", new SessionVolumes(runner, sb, sb.resolve("state/tmp"), "sb", "img", 501, 20));
    }

    private static AskRequest request(Path tmp, String prompt, String agentSessionId) throws Exception {
        Path session = Files.createDirectories(tmp.resolve("session"));
        Files.createDirectories(session.resolve("work/api"));
        Path promptFile = Files.writeString(session.resolve("prompt.txt"), prompt);
        return new AskRequest("6f1c", session, "lyngvi-sb-6f1c", "lyngvi-sb-6f1c-work", "img", List.of("api"), agentSessionId, "sonnet", promptFile, List.of(), false);
    }

    @Test
    void claudeAskExecsInsideTheContainerWithTheTemplateAndPromptOnStdin(@TempDir Path tmp) throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/fixtures/claude-code/turn.jsonl"));
        FakeRunner runner = new FakeRunner().ok("podman exec", fixture);
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json")), context(tmp, runner));
        List<AgentEvent> events = new ArrayList<>();

        int exit = adapter.ask(request(tmp, "make a file", null), events::add).waitFor();

        assertThat(exit).isZero();
        List<String> cmd = runner.calls().getFirst();
        assertThat(cmd).startsWith("podman", "exec", "-i", "-w", "/work", "lyngvi-sb-6f1c", "claude", "-p", "--output-format", "stream-json", "--verbose",
                "--permission-mode", "bypassPermissions", "--model", "sonnet", "--session-id");
        assertThat(cmd.get(cmd.indexOf("--session-id") + 1)).matches("[0-9a-f-]{36}");
        assertThat(runner.stdinOf(0)).isEqualTo("make a file");
        assertThat(events).extracting(AgentEvent::type).contains("start", "text", "tool_start", "tool_end", "usage", "done");
        assertThat(((AgentEvent.Done) events.getLast()).stopReason()).isEqualTo("end_turn");
    }

    @Test
    void claudeResumesWithTheAgentSessionId(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman exec", "");
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json")), context(tmp, runner));

        adapter.ask(request(tmp, "again", "3f2b1c9e-0000-4000-8000-000000000001"), e -> {}).waitFor();

        List<String> cmd = runner.calls().getFirst();
        assertThat(cmd).contains("--resume", "3f2b1c9e-0000-4000-8000-000000000001");
        assertThat(cmd).doesNotContain("--session-id");
    }

    @Test
    void opencodeOmitsTheSessionFlagOnTheFirstTurn(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman exec", "");
        OpenCodeAdapter adapter = new OpenCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json")), context(tmp, runner));

        adapter.ask(request(tmp, "go", null), e -> {}).waitFor();
        adapter.ask(request(tmp, "go", "ses_01"), e -> {}).waitFor();

        assertThat(runner.calls().get(0)).containsSequence("opencode", "run", "--format", "json", "--auto", "--model", "sonnet").doesNotContain("--session");
        assertThat(runner.calls().get(1)).containsSequence("--session", "ses_01");
    }

    @Test
    void prepareLinksTheInstructionFileWhenTheAgentHasOne(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman exec", "");
        ClaudeCodeAdapter claude = new ClaudeCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json")), context(tmp, runner));
        OpenCodeAdapter opencode = new OpenCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json")), context(tmp, runner));

        claude.prepare(request(tmp, "x", null));
        opencode.prepare(request(tmp, "x", null));

        assertThat(runner.calls()).hasSize(1);
        assertThat(runner.calls().getFirst()).containsSequence("ln", "-sfn", "/work/AGENTS.md", "/work/CLAUDE.md");
    }

    @Test
    void authStatusComesFromTheStateDirectory(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner();
        AgentContext ctx = context(tmp, runner);
        ClaudeCodeAdapter claude = new ClaudeCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json")), ctx);

        assertThat(claude.authStatus().loggedIn()).isFalse();

        Path state = Files.createDirectories(ctx.paths().agentState("claude-code"));
        Files.writeString(state.resolve(".credentials.json"), "{\"claudeAiOauth\":{\"accessToken\":\"x\",\"expiresAt\":1999999999999,\"subscriptionType\":\"max\"}}");
        assertThat(claude.authStatus().loggedIn()).isTrue();
        assertThat(claude.authStatus().mode()).isEqualTo("subscription");
        assertThat(claude.authStatus().account()).isEqualTo("max");

        Files.delete(state.resolve(".credentials.json"));
        Files.writeString(state.resolve("env"), "CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat-secret\n");
        assertThat(claude.authStatus().mode()).isEqualTo("token");
        assertThat(claude.authStatus().loggedIn()).isTrue();

        OpenCodeAdapter opencode = new OpenCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json")), ctx);
        assertThat(opencode.authStatus().loggedIn()).isFalse();
        Path auth = Files.createDirectories(ctx.paths().agentState("opencode").resolve("data/opencode"));
        Files.writeString(auth.resolve("auth.json"), "{\"anthropic\":{\"type\":\"oauth\",\"access\":\"x\",\"refresh\":\"y\",\"expires\":1}}");
        assertThat(opencode.authStatus().loggedIn()).isTrue();
        assertThat(opencode.authStatus().account()).isEqualTo("anthropic");
    }

    @Test
    void loginRunsAnInteractiveContainerWithTheStateMountedAndTheProxy(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner();
        AgentContext ctx = context(tmp, runner);
        ClaudeCodeAdapter claude = new ClaudeCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json")), ctx);

        int exit = claude.login("subscription", line -> {});

        assertThat(exit).isZero();
        List<String> cmd = runner.calls().getFirst();
        assertThat(cmd).startsWith("podman", "run", "--rm", "-it");
        assertThat(cmd).containsSequence("--network", "sb_internal");
        assertThat(cmd).contains(ctx.paths().agentState("claude-code") + ":/home/agent/.claude:rw,z");
        assertThat(cmd).containsSequence("-e", "HTTPS_PROXY=http://172.31.240.5:3128");
        assertThat(cmd).containsSequence("-e", "CLAUDE_CONFIG_DIR=/home/agent/.claude");
        assertThat(cmd).contains("localhost/lyngvi-claude-code:2.0.0-M7");
        assertThat(cmd).containsSequence("claude", "auth", "login", "--claudeai");
        assertThat(Files.getPosixFilePermissions(ctx.paths().agentState("claude-code"))).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    void modelsComeFromTheDescriptorAndTheEnvFileIsPassedToSessions(@TempDir Path tmp) throws Exception {
        AgentContext ctx = context(tmp, new FakeRunner());
        ClaudeCodeAdapter claude = new ClaudeCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json")), ctx);

        assertThat(claude.models()).extracting("id").containsExactly("sonnet", "opus", "fable", "haiku");
        assertThat(claude.models().getFirst().isDefault()).isTrue();
        assertThat(claude.descriptor().models()).hasSize(4);
    }

    /** opencode declares a {@code commands.models} template, so its catalogue is read from the CLI, cached and merged. */
    @Test
    void opencodeModelsComeFromItsOwnCatalogueMergedBehindTheDeclaredOnes(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman run --rm --network", """
                Providers:
                anthropic/claude-sonnet-4-5
                openai/gpt-5
                openrouter/anthropic/claude-sonnet-4-5

                Run opencode auth login to add a provider.
                """);
        AgentContext ctx = context(tmp, runner);
        OpenCodeAdapter opencode = new OpenCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json")), ctx);

        List<Model> models = opencode.models();

        assertThat(models).extracting(Model::id).containsExactly("anthropic/claude-sonnet-4-5", "anthropic/claude-opus-4-1",
                "openai/gpt-5", "openrouter/anthropic/claude-sonnet-4-5");
        // the declared model keeps its curated label and its default; a fetched one is labelled from its id
        assertThat(models.getFirst().label()).isEqualTo("Claude Sonnet 4.5 (Anthropic)");
        assertThat(models.getFirst().isDefault()).isTrue();
        assertThat(models.get(2).label()).isEqualTo("gpt-5 (openai)");
        assertThat(models.getLast().label()).isEqualTo("anthropic/claude-sonnet-4-5 (openrouter)");
        assertThat(runner.calls()).hasSize(1);
        assertThat(runner.calls().getFirst()).endsWith("opencode", "models");

        // cached: the second call does not run the CLI again, the third does because it asks for a refresh
        assertThat(ctx.paths().agentState("opencode").resolve("models.json")).isRegularFile();
        assertThat(opencode.models()).isEqualTo(models);
        assertThat(runner.calls()).hasSize(1);
        assertThat(opencode.models(true)).isEqualTo(models);
        assertThat(runner.calls()).hasSize(2);
    }

    @Test
    void opencodeFallsBackToTheDeclaredModelsWhenItsCatalogueCannotBeRead(@TempDir Path tmp) throws Exception {
        AgentContext ctx = context(tmp, new FakeRunner());
        OpenCodeAdapter opencode = new OpenCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json")), ctx);

        assertThat(opencode.models()).isEqualTo(opencode.descriptor().models());
        assertThat(ctx.paths().agentState("opencode").resolve("models.json")).doesNotExist();
    }

    /**
     * The models the user configured for a model server of their own are in the catalogue because they are in the
     * agent's configuration file: right behind the declared ones, whether or not the CLI's own listing can be read.
     */
    @Test
    void theConfiguredLocalModelsAreOfferedAheadOfTheFetchedCatalogue(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman run --rm --network", """
                anthropic/claude-sonnet-4-5
                openrouter/qwen/qwen3-coder
                lmstudio/qwen3-coder-30b
                """);
        AgentContext ctx = context(tmp, runner);
        AgentDescriptor descriptor = AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json"));
        Path configDir = Files.createDirectories(ctx.paths().agentState("opencode").resolve("config/opencode"));
        Files.writeString(configDir.resolve("opencode.json"), """
                {"provider":{"lmstudio":{"options":{"baseURL":"https://llm.example.com/v1"},
                 "models":{"qwen3-coder-30b":{"name":"qwen3-coder-30b"},"gpt-oss-120b":{"name":"gpt-oss-120b"}}}}}
                """);

        List<Model> models = new OpenCodeAdapter(descriptor, ctx).models();

        assertThat(models).extracting(Model::id).containsExactly("anthropic/claude-sonnet-4-5", "anthropic/claude-opus-4-1",
                "lmstudio/qwen3-coder-30b", "lmstudio/gpt-oss-120b", "openrouter/qwen/qwen3-coder");
        assertThat(models.get(2).label()).isEqualTo("qwen3-coder-30b (lmstudio)");
        // marked as the user's own, which is what lets the GUI offer them as a group of their own
        assertThat(models).extracting(Model::local).containsExactly(false, false, true, true, false);

        // the one failure the user cannot see: without the agent's listing they are still the models they configured
        AgentContext noCli = context(tmp.resolve("other"), new FakeRunner());
        Path otherConfig = Files.createDirectories(noCli.paths().agentState("opencode").resolve("config/opencode"));
        Files.writeString(otherConfig.resolve("opencode.json"), Files.readString(configDir.resolve("opencode.json")));
        assertThat(new OpenCodeAdapter(descriptor, noCli).models()).extracting(Model::id)
                .containsExactly("anthropic/claude-sonnet-4-5", "anthropic/claude-opus-4-1",
                        "lmstudio/qwen3-coder-30b", "lmstudio/gpt-oss-120b");
    }

    @Test
    void loginCommandIsTheInteractiveArgvAndEmptyForEnvFileModes(@TempDir Path tmp) throws Exception {
        AgentContext ctx = context(tmp, new FakeRunner());
        ClaudeCodeAdapter claude = new ClaudeCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json")), ctx);
        assertThat(claude.loginCommand("subscription")).isPresent().get().satisfies(cmd -> {
            assertThat(cmd).startsWith("podman", "run", "--rm", "-it");
            assertThat(cmd).contains("localhost/lyngvi-claude-code:2.0.0-M7");
        });
        assertThat(claude.loginCommand("api-key")).isEmpty();
    }

    @Test
    void opencodeResolvesOpenRouterModelFromWebsiteSlug(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman exec", "");
        AgentContext ctx = context(tmp, runner);
        OpenCodeAdapter opencode = new OpenCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json")), ctx);

        // Case 1: auth.json has openrouter provider, user copied qwen/qwen3.8-flash from openrouter.ai
        Path authDir = Files.createDirectories(ctx.paths().agentState("opencode").resolve("data/opencode"));
        Files.writeString(authDir.resolve("auth.json"), "{\"openrouter\":{\"type\":\"api\",\"key\":\"sk-or-v1-abc\"}}");

        AskRequest req1 = new AskRequest("s1", tmp.resolve("session"), "c1", "c1-work", "img", List.of(), null, "qwen/qwen3.8-flash", Files.writeString(tmp.resolve("p1.txt"), "hi"), List.of(), false);
        opencode.ask(req1, e -> {}).waitFor();
        assertThat(runner.calls().getLast()).containsSequence("--model", "openrouter/qwen/qwen3.8-flash");

        // Case 2: Model already has openrouter prefix
        AskRequest req2 = new AskRequest("s2", tmp.resolve("session"), "c1", "c1-work", "img", List.of(), null, "openrouter/qwen/qwen3.8-flash", Files.writeString(tmp.resolve("p2.txt"), "hi"), List.of(), false);
        opencode.ask(req2, e -> {}).waitFor();
        assertThat(runner.calls().getLast()).containsSequence("--model", "openrouter/qwen/qwen3.8-flash");

        // Case 3: auth.json has anthropic, user specified anthropic/claude-sonnet-4-5
        Files.writeString(authDir.resolve("auth.json"), "{\"anthropic\":{\"type\":\"api\",\"key\":\"sk-ant-abc\"}}");
        AskRequest req3 = new AskRequest("s3", tmp.resolve("session"), "c1", "c1-work", "img", List.of(), null, "anthropic/claude-sonnet-4-5", Files.writeString(tmp.resolve("p3.txt"), "hi"), List.of(), false);
        opencode.ask(req3, e -> {}).waitFor();
        assertThat(runner.calls().getLast()).containsSequence("--model", "anthropic/claude-sonnet-4-5");

        // Case 4: No auth.json, user passes qwen/qwen3.8-flash (not a built-in provider, routes to openrouter)
        Files.delete(authDir.resolve("auth.json"));
        AskRequest req4 = new AskRequest("s4", tmp.resolve("session"), "c1", "c1-work", "img", List.of(), null, "qwen/qwen3.8-flash", Files.writeString(tmp.resolve("p4.txt"), "hi"), List.of(), false);
        opencode.ask(req4, e -> {}).waitFor();
        assertThat(runner.calls().getLast()).containsSequence("--model", "openrouter/qwen/qwen3.8-flash");
    }

    /** A model server on the developer's own machine has no credentials: opencode.json is where its provider exists. */
    @Test
    void opencodeKeepsModelsOfAProviderDeclaredOnlyInItsOwnConfig(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman exec", "");
        AgentContext ctx = context(tmp, runner);
        OpenCodeAdapter opencode = new OpenCodeAdapter(AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json")), ctx);
        Path authDir = Files.createDirectories(ctx.paths().agentState("opencode").resolve("data/opencode"));
        Files.writeString(authDir.resolve("auth.json"), "{\"openrouter\":{\"type\":\"api\",\"key\":\"sk-or-v1-abc\"}}");
        Path configDir = Files.createDirectories(ctx.paths().agentState("opencode").resolve("config/opencode"));
        Files.writeString(configDir.resolve("opencode.json"), """
                {"provider":{"lmstudio":{"options":{"baseURL":"http://host.containers.internal:1234/v1"},
                 "models":{"mlx-community/Qwen3-30B-A3B-Instruct-2507-4bit":{"name":"Qwen3 30B A3B (local)"}}}}}
                """);

        String model = "lmstudio/mlx-community/Qwen3-30B-A3B-Instruct-2507-4bit";
        AskRequest req = new AskRequest("s5", tmp.resolve("session"), "c1", "c1-work", "img", List.of(), null, model,
                Files.writeString(tmp.resolve("p5.txt"), "hi"), List.of(), false);
        opencode.ask(req, e -> {}).waitFor();

        // without the config the same id looks like a website slug and would be routed to the configured OpenRouter
        assertThat(runner.calls().getLast()).containsSequence("--model", model);
        Files.delete(configDir.resolve("opencode.json"));
        opencode.ask(req, e -> {}).waitFor();
        assertThat(runner.calls().getLast()).containsSequence("--model", "openrouter/" + model);

        // opencode reads opencode.jsonc as well, comments and all
        Files.writeString(configDir.resolve("opencode.jsonc"), "// local models\n{\"provider\":{\"lmstudio\":{}},}\n");
        opencode.ask(req, e -> {}).waitFor();
        assertThat(runner.calls().getLast()).containsSequence("--model", model);
    }

    /** Podman adds the host mapping itself on macOS; on Linux a throwaway container needs it as a session's does. */
    @Test
    void oneOffContainersReachTheDevelopersOwnMachineOnLinux(@TempDir Path tmp) throws Exception {
        AgentDescriptor descriptor = AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json"));

        List<String> onLinux = new OpenCodeAdapter(descriptor, context(tmp, new FakeRunner(), Platform.Os.LINUX))
                .loginCommand("subscription").orElseThrow();

        assertThat(onLinux).containsSequence("--add-host", "host.containers.internal:host-gateway");
        assertThat(new OpenCodeAdapter(descriptor, context(tmp, new FakeRunner())).loginCommand("subscription").orElseThrow())
                .doesNotContain("host.containers.internal:host-gateway");
    }
}
