package no.syntakserror.lyngvi.core.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * The gateway's self-signed TLS certificate (spec §4.5, 1.1.2 Del B §4.4), generated
 * with the host's {@code openssl}. Regenerated when it does not cover every hostname.
 */
public final class CertGenerator {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final DateTimeFormatter OPENSSL_DATE = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy z", Locale.ENGLISH);
    private final Runner runner;
    private final Path dir;

    public CertGenerator(Runner runner, Path certDir) {
        this.runner = runner;
        this.dir = certDir;
    }

    public Path cert() { return dir.resolve("gateway.crt"); }
    public Path key() { return dir.resolve("gateway.key"); }

    /** Generates when missing, not covering, or {@code force}. Returns true when a certificate was generated. */
    public boolean ensure(List<String> hosts, String gatewayIp, boolean force) {
        if (!force && Files.isRegularFile(cert()) && Files.isRegularFile(key()) && covers(hosts, gatewayIp)) {
            return false;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new SandboxException(no.syntakserror.lyngvi.core.api.ErrorCode.INTERNAL, "Cannot create " + dir, e);
        }
        List<String> cmd = new ArrayList<>(List.of("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", key().toString(), "-out", cert().toString(), "-days", "3650", "-sha256",
                "-subj", "/CN=lyngvi-gateway/O=lyngvi",
                "-addext", "subjectAltName=" + san(hosts, gatewayIp),
                "-addext", "basicConstraints=critical,CA:FALSE",
                "-addext", "extendedKeyUsage=serverAuth"));
        runner.run(cmd, dir, TIMEOUT).requireSuccess("openssl req");
        if (Files.isRegularFile(key())) {
            try {
                Files.setPosixFilePermissions(key(), PosixFilePermissions.fromString("rw-------"));
                Files.setPosixFilePermissions(cert(), PosixFilePermissions.fromString("rw-r--r--"));
            } catch (IOException | UnsupportedOperationException ignored) {
                // non-POSIX file system; nothing to tighten
            }
        }
        return true;
    }

    static String san(List<String> hosts, String gatewayIp) {
        StringBuilder sb = new StringBuilder();
        for (String h : hosts) sb.append("DNS:").append(h).append(',');
        sb.append("DNS:gateway,DNS:localhost,IP:127.0.0.1,IP:").append(gatewayIp);
        return sb.toString();
    }

    /** Subject alternative names of the existing certificate, e.g. {@code DNS:wiki} and {@code IP Address:127.0.0.1}. */
    public List<String> sans() {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(cert())) return out;
        Runner.Result r;
        try {
            r = runner.run(List.of("openssl", "x509", "-in", cert().toString(), "-noout", "-ext", "subjectAltName"), dir, TIMEOUT);
        } catch (SandboxException e) {
            return out;
        }
        if (!r.success()) return out;
        for (String line : r.stdout().split("\n")) {
            String l = line.strip();
            if (l.startsWith("DNS:") || l.startsWith("IP Address:")) {
                for (String part : l.split(",")) {
                    out.add(part.strip());
                }
            }
        }
        return out;
    }

    public boolean covers(List<String> hosts, String gatewayIp) {
        List<String> sans = sans();
        if (sans.isEmpty()) return false;
        for (String h : hosts) {
            if (!sans.contains("DNS:" + h)) return false;
        }
        return sans.contains("IP Address:" + gatewayIp) && sans.contains("DNS:gateway");
    }

    /** Expiry of the existing certificate, or null when absent or unreadable. */
    public Instant notAfter() {
        if (!Files.isRegularFile(cert())) return null;
        try {
            Runner.Result r = runner.run(List.of("openssl", "x509", "-in", cert().toString(), "-noout", "-enddate"), dir, TIMEOUT);
            if (!r.success()) return null;
            String text = r.stdout().strip().replaceFirst("^notAfter=", "").replaceAll("\\s+", " ");
            return ZonedDateTime.parse(text, OPENSSL_DATE).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
