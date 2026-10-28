package no.syntakserror.lyngvi.core.gateway;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.GatewayPluginInfo;
import no.syntakserror.lyngvi.gateway.api.GatewayPlugin;

/**
 * Discovers plugin types on the host the same way the runtime does: {@link ServiceLoader} over each jar in
 * {@code gateway/plugins/}. Used to validate {@code instance add} and {@code plugin install} before the gateway runs.
 */
public final class PluginScanner {

    /** The runtime's built-in plugin; it is not a jar. */
    public static final GatewayPluginInfo HTTP = new GatewayPluginInfo("http", "Generic HTTP", "entries", GatewayPlugin.API_VERSION, "builtin", null);

    private PluginScanner() {}

    /** Built-in first, then every jar in the directory in name order. Broken jars are reported with an error message. */
    public static List<GatewayPluginInfo> scan(Path dir) {
        List<GatewayPluginInfo> out = new ArrayList<>();
        out.add(HTTP);
        if (!Files.isDirectory(dir)) return out;
        List<Path> jars;
        try (Stream<Path> files = Files.list(dir)) {
            jars = files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot list " + dir + ": " + e.getMessage(), e);
        }
        for (Path jar : jars) {
            try {
                out.addAll(inspect(jar));
            } catch (SandboxException e) {
                out.add(new GatewayPluginInfo(null, null, null, 0, jar.getFileName().toString(), e.getMessage()));
            }
        }
        return out;
    }

    /** The plugin types a jar declares; throws {@code VALIDATION_FAILED} when it declares none or cannot be loaded. */
    public static List<GatewayPluginInfo> inspect(Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Plugin jar not found: " + jar, java.util.Map.of("file", jar.toString()));
        }
        String name = jar.getFileName().toString();
        List<GatewayPluginInfo> out = new ArrayList<>();
        // Read the jar's own service file rather than ServiceLoader, so plugins on the host classpath are not counted twice.
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile());
             URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, GatewayPlugin.class.getClassLoader())) {
            java.util.jar.JarEntry entry = jf.getJarEntry("META-INF/services/" + GatewayPlugin.class.getName());
            List<String> classes = entry == null ? List.of() : new String(jf.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().map(l -> l.replaceAll("#.*", "").strip()).filter(l -> !l.isEmpty()).toList();
            for (String cls : classes) {
                GatewayPlugin p = (GatewayPlugin) Class.forName(cls, true, loader).getDeclaredConstructor().newInstance();
                out.add(new GatewayPluginInfo(p.type(), p.label(), p.allowlistLabel(), p.apiVersion(), name, null));
            }
        } catch (IOException | ReflectiveOperationException | ServiceConfigurationError | RuntimeException | LinkageError e) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, name + " is not a usable plugin jar: " + e.getMessage(), java.util.Map.of("file", jar.toString()));
        }
        if (out.isEmpty()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, name + " declares no gateway plugin (missing META-INF/services/"
                    + GatewayPlugin.class.getName() + ")", java.util.Map.of("file", jar.toString()));
        }
        return out;
    }
}
