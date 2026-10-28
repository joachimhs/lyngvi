package no.syntakserror.lyngvi.core.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentEventTest {

    @Test
    void everyEventTypeRoundTripsThroughJson() {
        List<AgentEvent> events = List.of(
                AgentEvent.start("mock", "s1", "m1"),
                AgentEvent.text("hello"),
                AgentEvent.reasoning("thinking"),
                AgentEvent.toolStart("t1", "write_file", "/work/x"),
                AgentEvent.toolEnd("t1", "ok", false),
                AgentEvent.notice("warn", "context compacted"),
                AgentEvent.usage(10, 20, 0, 0, null, 30L, 200000L),
                AgentEvent.error("X", "boom"),
                AgentEvent.done("end_turn"));

        for (AgentEvent e : events) {
            AgentEvent stamped = e.withMeta(1757000000000L, 7);
            String json = AgentEvents.toJson(stamped);
            Optional<AgentEvent> back = AgentEvents.parse(json);
            assertThat(back).as(json).contains(stamped);
            assertThat(json).contains("\"type\":\"" + stamped.type() + "\"").contains("\"ts\":1757000000000").contains("\"seq\":7");
        }
    }

    @Test
    void parsesAdapterLinesWithoutMeta() {
        Optional<AgentEvent> e = AgentEvents.parse("{\"type\":\"text\",\"delta\":\"hi\"}");

        assertThat(e).isPresent();
        assertThat(e.get()).isInstanceOf(AgentEvent.Text.class);
        assertThat(((AgentEvent.Text) e.get()).delta()).isEqualTo("hi");
        assertThat(e.get().ts()).isNull();
    }

    @Test
    void unknownTypesAndGarbageAreDropped() {
        assertThat(AgentEvents.parse("{\"type\":\"dance\"}")).isEmpty();
        assertThat(AgentEvents.parse("not json")).isEmpty();
        assertThat(AgentEvents.parse("")).isEmpty();
        assertThat(AgentEvents.parse("{\"delta\":\"no type\"}")).isEmpty();
    }

    @Test
    void doneAndErrorHaveTheDocumentedShapes() {
        assertThat(AgentEvents.toJson(AgentEvent.done("cancelled"))).isEqualTo("{\"type\":\"done\",\"stopReason\":\"cancelled\"}");
        assertThat(AgentEvents.toJson(AgentEvent.toolEnd("t", "o", true))).isEqualTo("{\"type\":\"tool_end\",\"id\":\"t\",\"output\":\"o\",\"isError\":true}");
    }
}
