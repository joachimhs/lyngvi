package no.syntakserror.lyngvi.core.ask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/** JSON form of {@link AgentEvent}: one object per line. */
public final class AgentEvents {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AgentEvents() {}

    public static String toJson(AgentEvent e) {
        try {
            return JSON.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise event", ex);
        }
    }

    /** Parses one line; unknown types and malformed input yield empty. */
    public static Optional<AgentEvent> parse(String line) {
        if (line == null || line.isBlank() || !line.stripLeading().startsWith("{")) return Optional.empty();
        try {
            return Optional.ofNullable(JSON.readValue(line, AgentEvent.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
