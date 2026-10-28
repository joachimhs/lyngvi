package no.syntakserror.lyngvi.gui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The repository tags under a conversation: what fits in the sidebar, and what is counted instead. */
class ConversationReposTest {

    @Test
    void everyRepositoryIsShownWhileTheyFit() {
        assertThat(ConversationRepos.of(List.of("api")).shown()).containsExactly("api");
        assertThat(ConversationRepos.of(List.of("api")).more()).isZero();
        assertThat(ConversationRepos.of(List.of("api", "lyngvi")).shown()).containsExactly("api", "lyngvi");
        assertThat(ConversationRepos.of(List.of("api", "lyngvi")).more()).isZero();
    }

    @Test
    void theTailBecomesACount() {
        ConversationRepos.Tags tags = ConversationRepos.of(List.of("api", "lyngvi", "web", "docs"));

        assertThat(tags.shown()).containsExactly("api", "lyngvi");
        assertThat(tags.more()).isEqualTo(2);
    }

    @Test
    void aConversationWithoutRepositoriesGetsNoTags() {
        assertThat(ConversationRepos.of(List.of()).isEmpty()).isTrue();
        assertThat(ConversationRepos.of(null).isEmpty()).isTrue();
        assertThat(ConversationRepos.of(Arrays.asList(" ", null)).isEmpty()).isTrue();
        assertThat(ConversationRepos.of(List.of()).more()).isZero();
    }

    @Test
    void namesAreTrimmedAndTheCountOnlyCoversRealOnes() {
        ConversationRepos.Tags tags = ConversationRepos.of(Arrays.asList(" api ", null, "lyngvi", "  ", "docs"));

        assertThat(tags.shown()).containsExactly("api", "lyngvi");
        assertThat(tags.more()).isEqualTo(1);
    }

    @Test
    void theTagsAreTheirOwnCopyOfTheList() {
        List<String> repos = new ArrayList<>(List.of("api", "lyngvi"));
        ConversationRepos.Tags tags = ConversationRepos.of(repos);
        repos.clear();

        assertThat(tags.shown()).containsExactly("api", "lyngvi");
    }
}
