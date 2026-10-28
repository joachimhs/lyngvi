package no.syntakserror.lyngvi.core.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.ask.AgentContext;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.stack.SessionVolumes;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The provider block for a model server on the developer's own machine: read, written, and tested against the server. */
class LocalModelConfigTest {

    private static final Path REPO_AGENTS = Path.of("..", "agents").toAbsolutePath().normalize();

    private static AgentContext context(Path tmp, FakeRunner runner) throws Exception {
        Path sb = Files.createDirectories(tmp.resolve("sb"));
        Files.createDirectories(sb.resolve("state/agents"));
        return new AgentContext(Paths.of(sb), new Platform(Platform.Os.MACOS, tmp.resolve("home"), Map.of(), 501, 20),
                runner, "172.31.240", "2.0.0-M7", new SessionVolumes(runner, sb, sb.resolve("state/tmp"), "sb", "img", 501, 20));
    }

    private static AgentDescriptor opencode() {
        return AgentDescriptor.read(REPO_AGENTS.resolve("opencode/agent.json"));
    }

    @Test
    void theDescriptorSaysWhereTheProviderLives() {
        AgentDescriptor.LocalModels spec = opencode().localModels();

        assertThat(spec).isNotNull();
        assertThat(spec.provider()).isEqualTo("lmstudio");
        assertThat(spec.configFile()).isEqualTo("config/opencode/opencode.json");
        assertThat(spec.defaultBaseUrl()).startsWith("http://host.containers.internal:");
        assertThat(spec.apiKeyEnv()).isEqualTo("LMSTUDIO_API_KEY");
        assertThat(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json")).localModels()).isNull();
    }

    @Test
    void withoutAConfigurationTheDescriptorsDefaultAddressIsOffered(@TempDir Path tmp) throws Exception {
        LocalModelConfig config = new LocalModelConfig(context(tmp, new FakeRunner()));

        LocalModelConfig.Settings settings = config.read(opencode());

        assertThat(settings.provider()).isEqualTo("lmstudio");
        assertThat(settings.baseUrl()).isEqualTo(opencode().localModels().defaultBaseUrl());
        assertThat(settings.models()).isEmpty();
    }

    @Test
    void savingKeepsEverythingElseInTheFileAndDropsTheCachedCatalogue(@TempDir Path tmp) throws Exception {
        AgentContext ctx = context(tmp, new FakeRunner());
        LocalModelConfig config = new LocalModelConfig(ctx);
        Path state = ctx.paths().agentState("opencode");
        Path file = Files.createDirectories(state.resolve("config/opencode")).resolve("opencode.json");
        Files.writeString(file, """
                {"theme":"system",
                 "provider":{
                   "openrouter":{"options":{"apiKey":"sk-or-v1-abc"}},
                   "lmstudio":{"options":{"baseURL":"http://host.containers.internal:1234/v1"},
                     "models":{"qwen-flash":{"name":"Qwen Flash","limit":{"context":262144,"output":16384}}}}}}
                """);
        Files.writeString(state.resolve("models.json"), "[]");

        assertThat(config.read(opencode()).models()).containsExactly("qwen-flash");

        config.save(opencode(), new LocalModelConfig.Settings("lmstudio", "http://host.containers.internal:10240/v1",
                List.of("qwen-flash", "qwen-coder")));

        String written = Files.readString(file);
        assertThat(written).contains("\"theme\"").contains("sk-or-v1-abc");
        // the model that was already there keeps the fields this dialog knows nothing about
        assertThat(written).contains("\"context\"").contains("262144");
        LocalModelConfig.Settings after = config.read(opencode());
        assertThat(after.baseUrl()).isEqualTo("http://host.containers.internal:10240/v1");
        assertThat(after.models()).containsExactly("qwen-flash", "qwen-coder");
        // the catalogue was cached before the new model existed, so it must be re-read
        assertThat(state.resolve("models.json")).doesNotExist();

        // a model taken out of the list goes out of the file as well
        config.save(opencode(), new LocalModelConfig.Settings("lmstudio", after.baseUrl(), List.of("qwen-coder")));
        assertThat(config.read(opencode()).models()).containsExactly("qwen-coder");
        assertThat(Files.readString(file)).doesNotContain("qwen-flash");
    }

    @Test
    void aFirstSaveWritesAWholeConfigurationFile(@TempDir Path tmp) throws Exception {
        AgentContext ctx = context(tmp, new FakeRunner());
        LocalModelConfig config = new LocalModelConfig(ctx);

        config.save(opencode(), new LocalModelConfig.Settings("lmstudio", "http://host.containers.internal:1234/v1", List.of(" qwen-flash ")));

        Path file = ctx.paths().agentState("opencode").resolve("config/opencode/opencode.json");
        assertThat(file).isRegularFile();
        assertThat(Files.readString(file)).contains("opencode.ai/config.json").contains("\"qwen-flash\"").contains("\"tool_call\"");
        assertThat(config.read(opencode()).models()).containsExactly("qwen-flash");
    }

    @Test
    void anAddressAContainerCannotUseIsRefused(@TempDir Path tmp) throws Exception {
        LocalModelConfig config = new LocalModelConfig(context(tmp, new FakeRunner()));

        assertThat(LocalModelConfig.check("http://host.containers.internal:1234/v1")).isEqualTo(LocalModelConfig.UrlProblem.NONE);
        assertThat(LocalModelConfig.check("")).isEqualTo(LocalModelConfig.UrlProblem.BLANK);
        assertThat(LocalModelConfig.check("host.containers.internal:1234")).isEqualTo(LocalModelConfig.UrlProblem.NOT_HTTP);
        assertThat(LocalModelConfig.check("http://localhost:1234/v1")).isEqualTo(LocalModelConfig.UrlProblem.LOOPBACK);
        assertThat(LocalModelConfig.check("http://127.0.0.1:1234/v1")).isEqualTo(LocalModelConfig.UrlProblem.LOOPBACK);

        assertThatThrownBy(() -> config.save(opencode(), new LocalModelConfig.Settings("lmstudio", "http://localhost:1234/v1", List.of())))
                .hasMessageContaining("localhost");
    }

    @Test
    void theServerIsAskedThroughTheProxyFromAThrowawayContainer(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman run --rm --network", """
                status=200
                {"object":"list","data":[{"id":"qwen-flash","object":"model"},{"id":"gpt-oss","object":"model"}]}
                """);
        LocalModelConfig config = new LocalModelConfig(context(tmp, runner));

        LocalModelConfig.Probe probe = config.probe(opencode(), "http://host.containers.internal:1234/v1");

        assertThat(probe.reachable()).isTrue();
        assertThat(probe.available()).containsExactly("qwen-flash", "gpt-oss");
        assertThat(probe.missing(List.of("qwen-flash", "qwen-coder"))).containsExactly("qwen-coder");
        List<String> cmd = runner.calls().getFirst();
        assertThat(cmd).containsSequence("--network", "sb_internal").contains("--cap-drop", "ALL");
        assertThat(cmd.getLast()).contains("-x http://172.31.240.5:3128").contains("http://host.containers.internal:1234/v1/models");
    }

    /** The key lives with the agent's other credentials and is sent as a bearer token — never as a command-line argument. */
    @Test
    void theApiKeyIsStoredWithTheAgentsCredentialsAndSentByTheTest(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman run --rm --network", "status=200\n{\"data\":[]}\n");
        AgentContext ctx = context(tmp, runner);
        LocalModelConfig config = new LocalModelConfig(ctx);
        Path env = ctx.paths().agentState("opencode").resolve("env");
        Files.createDirectories(env.getParent());
        Files.writeString(env, "# kept\nOPENROUTER_API_KEY=sk-or-v1-abc\n");

        assertThat(config.state(opencode()).apiKeySet()).isFalse();
        assertThat(config.state(opencode()).apiKeyEnv()).isEqualTo("LMSTUDIO_API_KEY");

        config.setApiKey(opencode(), "lm-secret");

        assertThat(Files.readString(env)).contains("# kept").contains("OPENROUTER_API_KEY=sk-or-v1-abc").contains("LMSTUDIO_API_KEY=lm-secret");
        assertThat(config.state(opencode()).apiKeySet()).isTrue();

        config.probe(opencode(), "http://host.containers.internal:1234/v1");
        List<String> cmd = runner.calls().getLast();
        assertThat(cmd).containsSequence("--env-file", env.toString());
        assertThat(cmd.getLast()).contains("Authorization: Bearer $LMSTUDIO_API_KEY");
        assertThat(String.join(" ", cmd)).doesNotContain("lm-secret");

        // a key that is not stored yet can be tested: it goes in a private file for the run, and is gone afterwards
        config.probe(opencode(), "http://host.containers.internal:1234/v1", "typed-but-not-saved");
        String envFileArg = runner.calls().getLast().get(runner.calls().getLast().indexOf("--env-file") + 1);
        assertThat(envFileArg).isNotEqualTo(env.toString());
        assertThat(Path.of(envFileArg)).doesNotExist();
        assertThat(Files.readString(env)).doesNotContain("typed-but-not-saved");

        config.setApiKey(opencode(), "");
        assertThat(config.state(opencode()).apiKeySet()).isFalse();
        assertThat(Files.readString(env)).contains("OPENROUTER_API_KEY=sk-or-v1-abc").doesNotContain("LMSTUDIO_API_KEY");
    }

    @Test
    void aServerThatRefusesTheKeyIsNotTheProxyDenyingThePort() {
        assertThat(LocalModelConfig.parse("status=401\n{\"error\":\"unauthorized\"}", "u").detail()).contains("API key");
        assertThat(LocalModelConfig.parse("status=403\n{\"error\":\"forbidden\"}", "u").detail()).contains("API key");
        // the proxy's own denial names both doors, because the detail cannot know which one this address needs
        assertThat(LocalModelConfig.parse("status=403\n<html>generated by squid</html>", "u").detail())
                .contains("not allowed out of the sandbox").contains("localModels.ports").contains("allowlist");
    }

    /**
     * Which door the proxy needs opened follows from the address, so the dialog can offer the one action that
     * helps: a private address is only ever reached through the {@code localModels} ports, a host out on the
     * network only through the allowlist — and when that door is open already, the denial was about something else.
     */
    @Test
    void theWayOutOfADenialIsThePortForThisMachineAndTheAllowlistForTheNetwork() {
        List<Integer> open = List.of(1234);
        List<String> allowed = List.of("api.anthropic.com", ".example.org");
        String own = "host.containers.internal";

        // the developer's own machine: the port rule, and nothing to offer once the port is open
        assertThat(LocalModelConfig.wayOut("http://host.containers.internal:11434/v1", own, open, allowed))
                .isEqualTo(LocalModelConfig.WayOut.OPEN_PORT);
        assertThat(LocalModelConfig.wayOut("http://host.containers.internal:1234/v1", own, open, allowed))
                .isEqualTo(LocalModelConfig.WayOut.NONE);
        assertThat(LocalModelConfig.wayOut("http://mymac:1234/v1", own, List.of(), allowed))
                .isEqualTo(LocalModelConfig.WayOut.OPEN_PORT);
        assertThat(LocalModelConfig.wayOut("http://192.168.1.9:1234/v1", own, List.of(), allowed))
                .isEqualTo(LocalModelConfig.WayOut.OPEN_PORT);

        // a server out on the network: the allowlist, where a leading dot covers subdomains as it does in Squid
        assertThat(LocalModelConfig.wayOut("https://llm.example.com/v1", own, open, allowed))
                .isEqualTo(LocalModelConfig.WayOut.ALLOW_HOST);
        assertThat(LocalModelConfig.wayOut("https://llm.example.org/v1", own, open, allowed))
                .isEqualTo(LocalModelConfig.WayOut.NONE);
        assertThat(LocalModelConfig.wayOut("https://API.Anthropic.com/v1", own, open, allowed))
                .isEqualTo(LocalModelConfig.WayOut.NONE);
        assertThat(LocalModelConfig.wayOut("https://llm.example.com/v1", own, open, List.of()))
                .isEqualTo(LocalModelConfig.WayOut.ALLOW_HOST);

        // nothing to offer: an address no door covers, and one the dialog would not let be saved either
        assertThat(LocalModelConfig.wayOut("https://203.0.113.5/v1", own, open, allowed)).isEqualTo(LocalModelConfig.WayOut.NONE);
        assertThat(LocalModelConfig.wayOut("http://localhost:1234/v1", own, open, allowed)).isEqualTo(LocalModelConfig.WayOut.NONE);
        assertThat(LocalModelConfig.wayOut("not a url", own, open, allowed)).isEqualTo(LocalModelConfig.WayOut.NONE);

        assertThat(LocalModelConfig.host("https://LLM.Example.org:8443/v1")).isEqualTo("llm.example.org");
        assertThat(LocalModelConfig.host("nonsense")).isEmpty();
    }

    @Test
    void whatWentWrongIsReportedRatherThanAnEmptyList() {
        assertThat(LocalModelConfig.parse("status=000\n", "u").detail()).contains("No answer");
        assertThat(LocalModelConfig.parse("status=403\n", "u").detail()).contains("not allowed out of the sandbox");
        assertThat(LocalModelConfig.parse("status=404\n", "u").detail()).contains("answered 404");
        assertThat(LocalModelConfig.parse("status=200\n<html>", "u").detail()).contains("not a model list");
        assertThat(LocalModelConfig.parse("status=200\n{\"data\":[]}", "u")).satisfies(p -> {
            assertThat(p.reachable()).isTrue();
            assertThat(p.available()).isEmpty();
        });
    }

    @Test
    void anAgentWithoutALocalModelSectionSaysSo(@TempDir Path tmp) throws Exception {
        LocalModelConfig config = new LocalModelConfig(context(tmp, new FakeRunner()));

        assertThatThrownBy(() -> config.read(AgentDescriptor.read(REPO_AGENTS.resolve("claude-code/agent.json"))))
                .hasMessageContaining("no local model configuration");
    }
}
