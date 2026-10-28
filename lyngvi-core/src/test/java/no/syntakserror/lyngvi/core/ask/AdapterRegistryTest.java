package no.syntakserror.lyngvi.core.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.stack.SessionVolumes;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterRegistryTest {

    private static SessionVolumes volumes(Path tmp) {
        return new SessionVolumes(new FakeRunner(), tmp, tmp.resolve("tmp"), "sb", "img", 1, 1);
    }

    @Test
    void listsBuiltInsAndExternalAdaptersWithExternalWinning(@TempDir Path tmp) throws Exception {
        Path agents = Files.createDirectories(tmp.resolve("agents"));
        Path ext = Files.createDirectories(agents.resolve("mock"));
        Files.writeString(ext.resolve("agent.json"), "{\"id\":\"mock\",\"label\":\"Overridden mock\",\"egress\":[]}");
        Files.writeString(ext.resolve("adapter.sh"), "#!/bin/sh\necho '{}'\n");
        Path noScript = Files.createDirectories(agents.resolve("claude-code"));
        Files.writeString(noScript.resolve("agent.json"), "{\"id\":\"claude-code\",\"label\":\"Claude Code\",\"egress\":[]}");
        Platform platform = new Platform(Platform.Os.LINUX, tmp, java.util.Map.of(), 1, 1);

        AdapterRegistry registry = new AdapterRegistry(new AgentContext(no.syntakserror.lyngvi.core.config.Paths.of(tmp), platform, new FakeRunner(), null, "2.0.0", volumes(tmp)));

        assertThat(registry.ids()).contains("mock");
        assertThat(registry.find("mock")).isPresent();
        assertThat(registry.find("mock").get()).isInstanceOf(ProcessAdapter.class);
        assertThat(registry.find("mock").get().descriptor().label()).isEqualTo("Overridden mock");
        assertThat(registry.find("claude-code")).as("a built-in adapter driven by agents/claude-code/agent.json").isPresent();
        assertThat(registry.find("claude-code").get()).isInstanceOf(no.syntakserror.lyngvi.core.agents.ClaudeCodeAdapter.class);
        assertThat(registry.find("nope")).isEmpty();
    }

    @Test
    void builtInMockIsAvailableWithoutAnyFiles(@TempDir Path tmp) {
        Platform platform = new Platform(Platform.Os.LINUX, tmp, java.util.Map.of(), 1, 1);
        AdapterRegistry registry = new AdapterRegistry(new AgentContext(no.syntakserror.lyngvi.core.config.Paths.of(tmp), platform, new FakeRunner(), null, "2.0.0", volumes(tmp)));

        assertThat(registry.find("mock")).isPresent();
        assertThat(registry.find("claude-code")).as("no agents/claude-code/agent.json in this sandbox").isEmpty();
        assertThat(registry.find("mock").get()).isInstanceOf(no.syntakserror.lyngvi.core.agents.MockAdapter.class);
        assertThat(registry.find("mock").get().descriptor().label()).isEqualTo("Mock agent");
    }
}
