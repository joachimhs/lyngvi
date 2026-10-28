package no.syntakserror.lyngvi.gateway.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public record UpstreamResponse(int status, Map<String, String> headers, byte[] body) {

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
