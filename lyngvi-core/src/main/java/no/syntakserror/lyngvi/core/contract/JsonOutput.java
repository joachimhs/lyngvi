package no.syntakserror.lyngvi.core.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * Serialises command results to the JSON envelope used by {@code --json}.
 *
 * <p>Success: the payload's fields are placed next to {@code "ok": true}.
 * Failure: {@code {"ok": false, "error": {code, message, data}}}. Output is a
 * single line without a trailing newline; callers decide the framing.
 */
public final class JsonOutput {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonOutput() {}

    /** {@code {"ok":true}} with the payload's fields flattened in after the flag. */
    public static String ok(Object payload) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", true);
        if (payload != null) {
            node.setAll((ObjectNode) MAPPER.valueToTree(payload));
        }
        return write(node);
    }

    /** {@code {"ok":true}}. */
    public static String ok() {
        return ok(null);
    }

    /** {@code {"ok":false,"error":{...}}}. */
    public static String error(SandboxException e) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", false);
        ObjectNode error = node.putObject("error");
        error.put("code", e.code().name());
        error.put("message", e.getMessage());
        error.set("data", MAPPER.valueToTree(e.data()));
        return write(node);
    }

    /** One JSON document without the envelope, for JSONL streams ({@code logs}, {@code build}, {@code domain blocked --follow}). */
    public static String line(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise JSON line", e);
        }
    }

    /** The shared mapper, for callers that need to serialise streams of records. */
    /** Parses a JSON object given on the command line; throws {@code INVALID_ARGUMENT} otherwise. */
    public static java.util.Map<String, Object> readObject(String json, String what) {
        try {
            return mapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        } catch (java.io.IOException e) {
            throw new no.syntakserror.lyngvi.core.api.SandboxException(no.syntakserror.lyngvi.core.api.ErrorCode.INVALID_ARGUMENT,
                    what + " must be a JSON object: " + e.getMessage().lines().findFirst().orElse(""), java.util.Map.of("json", json));
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise JSON output", e);
        }
    }
}
