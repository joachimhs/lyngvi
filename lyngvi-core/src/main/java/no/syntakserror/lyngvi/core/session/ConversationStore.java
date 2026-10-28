package no.syntakserror.lyngvi.core.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.Paths;

/** Reads and writes conversation files atomically. */
public final class ConversationStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Paths paths;

    public ConversationStore(Paths paths) {
        this.paths = paths;
    }

    public boolean exists(String id) {
        return Files.isRegularFile(paths.conversation(id));
    }

    public Conversation load(String id) {
        Path file = paths.conversation(id);
        if (!Files.isRegularFile(file)) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "No session with id '" + id + "'", Map.of("id", id));
        }
        try {
            JsonNode node = JSON.readTree(file.toFile());
            int schema = node.path("schemaVersion").asInt(1);
            if (schema != Conversation.CURRENT_SCHEMA_VERSION) {
                throw new SandboxException(ErrorCode.NOT_INITIALIZED, file + " has schema version " + schema
                        + "; run 'setup.sh migrate' to upgrade it", Map.of("file", file.toString(), "schemaVersion", schema));
            }
            return JSON.treeToValue(node, Conversation.class);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public void save(Conversation c) {
        Path file = paths.conversation(c.id());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "." + c.id(), ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(c) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    public void delete(String id) {
        try {
            Files.deleteIfExists(paths.conversation(id));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot delete conversation " + id + ": " + e.getMessage(), e);
        }
    }

    /** All conversations, newest first. Unreadable files are skipped. */
    public List<Conversation> list() {
        List<Conversation> out = new ArrayList<>();
        if (!Files.isDirectory(paths.conversations())) return out;
        try (Stream<Path> files = Files.list(paths.conversations())) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                try {
                    out.add(load(id));
                } catch (SandboxException ignored) {
                    // a foreign or broken file; doctor reports it later
                }
            });
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot list " + paths.conversations() + ": " + e.getMessage(), e);
        }
        out.sort(Comparator.comparingLong(Conversation::createdAt).reversed());
        return out;
    }
}
