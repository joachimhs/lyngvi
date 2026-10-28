package no.syntakserror.lyngvi.core.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkillsTest {

    @Test
    void frontMatterReadsTheTopLevelFieldsOfTheBlock() {
        String content = """
                ---
                name: deliver-change
                description: Use before reporting that work is done.
                ---

                # Deliver
                description: not this one
                """;

        assertThat(Skills.frontMatter(content))
                .containsEntry("name", "deliver-change")
                .containsEntry("description", "Use before reporting that work is done.")
                .hasSize(2);
    }

    @Test
    void frontMatterIgnoresQuotesAndNestedLines() {
        String content = """
                ---
                name: "quoted-name"
                allowed-tools:
                  - Bash
                description: 'in single quotes'
                ---
                body
                """;

        assertThat(Skills.frontMatter(content))
                .containsEntry("name", "quoted-name")
                .containsEntry("description", "in single quotes");
    }

    @Test
    void textWithoutAClosedBlockIsNotFrontMatter() {
        assertThat(Skills.frontMatter("# Just a heading\n")).isEmpty();
        assertThat(Skills.frontMatter("---\nname: x\nno closing marker\n")).isEmpty();
        assertThat(Skills.frontMatter(null)).isEmpty();
    }

    @Test
    void aSkillIsUsableWhenTheBlockNamesItAndSaysWhenItApplies() {
        assertThat(Skills.problem("deliver-change", """
                ---
                name: deliver-change
                description: Use before reporting that work is done.
                ---
                body
                """)).isNull();
    }

    @Test
    void everyProblemSaysWhatIsWrong() {
        assertThat(Skills.problem("a-skill", null)).contains("is missing");
        assertThat(Skills.problem("a-skill", "   ")).contains("is empty");
        assertThat(Skills.problem("a-skill", "# no front matter\n")).contains("front matter");
        assertThat(Skills.problem("a-skill", "---\ndescription: d\n---\n")).contains("no 'name:'");
        assertThat(Skills.problem("a-skill", "---\nname: other\ndescription: d\n---\n")).contains("folder name");
        assertThat(Skills.problem("a-skill", "---\nname: a-skill\n---\n")).contains("'description:'");
    }

    @Test
    void namesAreLowerCaseWordsJoinedByHyphens() {
        assertThat(Skills.isValidName("deliver-change")).isTrue();
        assertThat(Skills.isValidName("skill2")).isTrue();
        assertThat(Skills.isValidName("Deliver")).isFalse();
        assertThat(Skills.isValidName("two words")).isFalse();
        assertThat(Skills.isValidName("-leading")).isFalse();
        assertThat(Skills.isValidName("trailing-")).isFalse();
        assertThat(Skills.isValidName("../escape")).isFalse();
        assertThat(Skills.isValidName("x".repeat(Skills.NAME_MAX + 1))).isFalse();
        assertThat(Skills.isValidName("")).isFalse();
        assertThat(Skills.isValidName(null)).isFalse();
    }

    @Test
    void aNewSkillStartsOutAsAUsableFile() {
        String template = Skills.template("my-skill");

        assertThat(Skills.problem("my-skill", template)).isNull();
        assertThat(Skills.description(template)).isNotBlank();
    }
}
