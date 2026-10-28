package no.syntakserror.lyngvi.core.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.contract.SkillInfo;

/**
 * {@code <sandbox>/skills/} on disk: one folder per skill, and {@code skills.json} beside them saying which of
 * them are switched on.
 *
 * <p>The folders decide what exists — a skill copied in by hand shows up without anything else being done, and
 * is on until it is switched off — while {@code skills.json} only carries the flags. Entries for folders that
 * are gone are dropped the next time the file is written, so the two cannot drift apart for long.
 *
 * <p>The skills of one sandbox are that sandbox's own: nothing here reaches outside {@code paths.root()}, and a
 * second sandbox directory starts out with the skills its {@code init} laid down and no others (spec §3.6).
 */
public final class SkillStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private final Paths paths;

    public SkillStore(Paths paths) {
        this.paths = paths;
    }

    /** Every skill folder, in name order, with the flag {@code skills.json} gives it (on when it says nothing). */
    public List<SkillInfo> list() {
        Map<String, Boolean> flags = flags();
        List<SkillInfo> out = new ArrayList<>();
        for (String name : folders()) {
            String content = readOrNull(paths.skillFile(name));
            int extra = countExtraFiles(paths.skill(name));
            out.add(new SkillInfo(name, Skills.description(content), flags.getOrDefault(name, true),
                    Skills.problem(name, content), paths.skill(name).toString(), extra));
        }
        return out;
    }

    /** The skills an agent should be given: switched on and without a problem that stops them from loading. */
    public List<SkillInfo> enabled() {
        return list().stream().filter(s -> s.enabled() && s.valid()).toList();
    }

    public SkillInfo get(String name) {
        return list().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new SandboxException(ErrorCode.VALIDATION_FAILED,
                        "No skill named '" + name + "' in " + paths.skills(), Map.of("skill", String.valueOf(name))));
    }

    /** The skill's {@code SKILL.md}, or an empty string when the folder has none. */
    public String read(String name) {
        get(name);
        String content = readOrNull(paths.skillFile(name));
        return content == null ? "" : content;
    }

    /** Replaces the skill's {@code SKILL.md}. The content is the user's: it is stored as written, never reformatted. */
    public SkillInfo write(String name, String content) {
        get(name);
        writeFile(paths.skillFile(name), content);
        return get(name);
    }

    /** A new skill folder with a {@code SKILL.md} to fill in, switched on. */
    public SkillInfo create(String name) {
        String clean = name == null ? "" : name.strip();
        if (!Skills.isValidName(clean)) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "'" + clean + "' is not a skill name: use lower-case words joined by hyphens, at most "
                            + Skills.NAME_MAX + " characters (for example 'deliver-change')",
                    Map.of("skill", clean));
        }
        if (Files.exists(paths.skill(clean))) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "A skill named '" + clean + "' already exists",
                    Map.of("skill", clean));
        }
        writeFile(paths.skillFile(clean), Skills.template(clean));
        setEnabled(clean, true);
        return get(clean);
    }

    /** Deletes the skill's whole folder, and its flag with it. */
    public SkillInfo remove(String name) {
        SkillInfo skill = get(name);
        deleteTree(paths.skill(skill.name()));
        Map<String, Boolean> flags = flags();
        flags.remove(skill.name());
        writeFlags(flags);
        return skill;
    }

    /**
     * Switches a skill on or off for this sandbox. Nothing is copied or deleted here: {@link SkillSync} is what
     * carries the change into the agents, and it runs before every message (spec §3.6).
     */
    public SkillInfo setEnabled(String name, boolean enabled) {
        Map<String, Boolean> flags = flags();
        flags.put(name, enabled);
        writeFlags(flags);
        return get(name);
    }

    /**
     * Lays down the skills that ship with Lyngvi ({@code templates/skills/}) and that this sandbox has not been
     * offered before. Called by {@code init} and by {@code upgrade}, so a sandbox made by an older version also
     * gets them — and a later version can ship one more.
     *
     * <p>The names that have been offered are kept in {@code state/skills-offered.txt}. That is what makes the
     * folder the user's from the first run: an edited starter skill is never written over, and a deleted one
     * stays deleted rather than coming back on the next upgrade.
     *
     * @return the names that were installed
     */
    public List<String> installStarters() {
        Path templates = paths.skillTemplates();
        if (!Files.isDirectory(templates)) return List.of();
        Set<String> offered = new LinkedHashSet<>(readLines(paths.skillsOffered()));
        List<String> installed = new ArrayList<>();
        List<String> available = new ArrayList<>();
        try (Stream<Path> entries = Files.list(templates)) {
            for (Path source : entries.filter(Files::isDirectory).sorted().toList()) {
                String name = source.getFileName().toString();
                if (!Skills.isValidName(name)) continue;
                available.add(name);
                if (offered.contains(name) || Files.exists(paths.skill(name))) continue;
                copyTree(source, paths.skill(name));
                installed.add(name);
            }
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + templates + ": " + e.getMessage(), e);
        }
        offered.addAll(available);
        writeFile(paths.skillsOffered(), "# Starter skills this sandbox has been offered; delete a line to be offered it again.\n"
                + String.join("\n", offered) + (offered.isEmpty() ? "" : "\n"));
        if (!installed.isEmpty()) writeFlags(flags());
        return installed;
    }

    private static List<String> readLines(Path file) {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** The folder names, sorted; anything that is not a directory beside them is ignored. */
    private List<String> folders() {
        Path dir = paths.skills();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(Skills::isValidName)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + dir + ": " + e.getMessage(), e);
        }
    }

    private static int countExtraFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.walk(dir)) {
            return (int) files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(Skills.FILE) || !p.getParent().equals(dir))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** {@code skills.json} as a name → on/off map; a file that cannot be read leaves every skill on. */
    private Map<String, Boolean> flags() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        Path file = paths.skillsFile();
        if (!Files.isRegularFile(file)) return out;
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonNode skill : root.path("skills")) {
                String name = skill.path("name").asText("");
                if (!name.isBlank()) out.put(name, skill.path("enabled").asBoolean(true));
            }
            return out;
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Cannot read " + file + ": " + e.getMessage(),
                    Map.of("file", file.toString()));
        }
    }

    /** Writes the flags of the skills that are actually there, so a removed skill leaves no entry behind. */
    private void writeFlags(Map<String, Boolean> flags) {
        List<String> folders = folders();
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        ArrayNode skills = root.putArray("skills");
        for (String name : folders) {
            ObjectNode entry = skills.addObject();
            entry.put("name", name);
            entry.put("enabled", flags.getOrDefault(name, true));
        }
        try {
            writeFile(paths.skillsFile(), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + paths.skillsFile() + ": " + e.getMessage(), e);
        }
    }

    static String readOrNull(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    static void writeFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** Copies a whole skill folder: {@code SKILL.md} and whatever else it brings (references, scripts). */
    static void copyTree(Path from, Path to) {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> entries = Files.walk(from)) {
            for (Path source : entries.toList()) {
                Path destination = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot copy " + from + " to " + to + ": " + e.getMessage(), e);
        }
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot delete " + dir + ": " + e.getMessage(), e);
        }
    }
}
