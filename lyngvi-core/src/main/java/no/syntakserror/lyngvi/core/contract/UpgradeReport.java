package no.syntakserror.lyngvi.core.contract;

import java.util.List;
import no.syntakserror.lyngvi.core.render.RenderReport;

/** Outcome of {@code upgrade}: the sandbox's previous version, the app version, the bundled files replaced. */
public record UpgradeReport(String from, String to, List<String> updated, RenderReport render) {}
