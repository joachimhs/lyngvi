package no.syntakserror.lyngvi.core.contract;

import java.util.List;

/** Outcome of {@code migrate}: what was converted and what the user must still do by hand (spec §12). */
public record MigrateReport(boolean alreadyMigrated, String profilePack, int migratedConversations, int migratedSessions, int gatewayInstances,
                            List<String> manualSteps) {}
