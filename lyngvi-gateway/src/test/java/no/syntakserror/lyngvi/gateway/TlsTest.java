package no.syntakserror.lyngvi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import no.syntakserror.lyngvi.gateway.testing.GatewayHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIf;

class TlsTest {

    @TempDir Path tmp;

    static boolean opensslAvailable() {
        try {
            return new ProcessBuilder("openssl", "version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("opensslAvailable")
    void servesHttpsFromPemFiles() throws Exception {
        Path key = tmp.resolve("key.pem");
        Path cert = tmp.resolve("cert.pem");
        new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key.toString(), "-out", cert.toString(),
                "-days", "1", "-subj", "/CN=gateway", "-addext", "subjectAltName=IP:127.0.0.1").redirectErrorStream(true).start().waitFor();
        int free;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            free = ss.getLocalPort();
        }
        try (GatewayHarness h = new GatewayHarness(tmp).env("TLS_PORT", String.valueOf(free)).env("TLS_CERT_FILE", cert.toString()).env("TLS_KEY_FILE", key.toString()).start()) {
            int tlsPort = h.gateway().tlsPort();
            assertThat(tlsPort).isGreaterThan(0);
            SSLContext trustAll = SSLContext.getInstance("TLS");
            trustAll.init(null, new TrustManager[] {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            HttpClient c = HttpClient.newBuilder().sslContext(trustAll).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + tlsPort + "/healthz")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(new JacksonJson().readObject(r.body()).get("tls")).isEqualTo(true);
        }
    }
}
