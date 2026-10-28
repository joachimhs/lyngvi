# Acceptance

`acceptance/run.sh <sandbox>` runs the automatable criteria of spec §10 (and 1.1.2 Del F) against a sandbox with
a running stack and built images; it prints one PASS/FAIL line per criterion. The rest is a checklist.

## Automated (run.sh)
S1–S4 egress, S5' user and capabilities, S6 no secrets in the session, S7 read-only gateway, S8 allowlist before
upstream, S10 no repo mounts, S11/S22/S23 working copy and history, S12/S13/S14/S19 delivery classification and
refusals, S17 session isolation, S18 busy session, S20 no credentials in generated files, S29 export,
G1 default profile conversation, G3 agent switch, G7 new http instance, P3' review from the CLI,
D1 `doctor --deep`.

Last run: 2026-09-06 on macOS 15 / Podman 6.1.1 (rootful machine), 27/27 passed. S29 has not been run against
a real sandbox yet; it is unit-covered by `SessionExportTest`.

## Unit-covered
S9 (search narrowing: `ConfluencePluginTest`), S15 (`image/base.Containerfile` sets `npm_config_ignore_scripts`),
S16 (pinned digests in `image/` and `templates/`), S21 (`HostChecks`), P1' (`ArchitectureTest`), P2' (`ViewModelsTest`
reads the same conversation files the CLI writes), G2 (`Migrate112Test`), G4 (`MessagesTest`, `FxmlBindingTest`),
G8 (`PluginLoaderTest` loads a jar built in the test).

## Manual checklist
| # | Criterion | Result |
|---|---|---|
| U1 | First-time user gets going with `init` alone | verified 2026-09-05 (Sandbox1) |
| U2 | Welcome dialog once, «Do not show again» | verified 2026-09-05 |
| U3 | Version button shows app version, sandbox version when they differ | verified (changelog dialog) |
| U4 | New changelog entries shown once | verified (last seen version in settings) |
| U5 | Close warning singular/plural | unit test `closeWarningIsPhrasedForOneOrManyConversations` |
| U6 | Theme toggle remembered | verified |
| U7 | Problem report zip without secrets | unit test `ProblemReportTest` |
| U8 | Resource warning against the Podman machine | verified (Resources tab) |
| U9 | Blocked domains allowed from the Traffic tab | verified |
| G5 | Same installation on macOS (Apple Silicon) and Linux | macOS verified; Linux via CI (ubuntu, rootless) pending a real run |
