package no.syntakserror.lyngvi.core.ask;

import static org.assertj.core.api.Assertions.assertThat;

import no.syntakserror.lyngvi.core.session.Conversation;
import org.junit.jupiter.api.Test;

class WorkTimelineTest {

    @Test
    void keepsTextAndToolsInArrivalOrderAndJoinsTextBlocksWithBlankLines() {
        WorkTimeline t = new WorkTimeline();
        t.accept(AgentEvent.text("I will look"));
        t.accept(AgentEvent.text(" at it."));
        t.accept(AgentEvent.toolStart("t1", "Bash", "{\"command\":\"ls\"}"));
        t.accept(AgentEvent.toolEnd("t1", "a\nb", false));
        t.accept(AgentEvent.text(" Two files."));
        t.accept(AgentEvent.toolStart("t2", "Read", "{\"file_path\":\"a\"}"));
        t.accept(AgentEvent.toolEnd("t2", "", true));
        t.accept(AgentEvent.text("Done."));

        assertThat(t.items()).extracting(Conversation.WorkItem::type).containsExactly("text", "tool", "text", "tool", "text");
        assertThat(t.items().get(0).output()).isEqualTo("I will look at it.");
        assertThat(t.items().get(1).name()).isEqualTo("Bash");
        assertThat(t.items().get(1).output()).isEqualTo("a\nb");
        assertThat(t.items().get(3).isError()).isTrue();
        assertThat(t.text()).isEqualTo("I will look at it.\n\nTwo files.\n\nDone.");
        assertThat(t.toolCount()).isEqualTo(2);
    }

    @Test
    void blankTextBetweenToolsStartsNoSegment() {
        WorkTimeline t = new WorkTimeline();
        t.accept(AgentEvent.text(""));
        t.accept(AgentEvent.toolStart("t1", "Bash", "{}"));
        t.accept(AgentEvent.toolEnd("t1", "ok", false));
        t.accept(AgentEvent.text("   "));
        t.accept(AgentEvent.toolStart("t2", "Bash", "{}"));
        t.accept(AgentEvent.toolEnd("t2", "ok", false));
        t.accept(AgentEvent.text("Both ran."));
        // whitespace inside a segment that already has text is kept: it is part of the streamed text
        t.accept(AgentEvent.text(" "));
        t.accept(AgentEvent.text("Done."));

        assertThat(t.items()).extracting(Conversation.WorkItem::type).containsExactly("tool", "tool", "text");
        assertThat(t.text()).isEqualTo("Both ran. Done.");
    }

    @Test
    void toolEndWithoutStartAndEmptyTimeline() {
        WorkTimeline t = new WorkTimeline();
        assertThat(t.text()).isEmpty();
        assertThat(t.items()).isEmpty();
        t.accept(AgentEvent.toolEnd("x", "late", false));
        assertThat(t.items()).hasSize(1);
        assertThat(t.items().getFirst().name()).isEqualTo("x");
    }
}
