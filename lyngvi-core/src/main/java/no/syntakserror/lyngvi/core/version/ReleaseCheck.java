package no.syntakserror.lyngvi.core.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * {@code version --check}: asks the release API (GitHub's "latest release" shape) whether a newer version exists.
 * The endpoint comes from {@code LYNGVI_RELEASE_API}; the default points at the project's GitHub releases.
 */
public final class ReleaseCheck {

    public static final String DEFAULT_API = "https://api.github.com/repos/joachimhs/lyngvi/releases/latest";

    /** {@code assets} are the download URLs of the latest release; {@code upToDate} compares parsed versions. */
    public record ReleaseInfo(String current, String latest, boolean upToDate, String releaseUrl, List<String> assets) {}

    private final HttpClient client;

    public ReleaseCheck() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ReleaseCheck(HttpClient client) {
        this.client = client;
    }

    public static URI apiFrom(Map<String, String> env) {
        String v = env.get("LYNGVI_RELEASE_API");
        return URI.create(v == null || v.isBlank() ? DEFAULT_API : v.strip());
    }

    public ReleaseInfo check(Version current, URI api) {
        HttpRequest req = HttpRequest.newBuilder(api).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "lyngvi/" + current).GET().build();
        HttpResponse<String> r;
        try {
            r = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "Cannot reach the release API " + api + ": " + e.getMessage(), Map.of("api", api.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException(ErrorCode.INTERRUPTED, "Interrupted while checking for releases");
        }
        if (r.statusCode() != 200) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "The release API answered " + r.statusCode(), Map.<String, Object>of("api", api.toString(), "status", r.statusCode()));
        }
        try {
            JsonNode n = new ObjectMapper().readTree(r.body());
            String tag = n.path("tag_name").asText(n.path("name").asText(""));
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;
            List<String> assets = new ArrayList<>();
            n.path("assets").forEach(a -> assets.add(a.path("browser_download_url").asText()));
            boolean upToDate;
            try {
                upToDate = Version.parse(latest).compareTo(current) <= 0;
            } catch (RuntimeException e) {
                upToDate = latest.equals(current.toString());
            }
            return new ReleaseInfo(current.toString(), latest, upToDate, n.path("html_url").asText(null), assets);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "The release API returned malformed JSON", Map.of("api", api.toString()));
        }
    }
}
