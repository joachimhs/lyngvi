package no.syntakserror.lyngvi.gui.viewmodel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Properties;

/**
 * The GUI's own settings in {@code ~/.lyngvi-gui.properties} (spec §2.2): theme, language, sandbox directory,
 * window bounds.
 */
public final class Settings {

    public enum Theme { LIGHT, DARK }

    public record WindowBounds(double x, double y, double width, double height, boolean maximized) {}

    private final Path file;
    private final Properties props = new Properties();

    public Settings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
                // Unreadable settings are treated as defaults.
            }
        }
    }

    public static Settings inHome(Path home) {
        return new Settings(home.resolve(".lyngvi-gui.properties"));
    }

    public Theme theme() {
        return "dark".equalsIgnoreCase(props.getProperty("theme", "light")) ? Theme.DARK : Theme.LIGHT;
    }

    public void setTheme(Theme theme) {
        props.setProperty("theme", theme.name().toLowerCase());
    }

    public Optional<Path> sandboxDir() {
        String v = props.getProperty("sandboxDir");
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(Path.of(v));
    }

    public void setSandboxDir(Path dir) {
        props.setProperty("sandboxDir", dir.toAbsolutePath().toString());
    }

    /** How many sandboxes the switcher in the toolbar remembers. */
    public static final int MAX_RECENT_SANDBOXES = 12;

    /**
     * The sandboxes that have been open on this machine, the most recently opened first — what the switcher in
     * the toolbar offers. One key per sandbox ({@code recent.sandbox.<n>}) rather than one separated line: a
     * folder name may hold any character a separator could be.
     */
    public List<Path> recentSandboxes() {
        List<Path> dirs = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT_SANDBOXES; i++) {
            String v = props.getProperty(recentKey(i));
            if (v == null || v.isBlank()) continue;
            Path dir = Path.of(v.strip());
            if (!dirs.contains(dir)) dirs.add(dir);
        }
        return List.copyOf(dirs);
    }

    /** Puts a sandbox at the head of the list, leaving no second copy of it behind; the oldest beyond the cap falls off. */
    public void addRecentSandbox(Path dir) {
        Path head = normalised(dir);
        List<Path> dirs = new ArrayList<>();
        dirs.add(head);
        for (Path p : recentSandboxes()) {
            if (!normalised(p).equals(head)) dirs.add(p);
        }
        writeRecentSandboxes(dirs);
    }

    /** Forgets a sandbox — the folder is gone, or the user asked for it to be taken off the list. */
    public void removeRecentSandbox(Path dir) {
        Path gone = normalised(dir);
        writeRecentSandboxes(recentSandboxes().stream().filter(p -> !normalised(p).equals(gone)).toList());
    }

    private void writeRecentSandboxes(List<Path> dirs) {
        for (int i = 0; i < MAX_RECENT_SANDBOXES; i++) props.remove(recentKey(i));
        for (int i = 0; i < Math.min(dirs.size(), MAX_RECENT_SANDBOXES); i++) {
            props.setProperty(recentKey(i), normalised(dirs.get(i)).toString());
        }
    }

    private static String recentKey(int index) {
        return "recent.sandbox." + index;
    }

    private static Path normalised(Path dir) {
        return dir.toAbsolutePath().normalize();
    }

    /**
     * The language of the sandbox that was open last. The language is a sandbox setting, and the splash has text
     * on it before any sandbox is open — so the last one's language is kept here, and the first run is English.
     */
    public Optional<String> language() {
        String v = props.getProperty("language");
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    public void setLanguage(String languageCode) {
        props.setProperty("language", languageCode);
    }

    public Optional<WindowBounds> windowBounds() {
        return bounds("window");
    }

    public void setWindowBounds(WindowBounds b) {
        setBounds("window", b);
    }

    /** Where the files window was last left; it is placed beside the window it was opened from until it has been. */
    public Optional<WindowBounds> filesWindowBounds() {
        return bounds("filesWindow");
    }

    public void setFilesWindowBounds(WindowBounds b) {
        setBounds("filesWindow", b);
    }

    private Optional<WindowBounds> bounds(String key) {
        String v = props.getProperty(key);
        if (v == null) return Optional.empty();
        String[] p = v.split(",");
        if (p.length != 5) return Optional.empty();
        try {
            return Optional.of(new WindowBounds(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Boolean.parseBoolean(p[4])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void setBounds(String key, WindowBounds b) {
        props.setProperty(key, b.x() + "," + b.y() + "," + b.width() + "," + b.height() + "," + b.maximized());
    }

    public boolean welcomeShown() {
        return Boolean.parseBoolean(props.getProperty("welcomeShown", "false"));
    }

    public void setWelcomeShown(boolean shown) {
        props.setProperty("welcomeShown", String.valueOf(shown));
    }

    public String lastChangelogSeen() {
        return props.getProperty("lastChangelogSeen", "");
    }

    public void setLastChangelogSeen(String version) {
        props.setProperty("lastChangelogSeen", version);
    }

    /** The models the user starred for this agent, in the order they were starred; they lead every model list. */
    public List<String> favouriteModels(String agent) {
        String v = props.getProperty(favouriteModelsKey(agent), "");
        return v.isBlank() ? List.of() : Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public void setFavouriteModels(String agent, List<String> ids) {
        String key = favouriteModelsKey(agent);
        if (ids.isEmpty()) props.remove(key);
        else props.setProperty(key, String.join(",", ids));
    }

    /** The model last used with this agent, so a new conversation starts where the last one left off. */
    public Optional<String> lastModel(String agent) {
        String v = props.getProperty("models.last." + agent);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    public void setLastModel(String agent, String id) {
        if (id == null || id.isBlank()) props.remove("models.last." + agent);
        else props.setProperty("models.last." + agent, id);
    }

    private static String favouriteModelsKey(String agent) {
        return "models.favourites." + agent;
    }

    /**
     * The repositories ticked when the last conversation was made in this sandbox, so the next «New conversation»
     * starts from the same choice. An empty list is «no repositories»; absent means nothing has been remembered,
     * and the dialog ticks every repository. Keyed by the sandbox directory: repositories are a sandbox setting,
     * and the same name can mean something else in another sandbox.
     */
    public Optional<List<String>> lastRepos(Path sandboxDir) {
        String v = props.getProperty(lastReposKey(sandboxDir));
        if (v == null) return Optional.empty();
        return Optional.of(v.isBlank() ? List.of()
                : Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    public void setLastRepos(Path sandboxDir, List<String> names) {
        props.setProperty(lastReposKey(sandboxDir), String.join(",", names));
    }

    private static String lastReposKey(Path sandboxDir) {
        return "repos.last." + sandboxDir.toAbsolutePath().normalize();
    }

    /**
     * How wide the conversation list in the chat was left, in pixels. Absent until the user has dragged it, and the
     * chat clamps whatever is here to what it will allow — a settings file written by hand, or by an older version
     * with other limits, cannot leave the list unusably narrow or wide.
     */
    public OptionalDouble sidebarWidth() {
        String v = props.getProperty("chatSidebarWidth");
        if (v == null || v.isBlank()) return OptionalDouble.empty();
        try {
            return OptionalDouble.of(Double.parseDouble(v.strip()));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    public void setSidebarWidth(double width) {
        props.setProperty("chatSidebarWidth", String.valueOf(Math.round(width)));
    }

    /**
     * Whether the files window was open when the application was last left, so that it comes back with it.
     * Unset means closed: a window that puts itself over the main one on a first run is not what is wanted.
     */
    public boolean filesWindowOpen() {
        return Boolean.parseBoolean(props.getProperty("filesWindow.open", "false"));
    }

    public void setFilesWindowOpen(boolean open) {
        props.setProperty("filesWindow.open", String.valueOf(open));
    }

    public void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "lyngvi GUI settings");
            }
        } catch (IOException ignored) {
            // Settings are a convenience; losing them is not an error the user must act on.
        }
    }
}
