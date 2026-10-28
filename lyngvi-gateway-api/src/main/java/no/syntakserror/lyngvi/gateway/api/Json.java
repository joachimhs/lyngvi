package no.syntakserror.lyngvi.gateway.api;

import java.util.List;
import java.util.Map;

/** JSON in and out, as plain maps and lists so the contract stays library-free. */
public interface Json {

    String write(Object value);

    /** Parses an object; throws {@link GatewayException} 502 when upstream JSON is malformed. */
    Map<String, Object> readObject(String json);

    List<Object> readArray(String json);

    /** Parses any JSON value: {@code Map}, {@code List}, {@code String}, {@code Number}, {@code Boolean} or null. */
    Object read(String json);
}
