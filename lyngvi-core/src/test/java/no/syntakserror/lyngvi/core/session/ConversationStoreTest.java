package no.syntakserror.lyngvi.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationStoreTest {

    static Conversation sample(String id, long createdAt) {
        return new Conversation(2, id, "Fix login", createdAt, createdAt, createdAt, "claude-code", null, null,
                List.of("api"), new ResourceLimits("8g", "12g", "4.0", "2048"), "lyngvi-sb-" + id.substring(0, 8),
                Map.of("api", new Conversation.RepoBase("abc", "main", "abc", false, History.SHALLOW, 100)),
                List.of(), List.of());
    }

    @Test
    void roundTripsThroughJson(@TempDir Path dir) throws Exception {
        ConversationStore store = new ConversationStore(Paths.of(dir));
        Conversation c = sample("6f1c0000-0000-4000-8000-000000000001", 1757000000000L);

        store.save(c);
        Conversation read = store.load(c.id());

        assertThat(read).isEqualTo(c);
        String json = Files.readString(Paths.of(dir).conversation(c.id()));
        assertThat(json).contains("\"schemaVersion\" : 2").contains("\"agentSessionId\" : null").contains("\"history\" : \"shallow\"");
        assertThat(Files.list(Paths.of(dir).conversations()).map(p -> p.getFileName().toString())).containsExactly(c.id() + ".json");
    }

    @Test
    void listsNewestFirstAndDeletes(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(Paths.of(dir));
        store.save(sample("6f1c0000-0000-4000-8000-000000000001", 1000));
        store.save(sample("6f1c0000-0000-4000-8000-000000000002", 3000));
        store.save(sample("6f1c0000-0000-4000-8000-000000000003", 2000));

        assertThat(store.list()).extracting("createdAt").containsExactly(3000L, 2000L, 1000L);

        store.delete("6f1c0000-0000-4000-8000-000000000002");
        assertThat(store.list()).hasSize(2);
        assertThat(store.exists("6f1c0000-0000-4000-8000-000000000002")).isFalse();
    }

    @Test
    void unknownIdAndSchemaVersionAreErrors(@TempDir Path dir) throws Exception {
        ConversationStore store = new ConversationStore(Paths.of(dir));

        assertThatThrownBy(() -> store.load("nope"))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        Files.createDirectories(Paths.of(dir).conversations());
        Files.writeString(Paths.of(dir).conversation("old"), "{\"schemaVersion\":1,\"id\":\"old\"}");
        assertThatThrownBy(() -> store.load("old")).hasMessageContaining("migrate");
    }
}
