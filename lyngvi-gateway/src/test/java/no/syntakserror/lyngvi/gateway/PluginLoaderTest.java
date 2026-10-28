package no.syntakserror.lyngvi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import no.syntakserror.lyngvi.gateway.testing.GatewayHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginLoaderTest {

    @TempDir Path tmp;

    /** A jar whose service file points at a class already on the test classpath. */
    private Path jarDeclaring(String name, String className) throws Exception {
        Path jar = tmp.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/no.syntakserror.lyngvi.gateway.api.GatewayPlugin"));
            out.write((className + "\n").getBytes());
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void loadsPluginsFromJarsAndSkipsBrokenOnes() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("plugins"));
        Files.move(jarDeclaring("a-echo.jar", EchoPlugin.class.getName()), dir.resolve("a-echo.jar"));
        Files.move(jarDeclaring("b-dup.jar", EchoPlugin.class.getName()), dir.resolve("b-dup.jar"));
        Files.move(jarDeclaring("c-missing.jar", "no.such.Plugin"), dir.resolve("c-missing.jar"));
        Files.writeString(dir.resolve("d-garbage.jar"), "not a jar");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        MaskedLog log = new MaskedLog(new PrintStream(buf, true), "t");
        Map<String, PluginLoader.Loaded> loaded = PluginLoader.load(dir, List.of(new HttpPlugin()), log);
        assertThat(loaded.keySet()).containsExactly("http", "echo");
        assertThat(loaded.get("echo").source()).isEqualTo("a-echo.jar");
        String logs = buf.toString();
        assertThat(logs).contains("already provided by a-echo.jar");
        assertThat(logs).contains("cannot load plugin jar c-missing.jar");
        assertThat(logs).contains("d-garbage.jar");
    }

    @Test
    void jarPluginServesRequestsThroughTheGateway() throws Exception {
        try (GatewayHarness h = new GatewayHarness(tmp)) {
            Files.move(jarDeclaring("echo.jar", EchoPlugin.class.getName()), h.pluginDir().resolve("echo.jar"));
            h.manifest("{\"schemaVersion\":1,\"instances\":[{\"id\":\"e\",\"plugin\":\"echo\",\"hostname\":\"e.local\",\"baseUrlEnv\":\"E_URL\",\"secret\":{\"env\":\"E_TOKEN\"},\"allowlist\":{\"file\":\"e.txt\"}}]}")
             .env("E_URL", "https://e.example.com").env("E_TOKEN", "x").allowlist("e.txt", "hello\n").start();
            assertThat(h.get("e.local", "/echo/hello").body()).isEqualTo("{\"echo\":\"hello\"}");
            assertThat(h.get("e.local", "/echo/other").statusCode()).isEqualTo(403);
            assertThat(h.get("e.local", "/healthz").body()).contains("\"echo\":\"echo.jar\"");
        }
    }
}
