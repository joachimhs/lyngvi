package no.syntakserror.lyngvi.gateway.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The per-jar service files must name the plugin classes exactly. */
class ServiceFilesTest {
    @Test
    void serviceFilesPointAtExistingClasses() throws Exception {
        for (String p : new String[] {"confluence", "jira"}) {
            Path f = Path.of("src/main/plugin-services", p, "META-INF/services/no.syntakserror.lyngvi.gateway.api.GatewayPlugin");
            assertThat(f).exists();
            Class<?> c = Class.forName(Files.readString(f).strip());
            assertThat(c.getDeclaredConstructor().newInstance()).isInstanceOf(no.syntakserror.lyngvi.gateway.api.GatewayPlugin.class);
        }
    }
}
