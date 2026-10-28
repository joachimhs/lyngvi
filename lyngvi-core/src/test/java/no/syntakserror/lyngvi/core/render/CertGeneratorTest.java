package no.syntakserror.lyngvi.core.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses the real openssl on the build machine. */
class CertGeneratorTest {

    @Test
    void generatesVerifiesAndReusesACertificate(@TempDir Path dir) throws Exception {
        ProcessRunner runner = new ProcessRunner(Platform.current());
        CertGenerator certs = new CertGenerator(runner, dir);
        List<String> hosts = List.of("wiki.internal.example", "wiki");

        boolean generated = certs.ensure(hosts, "172.31.240.10", false);
        assertThat(generated).isTrue();
        assertThat(dir.resolve("gateway.crt")).isRegularFile();
        assertThat(Files.getPosixFilePermissions(dir.resolve("gateway.key"))).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(certs.sans()).containsExactlyInAnyOrder("DNS:wiki.internal.example", "DNS:wiki", "DNS:gateway", "DNS:localhost", "IP Address:127.0.0.1", "IP Address:172.31.240.10");
        assertThat(certs.covers(hosts, "172.31.240.10")).isTrue();

        assertThat(certs.ensure(hosts, "172.31.240.10", false)).isFalse();
        assertThat(certs.covers(List.of("jira.internal.example"), "172.31.240.10")).isFalse();
        assertThat(certs.ensure(List.of("jira.internal.example"), "172.31.240.10", false)).isTrue();
        assertThat(certs.covers(List.of("jira.internal.example"), "172.31.240.10")).isTrue();
        assertThat(certs.notAfter()).isAfter(java.time.Instant.now());
    }
}
