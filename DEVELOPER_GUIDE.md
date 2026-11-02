# Lyngvi developer guide

This guide is for a developer who is going to change Lyngvi. It explains what the
product does, how the repository is organised, how the code is layered, how the pieces
talk to each other, how to build and test, and the rules that every change must respect.
The README covers installation and day-to-day use; this document covers the inside.

The specification lives in `lyngvi-generell-spesifikasjon.md` (in Norwegian) and the
increment plan in `lyngvi-utviklingsplan.md`. Section references below (`§8.4` and so on)
point into the specification. `agentsandbox-kravspesifikasjon.md` is the original 1.1.2
requirement document and is kept only as history; it is exempt from the forbidden-terms check.

## Contents

1. What the product does
2. Repository layout
3. Modules and dependency rules
4. The sandbox directory
5. The core, package by package
6. Command flow: how a CLI command or GUI action reaches Podman
7. Sessions and agent runs
8. Delivery
9. The gateway
10. Rendering and templates
11. The CLI
12. The GUI
13. Internationalisation
14. Versioning, upgrade and migration
15. Build, test and packaging
16. Testing conventions
17. Rules for every change
18. Common tasks, step by step
19. Troubleshooting

---

## 1. What the product does

Lyngvi runs coding agents (Claude Code, OpenCode, or your own) inside hardened Podman
containers so that an agent can work on a copy of a repository without seeing the host, the
network or any credentials.

The pieces, from the outside in:

- **A sandbox directory.** Created by `init`. Holds the profile, the bundled templates and
  images, the rendered files, the state and one working copy per session. One sandbox can
  serve many repositories.
- **A shared stack.** Two long-running containers started with `podman compose`: a Squid
  **proxy** that enforces an egress allowlist, and an optional **gateway** sidecar that holds
  credentials for internal systems (Confluence, Jira, any REST API) and exposes them read-only
  and allowlisted.
- **Session containers.** One container per conversation. The agent sees `/work/<repo>`
  (a working copy with a fresh `.git`), `/work/output`, `/work/_attachments` and `/work/AGENTS.md`.
  `/work` is the session's own Podman volume, so nothing the agent writes is on the host; the
  attachments and `AGENTS.md` are read-only, and nothing else from the host is mounted. All
  traffic goes through the proxy.
- **Delivery.** Changes made in the working copy are diffed against a baseline, classified
  (apply, hold, reject) and applied to the host repository as a commit or staged changes on a
  working branch. Protected branches are refused unless the user allows them.
- **Two front-ends.** A picocli CLI where every command has `--json`, and a JavaFX GUI.
  Both are thin: all logic is in the core.

## 2. Repository layout

```
pom.xml                       parent POM: versions, modules, shared plugin config
VERSION                       the product version (single source; read by Version.current())
CHANGELOG.md                  versioned entries; the GUI shows what is new since the last seen version
README.md                     user documentation
DEVELOPER_GUIDE.md            this file
LICENSE                       MIT

lyngvi-gateway-api/        plugin contract for the gateway (pure JDK, no dependencies)
lyngvi-gateway/            gateway runtime (jdk.httpserver + Jackson) plus a test harness jar
lyngvi-gateway-plugins/    reference plugins confluence and jira, built as two separate jars
lyngvi-core/               all product logic, no UI; bundles the assets below as classpath resources
lyngvi-cli/                picocli front-end
lyngvi-gui/                JavaFX front-end

agents/                       one directory per built-in agent: agent.json, image.Containerfile, AGENTS.d/
image/                        base.Containerfile and toolchain fragments (node22, java25, python312, xvfb, ...); <name>.md next to a fragment is its note to the agent
proxy/                        squid.conf.template and the bundled blocked-domains list
templates/                    docker-compose templates, AGENTS.md template, .env.example
profiles/default/             the default profile pack (profile.json)
gateway/                      Containerfile for the gateway image (jlink)
scripts/                      docker-entrypoint.sh (session container), fetch-cli.sh (download a release)
setup.sh                      the launcher; copied into every sandbox by init/upgrade
packaging/                    jlink and jpackage scripts (CLI image, GUI app, dmg/deb)
acceptance/                   run.sh: 28 end-to-end checks against a real stack, plus a manual checklist
docs/                         agents.md, gateway-plugins.md, profiles.md, plans/ (one plan per increment)
.github/                      build.yml (CI), release.yml (artifacts on tag), forbidden-terms.txt
```

Everything under `agents/`, `image/`, `proxy/`, `templates/`, `profiles/`, `gateway/`,
`scripts/` and `setup.sh` is **bundled into the core jar** at build time and written into every
sandbox. The list of bundled files is `lyngvi-core/src/main/resources/bundle/index.txt`.
If you add a file under one of those directories, add it to the index, or `init` will not
write it and `TemplatesTest` will fail.

## 3. Modules and dependency rules

All six modules are JPMS modules. The dependency graph is strictly one-directional:

```
gateway-api  <──  gateway  <──  gateway-plugins
    ^                ^  (jar copied as a resource, not a compile dependency)
    |                |
   core  ────────────┘
    ^
    |
  cli, gui
```

| Module | Java module name | Depends on | Role |
|---|---|---|---|
| `lyngvi-gateway-api` | `no.syntakserror.lyngvi.gateway.api` | nothing | Records and interfaces a plugin implements. Plugins compile only against this. |
| `lyngvi-gateway` | `…gateway` | api, Jackson, `jdk.httpserver`, `java.net.http` | The HTTP runtime that runs inside the gateway container. Also produces a test jar with `GatewayHarness` and `StubUpstream`. |
| `lyngvi-gateway-plugins` | (no module-info; plain jars) | api | `confluence.jar` and `jira.jar`. Each jar gets its own `META-INF/services` file from `src/main/plugin-services/<name>/`. |
| `lyngvi-core` | `…core` | api (transitive), Jackson, `java.net.http`, `jdk.management` | All logic. The gateway jar and both plugin jars are copied into `bundle/gateway/` by `maven-dependency-plugin` at `generate-resources`. |
| `lyngvi-cli` | `…cli` | core, picocli | Commands only. |
| `lyngvi-gui` | `…gui` | core, JavaFX controls + fxml, `java.desktop` | Viewmodels and views only. |

Rules that tests enforce:

- The **GUI never starts processes and never touches core internals.** `ArchitectureTest`
  greps the GUI sources for `ProcessBuilder`, `Runtime.exec`, `core.stack.Podman`,
  `core.delivery.`, `core.render.Renderer`, `ConversationStore` and similar. Views may only
  call viewmodels; viewmodels call `core.api` services through `SandboxContext`.
- The **core only reaches the outside world through `Runner`** (`core.runtime.Runner`).
  Every `podman`, `git`, `openssl` or agent invocation goes through it so tests can script
  results with `FakeRunner`.
- **No organisation-specific values** anywhere. CI greps for the terms in
  `.github/forbidden-terms.txt` and `ForbiddenTermsTest` does the same locally.
- Product name, package root and module names are `lyngvi` / `no.syntakserror.lyngvi`.

## 4. The sandbox directory

`core.config.Paths` is the only place that knows where anything lives inside a sandbox.
Never build a sandbox path by hand; add a method to `Paths` instead.

