package no.syntakserror.lyngvi.core.ask;

import java.nio.file.Path;
import java.util.List;

/**
 * What an adapter needs for one {@code ask}.
 *
 * @param sessionDir     the host side of the session: the attachments and the prompt file, nothing the agent writes
 * @param volume         the Podman volume holding the session's files, mounted at {@code /work} in the container
 * @param image          the image to mount {@code volume} with when an adapter outside the container needs the files
 * @param agentSessionId the agent's own session id from a previous turn; null on the first turn
 * @param promptFile     host path of the file holding the prompt (never passed as an argument)
 * @param attachments    container paths under {@code /work/_attachments/}
 * @param local          true when no container is involved (built-in adapters in tests)
 */
public record AskRequest(String sessionId, Path sessionDir, String container, String volume, String image, List<String> repos,
                         String agentSessionId, String model, Path promptFile, List<String> attachments, boolean local) {

    public AskRequest withAgentSessionId(String id) {
        return new AskRequest(sessionId, sessionDir, container, volume, image, repos, id, model, promptFile, attachments, local);
    }
}
