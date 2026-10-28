package no.syntakserror.lyngvi.gui.viewmodel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import no.syntakserror.lyngvi.core.contract.JsonOutput;

/**
 * A support zip: application log, status and doctor reports, profile, local config, generated files.
 * Never {@code .env}, {@code gateway/secrets}, {@code state/agents} or conversation contents.
 */
public final class ProblemReport {

    private ProblemReport() {}

    public static Path create(Path target, SandboxContext ctx, AppLog log) throws IOException {
        Files.createDirectories(target.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            put(zip, "app.log", log.text());
            put(zip, "system.txt", "java " + Runtime.version() + "\nos " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + "\n");
            if (ctx.isOpen()) {
                var sb = ctx.sandbox();
                put(zip, "status.json", safe(() -> JsonOutput.ok(sb.sandboxService().status())));
                put(zip, "doctor.json", safe(() -> JsonOutput.ok(sb.sandboxService().doctor())));
                put(zip, "sessions.json", safe(() -> JsonOutput.ok(sb.sessionService().list())));
                for (String rel : List.of("profile.json", "sandbox.config", "VERSION", "Containerfile", "docker-compose.yml", "AGENTS.md",
                        "proxy/allowed-domains.txt", "proxy/gateway-hosts.txt", "proxy/squid.conf", "gateway/gateway.manifest.json")) {
                    Path f = sb.paths().root().resolve(rel);
                    if (Files.isRegularFile(f)) put(zip, "sandbox/" + rel, Files.readString(f));
                }
            }
        }
        return target;
    }

    private static String safe(java.util.concurrent.Callable<String> c) {
        try {
            return c.call();
        } catch (Exception e) {
            return "unavailable: " + e.getMessage() + "\n";
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