```
<sandbox>/
  setup.sh                    launcher (copy of the repo's), runs cli/ or a found runtime
  cli/                        jlink image (bin/lyngvi) or lyngvi-cli.jar, placed by init/upgrade
  VERSION                     the Lyngvi version that last wrote the bundled files
  profile.json                copied from the profile pack at init; edited by profile commands
  sandbox.config              key=value local state (schema, uid/gid, default agent, language, repos, subnet)
  .env                        build args and local overrides; existing values never changed
  profiles/, templates/, agents/, image/, proxy/, gateway/, scripts/   bundled files (see index.txt)
  Containerfile               rendered: base + toolchain fragments
  image/agents/<id>.Containerfile   rendered per enabled agent
  docker-compose.yml          rendered stack (proxy, gateway when instances exist)
  AGENTS.md                   rendered instructions for the agent
  proxy/allowed-domains.txt   rendered allowlist: profile, agents, user sections
  proxy/gateway-hosts.txt     hostnames Squid forwards to the gateway
  proxy/squid.conf            rendered from squid.conf.template
  gateway/gateway.manifest.json   instances (id, hostname, plugin, scheme, allowlist mode, config)
  gateway/allowlists/<id>.txt     one entry per line, hot-reloaded by the runtime
  gateway/secrets/<id>.env        BASE_URL and TOKEN; mounted only into the gateway container
  gateway/certs/gateway.{crt,key} self-signed certificate for the gateway hostnames
  gateway/plugins/*.jar           plugin jars (confluence, jira, user-installed)
  skills/<name>/SKILL.md      the sandbox's skills; user-owned, never rendered or upgraded
  skills/skills.json          which of them are switched on
  state/
    conversations/<id>.json   one conversation per session (schema 2)
    agents/<id>/              credentials and env for that agent, mounted only into its containers
      skills/                   the switched-on skills, mirrored here for an agent with skillsDir
    locks/                    file locks (Locks)
    .migrated-from-1.1.2      marker written by migrate
  .sessions/<id>/             the host side of a session: only what the host writes
    attachments/              mounted read-only as /work/_attachments
    patches/<repo>.patch      the last review
    container.env, env        environment for the session container
    runs/                     event logs of each ask
```

The session's own files are **not** on the host. Each session has a Podman volume,
`<container>-work`, which is the whole of `/work` in its container:

```
<container>-work/
  <repo>/                     the working copy (fresh .git; history none|shallow|full)
  output/                     what the agent leaves for the human
```

The working copy's `.git` is the agent's to write, so if it lived on the host every
`git` the host runs in it during `review` and `apply` would run the agent's `hooks/`
and `config` (`core.fsmonitor`, `core.sshCommand`, aliases) as the human. Instead the
host reaches the files only through `stack.SessionVolume`, which starts a throwaway
helper container per operation: `--network none`, `--cap-drop ALL`,
`--security-opt no-new-privileges`, the base image, the session's user namespace, and
for git also `-c core.hooksPath=/dev/null -c core.fsmonitor=false` (`session.WorkGit`).
A session made before the volumes existed is migrated on first use —
`SessionService.volume` moves `work/` and `output/` in and deletes them from the host.

Files that are bind-mounted into running containers (`allowed-domains.txt`, `gateway-hosts.txt`,
`squid.conf`, `AGENTS.md`, allowlists) are written **in place** by `render.InPlace.write` so the
inode survives and the container sees the new content. Files that are only read at start
(`Containerfile`, `docker-compose.yml`) are written atomically via a temp file and rename.

`sandbox.config` schema 2 is current. A schema 1 file means a 1.1.2 sandbox and `Sandbox.open`
throws `NOT_INITIALIZED` with `code MIGRATION_REQUIRED` in its data; the CLI and GUI offer `migrate`.

## 5. The core, package by package

The entry point is `core.api.Sandbox`. `Sandbox.open(dir)` reads `sandbox.config` and
`profile.json`, resolves the platform and a `Runner`, and hands out one service object per
command family. `Sandbox.init(dir, options)` creates a sandbox. Both have overloads that accept
a `Platform` and a `Runner` for tests.

