package no.syntakserror.lyngvi.core.contract;

import java.util.List;

/**
 * Outcome of {@code session gc}: the sessions whose idle containers were stopped, the sessions whose
 * containers were removed after longer idleness (the work is in the volume, so they resume as they were),
 * the orphan containers removed, and the session volumes removed with them — a volume whose conversation
 * is gone holds work nothing can reach any more.
 */
public record CleanupReport(List<String> stopped, List<String> retired, List<String> removed, List<String> removedVolumes) {}
