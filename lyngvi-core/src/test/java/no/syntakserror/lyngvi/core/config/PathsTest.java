package no.syntakserror.lyngvi.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PathsTest {

    @Test
    void resolvesEveryKnownLocationUnderTheRoot() {
        Paths p = Paths.of(Path.of("/sb"));

        assertThat(p.root()).isEqualTo(Path.of("/sb"));
        assertThat(p.profileJson()).isEqualTo(Path.of("/sb/profile.json"));
        assertThat(p.sandboxConfig()).isEqualTo(Path.of("/sb/sandbox.config"));
        assertThat(p.dotEnv()).isEqualTo(Path.of("/sb/.env"));
        assertThat(p.dotEnvExample()).isEqualTo(Path.of("/sb/templates/.env.example"));
        assertThat(p.state()).isEqualTo(Path.of("/sb/state"));
        assertThat(p.conversations()).isEqualTo(Path.of("/sb/state/conversations"));
        assertThat(p.agentsState()).isEqualTo(Path.of("/sb/state/agents"));
        assertThat(p.locks()).isEqualTo(Path.of("/sb/state/locks"));
        assertThat(p.sessions()).isEqualTo(Path.of("/sb/.sessions"));
        assertThat(p.sessionMeta("abc")).isEqualTo(Path.of("/sb/.sessions/abc"));
        assertThat(p.sessionAttachments("abc")).isEqualTo(Path.of("/sb/.sessions/abc/attachments"));
        assertThat(p.sessionPatches("abc")).isEqualTo(Path.of("/sb/.sessions/abc/patches"));
        assertThat(p.sessionEnvFile("abc")).isEqualTo(Path.of("/sb/.sessions/abc/container.env"));
        assertThat(p.temp()).isEqualTo(Path.of("/sb/state/tmp"));
        assertThat(p.conversation("abc")).isEqualTo(Path.of("/sb/state/conversations/abc.json"));
        assertThat(p.profiles()).isEqualTo(Path.of("/sb/profiles"));
        assertThat(p.defaultProfilePack()).isEqualTo(Path.of("/sb/profiles/default"));
        assertThat(p.templates()).isEqualTo(Path.of("/sb/templates"));
        assertThat(p.agentsMd()).isEqualTo(Path.of("/sb/AGENTS.md"));
        assertThat(p.containerfile()).isEqualTo(Path.of("/sb/Containerfile"));
        assertThat(p.compose()).isEqualTo(Path.of("/sb/docker-compose.yml"));
        assertThat(p.cli()).isEqualTo(Path.of("/sb/cli"));
    }

    @Test
    void normalisesToAnAbsolutePath() {
        Paths p = Paths.of(Path.of("relative/../dir"));

        assertThat(p.root()).isAbsolute();
        assertThat(p.root().toString()).endsWith("/dir");
    }
}
