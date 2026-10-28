package no.syntakserror.lyngvi.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Upstream TLS trust: the JDK's default anchors plus every PEM certificate in {@code CA_CERT_DIR} (corporate roots). */
public final class TrustStores {

    private TrustStores() {}

    public static SSLContext sslContext(Path caDir, MaskedLog log) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {trustManager(caDir, log)}, null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot build upstream trust: " + e.getMessage(), e);
        }
    }

    static X509TrustManager trustManager(Path caDir) throws GeneralSecurityException, IOException {
        return trustManager(caDir, null);
    }

    static X509TrustManager trustManager(Path caDir, MaskedLog log) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        for (X509Certificate c : defaultTrust().getAcceptedIssuers()) {
            ks.setCertificateEntry("jdk-" + c.getSubjectX500Principal().getName().hashCode() + "-" + c.getSerialNumber(), c);
        }
        if (caDir != null && Files.isDirectory(caDir)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (Stream<Path> files = Files.list(caDir)) {
                for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                    try (InputStream in = Files.newInputStream(f)) {
                        int n = 0;
                        for (Certificate c : cf.generateCertificates(in)) {
                            ks.setCertificateEntry("ca-" + f.getFileName() + "-" + n++, c);
                        }
                        if (n > 0 && log != null) log.info("trusting " + n + " certificate(s) from " + f.getFileName());
                    } catch (GeneralSecurityException | IOException e) {
                        if (log != null) log.warn("ignoring " + f.getFileName() + " in CA_CERT_DIR: " + e.getMessage());
                    }
                }
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) return x;
        }
        throw new GeneralSecurityException("No X509TrustManager available");
    }

    private static X509TrustManager defaultTrust() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) return x;
        }
        throw new GeneralSecurityException("No default X509TrustManager");
    }
}