| Package | What lives there |
|---|---|
| `api` | The service layer and its options. `SandboxService` (status, doctor, upgrade), `ConfigService` (repos, resources, profile, toolchains, protected branches), `StackService` (up/down/build/logs/reload proxy/blocked requests), `DomainService` (user egress allowlist), `SessionService` (sessions and asks), `AgentService` (list/status/models/login/logout/usage), `DeliveryService` (diff/review/apply), `GatewayService` (instances/plugins/secrets/allowlists/status), `SkillService` (the sandbox's skills: list/read/save/new/remove/enable/disable, and the sync into the agents). `SandboxException` + `ErrorCode` are the only error type; `ErrorCode` maps to CLI exit codes. |
| `contract` | Records returned by services and serialised with `--json`: `StatusReport`, `SessionInfo`, `ApplyResult`, `DoctorReport`, `GatewayInstanceInfo`, `MigrateReport`, `UpgradeReport` and so on. `JsonOutput` is the `{"ok":…}` envelope. Keep these stable; scripts depend on them. |
| `config` | `Paths`, `SandboxConfig` + `SandboxConfigFile`, `Profile` + `ProfileLoader` (validates `profile.json`), `RepoEntry`, `ResourceLimits`, `ResourcePreset` (the profile's `resourcePresets`, and the built-ins a profile that names none gets), `History` (none/shallow/full), `DotEnv`, `ReservedNames`. |
| `runtime` | `Runner` (interface) and `ProcessRunner` (real implementation, streaming, interactive, macOS Podman machine socket), `Platform` (OS, uid/gid, home), `Subnets` (per-sandbox subnet allocation from a registry in the cache dir), `TerminalLauncher` (opens a terminal window for interactive logins). |
| `stack` | `Podman` and `Compose` wrappers, `SessionVolume` + `SessionVolumes` (a session’s files in a Podman volume, and the helper containers the host reaches them through), `StackStatus`, `MachineInfo` (Podman machine resources), `ResourceAdvice`/`ResourceSizes`, `ResourceFit` (how many sessions of a size fit the machine, and the preset derived from it), `SquidLogParser` + `BlockedRequests` (denied hosts from the proxy log), `DomainEntry`. |
| `render` | Everything that turns profile + config + agents into files. See section 10. |
| `template` | `Templates`: the bundled file index, `writeAll`, `missing`, `stale` (used by `init`, `upgrade` and doctor's `bundle` check). |
| `session` | `Conversation` (the persisted record), `ConversationStore` (atomic read/write), `Workspace` + `CopyFilter` (creates the working copy in a staging directory and moves it into the session volume), `WorkGit` (git in the working copy, always in a helper container), `HostRepo`, `SessionContainer` (builds the `podman run` line), `Locks`. |
| `skills` | The sandbox's skills (spec §3.6): `Skills` (pure — the name rules, the `SKILL.md` front matter, what makes a skill unusable, the text a new one starts as), `SkillStore` (the folders under `skills/` and the flags in `skills.json`, plus the starter skills `init` lays down), `SkillSync` (mirrors the switched-on skills into `state/agents/<id>/<skillsDir>/`, deleting only the names it put there, recorded in `.lyngvi-skills`). |
| `ask` | The agent contract: `AgentAdapter`, `AgentDescriptor`, `AgentEvent` (sealed: start, text, reasoning, tool_start, tool_end, notice, usage, done, …), `AgentRun` (drives one ask, folds events into the conversation using `WorkTimeline`), `AdapterRegistry`, `ProcessAdapter` (external `adapter.sh`), `Model`, `AuthStatus`, `Suggestion`, `AgentUsage`, `UsageLimit`. |
| `agents` | Built-in adapters: `ContainerCliAdapter` (generic, driven by `agent.json`), `ClaudeCodeAdapter`, `OpenCodeAdapter`, their `StreamMapper`s (agent output lines → `AgentEvent`), `MockAdapter`; `LocalModelConfig` reads, writes and tests the provider block for a model server on the developer's own machine (`agent.json` `localModels`). |
| `delivery` | `Baseline` (which commit a delivery is measured from), `PatchBuilder`, `PatchClassifier` (apply/hold/reject per file), `HostGit` (branches, apply with 3-way, baseline blob import, rollback). |
| `gateway` | The host side of the gateway: `ManifestIo` (reads/writes the manifest through `gateway-api` records) and `PluginScanner` (lists plugin jars and their types by reading each jar's service file). |
| `doctor` | `Check`, `HostChecks` (podman, machine, known_hosts, bundle, cli), `PodmanChecks` (rendered files, subnet, mounts, cert, image), `DeepChecks` (egress self-test from a throwaway container). |
| `i18n` | `Messages`: one bundle, `messages.properties` (en) and `messages_nb.properties`. |
| `migrate` | `Migrate112`: converts a 1.1.2 sandbox in place (spec §12). |
| `version` | `Version` (parses `VERSION`, comparable) and `ReleaseCheck` (`version --check` against the GitHub releases API). |

Design rules inside the core:

- Services are **stateless** wrappers around a `Sandbox`; create them per call
  (`sandbox.sessionService()`). State lives in files.
- Anything that changes `sandbox.config` goes through `Sandbox.updateConfigLocked`.
- Exceptions carry a `Map<String,Object> data` that ends up in `--json` output and in GUI
  error details. Put the facts a user or script needs there (command, stderr, path).
- The core has **no logging framework**. Progress that the user should see is returned as
  data or delivered through callbacks (`Consumer<String>` for log lines, `Consumer<AgentEvent>`
  for asks).

## 6. Command flow: how a CLI command or GUI action reaches Podman

Take `session start <id>` as an example.

1. **CLI:** `SessionCommand.Start` (picocli) parses arguments, resolves the sandbox from
   `--sandbox`/`LYNGVI_HOME`/cwd via `Context`, and calls
   `sandbox.sessionService().start(id)`.
2. **Service:** `SessionService.start` loads the `Conversation`, checks the stack is up,
   makes sure the agent image exists, builds a `SessionContainer` and calls `Podman.run`.
3. **Runner:** `Podman` assembles the argument list and calls `Runner.run(...)`. In production
   `ProcessRunner` starts the process (on macOS with `CONTAINER_HOST` pointing at the Podman
   machine's API socket). In tests `FakeRunner` returns the scripted result.
4. **Result:** the service returns a `SessionInfo`. The CLI prints it as text or as JSON;
   exceptions become `{"ok":false,"error":…}` and an exit code from `ErrorCode`.

The GUI path is the same from step 2 on: a view calls `SessionViewModel.start(id)`, the
viewmodel runs the service call on `Async`'s executor, and delivers the result to the FX
thread via `UiThread`.

## 7. Sessions and agent runs

**Creating a session** (`SessionService.create`): allocates an id, creates the session's
volume, writes the `Conversation` file, and for each selected repository calls
`Workspace.createIn`, which copies the host checkout (honouring `CopyFilter` excludes and max
file size) and creates a fresh `.git` with `none`, `shallow` (last N commits) or `full`
history. The copy is built in a staging directory under `state/tmp/` and moved into the
volume as the last step; the staging directory is deleted right after, and from then on the
tree exists only in the volume. The result records the host SHA, branch, dirty flag and the
**base commit** in the copy, which delivery later uses as its baseline. Because the copy is
built with the git in the base image, `session new` requires that image to be built.

**The container** (`SessionContainer`): one per session, named from the project and session
id, on the sandbox's internal network, `--userns keep-id` with the host uid/gid,
`--cap-drop ALL`, `no-new-privileges`, memory/cpu/pid limits from the session's resources,
`--add-host` for every gateway hostname, an env file with the proxy settings and the agent's
env file from `state/agents/<id>/env`. The image is `image.Containerfile` in the session
directory: the base image plus the agent's `image.Containerfile`.

**Lifecycle.** A container is created on the first prompt or `session start`, stopped by `session stop`,
by `gc`/`cleanup` when idle longer than the profile's `session.idleStopMinutes`, and removed with the
session. `SessionService.containers()` lists every container Podman holds for the sandbox and marks
orphans (no conversation); `cleanup` stops idle ones and removes orphans. The GUI runs `cleanup` at
start-up and every five minutes, and shows the list on the Sandbox tab.

**An ask** (`SessionService.ask` → `AgentRun`):

1. The adapter is looked up in `AdapterRegistry` by the conversation's agent id.
2. `AgentRun` appends the user message, then calls `adapter.ask(request, onEvent)`.
3. The adapter execs the agent inside the container (`podman exec … claude -p --output-format
   stream-json …`) through `Runner.stream`, and its `StreamMapper` translates every output
   line into `AgentEvent`s.
4. `AgentRun` folds events into a `WorkTimeline` (ordered text and tool items), stamps `ts`
   and `seq`, forwards them to the caller, and on `done` persists the assistant message with
   its `work` list and usage.
5. `AgentRunHandle` lets the caller cancel (kills the exec) or wait.

`ContainerCliAdapter` is declarative: `agents/<id>/agent.json` gives the command templates
with placeholders such as `{model:--model}` and `{session:--session-id|--resume}` (first form
for a new agent session, second for resume), the login commands per auth mode, the credential
files, egress hosts, capabilities and the model list. `ClaudeCodeAdapter` and `OpenCodeAdapter`
add the stream mapping, and `OpenCodeAdapter` how a model id is read and resolved: a provider
opencode has credentials for, or one declared in its own `opencode.json` (a local model server),
keeps its id; anything else that looks like a `vendor/model` slug is routed to OpenRouter. See
`docs/agents.md` for the full descriptor, for models on the developer's own machine, and for
external `adapter.sh` agents (`ProcessAdapter`).

Pinned agent versions live in `agent.json` (`pinnedCli`) and the agent's `image.Containerfile`.
When you bump one, re-verify the command templates and the stream mapping against a real run;
the `verify` field in the descriptor says so.

## 8. Delivery

`DeliveryService` implements `session diff|review|apply` (spec §5.6–5.8).

- **Baseline** (`delivery.Baseline`): `start` is the base commit recorded when the working copy
  was created, `last` is the copy commit of the last delivery, an integer selects an earlier
  delivery. Diffs are always working copy vs baseline.
- **Patch** (`PatchBuilder`): `git diff` in the working copy, per repository, optionally
  narrowed to a path. The copy is in the session's volume, so the whole sequence (temporary
  index, `add -A`, `write-tree`, `diff`) runs as one script in a helper container through
  `WorkGit`; the patch text is what comes back out. The same goes for the commit a delivery
  records in the copy and for the baseline blobs it exports.
- **Classification** (`PatchClassifier`): every file becomes `apply`, `hold` (executables,
  symlinks, binaries, large files) or `reject` (paths under `.git`, outside the repo, and
  similar). Held files are delivered only when named with `--include`; rejected never.
- **Apply** (`HostGit`): checks the host repo is clean, creates or checks out the branch
  (refusing names listed in `profile.json` `git.protectedBranches` unless the user extends the
  list), imports the baseline blobs from the working copy so `git apply --3way` can run even
  when the host never had those objects, applies with `--index`, then commits with the profile's
  committer or leaves the changes staged (`--mode stage`). On failure the working tree and index
  are rolled back. Conflicts are left as markers and reported.
- The `Conversation` records each delivery (branch, message, mode, per-repo commits) so the next
  `last` baseline is known.
- **Export** (`session.SessionExport`, `SessionService.export`, spec §5.9) is the way out that
  depends on none of the above: `session export` / the chat's «Export ZIP» writes every working
  copy and the agent's `output/` to one zip on the host. `SessionVolume.zipTo` builds the archive
  inside a helper container — `find` lists what goes in (so symlinks are dropped and a link to its
  own directory cannot make `zip` copy a subtree per level) and the finished archive is the only
  thing that leaves the volume. It is staged in the sandbox's temp area and moved to the target, so
  a half-written archive never appears there. An exported `.git` never carries `hooks/` or `config`:
  the copy's `.git` is the agent's to write and the archive is extracted on the human's machine,
  where `EXPORT.txt` tells them to run git in it — the same reason the host disarms every git it runs
  in the volume. Nothing is applied and no baseline moves: a delivery after an export is the delivery
  it would have been before.

## 9. The gateway

The gateway is a separate Java runtime that runs in its own container next to Squid. Squid
forwards requests for the configured hostnames (plain HTTP inside the network; CONNECT is
denied by design) to the gateway, which validates and proxies them upstream with the real
credential.

**Runtime** (`lyngvi-gateway`):

- `GatewayMain` reads its configuration from the environment (`Config`, spec §8.10): manifest
  path, allowlist and secret directories, plugin directory, ports, TLS PEM files, `ALLOW_WRITE`,
  `CA_CERT_DIR`. `--health [--deep]` prints the health document and exits.
- `Gateway` assembles: `PluginLoader` (one `URLClassLoader` per jar, `ServiceLoader` for
  `GatewayPlugin`; `HttpPlugin` is built in), `InstanceRuntime` per manifest instance (config
  deserialised into the plugin's `configType`, `HttpUpstream` locked to the secret's origin,
  `AllowlistStore` watching the allowlist file, compiled routes), `Router`, `Listeners`.
- `Router` handles a request in the order of spec §8.4: health, unknown host → 404, method →
  405, instance without secret → 503, allowlist → 403, then the plugin's handler. Responses get
  `cache-control: no-store`. HTTP/2 pseudo-headers (`:status` and friends) are stripped in
  both directions.
- `HttpUpstream` injects the secret as bearer, basic, a named header or a query parameter and
  never lets a request leave the locked origin. `TrustStores` adds every PEM in `CA_CERT_DIR`
  to the JDK trust anchors. `MaskedLog` replaces every known secret value with asterisks.

**Plugins** (`lyngvi-gateway-api`, `docs/gateway-plugins.md`): implement `GatewayPlugin`
(`type`, `routes`, `allowlist` resolver, optional `configType`, `restBase`, `parseBrowserUrl`,
`health`), package with a `META-INF/services/no.syntakserror.lyngvi.gateway.api.GatewayPlugin`
entry, and drop the jar into `gateway/plugins/`. The API module has no dependencies; JSON is
plain maps and lists through the `Json` interface the runtime provides. Test a plugin with
`GatewayHarness` from the gateway test jar: it boots the runtime in-process with a `StubUpstream`.

**Host side** (`core.api.GatewayService`): instance add/show/remove edit the manifest, secret
set/status/clear write `gateway/secrets/<id>.env`, allow list/add/remove edit the allowlist
file, `plugin list|install|remove` manage jars, `status` calls the container's `--health`,
`restart` recreates the container through compose (so a changed env file is picked up),
`rebuild` rebuilds the image, `cert show|renew` handles the self-signed certificate. After an
instance change the proxy configuration is re-rendered and Squid is reloaded.

**Image**: `gateway/Containerfile` builds a jlink image from `gateway/gateway.jar`. The jar and
the two plugin jars are bundled in the core and written into the sandbox, so a sandbox is
self-contained and `upgrade` refreshes them.

## 10. Rendering and templates

`render.Renderer.render()` is `rebuild-config`. It gathers everything once into
`RenderInputs` (profile, config, agents' descriptors, gateway manifest, platform) and writes,
in the order of spec §4.5:

1. `proxy/allowed-domains.txt` (`AllowlistRenderer`): profile section, one section per enabled
   agent from its `egress` list, and the user section, which `Allowlists` edits in place.
2. `proxy/gateway-hosts.txt` (`GatewayHostsRenderer`).
3. `proxy/squid.conf` (`SquidConfRenderer`): from the template; the gateway peer only when
   instances exist, and the one private host containers may reach — the profile's `localModels`
   host on its ports, for a model server running on the developer's own machine.
4. `AGENTS.md` (`AgentsMdRenderer`): template + gateway section + profile pack `AGENTS.d/` +
   each agent's `AGENTS.d/`.
5. `Containerfile` (`ContainerfileRenderer`): `image/base.Containerfile` plus the fragment for
   every toolchain in the profile, in profile order, with a label listing the toolchains so the
   GUI can tell whether the built image matches the profile.
6. `docker-compose.yml` (`ComposeRenderer`): proxy always, gateway service from the gateway
   template when instances exist, CA certs directory from the profile.
7. Per-agent Containerfiles, empty allowlist and secret files for new instances, the
   certificate when missing (`CertGenerator`, via `openssl` through the runner).

Templates use simple `${NAME}` placeholders substituted by the renderers; there is no template
engine. To add a toolchain: add `image/fragments/<name>.Containerfile`, add it to
`bundle/index.txt`, and it becomes selectable (`ConfigService.availableToolchains` lists the
fragments). A `<name>.md` beside the fragment is rendered into `AGENTS.md` whenever the toolchain
is in the image (`{{TOOLCHAIN_SECTION}}`), for what the agent has to know to use it — the virtual
display of `xvfb`, the browser of `chromium`. Pin image digests in fragments and templates; the acceptance checklist (S16) expects it.

## 11. The CLI

`cli.Main` is the picocli root command with every subcommand listed in its `@Command`
annotation. `GlobalOptions` holds the inherited options (`--json`, `--quiet`, `--lang`,
`--no-color`, `--yes`, `--sandbox`). `Context` resolves the sandbox directory (option, then
`LYNGVI_HOME`, then cwd), owns the output streams, platform, runner and stdin.

Conventions for a command class:

- It calls exactly one service method and prints the returned contract record. With `--json`
  the record is serialised as-is inside the `JsonOutput` envelope, so the text and JSON output
  cannot drift apart.
- It never contains logic that a GUI would need; that goes into the service.
- Text output uses `Messages` keys; nothing user-visible is hard-coded.
- `SandboxException` is mapped to the exit code of its `ErrorCode` by `Main`; commands do not
  catch it.

`Main.commandLine(platform, runner, stdin)` builds the command tree with test doubles, which is
how `CommandsTest`, `SessionCommandsTest` and friends run whole commands in-process.

`CliInstaller` is what `init` and `upgrade` use to put a runnable CLI into `<sandbox>/cli/`:
a copy of the running jar, or a symlink to the jlink runtime image when the CLI runs from one.

## 12. The GUI

The GUI follows spec §9: viewmodels over the core, FXML views, no logic in views.

- `Splash` is the undecorated window shown first: the mark, the name and the version on a themed
  card, centred on the primary screen, with a close button in the top-right corner, and Escape or a
  press anywhere on the card doing the same thing as the button. Its one line of
  prose, the tagline (`gui.splash.tagline`, the words `package-gui.sh` gives jpackage), is in the
  language of the sandbox that was open last: the language is a sandbox setting, and opening one is
  the work the splash is covering for, so `App` remembers the language in `Settings` on every
  sandbox change and reads it back before the toolkit has done anything else. A first-ever start is
  in English. The mark is
  `icon/splash-mark-512.png`, its own resource rather than a window icon: the icons are the mark on a
  pale plate with the padding an application icon needs, and the splash wants the mark alone, in a box
  it draws itself. It is decoded at `decodeSize(...)` — the size in points times the primary screen's
  output scale, capped at the 512 the file holds — so there is one image pixel per device pixel
  and neither the loader nor the view has to scale it afterwards. The disc behind it is not
  decoration: the artwork is a navy wolf with a cyan ring and no background, and on the dark theme
  nothing but the ring survives without a light surface under it.
  `App.start` shows it and then hands the whole of the
  start-up to `whenPainted`, which runs it from the splash's second pulse — `start` holds the FX
  thread, and nothing is painted while it does, so work left in `start` would keep the splash an
  empty rectangle until the main window was already up. `dismiss` holds it for `MIN_VISIBLE`
  (ten seconds) counted from `show`, fades the stage out over the main window and then runs what
  `App` hands it — the first-run dialogs and the migration offer, which are modal and would
  otherwise open behind a splash that is always on top. The minimum is the reason `-Psmoke`
  gives the run 16 seconds: a shorter one would close the window while the splash still had it.
  The close button, Escape and a press anywhere on the card are the ways past the wait, and they
  all go through the same `handOver`, so what waited for the splash runs whichever way it goes, and
  only once. A click that lands while the start-up still holds the FX thread is delivered when it
  lets go, which can be before `dismiss` is called at all; `dismiss` then finds the splash gone and
  runs its continuation on the spot. Two details make those ways past the wait actually work. The
  main window is shown under the splash and takes the keyboard with it, so `App` installs
  `splash.dismissOn(scene)` on the main scene as well as the splash's own — the filter consumes
  Escape only while the splash is up, and stands down once it is gone. And a window without the
  keyboard spends the first press on becoming focused rather than on the button under the pointer,
  which is a close button that needs two clicks, so `App` calls `splash.toFront()` right after
  `stage.show()` to hand the splash the focus the new window just took.
- `App` (JavaFX `Application`) shows the splash, reads `Settings`, builds the first `MainWindow`,
  installs the FX-thread uncaught-exception handler, and hands the splash the first-run dialogs
  and the migration offer to run once it is gone. `-Dlyngvi.gui.smoke=<ms>` closes the window
  after a delay; CI and `-Psmoke javafx:run` use it. `Launcher` is a plain `main` for jpackage.
- `MainWindow` is one window: it creates `SandboxContext`, `AppLog`, `Async` and the `Services`
  record (one viewmodel per family), opens the sandbox *before* loading `main.fxml` so the window
  is built in that sandbox's language, applies the theme, and warns about conversations of its own
  that are still working when it is closed. Several windows can be open at once, one sandbox each,
  sharing nothing but the settings file and the toolkit — `MainWindow.showing(dir)` is how the
  switcher finds a sandbox that already has a window instead of opening it twice, and only the
  first window's bounds are remembered (the rest are cascaded from the window they were opened
  from). The windows live in a static list, read and written on the FX thread only.
- `MainController` owns the window: toolbar, navigation rail, tabs (conversations, setup,
  traffic, resources, profile, gateway, agents, skills) and console. Every action calls a
  viewmodel method. The skills page is the one page with an editor of its own: the tick per skill
  is saved as it is clicked (`SkillsViewModel.setEnabled`), while the text in the editor is the
  user's to save or discard, and a refresh must neither clear it nor pop a dialog — the rows are
  replaced on every refresh, so the selection is matched by skill name rather than by record. The sandbox switcher in the toolbar is a `MenuButton` whose items are built
  when it opens, from `SandboxChoices` over `Settings.recentSandboxes()`; switching inside the
  window stops what it streams from the sandbox it leaves, and clears the chat and the files,
  which belong to it.
- `FilesWindow` is the files pane in a window of its own, one per main window: `MainController`
  builds it with the window (the chat hands its `FilesController` every conversation that is
  selected) and shows it when «Files» or ⌘/Ctrl+B asks for it, so the user gives the files the
  size and the place on the desktop they want. It has no owner window — an owned window is always
  held in front of its owner — so `MainController.shutdown()` closes it, and the size and position
  it was left at are kept in `Settings.filesWindowBounds()`, whether it was closed by itself or
  with the window it belongs to. `Settings.filesWindowOpen()` is whether it comes back on start.
- **Viewmodels** (`gui.viewmodel`) depend on `javafx.base` only (properties, observable lists)
  so they are testable without a display. `BaseViewModel` provides the context, `Async`, the
  log, an error handler and a busy flag. `UiThread` is where UI updates run: the app binds it to
  `Platform.runLater`, tests run it inline. `AgentRunBinder` subscribes to an `AgentRun` and
  batches events into the observable message model. `SessionViewModel` runs any number of
  conversations at once: it keeps a `LiveTurn` (the full state of the running reply) per
  conversation and lets the view that currently shows a conversation take over its updates with
  `watch`/`unwatch`. `Drafts` keeps unsent composer text and attachments per conversation. `SkillsViewModel`
  is the sandbox's skills: the list, the flags, one skill's `SKILL.md` at a time, and the
  agents that take skills at all. `ConversationGroups` builds the rows of
  the conversation list and `ConversationRepos` the repository tags under a row (as many names as the sidebar
  fits, then «+n»); both are pure, so the rules are tested without a toolkit. `Errors` maps `SandboxException`s to a
  dialog kind, title, message, details and alternatives. `Settings` is
  `~/.lyngvi-gui.properties`. `Changelog` parses `CHANGELOG.md`. `ProblemReport` builds a zip
  of logs and configuration with secrets removed.
- **Views** (`gui.view`) are controllers and custom nodes: `ChatController` (transcript with
  bubbles, a live bubble rebuilt from the `LiveTurn` when the user returns to a running
  conversation, `WorkLogPane` fed by `WorkLogModel` with stable keys so panes update
  in place instead of being rebuilt), `MarkdownParser`/`MarkdownArea` (headings, paragraphs, lists,
  fenced code, quotes, rules and pipe tables — a table becomes a `GridPane` of text flows, one per
  cell, so the columns line up and a cell wraps when the bubble is too narrow for them all),
  `DiffPaneBuilder`/
  `DiffArea`/`DiffWindow`, dialogs (`NewSessionDialog`, `DeliverDialog`, `ExportDialog`, `LoginDialog`,
  `AddSystemDialog`, `SandboxWizard`, `WelcomeDialog`, `ChangelogDialog`, `Dialogs`),
  `GatewayPane`, `UsagePane`, `ConsoleView`, `Theme`. `SandboxWizard` is «New sandbox…»: six pages — the
  folder with its repositories (`init` and then `rebuild-config`, which `init` does not do and a build needs),
  the base image, the shared stack, the checks, the agent login and a summary — so what the dialog leaves
  behind is a sandbox a conversation can be started in. One forward button runs the page's work and then moves
  on; which of the two it does, what it reads and when it is enabled is `WizardFlow`, which is pure and
  unit-tested. A page cannot be left while its work runs, so a call that comes back always finds the page that
  started it on screen, and the two optional pages (checks, login) can be passed by. `BaselineChoices` is the baseline picker
  behind both `DiffWindow` and `DeliverDialog`: the session start and one entry per delivery,
  the value being what `--from` takes (`start` or the delivery index) and the label the delivery's
  time and branch; it is pure, so the two windows cannot disagree. `DeliverFiles` is the same idea for the
  dialog's file lists: it sorts the review's files into the three buckets and keys each one `repo/path`, which
  is also how the parsed patches are indexed, so a row finds its own diff and two repositories with the same
  path cannot be confused; `DeliverDialog` then draws that diff with `DiffArea.fileView`, the view «Git Diff»
  uses. `DeliverFiles` also works out what a delivery would leave behind (`excluded`, for the warning above the
  buckets and the confirmation before Apply) and what `ApplyOptions.includes` must then say (`includes`): the
  core reads an explicit include list as the whole delivery rather than as an addition to the apply bucket, so
  the bucket has to be listed along with the ticks. `TranscriptSelection` is the transcript's
  text selection, which JavaFX does not give a scene graph: it scans the bubbles into runs (every text flow,
  and every label's own text node), hit-tests the pointer onto one with `TextFlow`/`Text.hitTest`, and draws
  the highlight as a path per visible run (`rangeShape`) on a mouse-transparent pane stacked over the
  transcript inside the scroll pane, so it scrolls and clips with it; the arithmetic — which run a point picks
  out, what a range yields as text, and how much of a run a scrolling box shows of it (`clamp`) — is in
  `TranscriptCopy`, which needs no toolkit and is unit-tested. Its pointer handling sits in event *filters* on the
  transcript box, not handlers: a control's own input map handles `MOUSE_PRESSED` on the way up and consumes it
  even when all it did was take the focus, and both a scroll pane (a code block's) and a titled pane (a tool
  call's) do, so a handler never saw a press that landed inside one. What a nested scroll pane shows of a run is
  then what the run is hit-tested and clipped to, since a code block's long line reaches past the block.
  `DiffSelection` does the same for a diff view, where the lines are a virtualised `ListView` whose rows are
  recycled: the selection is kept as a range over line numbers and character offsets, which no recycling can
  invalidate, and every visible row paints its share of it from `TextFlow.getRangeShape` onto an unmanaged `Path`
  behind the text. It reuses `TranscriptCopy.Range`, and what it copies is `DiffPaneBuilder.selectedText`;
  `DiffPaneBuilder.newFileText` is the new side of a whole-file diff, which is what «Copy file» puts on the
  clipboard. The list has no selection model at all, so a row can never be picked out whole.
  `FilesController` shows a markdown file rendered rather than as source — `MarkdownArea` in a box under a
  highlight pane inside a scroll pane, the three `TranscriptSelection` needs, so the text of a preview is
  selectable and copyable like a reply's — with «Raw»/«Preview» above it, Preview the first view of a `.md` file
  and the user's own choice remembered for the rest of the session. `previewOf` caps what is laid out
  (`MAX_PREVIEW_CHARS`, cut at a line break and said so under the text): a 300 kB specification is tens of
  thousands of text nodes, and Raw has all of it.
  A source file in a language Lyngvi has rules for opens coloured instead: `Languages` reads the `.syntax` files
  (the shipped ones from `syntax/index` in this module, the user's from `~/.lyngvi-syntax/`, which replace them by
  name), `Syntax` is one language's rules, `SyntaxScanner` turns a line into spans, and `CodeView` is the
  virtualised list that shows them — the diff's row arrangement, and `LineSelection` for the selection, which is
  `DiffSelection` renamed and made generic over the item type so both lists can use it. The whole file is scanned
  once for one `int` per line (the state the line begins in, so a block comment that opened above the viewport
  still colours); the spans themselves are worked out per row as it scrolls into sight. Kinds carry style classes
  only (`code-keyword`, `code-string`, …) and the colours are `-app-syntax-*` in app.css and dark.css, so a
  language added by a user is themed with everything else. The page that lists and edits the languages is in
  `MainController` beside the skills page, and is the one page that is not disabled when no sandbox is open.
  `ClipboardImage` writes an image pasted
  into the composer to a PNG in a scratch directory, so it can be attached like any other file;
  it encodes the pixels itself because `javafx.swing` (`SwingFXUtils`) is not one of the modules
  the app is built and jlinked with.
- **Resources:** `fxml/main.fxml`, `chat.fxml`, `files.fxml`; `css/app.css` and `dark.css`.
  All strings come from the core bundle under `gui.*` keys, so the GUI has no bundle of its own.

GUI tests run without a display:

- `FxmlBindingTest` loads every FXML and checks that every `%key` exists in the bundle,
  including dynamically built key families (`gui.chat.stop.*` and similar) that the bundle
  generator would otherwise miss.
- `ViewModelsTest` and `FoundationTest` drive viewmodels against a real sandbox directory with
  a `FakeRunner`, and read the same conversation files the CLI writes.
- `ArchitectureTest`, `SeverityStyleTest`, `PureViewHelpersTest`, `ProblemReportTest`.

Interactive agent logins (Claude Code's OAuth TUI) cannot run inside the GUI process, so
`AgentService.loginInTerminal` writes a small script and `TerminalLauncher` opens it in a
terminal window (`open -a Terminal` on macOS, common emulators on Linux); `LoginDialog` polls
`authStatus` until credentials appear.

## 13. Internationalisation

One bundle, `no/syntakserror/lyngvi/core/i18n/messages.properties` (English) and
`messages_nb.properties` (Norwegian bokmål). `Messages.forLanguage(code)` resolves the locale
explicitly; do not use `ResourceBundle.Control`, it is not allowed for named modules and the
GUI runs on the module path.

Rules:

- Every user-visible string in the core, CLI and GUI is a key. `MessagesTest` checks that both
  files have the same keys and that formats are valid.
- Add keys in both files in the same change. The nb file is not optional.
- Key families: `error.*`, `doctor.*`, `session.*`, `gateway.*`, `gui.*` (GUI only),
  `upgrade.*`, `version.*`. Keys built at runtime (`"gui.chat.stop." + kind`) must be listed in
  `FxmlBindingTest`'s dynamic families.
- Language for a sandbox comes from `profile.json` (`language`), overridable with `--lang`.

## 14. Versioning, upgrade and migration

- `VERSION` at the repository root is the single source of the product version. It is bundled
  into the core jar and read by `Version.current()`. All POMs carry the same version; bump them
  together (`mvn versions:set` or a search-and-replace) and add a `CHANGELOG.md` entry with the
  same number, because the GUI reads the changelog by version.
- Every sandbox stores the version that last wrote its bundled files in `<sandbox>/VERSION`.
  `Templates.stale` compares the bundled files with the classpath copies; doctor's `bundle`
  check warns when they differ and `upgrade` (`SandboxService.upgrade`) rewrites them, refreshes
  `cli/` and `setup.sh`, and re-renders.
- `version --check` (`ReleaseCheck`) asks the GitHub releases API for the latest tag. The
  default repository is `joachimhs/lyngvi`; override with `LYNGVI_RELEASE_API`.
  `scripts/fetch-cli.sh` downloads a release image (override with `LYNGVI_RELEASE_URL`).
- `migrate` (`Migrate112`) converts a 1.1.2 sandbox in place following spec §12: a profile pack
  in `profiles/migrated/`, conversations from the old locations, the gateway manifest, the
  subnet registry, and a marker in `state/`. It reports what the user must still do by hand.

## 15. Build, test and packaging

Prerequisites: JDK 25 with jmods (Temurin works), Maven 3.9+, Podman for anything beyond unit
tests. JavaFX comes from Maven; jpackage and jlink come from the JDK.

```sh
mvn -q verify                              # build every module and run all tests (~400)
mvn -q install                             # also install to ~/.m2 (the GUI compiles against the installed core)
mvn -pl lyngvi-core test -Dtest=RendererTest
./setup.sh version                         # run the CLI from the source tree (uses lyngvi-cli/target/*.jar)
mvn -pl lyngvi-gui javafx:run           # run the GUI
mvn -pl lyngvi-gui -Psmoke javafx:run   # GUI smoke run: opens, waits, exits
mvn -Pdist package -DskipTests             # release artifacts, see below
acceptance/run.sh <sandbox>                # 28 end-to-end checks against a running stack
```

Note on `mvn install`: the GUI module resolves the core from the reactor during a full build,
but an IDE or a `-pl lyngvi-gui` build resolves it from `~/.m2`. After changing the core,
run a full `mvn install` before building the GUI alone, or you will compile against a stale core.

**Packaging.** `mvn package` produces jars only. The `dist` profile binds the packaging scripts
to the `package` phase:

| Output | Produced by | Contents |
|---|---|---|
| `lyngvi-cli/target/dist/` | `packaging/package-cli.sh` | jlink runtime image with a custom `bin/lyngvi` launcher and an AppCDS archive, `lyngvi-cli-<v>-<os>-<arch>.tar.gz` and `.sha256` |
| `lyngvi-gui/target/dist/` | `packaging/package-gui.sh` + `package-gui-installer.sh` | jlink runtime with JavaFX platform jars, `lyngvi.app` (macOS) or `lyngvi/` (Linux) via jpackage, plus `lyngvi-<v>.dmg` on macOS or a `.deb` on Linux |

The scripts also run standalone: `packaging/package-cli.sh [out]` and
`packaging/package-gui.sh [out] [app-image|dmg|pkg|deb|rpm]`, default output `<repo>/dist/`.
The artifacts are unsigned; the README tells macOS users how to clear quarantine.

**The disk image's window.** The `.dmg` opens on the application and a link to `/Applications` to drag it
onto, over a background that draws the arrow between them. The window is 640 × 400 points with 128-point
icons at {160, 190} and {480, 190}; change those and `packaging/macos/dmg/background.svg` has to move
with them. The volume icon is the application icon, which jpackage uses for it on its own.

Laying a window out means asking Finder to do it, in AppleScript, on a mounted and writable image.
jpackage will do that: files named `<app>-dmg-setup.scpt` and `<app>-background.tiff` in the directory
given to `--resource-dir` replace the ones the JDK ships, and `package-gui.sh` assembles that directory
for the `dmg` type from `packaging/macos/dmg/` (the `.tiff` is built from the two PNGs by
`background-tiff.sh`, `tiffutil` joining them so a Retina screen gets the 2x one). What jpackage will not
do is tell you when the AppleScript fails, and it fails for a reason that has nothing to do with the
script: macOS wants the terminal the build runs in to hold an Automation permission for Finder (System
Settings → Privacy & Security → Automation), and without it `osascript` gets `-1743` and a plain window
comes out.

So the layout is done again afterwards, by `packaging/macos/dmg/layout-dmg.sh <image.dmg>`, which
`package-gui.sh` runs on the finished image: it opens the image read-write, writes the drop target as a
symlink with `ln` rather than leaving it to Finder, copies the background in, runs the same script and
reports what `osascript` said, then closes the image up again. Exit 3 is "everything but the layout",
which is not a build failure — a machine with no desktop session, CI among them, produces an image that
installs by dragging and has Finder's own window. `build-dmg.sh` asks about the permission before it
builds, since the answer is a dialog and the build takes minutes; `-l <image.dmg>` lays the window out
on an image already built, which is the retry once the permission is given, and `-p` (or
`LYNGVI_DMG_LAYOUT=no`) leaves the image as jpackage wrote it.

`packaging/build-dmg.sh [-o out] [-i icon] [-n]` is the disk image on its own, for a release built by
hand on a Mac: it runs Maven, hands the work to `package-gui.sh … dmg`, renames the image to
`lyngvi-gui-<version>-macos-<arch>.dmg` as `release.yml` does, writes the `.sha256`, and mounts it once
to check that `lyngvi.app` and its icon are inside, and to say so when Finder left the window plain.
`-n` skips Maven and packages the jars already
built, `-p` leaves the image plain; `-i` takes an `.icns` or a PNG it converts with `sips` and
`iconutil`, and reaches jpackage
through `LYNGVI_ICON`, which `package-gui.sh` reads instead of `packaging/icons/`. The app icon is the
logo either way: `packaging/icons/lyngvi.icns` is committed, and a checkout missing it gets one built
from `packaging/icons/lyngvi.png`.

**CI.** `.github/workflows/build.yml` runs on push and pull requests on Ubuntu and macOS:
forbidden-terms grep, `sh -n` on every shell script, `mvn verify`, a launcher smoke test, an
end-to-end run of `init`/`status`/`gateway …` against the packaged jar including the gateway
runtime as a process, and a check that the launcher exits 3 without a runtime.
`release.yml` runs on a version tag across macOS (arm64, x64) and Linux (x64, arm64), runs
`mvn -Pdist package`, and uploads the CLI and GUI artifacts to a GitHub release.

## 16. Testing conventions

The project is built test-first. Every behaviour has a unit test that was seen failing before
the implementation existed; keep it that way for changes.

- **Test doubles for the outside world.** `FakeRunner` (core test sources, package
  `core.testing`) scripts results keyed by the leading words of a command
  (`fake.ok("podman ps", "…")`) and records every call. `HybridRunner` scripts `podman` but runs
  `git`, `openssl` and adapters for real, which is what the workspace, delivery and mock-agent
  tests need. Unscripted commands fail like a missing executable, so a test cannot silently
  reach the host.
- **Real sandbox directories in temp folders.** Most core tests call `Sandbox.init` into a
  `@TempDir` with a `FakeRunner` and exercise the services against real files. This is
  deliberate: the file layout is the contract between CLI, GUI and future versions.
- **Gateway tests** use `GatewayHarness` (boots the runtime on a free port with a
  `StubUpstream`) and real HTTP through `java.net.http`. Surefire sets
  `-Djdk.httpclient.allowRestrictedHeaders=host` so tests can send `Host` headers for routing.
- **Tests run on the classpath** (`useModulePath=false` in surefire) even though the main code
  is modular. Keep test-only helpers in test sources.
- **Contract tests** guard cross-cutting rules: `ForbiddenTermsTest`, `TemplatesTest` (bundle
  index matches the files), `MessagesTest` (bundles in sync), `ArchitectureTest`,
  `FxmlBindingTest`, `ServiceFilesTest` (each plugin jar has its own service file).
- **Naming.** Test method names describe behaviour (`applyRefusesProtectedBranch`), and one
  test asserts one behaviour. AssertJ for assertions.
- **Acceptance.** `acceptance/run.sh` needs a real sandbox with the stack up and images built;
  it is the last step before a release and is not part of `mvn verify`. Its README records the
  last run and the manual checklist.

## 17. Rules for every change

1. **Logic goes in the core.** If a CLI command and a GUI action would need the same code,
   it belongs in a service. `ArchitectureTest` will catch a GUI shortcut.
2. **Use `Paths` and `Runner`.** No hand-built sandbox paths, no `ProcessBuilder` outside
   `ProcessRunner`.
3. **Both language files.** A new key goes into `messages.properties` and
   `messages_nb.properties` in the same commit.
4. **Bundled file? Update the index.** Anything new under `agents/`, `image/`, `proxy/`,
   `templates/`, `profiles/`, `gateway/`, `scripts/` must be listed in `bundle/index.txt`.
5. **Contracts are public API.** Adding a field to a `contract` record is fine; renaming or
   removing one breaks `--json` consumers and the GUI. `Conversation` and `sandbox.config` have
   schema versions; bump them and write a migration when the shape changes.
6. **No organisation-specific values** in code, templates, profiles, docs or tests. Use
   `example.com`-style placeholders. CI fails otherwise.
7. **Secrets never reach logs, generated files or problem reports.** The gateway masks them;
   the core never writes them anywhere except `gateway/secrets/` and `state/agents/`;
   `ProblemReportTest` checks the zip.
8. **In-place vs atomic writes.** Files mounted into running containers are written in place
   (`InPlace.write`), everything else atomically (`InPlace.atomic`, `ConversationStore`).
9. **English in the product,** including this guide, the README, `setup.sh`, the CLI, the GUI
   and the changelog. The specification is in Norwegian and stays that way.
10. **Nothing is committed on your behalf.** Tooling in this repository does not run
    `git add`/`commit`/`push`; the developer decides what goes in.

## 18. Common tasks, step by step

**Add a CLI command.**
Write the test in `lyngvi-cli/src/test` using `Main.commandLine(platform, fakeRunner, stdin)`
and assert on the JSON output. Add the service method in `core.api` with its own test in
`lyngvi-core` (this is where the logic goes). Add the command class in `lyngvi-cli` and
register it in `Main`'s `subcommands`. Add message keys in both bundles. Document it in the
README's command list.

**Add a GUI action.**
Add the viewmodel method (calls a service through `Async`), a `ViewModelsTest` case, then the
view code, then `gui.*` keys in both bundles. If the action needs new FXML, keep `%key` references
in sync with the bundle or `FxmlBindingTest` fails.

**Add a toolchain.**
Create `image/fragments/<name>.Containerfile` with pinned digests, add it to `bundle/index.txt`,
add a `RendererTest` case that selects it and checks the rendered `Containerfile`. It appears in
`profile show`, `ConfigService.availableToolchains` and the GUI's toolchain picker automatically.
The first line, `# --- toolchain: <name> (<what it is for>) ---`, is the checkbox's tooltip. If the
agent has to be told how to use it, put that in `image/fragments/<name>.md` (one `##` section); it is
added to `AGENTS.md` when the toolchain is selected, and `bundle/index.txt` must list it too.

**Add or update a built-in agent.**
Read `docs/agents.md`. Edit `agents/<id>/agent.json` (`pinnedCli`, commands, models, egress,
env, auth files) and `agents/<id>/image.Containerfile`. If the agent's output format changed,
update its `StreamMapper` and the fixtures in `StreamMappersTest`. Then do a real run:
`session ask` with each model alias, a login and a logout. Record the version in the changelog.

**Add a gateway plugin.**
Read `docs/gateway-plugins.md`. A new reference plugin goes into `lyngvi-gateway-plugins`
with its own `src/main/plugin-services/<name>/META-INF/services/…` file, a jar execution in that
module's POM, a copy execution in the core POM, and an entry in `bundle/index.txt`. A plugin
outside this repository only needs the api jar and `gateway plugin install`.

**Change the sandbox layout.**
Add the path to `Paths`, decide whether `init` creates it, whether `upgrade` must create it in
existing sandboxes, and whether `Migrate112` needs it. Add it to `.gitignore` if it is state.
Update section 4 of this guide.

**Release.**
Bump `VERSION` and every POM, write the `CHANGELOG.md` entry, run `mvn verify`, run
`acceptance/run.sh` against a real sandbox and update its README, build `mvn -Pdist package`
locally once, then tag `v<version>`; `release.yml` produces and uploads the artifacts.

## 19. Troubleshooting

- **`mvn -pl lyngvi-gui …` fails with "cannot find symbol" for a core class.** The GUI
  compiled against the installed core in `~/.m2`. Run `mvn -q install` from the root.
- **`TemplatesTest` fails after adding a file.** Add it to `bundle/index.txt`; the test also
  fails when the index lists a file that no longer exists.
- **`MessagesTest` fails.** A key exists in only one of the two properties files, or a
  `MessageFormat` pattern has an unbalanced brace.
- **A label shows `!gui.some.key!` and the console warns about a missing message key.** The GUI
  is newer than the core it runs with (a `-pl lyngvi-gui` build, or a packaged app built before
  the key was added). Run `mvn -q install` from the root, or rebuild the package with `-Pdist`.
- **`FxmlBindingTest` fails on a key you build at runtime.** Add the key family to the test's
  dynamic-family list and the concrete keys to both bundles.
- **Podman on macOS: "cannot connect" while a machine runs.** `ProcessRunner` sets
  `CONTAINER_HOST` to the machine's API socket unless `CONTAINER_HOST` or `CONTAINER_CONNECTION`
  is already set. A corrupt line in `~/.ssh/known_hosts` makes the ssh-based default fail; `doctor`
  reports the line, and the fix is the user's, never the tool's.
- **Squid reload does nothing.** The proxy must keep a pid file (`pid_filename /run/squid.pid`)
  for `squid -k reconfigure`; `StackService.reloadProxy` falls back to `kill -HUP`.
- **A gateway change is not picked up.** Env files are read when a container is created, so
  `gateway restart` recreates through compose instead of `podman restart`. Allowlist edits are
  hot-reloaded and need no restart.
- **`git apply` reports a missing blob during delivery.** The host lacks the baseline object;
  `HostGit.importBlob` writes it from the working copy before the 3-way apply. If you see this,
  the baseline recorded in the conversation and the copy disagree.
- **A JavaFX modal dialog blocks a smoke run.** Show first-run dialogs after `start()` returns
  (`Platform.runLater`) and skip them when `lyngvi.gui.smoke` is set.
