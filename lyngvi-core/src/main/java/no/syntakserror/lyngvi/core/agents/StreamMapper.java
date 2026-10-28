package no.syntakserror.lyngvi.core.agents;

import java.util.List;
import no.syntakserror.lyngvi.core.ask.AgentEvent;

/** Translates one agent's own output lines into the event contract. Stateful per run. */
public interface StreamMapper {

    /** Events for one stdout line; empty for noise. */
    List<AgentEvent> map(String line);

    /** Events to append when the process ends: a {@code done} when the stream did not produce one. */
    List<AgentEvent> finish(int exitCode, boolean cancelled);

    /** Events to append when the process ends, with optional stderr from the process. */
    default List<AgentEvent> finish(int exitCode, boolean cancelled, String stderr) {
        return finish(exitCode, cancelled);
    }
}
