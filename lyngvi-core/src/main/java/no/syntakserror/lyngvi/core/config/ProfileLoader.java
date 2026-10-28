package no.syntakserror.lyngvi.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/** Reads and validates {@code profile.json}. */
public final class ProfileLoader {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private ProfileLoader() {}

    public static Profile load(Path json) {
        if (!Files.isRegularFile(json)) {
            throw invalid("Profile file not found: " + json, "file", json.toString());
        }
        Profile profile;
        try {
            profile = MAPPER.readValue(json.toFile(), Profile.class);
        } catch (UnrecognizedPropertyException e) {
            throw invalid("Unknown field '" + e.getPropertyName() + "' in " + json, "field", e.getPropertyName());
        } catch (InvalidFormatException e) {
            String field = e.getPath().isEmpty() ? "?" : e.getPath().getLast().getFieldName();
            throw invalid("Invalid value for '" + field + "' in " + json + ": " + e.getValue(), "field", field);
        } catch (IOException e) {
            throw invalid("Cannot read profile " + json + ": " + e.getMessage(), "file", json.toString());
        }
        validate(profile, json);
        return profile;
    }

    private static void validate(Profile p, Path json) {
        if (p.schemaVersion() != Profile.SUPPORTED_SCHEMA_VERSION) {
            throw invalid("Unsupported schemaVersion " + p.schemaVersion() + " in " + json
                    + " (supported: " + Profile.SUPPORTED_SCHEMA_VERSION + ")", "field", "schemaVersion");
        }
        require(p.name() != null && NAME.matcher(p.name()).matches(),
                "Profile name must match [a-z][a-z0-9-]*", "name");
        require(p.language() != null && !p.language().isBlank(), "Profile language is required", "language");
        require(p.agents() != null && p.agents().defaultAgent() != null, "agents.default is required", "agents.default");
        require(p.agents().enabled() != null && p.agents().enabled().contains(p.agents().defaultAgent()),
                "agents.enabled must contain agents.default", "agents.enabled");
        require(p.toolchains() != null, "toolchains is required", "toolchains");
        require(p.egress() != null && p.egress().allow() != null, "egress.allow is required", "egress.allow");
        require(p.egress().usesBundledBlocklist() || p.egress().block() instanceof List<?>,
                "egress.block must be \"bundled\" or a list of domains", "egress.block");
        for (Integer port : p.localModels().ports()) {
            require(port != null && port > 0 && port <= 65535,
                    "localModels.ports must be TCP ports (1-65535), not " + port, "localModels.ports");
        }
        require(p.git() != null && p.git().protectedBranches() != null && p.git().committer() != null,
                "git.protectedBranches and git.committer are required", "git");
        require(p.resources() != null, "resources is required", "resources");
        List<String> bad = p.resources().invalidFields();
        require(bad.isEmpty(), "Invalid resources value for " + String.join(", ", bad), "resources." + (bad.isEmpty() ? "" : bad.getFirst()));
        validatePresets(p);
        require(p.copy() != null && p.copy().history() != null, "copy.history is required", "copy.history");
        require(p.copy().depth() > 0, "copy.depth must be positive", "copy.depth");
        require(p.copy().maxFileMiB() > 0, "copy.maxFileMiB must be positive", "copy.maxFileMiB");
        require(p.copy().excludes() != null, "copy.excludes is required", "copy.excludes");
        require(p.session() != null && p.session().idleStopMinutes() >= 0,
                "session.idleStopMinutes must be zero or positive", "session.idleStopMinutes");
        require(p.session().idleRemoveHours() >= 0,
                "session.idleRemoveHours must be zero or positive", "session.idleRemoveHours");
        validateCache(p);
        require(p.gateway() != null && p.gateway().allowWrite() != null, "gateway.allowWrite is required", "gateway.allowWrite");
    }

    /** The cache ids are a closed set, so a profile can never name a directory of its own to mount. */
    private static void validateCache(Profile p) {
        require(Profile.Cache.MODES.contains(p.cache().mode()),
                "cache.mode must be one of " + String.join(", ", Profile.Cache.MODES), "cache.mode");
        for (String id : p.cache().paths()) {
            require(no.syntakserror.lyngvi.core.stack.CacheVolumes.isKnown(id),
                    "Unknown cache '" + id + "'; known caches are "
                            + no.syntakserror.lyngvi.core.stack.CacheVolumes.all().stream().map(c -> c.id()).toList(),
                    "cache.paths");
        }
    }

    /** A preset is a whole set of limits, so — unlike {@code --mem} on its own — every field must be there. */
    private static void validatePresets(Profile p) {
        Set<String> ids = new HashSet<>();
        for (ResourcePreset preset : p.resourcePresets()) {
            require(preset.id() != null && NAME.matcher(preset.id()).matches(),
                    "resourcePresets[].id must match [a-z][a-z0-9-]*", "resourcePresets.id");
            require(ids.add(preset.id()), "Duplicate resource preset '" + preset.id() + "'", "resourcePresets.id");
            require(preset.limits() != null, "Preset '" + preset.id() + "' has no limits", "resourcePresets.limits");
            List<String> bad = preset.limits().invalidFields();
            require(bad.isEmpty(), "Preset '" + preset.id() + "' has an invalid or missing " + String.join(", ", bad),
                    "resourcePresets.limits");
        }
    }

    private static void require(boolean ok, String message, String field) {
        if (!ok) {
            throw invalid(message, "field", field);
        }
    }

    private static SandboxException invalid(String message, String key, String value) {
        return new SandboxException(ErrorCode.VALIDATION_FAILED, message, Map.of(key, value));
    }
}
