package no.syntakserror.lyngvi.core.agents;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Agents' own configuration files allow comments and trailing commas, so reading them has to as well. */
final class Jsonc {

    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private Jsonc() {}
}
