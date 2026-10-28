package no.syntakserror.lyngvi.core.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Arguments of {@code session ask}.
 *
 * @param prompt      the message text
 * @param model       overrides the session's model for this turn
 * @param attachments host files copied into the session's attachments directory
 */
public record AskOptions(String prompt, Optional<String> model, List<Path> attachments) {}
