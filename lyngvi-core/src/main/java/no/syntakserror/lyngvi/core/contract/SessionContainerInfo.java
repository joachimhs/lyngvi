package no.syntakserror.lyngvi.core.contract;

/**
 * A session container as Podman sees it, joined with its conversation. {@code sessionId} and {@code title} are null
 * for an orphan: a container of this sandbox whose conversation no longer exists.
 */
public record SessionContainerInfo(String container, String sessionId, String title, String agent, String state, String health,
                                   String image, long lastActiveAt, boolean busy) {

    public boolean orphan() {
        return sessionId == null;
    }

    public boolean running() {
        return "running".equals(state);
    }
}
