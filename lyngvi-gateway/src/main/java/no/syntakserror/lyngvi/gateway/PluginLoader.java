package no.syntakserror.lyngvi.gateway;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.gateway.api.GatewayPlugin;

/**
 * Loads plugin jars (spec §8.6): one {@link URLClassLoader} per jar with the runtime as parent,
 * {@link ServiceLoader} for the types it declares. A jar that fails is logged and skipped; the first
 * jar to declare a type wins; unsupported {@code apiVersion}s are skipped.
 */
public final class PluginLoader {

    /** A loaded plugin and where it came from ({@code builtin} or the jar name). */
    public record Loaded(GatewayPlugin plugin, String source) {}

    private PluginLoader() {}

    public static Map<String, Loaded> load(Path dir, List<GatewayPlugin> builtIns, MaskedLog log) {
        Map<String, Loaded> out = new LinkedHashMap<>();
        for (GatewayPlugin p : builtIns) out.put(p.type(), new Loaded(p, "builtin"));
        if (dir == null || !Files.isDirectory(dir)) return out;
        List<Path> jars;
        try (Stream<Path> files = Files.list(dir)) {
            jars = files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            log.warn("cannot list plugin directory " + dir + ": " + e.getMessage());
            return out;
        }
        for (Path jar : jars) {
            try {
                URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, PluginLoader.class.getClassLoader());
                int found = 0;
                for (GatewayPlugin p : ServiceLoader.load(GatewayPlugin.class, loader)) {
                    found++;
                    if (p.apiVersion() != GatewayPlugin.API_VERSION) {
                        log.warn("plugin '" + p.type() + "' in " + jar.getFileName() + " has apiVersion " + p.apiVersion()
                                + " (supported: " + GatewayPlugin.API_VERSION + "); skipped");
                        continue;
                    }
                    if (out.containsKey(p.type())) {
                        log.warn("plugin type '" + p.type() + "' in " + jar.getFileName() + " is already provided by "
                                + out.get(p.type()).source() + "; skipped");
                        continue;
                    }
                    out.put(p.type(), new Loaded(p, jar.getFileName().toString()));
                    log.info("plugin '" + p.type() + "' loaded from " + jar.getFileName());
                }
                if (found == 0) log.warn(jar.getFileName() + " declares no GatewayPlugin service; skipped");
            } catch (IOException | ServiceConfigurationError | RuntimeException | LinkageError e) {
                log.warn("cannot load plugin jar " + jar.getFileName() + ": " + e.getMessage());
            }
        }
        return out;
    }
}
