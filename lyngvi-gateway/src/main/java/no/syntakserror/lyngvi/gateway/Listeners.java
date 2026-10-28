package no.syntakserror.lyngvi.gateway;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** HTTP (+ optional alternate port) and HTTPS listeners from PEM files (spec §8.4 point 1). */
public final class Listeners {

    private final List<HttpServer> servers = new ArrayList<>();

    public void start(Config config, HttpHandler handler, MaskedLog log) throws IOException {
        add(HttpServer.create(new InetSocketAddress(config.port()), 64), handler);
        log.info("listening on http://0.0.0.0:" + port(servers.getLast()));
        if (config.altPort() > 0 && config.altPort() != config.port()) {
            add(HttpServer.create(new InetSocketAddress(config.altPort()), 64), handler);
            log.info("listening on http://0.0.0.0:" + port(servers.getLast()));
        }
        if (config.tlsPort() > 0) {
            if (config.tlsCertFile() == null || config.tlsKeyFile() == null
                    || !Files.isRegularFile(Path.of(config.tlsCertFile())) || !Files.isRegularFile(Path.of(config.tlsKeyFile()))) {
                log.warn("TLS_PORT set but TLS_CERT_FILE/TLS_KEY_FILE missing; HTTPS listener not started");
            } else {
                HttpsServer https = HttpsServer.create(new InetSocketAddress(config.tlsPort()), 64);
                https.setHttpsConfigurator(new HttpsConfigurator(sslContext(Path.of(config.tlsCertFile()), Path.of(config.tlsKeyFile()))));
                add(https, handler);
                log.info("listening on https://0.0.0.0:" + port(https));
            }
        }
    }

    private void add(HttpServer s, HttpHandler handler) {
        s.createContext("/", handler);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        servers.add(s);
    }

    public static int port(HttpServer s) {
        return s.getAddress().getPort();
    }

    public List<HttpServer> servers() {
        return servers;
    }

    public void stop() {
        for (HttpServer s : servers) s.stop(0);
        servers.clear();
    }

    /** Builds an SSL context from a PEM certificate chain and a PKCS#8 private key. */
    public static SSLContext sslContext(Path certPem, Path keyPem) throws IOException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<Certificate> chain = new ArrayList<>(cf.generateCertificates(Files.newInputStream(certPem)));
            String key = Files.readString(keyPem).replaceAll("-----(BEGIN|END)[A-Z ]*PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(key);
            PrivateKey pk;
            try {
                pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (java.security.spec.InvalidKeySpecException e) {
                pk = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("gateway", pk, new char[0], chain.toArray(new Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Cannot load TLS material: " + e.getMessage(), e);
        }
    }
}
