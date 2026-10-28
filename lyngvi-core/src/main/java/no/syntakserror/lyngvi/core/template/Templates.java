package no.syntakserror.lyngvi.core.template;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * The files that ship inside the core jar and are written into a sandbox
 * directory by {@code init} (profiles, templates, and later images, proxy
 * configuration, agents and scripts).
 *
 * <p>The sources live in the repository root and are packaged under
 * {@code bundle/}; {@code bundle/index.txt} lists them and a test keeps the
 * index in sync with the files on disk.
 */
public final class Templates {

    private static final String BUNDLE = "/bundle/";
    private static final String INDEX = BUNDLE + "index.txt";

    private Templates() {}

    /** Relative paths of every bundled file, in index order. */
    public static List<String> index() {
        try (InputStream in = Templates.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                throw new SandboxException(ErrorCode.INTERNAL, "Bundled resource " + INDEX + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + INDEX, e);
        }
    }

    /** Writes every bundled file that does not exist yet. Returns the paths written. */
    public static List<Path> writeAll(Path root) {
        return writeAll(root, false);
    }

    /** Writes every bundled file; with {@code overwrite} existing files are replaced. */
    public static List<Path> writeAll(Path root, boolean overwrite) {
        List<Path> written = new ArrayList<>();
        for (String rel : index()) {
            Path target = root.resolve(rel);
            if (!overwrite && Files.exists(target)) {
                continue;
            }
            try (InputStream in = Templates.class.getResourceAsStream(BUNDLE + rel)) {
                if (in == null) {
                    throw new SandboxException(ErrorCode.INTERNAL, "Bundled resource " + rel + " listed in index but missing");
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                if (rel.endsWith(".sh")) {
                    target.toFile().setExecutable(true, false);
                }
                written.add(target);
            } catch (IOException e) {
                throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + target + ": " + e.getMessage(), e);
            }
        }
        return written;
    }

    /** Relative paths of indexed files that are missing under {@code root}. */
    public static List<String> missing(Path root) {
        return index().stream().filter(rel -> !Files.isRegularFile(root.resolve(rel))).toList();
    }

    /** Indexed files that are missing under {@code root} or whose content differs from the bundled copy. */
    public static List<String> stale(Path root) {
        List<String> out = new ArrayList<>();
        for (String rel : index()) {
            Path target = root.resolve(rel);
            try (InputStream in = Templates.class.getResourceAsStream(BUNDLE + rel)) {
                if (in == null) continue;
                byte[] bundled = in.readAllBytes();
                if (!Files.isRegularFile(target) || !java.util.Arrays.equals(bundled, Files.readAllBytes(target))) out.add(rel);
            } catch (IOException e) {
                out.add(rel);
            }
        }
        return out;
    }

    public static boolean allPresent(Path root) {
        return missing(root).isEmpty();
    }
}
