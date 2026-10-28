package no.syntakserror.lyngvi.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileLoaderTest {

    private static final Path DEFAULT = Path.of("..", "profiles", "default", "profile.json");

    @Test
    void loadsTheBundledDefaultProfile() {
        Profile p = ProfileLoader.load(DEFAULT);

        assertThat(p.schemaVersion()).isEqualTo(1);
        assertThat(p.name()).isEqualTo("default");
        assertThat(p.language()).isEqualTo("en");
        assertThat(p.agents().defaultAgent()).isEqualTo("claude-code");
        assertThat(p.agents().enabled()).containsExactly("claude-code", "opencode");
        assertThat(p.toolchains()).containsExactly("node22");
        assertThat(p.egress().allow()).containsExactly("registry.npmjs.org");
        assertThat(p.egress().usesBundledBlocklist()).isTrue();
        assertThat(p.egress().blockExtra()).isEmpty();
        assertThat(p.mirrors().npm()).isNull();
        assertThat(p.git().protectedBranches()).containsExactly("main", "master", "develop");
        assertThat(p.git().branchPrefix()).isEqualTo("agent/");
        assertThat(p.git().committer().email()).isEqualTo("lyngvi@local");
        assertThat(p.resources()).isEqualTo(new ResourceLimits("8g", "12g", "4.0", "2048"));
        assertThat(p.resourcePresets()).isEqualTo(ResourcePreset.BUILT_IN);
        assertThat(p.copy().history()).isEqualTo(History.SHALLOW);
        assertThat(p.copy().depth()).isEqualTo(100);
        assertThat(p.copy().excludes()).contains("node_modules", "*.egg-info");
        assertThat(p.copy().maxFileMiB()).isEqualTo(50);
        assertThat(p.session().idleStopMinutes()).isEqualTo(60);
        assertThat(p.gateway().allowWrite()).isEmpty();
        assertThat(p.localModels()).isEqualTo(Profile.LocalModels.DEFAULT);
        assertThat(p.localModels().host()).isEqualTo("host.containers.internal");
        assertThat(p.localModels().ports()).containsExactly(11434, 1234);
    }

    /** A profile written before the section existed keeps the ports it had; an empty list closes the host off. */
    @Test
    void localModelsFallsBackToTheDefaultAndCanBeClosed(@TempDir Path dir) throws Exception {
        String without = defaultJson().replaceAll("(?s)\\s*\"localModels\": \\{.*?},", "");
        assertThat(ProfileLoader.load(write(dir, without)).localModels()).isEqualTo(Profile.LocalModels.DEFAULT);

        Profile closed = ProfileLoader.load(write(dir, defaultJson().replace("\"ports\": [11434, 1234]", "\"ports\": []")));
        assertThat(closed.localModels().enabled()).isFalse();

        Path bad = write(dir, defaultJson().replace("\"ports\": [11434, 1234]", "\"ports\": [99999]"));
        assertThatThrownBy(() -> ProfileLoader.load(bad)).hasMessageContaining("localModels.ports");
    }

    @Test
    void anExplicitBlocklistReplacesTheBundledOne(@TempDir Path dir) throws Exception {
        Path json = write(dir, defaultJson().replace("\"block\": \"bundled\"", "\"block\": [\".evil.example\"]"));

        Profile p = ProfileLoader.load(json);

        assertThat(p.egress().usesBundledBlocklist()).isFalse();
        assertThat(p.egress().blockList()).containsExactly(".evil.example");
    }

    @Test
    void rejectsUnknownFields(@TempDir Path dir) throws Exception {
        Path json = write(dir, defaultJson().replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"colour\": \"blue\","));

        assertThatThrownBy(() -> ProfileLoader.load(json))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("colour");
    }

    @Test
    void rejectsUnsupportedSchemaVersion(@TempDir Path dir) throws Exception {
        Path json = write(dir, defaultJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 7"));

        assertThatThrownBy(() -> ProfileLoader.load(json))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void rejectsInvalidProfileName(@TempDir Path dir) throws Exception {
        Path json = write(dir, defaultJson().replace("\"name\": \"default\"", "\"name\": \"Not Valid\""));

        assertThatThrownBy(() -> ProfileLoader.load(json)).hasMessageContaining("name");
    }

    @Test
    void rejectsInvalidResourceFormats(@TempDir Path dir) throws Exception {
        Path json = write(dir, defaultJson().replace("\"mem\": \"8g\"", "\"mem\": \"eight\""));

        assertThatThrownBy(() -> ProfileLoader.load(json)).hasMessageContaining("mem");
    }

    /** A profile written before presets existed gets the built-in ones; a profile may name its own instead. */
    @Test
    void resourcePresetsFallBackToTheBuiltInOnes(@TempDir Path dir) throws Exception {
        String without = defaultJson().replaceAll("(?s)\\s*\"resourcePresets\": \\[.*?],", "");
        assertThat(ProfileLoader.load(write(dir, without)).resourcePresets()).isEqualTo(ResourcePreset.BUILT_IN);

        Profile own = ProfileLoader.load(write(dir, without.replace("\"resources\": {",
                "\"resourcePresets\": [{ \"id\": \"tiny\", \"label\": \"Tiny\", \"limits\": "
                        + "{ \"mem\": \"1g\", \"memswap\": \"2g\", \"cpus\": \"1.0\", \"pids\": \"512\" } }], \"resources\": {")));
        assertThat(own.resourcePresets()).containsExactly(new ResourcePreset("tiny", "Tiny",
                new ResourceLimits("1g", "2g", "1.0", "512")));
    }

    @Test
    void rejectsPresetsWithABadIdADuplicateIdOrAMissingLimit(@TempDir Path dir) throws Exception {
        Path badId = write(dir, defaultJson().replace("\"id\": \"review\"", "\"id\": \"Review Me\""));
        assertThatThrownBy(() -> ProfileLoader.load(badId)).hasMessageContaining("resourcePresets");

        Path duplicate = write(dir, defaultJson().replace("\"id\": \"dev\"", "\"id\": \"review\""));
        assertThatThrownBy(() -> ProfileLoader.load(duplicate)).hasMessageContaining("review");

        // A preset is a whole set of limits: a missing field would silently keep whatever was there before.
        Path incomplete = write(dir, defaultJson().replace("\"mem\": \"2g\",  \"memswap\": \"3g\",", "\"memswap\": \"3g\","));
        assertThatThrownBy(() -> ProfileLoader.load(incomplete)).hasMessageContaining("mem");
    }

    @Test
    void rejectsUnknownHistoryMode(@TempDir Path dir) throws Exception {
        Path json = write(dir, defaultJson().replace("\"history\": \"shallow\"", "\"history\": \"deep\""));

        assertThatThrownBy(() -> ProfileLoader.load(json)).hasMessageContaining("history");
    }

    @Test
    void rejectsNonPositiveDepth(@TempDir Path dir) throws Exception {
        Path json = write(dir, defaultJson().replace("\"depth\": 100", "\"depth\": 0"));

        assertThatThrownBy(() -> ProfileLoader.load(json)).hasMessageContaining("depth");
    }

    @Test
    void missingFileIsReportedWithItsPath(@TempDir Path dir) {
        Path json = dir.resolve("nope.json");

        assertThatThrownBy(() -> ProfileLoader.load(json))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("nope.json");
    }

    @Test
    void resourceLimitsValidateEachField() {
        assertThat(ResourceLimits.isValidMemory("8g")).isTrue();
        assertThat(ResourceLimits.isValidMemory("512m")).isTrue();
        assertThat(ResourceLimits.isValidMemory("1073741824")).isTrue();
        assertThat(ResourceLimits.isValidMemory("8 g")).isFalse();
        assertThat(ResourceLimits.isValidCpus("4.0")).isTrue();
        assertThat(ResourceLimits.isValidCpus("2")).isTrue();
        assertThat(ResourceLimits.isValidCpus("two")).isFalse();
        assertThat(ResourceLimits.isValidPids("2048")).isTrue();
        assertThat(ResourceLimits.isValidPids("-1")).isTrue();
        assertThat(ResourceLimits.isValidPids("many")).isFalse();
        assertThat(new ResourceLimits("8g", "12g", "4.0", "2048").invalidFields()).isEmpty();
        assertThat(new ResourceLimits("8g", "lots", "x", "2048").invalidFields()).containsExactly("memswap", "cpus");
    }

    private static String defaultJson() throws Exception {
        return Files.readString(DEFAULT);
    }

    private static Path write(Path dir, String content) throws Exception {
        Path p = dir.resolve("profile.json");
        Files.writeString(p, content);
        return p;
    }
}
