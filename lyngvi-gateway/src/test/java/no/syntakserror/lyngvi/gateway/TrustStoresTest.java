package no.syntakserror.lyngvi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

class TrustStoresTest {

    @TempDir Path tmp;

    static boolean opensslAvailable() {
        return TlsTest.opensslAvailable();
    }

    @Test
    void withoutADirectoryTheDefaultTrustIsUsed() throws Exception {
        X509TrustManager tm = TrustStores.trustManager(null);
        assertThat(tm.getAcceptedIssuers().length).isGreaterThan(10);
        assertThat(TrustStores.trustManager(tmp.resolve("missing")).getAcceptedIssuers().length).isEqualTo(tm.getAcceptedIssuers().length);
    }

    @Test
    @EnabledIf("opensslAvailable")
    void pemFilesInTheDirectoryAreAddedToTheDefaultTrust() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("ca"));
        new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", tmp.resolve("k.pem").toString(),
                "-out", dir.resolve("corp-root.crt").toString(), "-days", "1", "-subj", "/CN=Corp Root").redirectErrorStream(true).start().waitFor();
        Files.writeString(dir.resolve("notes.txt"), "not a certificate");
        int base = TrustStores.trustManager(null).getAcceptedIssuers().length;
        X509TrustManager tm = TrustStores.trustManager(dir);
        assertThat(tm.getAcceptedIssuers().length).isEqualTo(base + 1);
        assertThat(java.util.Arrays.stream(tm.getAcceptedIssuers()).map(X509Certificate::getSubjectX500Principal).map(Object::toString)).anyMatch(s -> s.contains("Corp Root"));
    }
}
