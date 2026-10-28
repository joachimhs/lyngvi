package no.syntakserror.lyngvi.core.skills;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a skill is, without touching the file system: the name rules, the front matter every {@code SKILL.md}
 * carries, and the text a new skill starts out as. Pure and unit-tested; the file work is in {@link SkillStore}.
 *
 * <p>A skill is a folder under {@code <sandbox>/skills/} with a {@code SKILL.md} in it. The front matter is the
 * part the agent reads before it reads anything else: the name it is invoked by and one line about when it
 * applies. Both agents that take skills today use that format, so Lyngvi keeps the files as they are and never
 * rewrites them — what it decides is only <em>which</em> of them an agent gets to see (spec §3.6).
 */
public final class Skills {

    /** The file inside a skill folder that is the skill itself; everything else in the folder is support. */
    public static final String FILE = "SKILL.md";
    /** Lower-case words joined by single hyphens: a name the agent can be asked for and a folder name at once. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    public static final int NAME_MAX = 64;

    private Skills() {}

    /** True when {@code name} is a usable skill (and folder) name. */
    public static boolean isValidName(String name) {
        return name != null && name.length() <= NAME_MAX && NAME.matcher(name).matches();
    }

    /**
     * The {@code key: value} lines of the YAML front matter, in the order they appear, or an empty map when the
     * text does not start with a front matter block. Only the top-level scalar lines are read — that is all the
     * fields Lyngvi shows — and anything else in the block is left alone.
     */
    public static Map<String, String> frontMatter(String content) {
        if (content == null) return Map.of();
        String[] lines = content.stripLeading().split("\r?\n");
        if (lines.length == 0 || !lines[0].strip().equals("---")) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().equals("---")) return out;
            // Blank, commented and indented lines carry no top-level field; a nested block is left to the agent.
            if (line.isBlank() || line.startsWith("#") || line.startsWith(" ") || line.startsWith("\t")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            out.put(line.substring(0, colon).strip(), unquote(line.substring(colon + 1).strip()));
        }
        return Map.of(); // the block never closed, so this is not front matter
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Why this skill cannot be used, or null when it can. The checks are the ones that decide whether an agent
     * will load the file at all: a front matter block with a name that matches the folder and a description.
     */
    public static String problem(String name, String content) {
        if (content == null) return "skills/" + name + "/" + FILE + " is missing";
        if (content.isBlank()) return "skills/" + name + "/" + FILE + " is empty";
        Map<String, String> front = frontMatter(content);
        if (front.isEmpty()) {
            return "No front matter: the file must start with a '---' block holding 'name:' and 'description:'";
        }
        String declared = front.get("name");
        if (declared == null || declared.isBlank()) return "Front matter has no 'name:'";
        if (!declared.equals(name)) return "Front matter name '" + declared + "' is not the folder name '" + name + "'";
        String description = front.get("description");
        if (description == null || description.isBlank()) {
            return "Front matter has no 'description:' — without it the agent cannot tell when the skill applies";
        }
        return null;
    }

    /** The front matter description, or an empty string when there is none. */
    public static String description(String content) {
        return frontMatter(content).getOrDefault("description", "");
    }

    /** What a new skill is created as: a valid file the user only has to fill in. */
    public static String template(String name) {
        return """
                ---
                name: %s
                description: One line on what this skill does and when the agent should reach for it. The agent reads \
                only this line when it decides, so name the trigger, not the implementation.
                ---

                # %s

                ## When to use this

                - …

                ## How to do it

                1. …
                """.formatted(name, name);
    }
}
