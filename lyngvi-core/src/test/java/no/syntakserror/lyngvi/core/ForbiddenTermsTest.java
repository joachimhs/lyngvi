package no.syntakserror.lyngvi.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * G6: nothing organisation-specific in the repository; the terms live in .github/forbidden-terms.txt.
 * That list is optional, and a missing or empty one skips the test — it is for whoever runs Lyngvi for
 * an organisation and adds their own hostnames, enterprise URLs or CA names to it.
 */
class ForbiddenTermsTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final String LIST = ".github/forbidden-terms.txt";
    private static final List<String> EXEMPT = List.of(LIST);

    @Test
    void repositoryContainsNoForbiddenTerms() throws Exception {
        Path list = REPO.resolve(LIST);
        List<String> terms = Files.isRegularFile(list)
                ? Files.readAllLines(list).stream()
                  .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).map(t -> t.toLowerCase(Locale.ROOT)).toList()
                : List.of();
        assumeFalse(terms.isEmpty(), "no terms configured in " + LIST + "; nothing to check");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String rel = REPO.relativize(p).toString();
                if (rel.startsWith(".git/") || rel.contains("/target/") || rel.startsWith("target/") || rel.startsWith("dist/") || rel.startsWith(".claude/")
                        || EXEMPT.stream().anyMatch(rel::equals) || rel.endsWith(".jar") || rel.endsWith(".png") || rel.endsWith(".icns")) continue;
                String text;
                try {
                    text = Files.readString(p).toLowerCase(Locale.ROOT);
                } catch (java.nio.charset.MalformedInputException e) {
                    continue; // binary
                }
                for (String t : terms) if (text.contains(t)) hits.add(rel + " contains '" + t + "'");
            }
        }
        assertThat(hits).isEmpty();
    }
}
