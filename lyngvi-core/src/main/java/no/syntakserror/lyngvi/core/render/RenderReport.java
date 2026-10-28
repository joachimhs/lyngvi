package no.syntakserror.lyngvi.core.render;

import java.util.List;

/** Result of {@code rebuild-config}. */
public record RenderReport(String subnetPrefix, List<String> written, boolean certificateGenerated) {}
