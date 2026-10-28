package no.syntakserror.lyngvi.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.GatewayException;
import no.syntakserror.lyngvi.gateway.api.Json;

/** {@link Json} over Jackson; the runtime's JSON library is part of the plugin API surface (spec §8.6). */
public final class JacksonJson implements Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise", e);
        }
    }

    @Override
    public Map<String, Object> readObject(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new GatewayException(502, "upstream returned malformed JSON", Map.of("detail", e.getMessage()));
        }
    }

    @Override
    public List<Object> readArray(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            throw new GatewayException(502, "upstream returned malformed JSON", Map.of("detail", e.getMessage()));
        }
    }

    @Override
    public Object read(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new GatewayException(502, "upstream returned malformed JSON", Map.of("detail", e.getMessage()));
        }
    }
}
