package no.syntakserror.lyngvi.core.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;

class MachineInfoTest {

    private static final String INSPECT = """
            [{"Name":"podman-machine-default","State":"running","Resources":{"CPUs":8,"DiskSize":93,"Memory":26708}}]
            """;

    @Test
    void readsThePodmanMachineOnMacos() {
        Platform mac = new Platform(Platform.Os.MACOS, Path.of("/Users/u"), Map.of(), 501, 20);
        FakeRunner runner = new FakeRunner().ok("podman machine inspect", INSPECT);

        MachineInfo m = MachineInfo.read(mac, runner);

        assertThat(m.available()).isTrue();
        assertThat(m.name()).isEqualTo("podman-machine-default");
        assertThat(m.state()).isEqualTo("running");
        assertThat(m.cpus()).isEqualTo(8);
        assertThat(m.memoryBytes()).isEqualTo(26708L * 1024 * 1024);
        assertThat(m.diskBytes()).isEqualTo(93L * 1024 * 1024 * 1024);
    }

    @Test
    void reportsUnavailableWhenInspectFailsOnMacos() {
        Platform mac = new Platform(Platform.Os.MACOS, Path.of("/Users/u"), Map.of(), 501, 20);

        MachineInfo m = MachineInfo.read(mac, new FakeRunner().on("podman machine inspect", 125, "", "Error: no such machine"));

        assertThat(m.available()).isFalse();
        assertThat(m.state()).isEqualTo("unavailable");
    }

    @Test
    void usesTheHostItselfOnLinux() {
        Platform linux = new Platform(Platform.Os.LINUX, Path.of(System.getProperty("user.home")),
                Map.of("XDG_STATE_HOME", no.syntakserror.lyngvi.core.testing.IsolatedState.dir()), 1000, 1000);

        MachineInfo m = MachineInfo.read(linux, new FakeRunner());

        assertThat(m.available()).isTrue();
        assertThat(m.name()).isEqualTo("host");
        assertThat(m.cpus()).isPositive();
        assertThat(m.memoryBytes()).isPositive();
    }

    @Test
    void parsesSizes() {
        assertThat(ResourceSizes.parse("8g")).isEqualTo(8L << 30);
        assertThat(ResourceSizes.parse("512m")).isEqualTo(512L << 20);
        assertThat(ResourceSizes.parse("1024k")).isEqualTo(1024L << 10);
        assertThat(ResourceSizes.parse("2048")).isEqualTo(2048L);
        assertThat(ResourceSizes.parse("8G")).isEqualTo(8L << 30);
        assertThat(ResourceSizes.format(26708L * 1024 * 1024)).isEqualTo("26.1 GiB");
        assertThat(ResourceSizes.format(512L << 20)).isEqualTo("512 MiB");
    }

    @Test
    void advisesWhenLimitsDoNotFitTheMachine() {
        MachineInfo small = new MachineInfo("vm", "running", 2, 4L << 30, 40L << 30, true);

        var advice = ResourceAdvice.evaluate(new ResourceLimits("8g", "8g", "4.0", "256"), small);

        assertThat(advice).extracting("code").containsExactly("MEM_EXCEEDS_MACHINE", "CPUS_EXCEED_MACHINE", "MEMSWAP_EQUALS_MEM", "PIDS_LOW");
        assertThat(advice.getFirst().message()).contains("8 GiB").contains("4 GiB");
    }

    @Test
    void noAdviceWhenLimitsFit() {
        MachineInfo big = new MachineInfo("vm", "running", 8, 26L << 30, 90L << 30, true);

        assertThat(ResourceAdvice.evaluate(new ResourceLimits("8g", "12g", "4.0", "2048"), big)).isEmpty();
    }

    @Test
    void unavailableMachineIsItselfAdvice() {
        var advice = ResourceAdvice.evaluate(new ResourceLimits("8g", "12g", "4.0", "2048"), MachineInfo.unavailable());

        assertThat(advice).extracting("code").containsExactly("MACHINE_UNAVAILABLE");
    }
}
