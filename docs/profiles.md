# Profile packs

A profile pack is a directory with `profile.json` (and optional `AGENTS.md.d/*.md`). `init --profile <dir>`
copies its `profile.json` into the sandbox; the copy is the sandbox's own and is edited by `setup.sh` and the
GUI (toolchains, protected branches). `profiles/default/` ships with the product; `migrate` generates
`profiles/migrated/` from a 1.1.2 installation.

| Section | Meaning |
|---|---|
| `agents.default`, `agents.enabled` | Which adapters are offered and which a new conversation uses |
| `toolchains` | Fragments from `image/fragments/` baked into the base image (`node22`, `java25`, `maven`, ...). Two are for seeing what a program does rather than building it: `xvfb`, a virtual display with the desktop libraries and fonts GUI programs need and `scrot`/`xdotool` for screenshots, and `chromium`, a browser for page screenshots and Playwright/Puppeteer, whose own browser downloads the proxy blocks |
| `egress.allow`, `egress.block`, `egress.blockExtra` | Proxy allowlist (profile section) and blocklist |
| `localModels.host`, `localModels.ports` | The one private host containers may reach: a model server on the developer's own machine. Omitted, or absent from an older profile, means `host.containers.internal` on `11434` (Ollama) and `1234` (LM Studio, and MLX servers that speak its API); an empty `ports` list closes it off |
| `mirrors.npm|maven|pypi` | Registry mirrors passed to the session |
| `ca.certsDir` | Corporate CA certificates mounted into the gateway |
| `gateway.manifest`, `gateway.plugins`, `gateway.allowWrite` | Manifest and plugin paths; instances allowed to write |
| `git.protectedBranches`, `git.branchPrefix`, `git.committer` | Delivery rules |
| `copy.history`, `copy.depth`, `copy.excludes`, `copy.maxFileMiB` | How working copies are made |
| `resources` | Default limits for new sessions |
| `session.idleStopMinutes` | When idle containers are stopped by `session gc` |
| `session.idleRemoveHours` | When idle containers are *removed* by `session gc`; 0 never removes one. Nothing of the session goes with it — the work is in its volume and the next message builds the container again — but its tool caches do, which is what `cache` below is for. |
| `cache.mode`, `cache.paths` | Tool caches shared by the sessions of this sandbox: `off`, `shared` (one read-write volume per cache) or `readonly` (the same volume behind an overlay, so a session reads all of it and keeps nothing). The ids are a closed set — `npm`, `pip`, `go-mod`, `go-build`, `nuget`, `m2`, `gradle`, `gradle-dists` — and only those whose toolchain the image has are mounted. The default pack shares the first four: they verify what they read and tolerate several writers. Maven and Gradle are deliberately not in it (see below). |
| `language` | `en` or `nb` |

**Before you add `m2` or `gradle` to `cache.paths`.** They are the caches with most to save and least to
protect them. Maven takes no lock across processes, so two sessions building at once can write over each
other's downloads, and it does not re-check a jar that is already in the local repository — a poisoned one
stays poisoned. Gradle does lock, but it decides whether a lock is stale by looking at the owning process
id, and every container has its own pid namespace. Try two concurrent builds against one cache volume
before turning either on, or use `readonly`, where a session cannot write to the shared cache at all.

Nothing organisation-specific belongs in the product: hostnames, URLs and identifiers live in the pack and in
the sandbox's gateway manifest. CI greps for a list of forbidden terms (`.github/forbidden-terms.txt`).
