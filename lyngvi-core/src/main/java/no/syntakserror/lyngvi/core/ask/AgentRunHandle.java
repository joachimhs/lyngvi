package no.syntakserror.lyngvi.core.ask;

/** A running {@code ask}: cancel it, or wait for the adapter to finish. */
public interface AgentRunHandle {

    /** Asks the adapter to stop; the stream ends with {@code done{cancelled}}. */
    void cancel();

    /** Blocks until the run has ended and every event has been delivered. Returns the adapter's exit code. */
    int waitFor();
}
