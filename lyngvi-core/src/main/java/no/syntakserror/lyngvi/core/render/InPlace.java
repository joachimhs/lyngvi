package no.syntakserror.lyngvi.core.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/** Writes files that are bind-mounted into containers: the inode must survive, so never rename. */
final class InPlace {

    private InPlace() {}

    /** Truncates and rewrites (or creates) the file. Returns true when the content changed. */
    static boolean write(Path file, String content) {
        try {
            if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
                return false;
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    static void atomic(Path file, String content) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
