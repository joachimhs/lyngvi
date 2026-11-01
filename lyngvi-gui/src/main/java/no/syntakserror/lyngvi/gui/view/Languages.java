package no.syntakserror.lyngvi.gui.view;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The languages the file pane can colour: the ones Lyngvi ships, and the ones the user has added. A language is a
 * {@code .syntax} file ({@link Syntax}) — data, not code — and that is the whole of the plugin mechanism: drop
 * {@code rust.syntax} in the folder, press Refresh, and every {@code .rs} file is coloured.
 *
 * <p>The built-in ones are resources in this module, listed in {@code syntax/index} because a jar cannot be asked
 * what a folder in it holds. The user's are files in {@code ~/.lyngvi-syntax/}, and one of those with the same
 * name as a built-in replaces it completely — so the shipped Java rules can be corrected without a build, and
 * «Remove» puts the shipped version back by deleting the user's file.
 *
 * <p>A file that cannot be read keeps its place in the list with the reason beside it, rather than disappearing:
 * whoever wrote it is the only one who can fix it, and they need to be told what is wrong. It colours nothing
 * until it parses.
 *
 * <p>This is deliberately not a jar with code in it, the way a gateway plugin is. The gateway's plugins run in the
 * sidecar inside the container; the GUI runs on the host with the user's own rights, outside everything Lyngvi
 * sandboxes, and a colour scheme is no reason to load code there.
 */
public final class Languages {

    private static final String RESOURCES = "/no/syntakserror/lyngvi/gui/syntax/";
    private static final String SUFFIX = ".syntax";
    /** The folder under the user's home that holds added and overriding languages. */
    static final String USER_DIR = ".lyngvi-syntax";
    /** A language's name is its file name: lower case words joined by hyphens, as the shipped ones are. */
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Where a language came from. A user file that replaces a shipped one is {@link #USER}. */
    public enum Source { BUILT_IN, USER }

    /**
     * A language as the settings page lists it.
     *
     * @param problem why it colours nothing as it stands, or null when it is in order
     */
    public record Entry(String id, String name, String extensions, Source source, boolean overrides, String problem) {}

    private final Path dir;
    /** Every language by id, the user's over the built-in ones; broken ones are not in here. */
    private final Map<String, Syntax> byId = new LinkedHashMap<>();
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, String> problems = new LinkedHashMap<>();

    public Languages(Path dir) {
        this.dir = dir;
        reload();
    }

    public static Languages inHome(Path home) {
        return new Languages(home.resolve(USER_DIR));
    }

    /** The folder the user's own language files live in, named in the help text on the settings page. */
    public Path directory() {
        return dir;
    }

    /** Reads the shipped languages and then the user's, which replace them where the names are the same. */
    public void reload() {
        byId.clear();
        sources.clear();
        problems.clear();
        for (String id : builtInIds()) read(id, Source.BUILT_IN, builtIn(id));
        for (Path file : userFiles()) {
            String id = file.getFileName().toString();
            id = id.substring(0, id.length() - SUFFIX.length());
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                problems.put(id, "cannot be read: " + e.getMessage());
                sources.put(id, Source.USER);
                continue;
            }
            read(id, Source.USER, text);
        }
    }

    private void read(String id, Source source, String text) {
        sources.put(id, source);
        try {
            byId.put(id, Syntax.parse(id, text));
            problems.remove(id);
        } catch (Syntax.Problem p) {
            byId.remove(id);
            problems.put(id, p.getMessage());
        }
    }

    /** Every language, in the order the page lists them: by name, whatever they are called as files. */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        for (String id : sources.keySet()) {
            Syntax s = byId.get(id);
            out.add(new Entry(id,
                    s == null ? id : s.name(),
                    s == null ? "" : String.join(" ", s.extensions()),
                    sources.get(id),
                    sources.get(id) == Source.USER && builtInIds().contains(id),
                    problems.get(id)));
        }
        return out.stream().sorted(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT))).toList();
    }

    /**
     * The language for a file, by its name or its extension. Nothing when no language claims it: an unknown kind
     * of file is shown as it is, which is better than colouring it by a guess.
     */
    public Optional<Syntax> forPath(String path) {
        return byId.values().stream().filter(s -> s.covers(path)).findFirst();
    }

    /** The file's text as the editor should show it: the user's version when there is one, else the shipped one. */
    public String text(String id) {
        Path file = fileOf(id);
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
        String built = builtIn(id);
        return built == null ? "" : built;
    }

    /** Where a save would write: {@code ~/.lyngvi-syntax/<id>.syntax}, whether it exists yet or not. */
    public Path fileOf(String id) {
        return dir.resolve(id + SUFFIX);
    }

    /** True when the user has a file of their own for this language — the one «Remove» would delete. */
    public boolean isUser(String id) {
        return Files.isRegularFile(fileOf(id));
    }

    /** Saves the user's version of a language and reads everything again, so the pane colours by it at once. */
    public void save(String id, String text) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(fileOf(id), text, StandardCharsets.UTF_8);
        reload();
    }

    /** Makes a language from the template, for the user to fill in. Fails when the name is already taken. */
    public void create(String id) throws IOException {
        if (!isValidId(id)) throw new IOException("a language is named in lower-case words joined by hyphens: " + id);
        if (sources.containsKey(id) || Files.exists(fileOf(id))) throw new IOException("there is already a language called " + id);
        save(id, Syntax.TEMPLATE.formatted(id));
    }

    /** Deletes the user's file. A language that only came from that file is gone; a shipped one comes back. */
    public void remove(String id) throws IOException {
        Files.deleteIfExists(fileOf(id));
        reload();
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private List<Path> userFiles() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The shipped languages, from the index beside them: a jar cannot list the contents of a folder. */
    List<String> builtInIds() {
        String index = resource("index");
        if (index == null) return List.of();
        return index.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    private String builtIn(String id) {
        return isValidId(id) ? resource(id + SUFFIX) : null;
    }

    private String resource(String name) {
        try (InputStream in = Languages.class.getResourceAsStream(RESOURCES + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
