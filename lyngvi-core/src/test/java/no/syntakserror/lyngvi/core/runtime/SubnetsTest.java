package no.syntakserror.lyngvi.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubnetsTest {

    private static final String NETWORKS_JSON = """
            [{"name":"podman","subnets":[{"subnet":"10.88.0.0/16","gateway":"10.88.0.1"}]},
             {"name":"other_internal","subnets":[{"subnet":"172.31.240.0/24","gateway":"172.31.240.1"}]}]
            """;

    @Test
    void candidatePoolIsOrderedAndLarge() {
        assertThat(Subnets.candidates()).startsWith("172.31.240", "172.31.241").contains("172.31.254", "172.30.240", "10.200.0", "10.200.15");
        assertThat(Subnets.candidates()).hasSize(46);
    }

    @Test
    void allocatesTheFirstFreePrefixAndRecordsIt(@TempDir Path tmp) throws Exception {
        Path registry = tmp.resolve("subnets.txt");
        Subnets subnets = new Subnets(registry, new FakeRunner().ok("podman network ls", "[]"));

        String prefix = subnets.allocate(tmp.resolve("sb"));

        assertThat(prefix).isEqualTo("172.31.240");
        assertThat(Files.readString(registry)).isEqualTo("172.31.240\t" + tmp.resolve("sb").toAbsolutePath() + "\n");
    }

    @Test
    void reusesTheRegisteredPrefixForTheSameSandbox(@TempDir Path tmp) {
        Subnets subnets = new Subnets(tmp.resolve("subnets.txt"), new FakeRunner().ok("podman network ls", "[]"));
        String first = subnets.allocate(tmp.resolve("sb"));

        String second = subnets.allocate(tmp.resolve("sb"));

        assertThat(second).isEqualTo(first);
        assertThat(subnets.registered()).hasSize(1);
    }

    @Test
    void skipsPrefixesUsedByOtherSandboxesAndByPodman(@TempDir Path tmp) throws Exception {
        Path registry = tmp.resolve("subnets.txt");
        Path other = Files.createDirectories(tmp.resolve("other-sandbox"));
        Files.writeString(registry, "172.31.241\t" + other + "\n");
        Subnets subnets = new Subnets(registry, new FakeRunner().ok("podman network ls", NETWORKS_JSON));

        String prefix = subnets.allocate(tmp.resolve("sb"));

        assertThat(prefix).isEqualTo("172.31.242");
    }

    @Test
    void worksWhenPodmanIsUnavailable(@TempDir Path tmp) {
        Subnets subnets = new Subnets(tmp.resolve("subnets.txt"), new FakeRunner());

        assertThat(subnets.allocate(tmp.resolve("sb"))).isEqualTo("172.31.240");
    }

    @Test
    void reclaimsThePrefixOfASandboxThatNoLongerExists(@TempDir Path tmp) throws Exception {
        Path registry = tmp.resolve("subnets.txt");
        Files.writeString(registry, "172.31.240\t" + tmp.resolve("deleted-sandbox") + "\n");
        Subnets subnets = new Subnets(registry, new FakeRunner().ok("podman network ls", "[]"));
        Path sb = Files.createDirectories(tmp.resolve("sb"));

        String prefix = subnets.allocate(sb);

        assertThat(prefix).isEqualTo("172.31.240");
        assertThat(subnets.registered()).containsOnlyKeys("172.31.240");
    }

    @Test
    void releaseRemovesTheEntry(@TempDir Path tmp) {
        Subnets subnets = new Subnets(tmp.resolve("subnets.txt"), new FakeRunner().ok("podman network ls", "[]"));
        subnets.allocate(tmp.resolve("sb"));

        subnets.release(tmp.resolve("sb"));

        assertThat(subnets.registered()).isEmpty();
    }

    @Test
    void podmanPrefixesAreTheFirstThreeOctetsOfIpv4Subnets() {
        Subnets subnets = new Subnets(Path.of("/nonexistent"), new FakeRunner().ok("podman network ls", NETWORKS_JSON));

        assertThat(subnets.podmanUsedPrefixes()).containsExactlyInAnyOrder("10.88.0", "172.31.240");
    }
}
