package no.syntakserror.lyngvi.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import org.junit.jupiter.api.Test;

class JsonOutputTest {

    @Test
    void okFlattensThePayloadNextToTheOkFlag() {
        String json = JsonOutput.ok(new VersionInfo("2.0.0-M0"));

        assertThat(json).isEqualTo("{\"ok\":true,\"version\":\"2.0.0-M0\"}");
    }

    @Test
    void okWithoutPayloadIsJustTheFlag() {
        assertThat(JsonOutput.ok()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void okSerialisesNestedRecordsListsAndMaps() {
        record Inner(String name, List<Integer> values) {}
        record Outer(Inner inner, Map<String, String> tags) {}

        String json = JsonOutput.ok(new Outer(new Inner("a", List.of(1, 2)), Map.of("k", "v")));

        assertThat(json).isEqualTo(
                "{\"ok\":true,\"inner\":{\"name\":\"a\",\"values\":[1,2]},\"tags\":{\"k\":\"v\"}}");
    }

    @Test
    void errorWrapsCodeMessageAndData() {
        SandboxException e = new SandboxException(
                ErrorCode.NOT_INITIALIZED, "Sandbox is not initialised", Map.of("dir", "/tmp/x"));

        String json = JsonOutput.error(e);

        assertThat(json).isEqualTo(
                "{\"ok\":false,\"error\":{\"code\":\"NOT_INITIALIZED\","
                        + "\"message\":\"Sandbox is not initialised\",\"data\":{\"dir\":\"/tmp/x\"}}}");
    }

    @Test
    void errorWithoutDataEmitsAnEmptyObject() {
        String json = JsonOutput.error(new SandboxException(ErrorCode.INTERNAL, "boom"));

        assertThat(json).isEqualTo("{\"ok\":false,\"error\":{\"code\":\"INTERNAL\",\"message\":\"boom\",\"data\":{}}}");
    }

    @Test
    void lineFormEndsWithoutNewlineSoCallersControlFraming() {
        assertThat(JsonOutput.ok()).doesNotEndWith("\n");
    }
}
