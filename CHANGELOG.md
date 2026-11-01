# Changelog

## Unreleased

### Security
- **A session's files no longer live on the host.** The working copies and the agent's `output/` used to be
  directories under `<sandbox>/.sessions/<id>/`, bind-mounted read-write into the container. That put every
  byte the agent writes in the host's filesystem, and the working copy's `.git` with it — so the agent could
  put a `pre-commit` hook, a `core.fsmonitor` command or an `includeIf` into the copy's `.git`, and the next
  `git` the host ran there during `review` or `apply` would run it as the human, outside the sandbox. Each
  session now has a Podman volume, `<container>-work`, which is the whole of `/work` in its container. The
  host reaches into it only through short-lived helper containers (`--network none`, `--cap-drop ALL`,
  `no-new-privileges`, the base image, the session's user namespace), and every git command in a working copy
  carries `-c core.hooksPath=/dev/null -c core.fsmonitor=false`, where the copy's own configuration cannot
  reach it. The only host directory a session container still sees is `_attachments`, read-only, which nothing
  but the human writes to.
- Removing a session removes its volume; `session gc` also removes volumes whose conversation is gone. A
  session created by an earlier version has its `work/` and `output/` moved into the volume, and deleted from
  the host, the first time it is used again — no manual step, but the move needs the base image, so run
  `./setup.sh build` before reopening old sessions.

### Upgrading an existing sandbox

Nothing in a sandbox has to be rebuilt or re-initialised, and no session is lost. In order:

1. **Stop the session containers** before you use a sandbox with the new CLI:
   `podman stop $(podman ps -q --filter label=lyngvi.project=<project>)`. A container that is still
   *running* keeps the working copy it was started with, bound from the host; the move into the volume
   happens the first time the session is not running. Nothing breaks if you skip this — the copy is moved
   when the container next stops — but the session will go on working against the host directory until then.
2. **Put the new CLI in the sandbox**, the way you normally do: `setup.sh upgrade` refreshes the bundled
   files and `cli/`, or point `LYNGVI_CLI` at a development build. The images do not need rebuilding;
   `VERSION` has not changed and the base image is the same.
3. **Open the sessions you still care about.** The first time each one is used, its working copy and
   `output/` move from `.sessions/<id>/work` into the session's volume and are deleted from the host. The
   container is rebuilt at the same time — a container from before the volumes binds the host directory,
   and restarting it after the move would show the agent an empty tree, so it is replaced rather than
   started. Sessions you never open again keep their copy on the host until `setup.sh clean --work`
   takes it.
4. **Turn on what the new profile offers**, if you want it. `session.idleRemoveHours` and the `cache` block
   are read from each sandbox's own `profile.json`, which an upgrade never overwrites: without them the
   sandbox keeps its old behaviour (containers are stopped but not removed, every session downloads its own
   dependencies). Copy the two blocks from `profiles/default/profile.json`.
5. **Then `setup.sh clean`** to see what has accumulated, and `--yes` to reclaim it.

### Added
- **Skills: instructions the agent reaches for when it needs them, per sandbox.** A new «Skills» item on the
  nav rail lists the skills of the open sandbox, switches each one on or off, and edits them one at a time in
  an editor beside the list. A skill is a folder under `<sandbox>/skills/` with a `SKILL.md` whose front
  matter names it and says when it applies — the agents' own format, which Lyngvi stores as written and never
  rewrites. What Lyngvi decides is which of them an agent sees: the switched-on ones are mirrored into
  `state/agents/<id>/<skillsDir>/` (`skillsDir` is new in `agent.json`: `skills` for Claude Code,
  `config/opencode/skills` for OpenCode, which reads its global skills under `$XDG_CONFIG_HOME`), which is
  the configuration directory already mounted into every session container. The mirror is written before each
  message, so a change takes effect on the next message in every conversation, the running ones included —
  not only in the next conversation. Switching a skill off removes it from the agents and keeps the files;
  a skill with a broken front matter block is listed with the reason and never copied in. Skills set up in
  one sandbox directory are files in that directory and nowhere else, so another sandbox has its own.
  `setup.sh skill list|show|new|remove|enable|disable|sync` does the same from the command line. Four skills
  ship with Lyngvi and are laid down by `init` (and by `upgrade`, once, for a sandbox made before they
  existed): `deliver-change` (what to check before reporting that work is done, in the terms a patch review is
  done in), `blocked-host` (telling a host the proxy denied apart from a broken tool, and what to ask the human
  for), `verify-in-sandbox` (probing what the container can run, then driving it and leaving the human
  something to look at in `/work/output/`) and `gateway-call` (finding the configured internal systems in the
  environment and reading a 403 as the allowlist rather than a bug). They belong to the sandbox from then on:
  an edited or deleted starter skill is never written back. An online skill library is future work (spec §11).
- **Making a conversation shows that it is being made, and says afterwards what it did.** Creating one is a
  clone and a copy per repository, so it takes anything from a moment to the better part of a minute on a large
  sandbox — and the only sign of it was that the window did nothing. A card now comes up over the transcript
  with a spinner, the repositories being copied into `/work` and the seconds counted, from one second into the
  wait: shorter than that and it would only flash, which is worse than nothing. «New conversation» stays out
  until the conversation is open, so there is no second one on the way. When it is open, the top of the
  conversation carries a line or two about how it was set up — when the session was made, which agent answers
  in it, and for each working copy the branch and commit it was taken from, how much history came with it and
  whether your uncommitted changes are in it. That is read from the conversation itself, so it is there every
  time the conversation is opened and not only just after it was created.
- **A conversation can be given another repository while it runs.** The need for one more working copy turns
  up in the middle of the work — the change has to be made in the client too, or in the library underneath —
  and until now that meant starting the conversation again from «New conversation». A conversation's context
  menu has **«Add repository…»**, which lists the registered repositories it does not have yet, offers the
  same history choice as «New conversation», and puts the working copies in the session's volume. The whole of
  `/work` *is* that volume, so the copy appears under `/work/<name>` in the container the conversation is
  already running in: nothing is rebuilt, nothing restarted, and the agent keeps its session. Each copy gets
  its own baseline, so a delivery measures it from where it started rather than from where the conversation
  did. On the command line: `session add-repo <id> <name> … [--history] [--depth]`. Refused while the agent is
  in the middle of a turn, for a repository the conversation already has, and when `/work/<name>` is a
  directory the agent made itself. The agent is not told on its own — say in your next message what the new
  copy is for.
- **A model server of your own is offered before anything is configured, where you would look for it.** Setting
  one up was a button among five on «Setup → Agents» — reachable only with a row selected, enabled even for
  agents that cannot use one at all, and named in a help text that spoke of nothing but logging in. The one way
  of working that needs no account anywhere was the hardest one to find. The Agents page now has an **«Own
  models»** column, which says «not set up» for an agent that could use a server of yours and «N from your own
  server» for one that does; the page's help text names what such a server is (LM Studio, Ollama, vLLM, anything
  that answers like the OpenAI API) and that it needs no login; and «Local models…» is enabled only where the
  offer applies, so it can no longer open a dialog that could only fail on save. The first-run wizard's «Agent»
  step offers the same dialog beside «Log in…» and — the point of it — an agent that answers from a server of
  yours now finishes the step, which used to read «No agent is logged in» however many of your models were
  configured. `AgentInfo.canUseLocalModels` is what tells «none yet» apart from «not a thing for this agent»;
  `agent list` prints both, and `agent list --json` carries the field.
- **«New conversation» offers an agent with a model server of your own twice: «OpenCode (opencode)» and
  «OpenCode (local models)».** Both rows are the same agent, and the conversation is created the same way; the
  second one simply answers with the models from your own server and nothing else, so using your own server is a
  choice you make once instead of a model you have to pick out of the agent's catalogue every time. The row says
  nothing about being logged in — a server of your own needs no account, which is half of why it is there — and
  «All models…» is closed on it, because its list already is every model the server offers. `AgentInfo` carries
  how many models are configured (`agent list --json` shows it too), and an agent with none keeps its single row.
- **The model dropdown groups a model server of your own under «Local LLM models».** The models from the provider
  block in the agent's own configuration carry `Model.local`, and the dropdown in «New conversation» and in the
  composer puts every one of them under a heading of their own, above «Other models» and the short list of the
  rest. The handful you set up by hand were otherwise told apart from the hundreds the agent happens to know only
  by their provider prefix, in a list that shows six. A heading is a heading and not a row: it cannot be picked
  with the mouse, and the keyboard cannot leave it selected. With nothing local configured there is one group,
  which is no group, and the dropdown is exactly the short list it was.
- **«New sandbox…» is a wizard that leaves a sandbox you can work in.** The dialog used to be one form: a
  folder, a profile pack, a list of repositories, and a «Created the sandbox in …» when it was over. What it
  left behind could do nothing — `init` writes the sandbox's files but renders none of the generated ones, so
  the next thing the GUI said was «Generated files are missing», and the base image, the proxy, the gateway and
  the agent login were four more steps the user had to know about and find for themselves (the «Get started»
  checklist named them, but nothing walked through them). It is now six pages with a step rail: **folder and
  repositories** (`init`, then the `rebuild-config` a build needs), **base image** (`build`, its output in the
  dialog as it runs), **shared stack** (`up`, with the services that came up), **check** (`doctor`, one line per
  check), **agent** (the agent list and «Log in…», which opens the usual login dialog) and **ready** — a summary
  with «Start a conversation when this closes», which does exactly that. One forward button per page: it runs
  the page's work and only then reads «Next», and after a failure it reads «Try again» with the reason under the
  page and the detail in the console. A page cannot be left while its work runs. The checks and the login are
  optional — a warning from `doctor` is worth reading but is not a reason to stop, and an agent can be logged in
  later — so both can be passed by, and what was passed by is listed on the last page. Cancel at any point keeps
  what has been made: the sandbox is open in the window either way, which is why the button stops saying
  «Cancel» once the folder step is done.
- **The sandbox in the toolbar is a drop-down, and another sandbox can be opened in a window of its own.**
  The path at the top of the window used to be a label, and the only way to another sandbox was «Choose
  sandbox folder…» in the ⋯ menu and a trip through the file chooser — for sandboxes you have had open
  dozens of times. It is now a menu: every sandbox this machine has worked with (the twelve most recently
  opened, remembered in `~/.lyngvi-gui.properties` as `recent.sandbox.<n>`), the open one marked, a folder
  whose sandbox is gone left out. Picking one shows it in this window; the chat, the files pane and the
  console are emptied, and the logs and the traffic follow stop, because they belonged to the sandbox being
  left. Conversations still working are asked about first — they keep running in their containers, this
  window just stops following them, and switching back picks the live reply up again.
  Under «Open in a new window» the same list opens a sandbox beside this one instead: a second Lyngvi window,
  with its own sandbox, its own conversations and its own console, so two sandboxes can be worked on at the
  same time. The windows share nothing but the settings file; each is named after its sandbox in the task
  switcher, and closing one warns only about what it is running itself. A sandbox that already has a window
  is never opened twice — picking it raises the window it is in.
- **A picture you attach is shown in the conversation.** An attached image used to be a chip with its file
  name — which is all a screenshot pasted into the composer ever became, even though the whole point of
  sending it is that it is looked at. The message now shows the image itself, shrunk to fit the bubble and
  never blown up past its own size, and clicking it opens the file at full size in a window of its own (Esc
  closes it). Attachments that are not images, and images whose file is no longer on this machine, stay the
  chip they were. The picture comes from the copy the session keeps on the host — the one the agent reads as
  `/work/_attachments/` — and is decoded in the background at the size it is shown, so a conversation full of
  screenshots neither stalls the transcript nor holds a megabyte of pixels per picture.
- **GUI programs can be run and photographed in a session: the `xvfb` and `chromium` toolchains.** Two new
  checkboxes in the Toolchain tab. `xvfb` puts a virtual X display in the image — the entrypoint starts it on
  `$DISPLAY` — with the desktop libraries, Mesa software rendering and fonts that JavaFX, Swing, GTK, Qt, Tk
  and Electron programs load at run time, and `scrot` and `xdotool` to take screenshots and drive them.
  `chromium` is Debian's browser for page screenshots and Playwright/Puppeteer, whose own browser downloads
  the proxy blocks; its wrapper adds the flags a container with no capabilities needs. Each fragment ships a
  note for the agent (`image/fragments/<name>.md`) that is rendered into `AGENTS.md` when the toolchain is
  selected — a new `{{TOOLCHAIN_SECTION}}` in the template — and the Toolchain tab shows a fragment's header
  line as the checkbox's tooltip (`ConfigService.toolchainDescriptions`).
- **A way out that does not depend on the delivery: `session export`, and «Export ZIP» next to Git Diff in the
  chat.** Delivering changes is how work is meant to leave a session — reviewed, file by file, onto a branch —
  but when that cannot be made to work, the work still has to be reachable: the patch will not apply, the host
  repository has moved too far, the changes are the wrong shape for a patch at all. The export writes every
  working copy and the agent's `output/` to one zip on the host, with the working copies' git history unless
  `--no-git` says otherwise, so the extracted copy still knows what the agent did (`git log`, `git diff <the
  baseline>`) and can be fetched into a clone of your own. Build output and dependencies — the profile's
  `copy.excludes`, which never came into the sandbox either — are left out unless `--all` keeps them, and
  symbolic links never travel, as with `session fetch`. Nor does an exported `.git` carry its `hooks/` or its
  `config`: that `.git` is the agent's to write, the archive is extracted on your machine and the note in it
  tells you to run git there, so the two places a repository keeps programs for git to run as whoever runs it
  stay in the sandbox. `log`, `status`, `diff` and `fetch` all work without them. The archive carries an `EXPORT.txt` naming the session,
  the working copies and their baselines, what was left out, and the two commands that get the work out of it.
  Nothing is applied and no baseline moves: the delivery you could have made before the export is the delivery
  you can make after it. The archive is built by `zip` inside a helper container — `find` decides what goes in,
  so no symlink is followed and `ln -s . x` cannot make the archive grow one copy of the subtree per level — and
  only the finished archive leaves the volume, staged in the sandbox's temp area and moved into place, so a
  half-written zip never appears where you asked for it.
- **`setup.sh clean`, and a `disk` line in `doctor`.** A sandbox used for a while leaves a working copy and a
  container behind per session, and a whole set of images per version, and until now nothing counted them or
  removed them. Measured on a real sandbox: 68 working copies, 2.2 GB, for three live sessions. `clean` lists
  what can be reclaimed and removes it with `--yes`; `doctor` says when there is a lot. Three rules hold it
  up: only this sandbox's own objects (the `lyngvi.project` label, `localhost/lyngvi-*` images) are ever
  considered, nothing live is touched, and a working copy — the one place undelivered changes exist — goes
  only when its conversation is gone, or when the conversation is old *and* has delivered something, or when
  `--include-undelivered` says so in as many words. Without `--older-than` nothing belonging to a conversation
  that still exists is touched at all.
- **`session gc` now removes containers that have been idle for `session.idleRemoveHours`** (default 72), not
  just stops them. A session container holds nothing that cannot be made again — the work is in its volume,
  the credentials in `state/agents/`, the conversation in its file — so the session is untouched and the next
  message builds the container afresh. A stopped container keeps its disk; a removed one does not.
- **Shared tool caches.** Every session used to download the same dependencies into its own container,
  60–330 MB of it, because the copy filter leaves `node_modules`, `target` and the rest behind on purpose.
  The sessions of a sandbox now share one named volume per cache directory, and the number of those volumes
  does not grow with the number of sessions. The ids are a closed set in the code (`npm`, `pip`, `go-mod`,
  `go-build`, `nuget`, `m2`, `gradle`, `gradle-dists`), a profile picks from it, and only the caches whose
  toolchain the image has are mounted. `cache.mode` is `off`, `shared`, or `readonly` — the last mounts the
  cache behind an overlay, so a session reads all of it and keeps nothing, which is the mode to prefer where
  it works. `session new --cache` (and the New Session dialog) override it for one session; `off` is the right
  answer for code there is no reason to trust. The default pack shares npm, pip and the two Go caches: they
  verify what they read and tolerate several writers. Maven and Gradle are in the table but not in the
  default — Maven takes no lock across processes and does not re-check a jar it already has, and Gradle's
  stale-lock detection assumes a shared pid namespace, which containers do not have.
- `session fetch <id> [--path <dir>] [--to <dir>]` copies a directory out of a session, by default the agent's
  `output/`, now that it is not a host directory. Symlinks are dropped on the way out. The GUI's file pane
  reads and writes the same files through the volume, so browsing and editing a session's files is unchanged
  from the outside.
- Adapters that run outside the session container get `AS_VOLUME` and `AS_IMAGE`: the volume holding the
  session's files and an image to mount it with. There is no host path to the working copy any more, and
  `agents/mock-sh/adapter.sh` shows the form.
- **The macOS disk image opens on the usual window: the application, an alias to `/Applications`, and an arrow
  between them.** jpackage is given a resource directory for the `dmg` type (`packaging/macos/dmg/`) holding
  the AppleScript that lays the Finder window out and a background drawn in the brand's type and colours —
  640 × 400 with 128-point icons, rendered from `background.svg` at one and two times and turned into the
  `.tiff` jpackage wants by `tiffutil`. The volume icon is the application icon.
- The layout is not left to jpackage, which asks Finder for it in AppleScript and then says nothing when the
  answer is an error — and an error is what it gets on a Mac that has not granted the terminal permission to
  control Finder (System Settings → Privacy & Security → Automation), which is why the first images built
  this way came out plain. `packaging/macos/dmg/layout-dmg.sh`, which `package-gui.sh` runs on the finished
  image, opens it again, writes the `/Applications` link with `ln` so the drop target never depends on Finder,
  runs the same script and prints what `osascript` said when it fails. An image built without a desktop
  session — CI — is plain and installs the same way; `LYNGVI_DMG_LAYOUT=no` or `build-dmg.sh -p` asks for that
  on purpose. `build-dmg.sh` asks about the permission before building rather than after, and `-l <image.dmg>`
  lays the window out on an image already built, so granting it costs a second run of seconds, not minutes.
- `packaging/build-dmg.sh` builds the macOS disk image in one command, for a release made by hand rather than
  by `release.yml`: Maven, then the jlink and jpackage run of `package-gui.sh`, and an image named and
  checksummed the way the workflow publishes it, `lyngvi-gui-<version>-macos-<arch>.dmg`. It mounts the result
  once to see that `lyngvi.app` and its icon are inside. The app icon is the logo, `packaging/icons/lyngvi.icns`
  as before; `-i` takes another `.icns`, or a PNG converted with `sips` and `iconutil`, and `package-gui.sh`
  now reads `LYNGVI_ICON` for it.
- **The conversation list in the chat can be dragged wider or narrower.** It was 240 pixels whatever the
  conversations were called, which is too narrow for a sandbox whose sessions are named after branches and
  wasteful for one with three of them. The border between the list and the transcript is now a handle: drag it
  between 160 and 560 pixels, double-click it to go back to 240. The width is in pixels rather than a share of
  the window, so the list stays as wide as you left it when the window is resized, and it is remembered in
  `~/.lyngvi-gui.properties` (`chatSidebarWidth`) for the next start.
- **«Upgrade sandbox» can build the images where it says they have to be built.** The dialog that reported an
  upgrade ended with «Rebuild the images next» and left the user to find «build» under «Sandbox» — the job the
  upgrade had just made necessary. It now has «Rebuild now»: the build starts from the dialog, its output runs
  in a log pane that opens below the message, and a spinner and a status line say how far it has come and how
  it ended. The build belongs to the stack rather than to the dialog, so the dialog may be closed while it
  runs — it goes on, and the console keeps the output either way — and a build that fails leaves the button
  behind as «Try again» with the reason beside it. A sandbox that was already up to date is told so, with no
  build offered.

### Fixed
- **«Ask the agent» works again, and picks the agent as well as the model.** The tab hides the conversation list,
  and the only button that started a conversation sat in that list — so nothing on screen could make one, the
  composer stayed disabled with an empty model dropdown, and ⌘↵ did nothing at all. The question itself now makes
  the conversation: the composer is live from the moment the sandbox is open, and sending creates the ephemeral
  conversation and asks it in one step (a creation that fails puts the typed question back in the composer).
  Next to the model there is now an agent to pick, with the same rows «New conversation» has — one per agent,
  plus a row for an agent that has a model server of your own, which offers those models and no others. Changing
  the model goes to the conversation on screen; changing the *agent* takes effect on the next question, which
  gets a conversation of its own, since a conversation is tied to the agent that answers it. «New question» moved
  to the header, where it now only clears the board instead of starting a container for a question nobody has
  asked yet, and it waits while one is being answered.
- **«Local models…» offers the right way out when the proxy denies the server, and asks before widening the
  allowlist.** Any 403 was answered with «Open port N in the proxy», which adds the port to `localModels.ports`
  — the rule for the one private host in `profile.json`. For a model server of your own out on the network, say
  `https://llm.example.com/v1`, that is the wrong door and could never work: Squid denies it because the host is
  not in the egress allowlist. `LocalModelConfig.wayOut` now decides from the address which door is missing, and
  the dialog offers that one: the port for a private address or a name with no dot, «Allow `<host>` in the proxy»
  for a host out on the network. Allowing a host lets every agent in the sandbox reach it, so it is a question
  before it is an action; the answer goes in the user section of the allowlist, where «Setup → Domains» can take
  it out again. When the door the address needs is open already nothing is offered — the denial then came from
  the server and is about the request, usually the key. The proxy's own 403 no longer prescribes the port either;
  it names both doors. «Save» asks the same question when the host is not allowed yet, so a server configured
  without ever pressing «Test» does not fail for the first time in the middle of an ask; declining still saves.
- **A model configured in «Local models…» can be chosen for a conversation.** The models in the agent's own
  configuration file reached the catalogue only when the agent's own listing could be run and parsed, and they came
  last in it — so with a provider of hundreds of models the picker's first page (400) never reached them, and the
  short list «New conversation» and the composer offer (the starred models, or the first six of the catalogue)
  never held them. `ContainerCliAdapter` now reads the provider block itself and puts those models right behind
  the ones `agent.json` declares, whether or not `<cli> models` can be run at all, and the dialog stars what it
  saves, so the next conversation offers them without a trip through the picker. A model taken out of the
  configuration loses its star again.
- **The splash screen goes when you ask it to.** Its close button often needed two clicks, and Escape did
  nothing at all. The main window is shown under the splash while the ten-second minimum runs, and showing it
  takes the keyboard: the splash was then the window in front without being the focused one, and a desktop that
  focuses on click spends the first press on focusing the window rather than on the button under the pointer —
  so the first click did nothing visible, and the second one closed the splash. `App` now hands the focus back
  to the splash right after the main window is shown. Escape takes the splash down too, from either window: the
  filter is installed on the main window's scene as well, since that is where the key arrives while the splash
  is up, and it stands down once the splash is gone. A press anywhere on the card does the same as the button,
  which is 28 points square on a card of 480. All of them go through the same hand-over as the wait itself, so
  the first-run dialogs and the migration offer still follow exactly once.
- `apply` no longer aborts on a path the branch has already moved past. A delivery that renames files names both
  ends of every rename, and `git add` answers a pathspec matching nothing with exit 128 — so applying a delivery
  of renames a second time, or onto a branch that already holds some of them, stopped with
  `git add failed with exit code 128: fatal: pathspec '...' did not match any files` and rolled the whole thing
  back. The old name is gone from the index and from the disk alike at that point, so there is nothing left at
  that path to stage or commit: `apply` now drops such paths and carries the rest. `git commit` refuses the same
  pathspec with exit 1, so it is filtered too, and the filter asks `HEAD` as well as the index, because staging a
  deletion takes the path out of the index and the deletion still has to reach the commit.

### Changed
- **The file tree opens closed.** Every directory in «Files» was expanded when the tree was built, so opening the
  files on a working copy of any size meant a pane of thousands of lines with `target/`, `node_modules/` and
  `.github/workflows/` laid out in full, and the file you came for somewhere below them. The tree now comes up
  with the working copies and `output` closed, and you open what you need — or type in the filter, which still
  shows the hits all the way down, directories opened to reach them. A directory you open stays open for the rest
  of the session: a refresh, a save, or a filter typed and cleared again puts the tree back the way you left it
  rather than closing it. Choosing another session starts over, closed.
- **«Files» opens the files in a window of their own.** The files pane was a column inside the main window, and
  it took its width from a divider there: wide enough to read a source file meant squeezing the conversation
  beside it, and the pane could never be moved to the other screen the files belong on. Pressing «⧉ Files» (or
  ⌘/Ctrl+B, in either window) now opens a window of its own, which you give the size and the place you want —
  the first time beside the main window, and after that wherever you left it, since its geometry is remembered
  in `~/.lyngvi-gui.properties`. It has no owner window, so it can be put behind the main one; the main window
  closes it when it closes itself, and it comes back on the next start if it was open when you left. The pane
  itself is unchanged: the same tree, editing, saving and Raw/Preview, on the conversation the window has
  selected. One per main window, titled «Files — ‹sandbox›», so several sandboxes can have theirs up at once.
- **«Deliver changes» opens as large as the window it was asked from, and over it.** The dialog had a fixed
  preferred size of 1120×760 and was centred on its owner, so on a large or maximised window it came up as a box
  in the middle that had to be dragged bigger before the diff beside the buckets could be read. It now takes the
  owner window's size and position when it opens — maximised or not — and keeps it from there, so it can still be
  moved and resized like any other window. The preferred size stands for the case with no owner.
- **The confirmation before a delivery that leaves files behind is where you take them along.** «Not everything
  will be delivered» used to be a list of names and an instruction — go back to the buckets, find the file, tick
  it, press Apply again — which is three steps away from the moment you are told. The files are now listed in the
  confirmation itself, each held-back one with its own tick box, under the bucket titles they come from and with
  the reason the policy gave beside the path; a rejected file is in the same list with its box ticked off and
  disabled, so it is named for the same reason but cannot be chosen. The count at the top falls as you tick («3 of
  19 changed files will not be delivered» → «Everything will be delivered»), and the button follows: «Deliver the
  rest» while nothing is ticked, «Deliver with the ticked files» once something is. What you tick here is what the
  buckets behind show afterwards, so the dialog that is still open after a failed delivery agrees with the choice
  you just made. The list scrolls rather than stopping at twelve files and saying «…and 4 more».
- **«New conversation» remembers the repositories you ticked last time, and is tidier.** The repositories ticked when a
  conversation is created are kept per sandbox in the GUI settings (`repos.last.<sandbox dir>`), and the next dialog
  starts from that choice — «no repositories» included — instead of ticking every repository again. The dialog is in
  three groups: what the conversation is about (title, repositories, each with its host path as `~/…` beside the
  name), who answers (agent, model), and the container itself (history, limits, shared caches) folded away under one
  heading, since the sandbox defaults are nearly always right.
- **The Setup tab is one page you read top to bottom**, and it no longer asks you to save. The profile is a line of
  context at the top with no buttons: the `rebuild-config` and refresh buttons that sat beside it are the same actions
  as the toolbar's Refresh and the Sandbox tab's `rebuild-config`, and are gone from here. Then the repositories,
  the toolchains and the protected branches, in that order. Ticking a toolchain writes it to `profile.json` at once,
  so the separate «Save» button is gone and «Save and rebuild image» is «Rebuild image»; each fragment's description
  (from its header line) is printed beside its checkbox instead of hiding in a tooltip, and the status line beside the
  button says what is actually off between the ticks and the built image — «Not in the image yet: chromium, xvfb» or
  «Still in the image: maven» — instead of listing both sets and leaving the comparison to you.
- The product is now called **Lyngvi**, after the island in Norse mythology where the wolf Fenrir was bound: a
  place where great power is kept within defined boundaries, which is what a sandbox for an autonomous agent is.
  The name is written «Lyngvi» in prose and in GUI titles, and `lyngvi` wherever it is an identifier — so the CLI
  is `lyngvi`, the Maven coordinates are `no.syntakserror.lyngvi:lyngvi-*`, the Java packages are
  `no.syntakserror.lyngvi.*`, the module directories are `lyngvi-core/`, `lyngvi-cli/`, `lyngvi-gui/`,
  `lyngvi-gateway{,-api,-plugins}/`, the images are `localhost/lyngvi-base` and `localhost/lyngvi-<agent>`, and
  session containers are `lyngvi-<project>-<short id>` with labels `lyngvi.session` and `lyngvi.project`. The
  environment variables are `LYNGVI_*` (`LYNGVI_HOME`, `LYNGVI_CLI`, `LYNGVI_RELEASE_API`, `LYNGVI_RELEASE_URL`,
  the three mirror variables and `LYNGVI_SESSION`), the state and cache directories are
  `~/.local/state/lyngvi/` and `~/.cache/lyngvi/`, the GUI settings file is `~/.lyngvi-gui.properties`, the
  per-repository copy filter is `.lyngviignore`, and the gateway certificate lands at
  `/etc/lyngvi/gateway.crt` inside the container. The 1.1.2 requirements document keeps its own name and
  wording, and the migrator still reads the 1.1.2 paths it has always read (`copilot-config/`,
  `~/.agentsandbox-conversations/`, the `cplt-sndbx` subnet register), so migrating an old installation is
  unaffected.
- **Upgrading an existing 2.0.0 sandbox needs a few manual steps**, because the rename moves names that live
  outside the sandbox directory. Rename `AGNTSNDBX_*` to `LYNGVI_*` in shell profiles and CI; move
  `~/.local/state/agntsndbx/` to `~/.local/state/lyngvi/` to keep the subnet register (otherwise a new sandbox
  can be allocated a subnet an old one already uses) and `~/.agntsndbx-gui.properties` to
  `~/.lyngvi-gui.properties` to keep the GUI's theme and last sandbox; rename any `.agntsndbxignore` in your
  repositories; then run `./setup.sh upgrade` and `./setup.sh build` to re-render the bundled files and rebuild
  the images under the new names. Session containers created before the rename keep their `agntsndbx-` names:
  conversations address them by the name they stored, so a running session still works, but `session list`,
  `gc` and the orphan cleanup only match the new prefix and will not see them. Stop and remove those containers
  (`podman rm -f $(podman ps -aq --filter name=agntsndbx-)`) before upgrading, or remove them by hand afterwards.
- **The application icon has a background.** It was the bare mark on transparency, so the navy wolf had whatever
  the desktop put behind it — a dark dock, and the icon was a smudge with a cyan ring around it. The mark keeps
  its own colours and now sits on a rounded square of a pale ice blue, which the navy and the cyan both read on,
  so the icon looks the same wherever it is shown. The window icons, `packaging/icons/lyngvi.png` and the `.icns`
  jpackage uses on macOS are all regenerated; see `docs/branding/README.md`.

## 2.0.0 - 2026-09-06

### Added
- A splash screen while the GUI starts. Launching the application used to show nothing at all until the main
  window was ready — settings, the sandbox context, the viewmodels and `main.fxml` take a moment, and a desktop
  launcher gives no feedback of its own — so a second click on the icon was a reasonable thing to do. The splash
  is the mark, the name, the tagline and the version on a card in the theme the settings hold, undecorated and
  centred on the primary screen. The tagline is the same line the installer describes the product with, and it is
  shown in the language of the sandbox that was open last — the language is a sandbox setting, and opening one is
  the work the splash is covering for, so the GUI settings keep it (`language` in `~/.lyngvi-gui.properties`,
  written whenever the open sandbox changes) and a first-ever start is in English. It stays up for ten seconds at
  the least — a start quicker than that
  waits out the rest of them — and then fades out over the main window, which is also when the first-run dialogs
  and the migration offer open, since they are modal and the splash is always on top. A close button in the
  top-right corner is the way past the wait: it takes the splash down at once and lets the same things follow.
  The mark is drawn at 192 points from a splash copy of the artwork with no icon padding and at 512 px, decoded
  at as many pixels as the screen can draw, and it sits on a pale disc: the wolf is navy on nothing, and on the
  dark theme nothing but its cyan ring came through without a light surface under it.
- Resource presets: limits are now chosen by what the session is about to do, instead of by one number that has to
  suit reading a diff and building a monorepo alike. The profile owns the list (`resourcePresets` in
  `profile.json`), and the bundled profile ships three — «review» (2g, 1.0 cpus), «dev» (8g, 4.0, the defaults as
  they were) and «build» (16g, 6.0) — each a whole set of the four limits, with `memswap` at the 1.5 x `mem` the
  advice asks for and `pids` well above what a JVM or a bundler starts. A profile that names none keeps the
  built-ins, so an installation upgraded from an older `profile.json` changes nothing. A fourth is derived from the
  machine itself rather than shipped: «this machine» takes three quarters of its memory and every core, for the one
  heavy session with nothing else running. The picker sits in the Resources tab (it fills the four fields, and
  nothing is saved until Save, so a preset can be tried against the warnings first) and in «New conversation», and
  the limits that are already saved show which preset they correspond to, or none when they are the user's own. On
  the command line: `resources presets`, `resources set --preset <id>` and `session new --preset <id>`, where a
  single `--mem` or `--cpus` on top of a preset still wins. The Resources tab and `resources machine` also say how
  many sessions of the chosen size the machine holds at once — the number that matters when several conversations
  run side by side, since the limits are caps rather than reservations and it is the machine that runs out. Only
  memory counts towards it: a CPU quota is time-shared, so more sessions than cores merely run slower.
- GUI «Local models…» (Agents): the model server on the developer's own machine is configured where the agent is,
  instead of by hand in the agent's own configuration file. The dialog holds the address and the model ids, and —
  since a local model is declared rather than discovered, and an id that is not exactly the one the server reports
  fails in the middle of an ask — it can ask the server itself: «Test» marks every listed model as on the server or
  not, and «Add from server» takes the ones it has and the list has not. The question it answers is the one that
  matters: not «is the server up on my machine», but «can the agent reach it» — the request goes from a throwaway
  container on the internal network through the proxy, so a denied port (403, pointing at `localModels.ports`) and
  a server that is not listening look different. Saving keeps everything else in the file — other providers, and
  the fields on a model the dialog knows nothing about — and drops the cached catalogue, so the models are offered
  at once. An agent is offered the dialog by declaring `localModels` in its `agent.json` (provider, config file,
  default address); opencode does, Claude Code does not. An address that a container cannot use is refused with the
  reason, `localhost` above all: inside a container that is the container itself. A denial is the one failure the
  user can undo from there, so it is offered as an action rather than as instructions: «Open port N in the proxy»
  adds the port to `localModels.ports` in `profile.json`, re-renders `squid.conf`, reloads the running proxy and
  tests again — a model server on a port nobody thought of (an MLX server on 8188, say) is otherwise a hand edit,
  a `rebuild-config` and a proxy reload away from working. A server that wants an API key gets one: the dialog has
  a password field, and the key is kept in `state/agents/<id>/env` as the variable the agent reads it from
  (`agent.json` `localModels.apiKeyEnv`, `LMSTUDIO_API_KEY` for opencode) — with the agent's other credentials,
  mounted only into that agent's containers, and not in the model configuration file, which is the one a user is
  likely to copy or paste somewhere. It is never read back into the window, and never becomes a command-line
  argument: the test hands it to the container as an env file, and a key typed but not yet saved is written to a
  private file for that one run and removed again. A server that refuses the key (401, or a 403 that did not come
  from the proxy) is now told apart from a port the proxy has closed.
- Models running on the developer's own machine (Ollama, LM Studio, and MLX servers such as oMLX that speak LM
  Studio's OpenAI-compatible API) can be used and, above all, picked. Three things were in the way. The proxy let
  `host.containers.internal` through on port 11434 only, so anything but Ollama was denied: the host and its ports
  are now `localModels` in `profile.json` (`11434` and `1234` by default, an empty `ports` list closes the host
  off), and the throwaway containers that fetch the model catalogue and log in get the same `host.containers.internal`
  mapping a session container has. opencode offers a model only when it knows it — models.dev knows three LM Studio
  models and none of the ones a local server has loaded — so `agents/opencode/opencode.example.json` ships as the
  starting point for `state/agents/opencode/config/opencode/opencode.json`, where the provider's `baseURL` and the
  models are declared; they then appear in `agent models opencode --refresh` and in the GUI picker as
  `lmstudio/<model>`. And a provider that lives only in that file now counts as configured, so its models are sent
  to it as they are instead of being re-routed to OpenRouter the way a bare `vendor/model` slug is.
- GUI baseline picker («Git Diff» and «Deliver changes»): every delivery made in the conversation is offered as a
  baseline, labelled with its time and branch («Delivery 2026-09-07 11:40 (agent/login)»), so the changes since any
  one of them can be seen and delivered — not only since the session start or the last delivery. The picker opens on
  the newest delivery, and its values are the delivery indices the core's `--from` takes. «Git Diff» offered only
  `start` and `last`, and «Deliver changes» listed indices `1..n` while the core counts them `0..n-1`, so its last
  entry was rejected and its first delivery could not be picked at all. The choices are built in one place
  (`BaselineChoices`), so the two windows cannot disagree.
- GUI chat: ⌘/Ctrl+V in the composer attaches what is on the clipboard. An image (a screenshot, or an image copied
  in a browser) is written as a PNG named `pasted-<date>-<time>.png` and shown as an attachment chip; copied files
  are attached as they are, the same as dropping them on the composer. Text still pastes as text — a clipboard that
  holds any text is left to the text area, because several apps offer an image flavour alongside the text they copy.
- Model selection: an agent with a `commands.models` template in its `agent.json` now has that command run, so the
  models offered are the ones the agent actually has rather than the handful declared in the descriptor. opencode
  declares `opencode models`, and its `provider/model` lines are parsed into the catalogue; the declared models stay
  in front, keeping their labels and the default. The catalogue is cached in `state/agents/<id>/models.json` and
  re-read on demand (`agent models <id> --refresh`, or the refresh button in the picker); anything that goes wrong —
  no image, no network, an unexpected output format — falls back to the declared list.
- Favourite models: starring a model in the GUI puts it at the top of every model list and in the composer's own
  short list, so the models you actually use are one click away. The star sits next to the model in the composer;
  «All models…» opens a searchable picker over the whole catalogue where every row can be starred. Favourites are
  per agent and are kept in `~/.lyngvi-gui.properties`, along with the model last used with each agent, which a
  new conversation starts from. Before anything is starred the lists show the models the descriptor declares.
- GUI chat: any text in a conversation can be selected with the pointer and copied, the way a page in a browser is.
  Press and drag over whatever the transcript shows — half a sentence, a heading, a tool call's title, a diff line —
  and the selection follows the characters, across bubbles and scrolling on past the edge of the viewport. A double
  click takes the word under it — a path, a file name or a model id counting as one word — a third click the line,
  shift-click extends it, ⌘/Ctrl+A takes the whole transcript, ⌘/Ctrl+C or «Copy selection» copies exactly what is
  highlighted, and Esc or a plain click clears it. Copied text reads as the transcript reads: runs that share a line
  stay on it, a new line is a newline and a new bubble a blank line. «Copy message» still takes the whole message as
  it was written — the author line, the message with its markdown, what the agent said between tool calls, each tool
  call as its one-line title, attachments and the stop notice. Selection used to be a whole bubble at a time, which
  meant a command or a path could only be copied by taking the message around it. Tool output, the thinking block
  and the diff lists keep their own selection and their own copy, and a link in a message is now ordinary text (with
  the click that opens it) so that it can be selected along with the sentence it sits in.
- GUI chat: a markdown table in a reply is laid out as a table instead of as the pipes it was written with. The
  header row is set apart with a rule under it, the columns line up and follow the `:---` / `---:` alignment the
  table states, and the cells carry their inline markdown — bold, code, links — like any other text. An agent that
  compares versions, lists files with sizes or sums up a usage report writes one of these, and a row of pipes read
  as prose is where the eye gives up. The columns are as wide as they need to be, and no wider, so a two-column
  table does not stretch across the conversation; when they do not all fit the bubble, the width is taken off the
  wide column first — the prose column wraps while «Size» and «Status» stay readable — so a wide table narrows with
  the window instead of pushing the conversation sideways. A table is a table only when a dashed row states the
  columns, as GitHub's markdown has it, and a row that states too few or too many cells is filled out or cut to the
  header's width instead of bending the grid. Selecting across it copies a row as a row, and «Copy message» still
  takes the markdown as it was written, pipes and all.
- GUI Files: a markdown file is shown rendered instead of as source, and that is now the first view of a `.md`
  file — a README, a changelog or a specification is written to be read, and the pane opened it as a wall of
  `#` and `|`. «Raw» is a click away for editing, and once Raw or Preview has been chosen the pane keeps that
  choice for every file opened after it, so nobody who came to edit has to click twice per file. The rendering is
  the one the chat uses, tables included, and so is the text selection over it: a paragraph, a command or a table
  row in a preview can be marked with the pointer and copied with ⌘/Ctrl+C or «Copy selection», while the
  markdown area's own «Copy» still takes the file as written. The Raw/Preview toggle now belongs to the file it
  was shown for — an image or a binary no longer inherits the last file's — and a document too long to lay out
  (tens of thousands of text nodes, which is seconds of work and no use to anyone) is rendered as far as 100 kB,
  cut at a line break, with a line under it saying that Raw has the rest.
- GUI chat: the conversation list says which repositories each conversation works in. A sandbox collects the work
  on every project the user has registered, and the title alone does not say which one a conversation belongs to —
  «fix the spacing» is a different job in each of them, and the only way to find out was to open it. The names now
  sit as small tags between the title and the line under it, where the eye passes anyway. Two of them fit the
  sidebar side by side; a conversation that spans more shows the first two and «+n», and the whole list is in the
  tooltip, as is a name too long for the width. A conversation with no repository — «Ask the agent» — keeps its two
  lines and gets no tag row. Searching the list already matched repository names; now the matches say so.
- Migration from 1.1.2 (M10): `setup.sh migrate` (and the GUI when a 1.1.2 folder is opened) converts a sandbox in
  place, idempotently (`state/.migrated-from-1.1.2`): `sandbox.config` gains the schema-2 keys; a profile pack
  `profiles/migrated/` carries the allowlist, the 1.1.2 toolchains, the language and the Copilot enterprise URL;
  the gateway manifest goes from schema 1 to 2 with one instance per adapter (plugin type = old id, hostname and
  aliases kept, `<ID>_BASE_URL` added to the secret file, allowlists renamed); conversations from
  `copilot-config/conversations/` and `~/.agentsandbox-conversations/conversations.json` become schema-2
  conversations of the `copilot-cli` agent; `.sessions/<id>/out` becomes `patches/`, `copilot-output-files/` is
  copied into every session's `output/`; `copilot-config/` moves to `state/agents/copilot-cli/`; the subnet register
  is carried over; generated files are re-rendered. Verified against a synthetic 1.1.2 sandbox (G2, G6).
- Packaging (M11): `packaging/package-cli.sh` builds a jlink CLI image with an AppCDS archive and a
  `tar.gz` + `.sha256`; `packaging/package-gui.sh` builds the GUI with jpackage on a jlink runtime that also holds
  the CLI; `.github/workflows/release.yml` builds both for macOS arm64/x86_64 and Linux x86_64/arm64 on a `v*` tag
  and publishes a release: the CLI image, the GUI app image as `tar.gz`, a `.dmg` on macOS and a `.deb` on Linux,
  each with a `.sha256`. `setup.sh --download` (via the bundled `scripts/fetch-cli.sh`) fetches the platform image
  into `cli/` and verifies the checksum; a launcher without a runtime prints the exact download commands.
  `version --check` asks the release API (`LYNGVI_RELEASE_API`) for a newer version. `init`/`upgrade` link a
  sandbox's `cli/` to the runtime image when the CLI runs from one (the GUI app or a downloaded image), and
  `doctor` fails when `cli/` is not the running version.
- Hardening and acceptance (M12): `doctor --deep` runs an egress self-test from a throwaway container (allowed
  host through the proxy, denied host, direct access, gateway); `acceptance/run.sh` automates 27 criteria of spec
  §10 and 1.1.2 Del F and passed 27/27 against a real Podman stack; `.github/forbidden-terms.txt` with a CI grep and
  a unit test enforce G6; docs for adapters, gateway plugins and profile packs; README covers install, upgrade and
  migration.

### Changed
- GUI: the conversation list shows what each conversation has spent so far (tokens and dollars), and the chat bar
  shows the accumulated spend of the open conversation instead of the last turn's cost, updated live while a turn
  runs. Accumulated token counts are shown in millions with an `M` suffix (`1.23M`), also in the usage view's
  token columns. `session list --json` and `session status --json` carry the same numbers as `usage`.
- Version 2.0.0. Images are tagged with it: run `setup.sh upgrade` and `setup.sh build` in existing sandboxes.
- GUI diff views («Git Diff» and «Deliver changes»): text is selected and copied the way a page in a browser is.
  Press and drag over the lines and the selection follows the characters, across lines and on past the edge of the
  viewport; a double click takes the word under it — a path or an identifier counting as one word — a third click
  the line, ⌘/Ctrl+A the whole file, ⌘/Ctrl+C or «Copy selection» copies exactly what is highlighted, and Esc or a
  plain click clears it. The gutter and the +/− are never part of what is copied, and a blank line stays a blank
  line. Selection used to be a whole row at a time, so a path or a call could only be copied by taking the lines
  around it; the row selection is gone with it, and a diff is now text rather than a list of choices. «Copy file»
  in the file header, and in the context menu, puts the whole file as it is after the change on the clipboard: the
  view shows hunks, which are not the file, so the core is asked for it in full and the new side of that patch is
  what is copied. A deleted or binary file has no «after», and there the button is not offered.
- GUI «Git Diff»: the changed files are listed on the left (badge, name, directory, +/− counts); the selected one
  is shown on the right in a list that scrolls on its own. A «Whole file» toggle shows the complete file with the
  changes coloured in place instead of the changed hunks only; `DeliveryService.diff` gained a `wholeFiles` flag
  (unlimited hunk context, still an ordinary unified diff) for it.
- GUI «Deliver changes»: the dialog shows the diff, not just a count of it. It is split — the three buckets on the
  left, and on the right the selected file drawn by the same view «Git Diff» uses: both line numbers in the gutter,
  the context around each hunk, the hunk headers, and the «Whole file» toggle. The question the dialog asks is
  whether a change should land on the developer's machine, and it used to be answered on a path and a pair of line
  counts, with what the lines actually say a separate window away. A row now carries the file name with its
  repository and directory beneath, each bucket's title carries its count — so a held-back or rejected file is not
  invisible behind a collapsed pane, and a bucket opens by itself when there is nothing to apply — selection is
  shared by the three lists, survives a refresh or a change of baseline, and the dialog can be resized.
- GUI «Deliver changes»: what the delivery leaves behind is said where it cannot be missed. A warning stands above
  the buckets whenever a file would stay back — «3 of 19 changed files will not be delivered» — and names the
  reasons the policy gave («2 held back by the sandbox policy (executable, binary)», «1 rejected and never
  delivered (path outside the repo)»), with «Show them» opening the first such file and its diff. The held bucket
  opens by itself, it and the rejected one carry a ⚠ in their titles with their counts, and ticking a held file
  updates the warning as the developer goes. Apply then asks before it runs, naming every file that would stay
  behind one by one, so the choice is «go back and tick them» or «deliver the rest» rather than a silence. The
  delivery's own result says it too, in the GUI and on the command line: «api: 1 of 4 changed file(s) were not
  delivered (held back, rejected or not selected)». A held-back file was previously a line in a collapsed pane,
  which is no way to be told that the change under review is not the change that lands.
- GUI chat: what the agent says between tool calls (`work-text`) sits on a faint tinted panel with a thin accent stripe
  at the left, so it stands out from the surrounding tool cards without heavier type.

### Fixed
- GUI chat: text in a code block can be selected from inside the block, not only by dragging into it from the line
  above it — and the same goes for the lines inside a tool call's pane. The transcript handles the pointer itself,
  and it did so in a handler, which runs on the way up: a JavaFX control's own input map also handles
  `MOUSE_PRESSED` on the way up and consumes it, even when all it did with the press was take the focus, so a press
  that landed in one of the controls the transcript holds never reached the transcript. A code block scrolls its
  long lines in a scroll pane of its own and a tool call is a titled pane, and both do this, which made the middle
  of a code block the one place in a conversation that could not be marked. The pointer is handled in filters now,
  which run on the way down, before any control inside the transcript sees the press; a scroll bar and the title of
  a tool call's pane are left to themselves, since a drag there is the thumb being dragged, or the click that opens
  and closes the pane. What the block shows of a line is also what that line is hit-tested and highlighted on: a
  long line reaches past the block, so a point to the right of a sideways-scrolled block used to take a character
  hidden behind the block's edge, and the highlight for it was drawn over whatever stood beside the block
  (`TranscriptCopy.clamp`, unit-tested with the rest of the selection's arithmetic).
- GUI «Deliver changes»: ticking a held-back file no longer delivers that file *instead of* all the others.
  `ApplyOptions.includes` is the whole delivery as far as the core is concerned — `--include` on the command line
  means «deliver exactly these», not «these as well» — and the dialog sent nothing but the ticks, so one ticked
  binary would have gone out alone while every reviewed source file stayed behind without a word. The dialog now
  spells out the apply bucket along with the ticks, and prefixes each path with its repository when the session
  has more than one, which is how the core tells two repositories with the same path apart.
- Delivering a second time in the same conversation no longer needs a commit on the host first. `apply` began by
  stashing a dirty host working tree, which is exactly wrong once a delivery has already gone out: the patch is
  measured from the working copy's baseline, and after `--mode stage` — the GUI's default — the host holds that
  baseline only in its working tree, staged and uncommitted. The stash took the patch's pre-image with it, so
  `git apply` was handed the state from before the previous delivery and failed, the three-way fallback merged the
  previous delivery against itself into conflict markers, and `git stash pop` then refused to restore into an
  unmerged tree — whereupon the stash entry was dropped and the user's local changes were gone. Removing a file
  that an earlier delivery had added failed outright with «does not exist in index»: the stash had taken the file
  away, and a deletion needs the file the patch deletes. The dialog said «no conflict» while all of this happened,
  because `review` checks the patch against the working tree as it is, which is precisely the tree `apply` then
  stashed away. Now the dirty state is recorded with `git stash create` — a commit that leaves the working tree
  untouched — and the patch is applied on top of the host as the user has it. Stashing is left for the one case it
  was meant for: a branch switch git itself refuses because the local changes would be overwritten. A failed apply
  rolls back from the snapshot (and removes the files the patch created, which no reset removes), so nothing of the
  user's is lost either way, and `git stash pop` never drops an entry it did not restore. Two more consequences:
  `--mode commit` commits the delivered paths only, so local work the user had staged before the delivery stays
  staged instead of being swept into the delivery commit; and a file the patch touches that the user has edited
  without staging no longer blocks the delivery — `git apply --index` refuses such a file, so the change is applied
  to the working tree and staged, with a notice that the user's own edit is staged with it.
- Work the agent left staged in the working copy but did not deliver is no longer swallowed by the delivery. The
  commit that marks the delivery in the copy committed the whole index, so anything staged there became part of the
  baseline for the next review and disappeared from it. It now commits the delivered paths only.
- GUI chat: an OpenCode turn no longer grows an empty text panel between its tool calls. `opencode run --format
  json` prints one line per finished part, and it opens a text part when the model starts a text block and closes
  it when the block ends — even when nothing was written, which is every step that went straight to its tool
  calls. Those parts arrive as `{"type":"text","part":{…,"text":""}}` and became a `work-text` panel with nothing
  in it, one per step; opencode's own printer trims the text and drops the empty ones. The mapper now does the
  same, for reasoning parts as well, and Claude Code's empty text blocks are dropped the same way. Two guards
  behind it, so conversations already recorded read right too: a blank text delta starts no timeline segment, and
  the work log renders no panel for a blank text item. Verified against the pinned CLI, opencode-ai 1.18.29.
- OpenCode no longer has tool calls fail for no visible reason. `opencode run` answers permission requests itself,
  and without `--auto` its answer is always `reject` — the tool then comes back with «The user rejected permission
  to use this specific tool call», which reads like a person denied it, in a container where nobody was asked. The
  default ruleset allows every tool inside the working directory, so most of a turn works and only some calls fail:
  what it asks about is any path outside the working directory (`external_directory`) and reading `.env` files. A
  write to `/home/agent/.m2/settings.xml` is the case that showed it. The reason was invisible as well as wrong —
  opencode prints «permission requested: …; auto-rejecting» as plain text on stdout, which the JSON event mapper
  drops. `commands.ask` and `commands.suggest` now pass `--auto`, which is opencode's counterpart to Claude Code's
  `--permission-mode bypassPermissions`: the container is the permission boundary for both. Verified against the
  pinned CLI, opencode-ai 1.18.29.
- The model picked for a turn no longer reverts while the conversation runs. Two causes: the model was never stored
  on the conversation, so it stayed whatever was set when the conversation was created; and the chat list is
  refreshed every five seconds, which produces a different `SessionInfo` for a running conversation (busy, tokens,
  `lastActiveAt`) and so fired the selection listener, re-opening the conversation and resetting the composer.
  `SessionService.ask` now stores the model the turn runs with — meaning a following turn that names no model
  resumes on it instead of silently falling back to the agent's default — and re-selecting the conversation that is
  already open no longer re-opens it. Re-opening the same conversation (as a finished turn does) keeps the model
  already picked for the next turn.

## 2.0.0-M9 - 2026-09-05

### Added
- The JavaFX GUI (M9a–M9d) as `lyngvi-gui`, a thin shell over the core (spec §9). A viewmodel layer
  (`SandboxContext`, `StackViewModel`, `ConfigViewModel`, `SessionViewModel`, `AgentRunBinder`,
  `GatewayViewModel`, `AgentsViewModel`, `ChatActivity`, `Settings`, `AppLog`, `Changelog`, `ProblemReport`,
  `Errors`) uses only `javafx.base`, runs core calls on virtual threads and marshals to the FX thread, so it
  is tested against the real core (temp sandbox, real git, scripted podman, the mock agent) without a display.
- Main window with toolbar, nav rail with chat badges, hidden tabs, console and files pane; Sandbox
  (Run, Resources), Traffic, Agents (login dialog that opens URLs and shows codes), Gateway (one pane per
  instance with secret and allowlist, «Add system…» from the plugin list, status, restart, rebuild,
  certificate, plugin install), Agentic coding (conversation list, «New conversation» dialog with repos,
  agent, model, history and limits; live bubble with markdown, «Thinking», tool calls and usage; stop;
  attachments; «Git Diff» window; «Deliver changes» dialog with baseline, three buckets, held files to
  include, suggested branch and message, commit or stage), «Ask the agent» (ephemeral session without
  repositories, removed on discard), Repositories, Domains, Profile, Toolchain (fragment checkboxes),
  welcome checklist, «What's new» from the bundled changelog, problem report zip without secrets,
  light and dark themes.
- GUI strings live in the shared core bundle under `gui.*` (English base, Norwegian translation).
- Contract tests: `FxmlBindingTest` (every `fx:id`, handler, import and `%key` resolves), `ArchitectureTest`
  (no `ProcessBuilder`, no core internals from the GUI; views only reach the core through viewmodels),
  `SeverityStyleTest`, plus parser tests for markdown, unified diffs, the work log and usage formatting.
- Core additions for the GUI: cancellable `StackService.streamLogs`/`followBlocked`,
  `ConfigService.availableToolchains|toolchains|setToolchains`, `BlockedRequests.remove`, `Messages.bundle()`.

### Fixed
- A GUI running with an older core no longer crashes on a message key the core lacks: the view shows `!key!`
  and logs one warning. The error dialog for uncaught exceptions no longer stacks on top of itself.
- Session containers are now visible and kept tidy: the Sandbox tab lists every session container with its
  conversation, state and image, marks containers whose conversation is gone, and offers «Clean up». The profile's
  `session.idleStopMinutes` is enforced while the GUI runs (idle containers are stopped, orphans removed, every five
  minutes and at start-up), and `session gc` removes orphan containers as well.
- `Messages` used a `ResourceBundle.Control`, which named modules reject; the GUI runs on the module path
  and hit it. Locale resolution is now explicit (`nb` gets its bundle, everything else the English root).

### Verified against a real Podman machine (macOS, Podman 6.1.1, rootful default connection)
- `doctor`, `up`, `status`, `down`; the proxy and the jlink gateway image build and start; egress from the internal
  network: allowed host 200, denied host and direct access blocked, `domain blocked` lists the denial (S1–S4).
- A gateway instance (`http` plugin against the npm registry) reached by hostname through Squid from a container:
  allowed path 200, path outside the allowlist 403, `POST` 405 (S6–S9, G7).
- The base and Claude Code images build (Claude Code 2.0.30 pinned); a session container starts from the agent
  image with the mapped user, `/work` layout, proxy environment, gateway URL and a writable working copy (S5, S17).

### Added (after the Podman run)
- `upgrade`: refreshes the sandbox's bundled files (images, fragments, proxy, templates, agents, gateway runtime and
  plugins) to the running version, records it in `VERSION` and re-renders; user-owned files are untouched. `init`
  writes `VERSION` too. `doctor` warns (`bundle`) when bundled files differ from the running version, and the GUI
  shows «Upgrade sandbox» in that case. This is the M10 upgrade path brought forward: fixes to bundled files never
  reached existing sandboxes, because `init` copies them only once.

- `init` and `upgrade` write the sandbox's own `setup.sh` and copy the running CLI jar to `<sandbox>/cli/`, so
  every sandbox is self-contained (spec §4.6). Opening a directory that is not a sandbox now says so and points at
  `--sandbox <dir>` / `LYNGVI_HOME` instead of failing on a missing `profile.json`.
- `.gitignore`: the `cli/` pattern (meant for the sandbox runtime directory) also matched the CLI source package
  `lyngvi-cli/src/.../cli/`, so those sources were never tracked. It is now `/cli/`; add the CLI sources on the next commit.

- GUI login opens the agent's interactive login in a terminal window (macOS Terminal; on Linux the first of
  x-terminal-emulator, gnome-terminal, konsole, xterm) through a generated script that carries the augmented PATH and
  the Podman machine socket, and the dialog polls the login state until the agent is logged in. Claude Code's login
  is a full-screen TUI that cannot run inside a dialog; running it attached to the JVM's own terminal was wrong from
  the GUI. `AgentAdapter.loginCommand` exposes the interactive argv; `agent login` in the CLI is unchanged.
- Claude Code 2.1.261 has `auth login|logout|status`: the subscription login runs `claude auth login --claudeai`
  in the terminal window (no onboarding, no `/exit` needed); logout runs `claude auth logout`.

- Claude Code pinned to 2.1.261: the 2.0.30 build resolves the `opus` alias to a model the API has retired
  (`404 model: claude-opus-4-1-20250805`). Aliases `sonnet`/`opus`/`haiku` stay the model list.
- Dynamic message keys (`gui.chat.stop.*`, `gui.deliver.reason.*`) were missing from the bundles and crashed the
  transcript on a failed turn; added, covered by the bundle test, and uncaught FX-thread exceptions now show a dialog.

- Work log: tool refreshes after a reasoning block overwrote tool entries and duplicated the «Thinking» pane at the
  end; the model now keeps one reasoning slot first and updates tools in place, the pane updates children by key
  so an expanded tool stays expanded during the run, and empty (redacted) thinking blocks show nothing.

- A turn is stored as an ordered timeline: the message's `work` list now holds `text` items between the `tool`
  items, so what the agent said between tool calls is shown in place (and `text` joins the segments with blank
  lines instead of gluing them). Tool panes are titled with the field that matters (`command`, `file_path`,
  `pattern`, ...) at full width instead of the truncated raw JSON.

- Toolchain tab: the base image is labelled with the toolchains it was built with, the tab shows «Image has … /
  selected …» and marks «Save and rebuild image» as the primary action when they differ, with progress and the
  result in the tab; a note explains that existing conversations keep their container until stopped.

- Claude Code model list gains the `fable` alias (Claude Fable); both the alias and the full id
  `claude-fable-5-1` were verified against the API. Claude Code has no command that lists models, so the list
  stays in `agents/claude-code/agent.json`; the model box in the GUI also accepts any full model id typed in.

- Chat bubbles no longer widen past the transcript: tool panes, text areas and markdown blocks may shrink (titles
  ellipsize, output wraps) and long code lines scroll inside their block; the transcript has no horizontal scrollbar.

- Protected branches are the sandbox's choice: `profile protected list|add|remove` and the Profile tab edit
  `git.protectedBranches` in the sandbox's profile.json, and the delivery dialog offers the repository's existing
  branches (current first) or a new name, tells whether the choice is existing, new or protected, and on a protected
  branch asks whether to allow it for this sandbox and then delivers. The implicit rule that the remote's default
  branch is always protected is gone; the list decides.

### Fixed
- Delivery when the host changed the same files since the baseline: `git apply --3way` failed with «repository lacks
  the necessary blob» because the baseline blobs live in the working copy (and never in the host after an
  uncommitted overlay or without history), so the whole delivery was refused. The pre-image blobs are now imported
  into the host repository first and three-way merge is used for every history mode: clean hunks land, real
  conflicts stay as markers to resolve. The failure message, when it still happens, is one line with details folded.
- macOS: the podman CLI talks to its machine over SSH by default and fails when `~/.ssh/known_hosts` is malformed
  (Podman Desktop keeps working because it uses the API socket). The core now sets `CONTAINER_HOST` to the running
  machine's API socket for every podman command unless the user set `CONTAINER_HOST`/`CONTAINER_CONNECTION`, and
  `doctor` reports a malformed `known_hosts` line by number with the fix.
- Base image: `groupadd` failed when the host GID already exists in Debian (GID 20 on macOS); the user step now
  reuses an existing group, as the gateway image already did.
- Squid: `pid_filename none` made `squid -k reconfigure` fail, so allowlist and gateway-host changes were only
  picked up on restart. The config now names a pid file, `reloadProxy` falls back to a HUP of the Squid process,
  and adding or removing a gateway instance reloads the proxy.
- `gateway restart` recreated nothing: `podman restart` keeps the old environment, so a new instance's secret file
  was never loaded. It now recreates the service through compose (`up -d --force-recreate --no-deps gateway`).
- The gateway copied HTTP/2 pseudo-headers (`:status`) from upstreams into its HTTP/1.1 response; Squid rejected
  those as malformed (502). Pseudo-headers are dropped.
- Gateway hostnames are plain HTTP through the proxy (Squid reverse-proxies them and denies `CONNECT` by design);
  AGENTS.md, `gateway list` and the GUI said `https://`.
- OpenCode pin 0.8.5 does not exist on npm; pinned to 1.18.29. Its command templates still need verification.

### Added (usage and usage limits)
- A usage view in the GUI: **Agents → Forbruk** picks an agent and shows what its account reports (plan, one
  bar per quota with warn at 75 % and danger at 90 %, reset times) above what this sandbox has spent on it —
  turns, input, output, cache, cost and context, rolled up per model and per conversation. The «Forbruk»
  button on the agent list opens the view for the selected agent instead of dumping a raw map into a dialog.
- `AgentUsage` is typed (`plan`, `note`, `resetAt`, `limits`) instead of an untyped map, with `UsageLimit`
  carrying `used`/`limit`/`unit`/`resetAt` and the warn and danger thresholds. Adapters keep the same
  `usage` action; members outside the known ones are kept in `extra` and still shown.
- `AgentService.usageReport(id)` and the `AgentUsageReport` contract: one core operation behind both
  front-ends. `lyngvi agent usage <id>` prints the quotas and the sandbox roll-up, `--json` the record.
- The mock agents (built-in `mock` and `agents/mock-sh`) report a sample account, so the view can be tried
  without a real agent.
- Claude Code 2.1.x exposes no account usage headlessly (`/usage` is interactive only), so `claude-code`
  keeps `capabilities.usage: false`: its card shows the sandbox roll-up and says the account part is not
  reported. Give the adapter a `usage` action when the agent gains one.

### Not included
- Syntax highlighting in the file editor and PDF preview (1.1.2 used RichTextFX and PDFBox; the GUI has no
  third-party dependencies beyond JavaFX). `Upgrade sandbox` waits for M10; packaging (jpackage) is M11.

## 2.0.0-M8 - 2026-09-05

### Added
- The gateway sidecar in Java (M8). `lyngvi-gateway-api` is the plugin contract with no dependencies
  (`GatewayPlugin`, `Route`, `Request`, `Response`, `PluginContext`, `Upstream`, `AllowlistResolver`,
  `HealthProbe`, the manifest records). `lyngvi-gateway` is the runtime on `jdk.httpserver`: HTTP,
  an alternate port and HTTPS from the sandbox's PEM certificate; routing by `Host` or `/<id>/` prefix;
  the order unknown host 404, method gate 405, unconfigured 503, allowlist 403, then the plugin; an
  origin-locked upstream client that injects the secret (`bearer`, `basic`, `header:<n>`, `query:<n>`)
  and never forwards the caller's `Authorization`; allowlist files re-read on change with a five-minute
  cache for entries that need an upstream lookup; secrets from the environment or `<ENV>_FILE`, masked in
  every log line; `/healthz` (and `?deep=1` for upstream probes); corporate CAs from `CA_CERT_DIR`.
- Plugins are jars: one `URLClassLoader` per jar, `ServiceLoader` for the types it declares; broken jars,
  duplicate types and unsupported `apiVersion`s are skipped with a warning. `http` is built in and covers
  any REST API behind a static secret (`restBase`, `pathAllow`, `queryAllow`, `methods`, `rewrite`, allowlist
  modes `path-prefix|path-exact|path-regex|query:<param>|none`). `confluence` (pages by id or space, children,
  search narrowed to an allowed space, storage HTML to text) and `jira` (issues by key or project, comments,
  project-scoped JQL, wiki markup and ADF to text) ship as separate jars.
- `GatewayHarness` in the runtime's test jar starts a gateway on an ephemeral port with an in-memory
  manifest and stub upstreams; plugin authors test against it.
- `init` writes `gateway/gateway.jar`, `gateway/plugins/{confluence,jira}.jar`, `gateway/Containerfile`
  (multi-stage jlink on pinned Temurin 25 and Debian digests) and an empty manifest; the compose file always
  has the gateway service, with a health check and `ALLOW_WRITE` from `profile.gateway.allowWrite`.
- `gateway list|status [--deep]|logs|restart|rebuild`, `gateway instance add|show|remove`,
  `gateway secret set --url --token-stdin|status|clear` (file mode 0600, never printed),
  `gateway allow <id> list|add|remove`, `gateway plugin list|install|remove` (jars are validated on the
  host before they are copied) and `gateway cert show|renew`. Adding or removing an instance re-renders
  Squid's gateway hosts, the certificate SANs and compose.

### Changed
- `core.gateway.ManifestFile` is replaced by the shared `Manifest`/`Instance` records from the API module
  (`ManifestIo` reads and writes them). The CLI reads a JSON argument through `JsonOutput.readObject`.

### To verify against real installations
- The gateway image build and the container (`build`, `up`, `gateway status`, a `curl` from a session
  container) could not be run in this session because Podman is unavailable here; the runtime itself was
  exercised as a process from the shaded jar with the plugin jars loaded from a sandbox directory.
- The Confluence and Jira plugins were written against the REST API v1 / v2 (v3 with `apiVersion: 3`)
  response shapes with recorded fixtures only.

## 2.0.0-M7 - 2026-09-04

### Added
- Claude Code and OpenCode as built-in adapters (M6, M7). Each is driven by `agents/<id>/agent.json`:
  pinned CLI version, required toolchain fragments, egress hosts, configuration directory, environment,
  models, and command templates for `ask`, `suggest`, `login` (per auth mode) and `logout`. Flag changes in
  a new CLI version are edits to `agent.json`, not code.
- One image per enabled agent: `rebuild-config` writes `image/agents/<id>.Containerfile` (base + profile
  toolchains + the agent's required fragments + its fragment) and `build` builds `localhost/lyngvi-<id>:<version>`.
  Sessions use the agent's image, mount `state/agents/<id>` as the agent's config directory, and pass
  `state/agents/<id>/env` (long-lived tokens or API keys) when it exists.
- Stream mapping: Claude Code `--output-format stream-json` (init, text, thinking, tool_use, tool_result,
  result) and OpenCode `run --format json` (session, text/tool parts, tokens) to the event contract, with a
  synthesised `done` when a process ends without one.
- `agent login <id> [--mode subscription|token|api-key]` runs the agent's own login flow in an interactive
  container with the proxy environment and the state directory mounted (mode 0700). `agent status` reads
  credentials from that directory; `agent logout` removes them.
- Claude Code gets `/work/CLAUDE.md` linked to `/work/AGENTS.md` before each turn; OpenCode reads `AGENTS.md` directly.

### To verify against real installations
- The command templates and stream formats were written for `@anthropic-ai/claude-code` 2.0.30 and
  `opencode-ai` 0.8.5 without access to a Podman machine or a subscription in this session. Verify both
  adapters end to end (`build`, `agent login`, one `session ask`) and adjust `agent.json` if a flag differs.

## 2.0.0-M5 - 2026-09-04

### Added
- Delivery: `session review <id> [--from start|last|n]` builds a patch per repository from the chosen
  baseline (a temporary index captures committed, uncommitted and untracked changes), classifies every
  file into `apply`, `hold` (symlink, executable, binary, too large) or `reject` (`..`, absolute paths,
  `.git/` paths) with a reason, and checks each file against the host repository (`conflict`).
- `session apply <id> --branch <b> [--message <m>] [--mode commit|stage] [--repo r]... [--include f]... [--from ...]`:
  refuses protected branches and the repository's default branch, stashes local changes, switches or
  creates the branch, applies only the selected files to the index (`git apply --index --include`),
  retries with `--3way` when the session has history (conflicts are left as markers and reported),
  commits (index only) or leaves the changes staged, restores the stash, records the delivered files in
  the working copy, and appends a delivery point to the conversation. Any failure after the branch switch
  rolls the host back.
- `session diff <id> [--from ...] [--repo r] [--path f]` prints the raw unified diff.
- `NO_CHANGES` (exit 7) carries `alternatives`: other baselines that do have changes.

## 2.0.0-M4 - 2026-09-04

### Added
- Agent runs: `session ask <id>` sends a prompt (from `--prompt-file` or stdin) to the session's
  agent, streams normalised events (`start, text, reasoning, tool_start, tool_end, notice, usage,
  done, error`; JSONL with `--json`, stamped with `ts` and `seq`), and appends the user and assistant
  messages (text, tool calls, usage, stop reason) to the conversation file. A run in progress marks
  the session busy; a second `ask` is rejected.
- Adapter contract: built-in Java adapters and external `agents/<id>/adapter.sh` scripts behind the
  same interface (`ask`, `suggest`, `models`, `auth-status`, `login`, `logout`, `usage`); an external
  adapter with the same id overrides a built-in one. `AS_*` environment variables carry the request.
- Built-in `mock` adapter and bundled `agents/mock-sh/` adapter for trying the product and for tests
  without Podman (`needsContainer: false`).
- `session suggest <id>` (branch name and commit message from the agent, with a fallback from the title)
  and `agent list|status|models|login|logout|usage`.
- Attachments (`session ask --attach <file>`) are copied into the session and exposed as `/work/_attachments/<name>`.

### Changed
- `AS_ATTACHMENTS` is newline-separated (environment variables cannot carry NUL); spec §6.2 is updated accordingly in the code comments.

## 2.0.0-M3 - 2026-09-04

### Added
- Sessions: `session new` creates a working copy per repository and a conversation file
  (`state/conversations/<id>.json`, schema 2). Working copies are either a plain copy with a single
  baseline commit (`history=none`) or a sanitised clone (`shallow` with `--depth`, or `full`):
  `git clone --no-local --no-hardlinks --template=<empty> --single-branch --no-tags`, remote removed,
  uncommitted host changes layered on top and committed as the baseline. Hooks, host config and
  remotes never travel. Detached HEAD is supported with git 2.49+ (`--revision`).
- `.lyngviignore` in a repository (gitignore-like) and the profile's `copy.excludes` and
  `copy.maxFileMiB` decide what is copied.
- One container per session: `session start` runs `podman run` with only that session's
  directories mounted (`/work/<repo>`, `/work/output`, `/work/_attachments`, `/work/AGENTS.md`),
  the hardening flags, resource limits and a rendered `container.env` (proxy, gateway, mirrors).
- `session list|show|rename|status|stop|remove [--keep-conversation]|gc [--idle N]` and `shell [<id>]`.
- Session ids can be abbreviated to a unique prefix.

## 2.0.0-M2 - 2026-09-04

### Added
- Runtime environment files: `image/base.Containerfile` (Debian bookworm-slim pinned by digest, user `agent`,
  hardening env), toolchain fragments (`node22`, `java17/21/25`, `kotlin`, `maven`, `gradle`, `python312`,
  `go123`, `dotnet8`), `scripts/docker-entrypoint.sh` (CA bundle, mirrors), `proxy/squid.conf.template`,
  `proxy/blocked-domains.txt`, `templates/docker-compose.template.yml` (Squid pinned by digest) and the
  gateway service fragment used once a gateway image exists.
- `rebuild-config`: renders `Containerfile`, `docker-compose.yml`, `proxy/allowed-domains.txt` (profile,
  agents and preserved user sections), `proxy/gateway-hosts.txt`, `proxy/squid.conf`, `AGENTS.md`, gateway
  directories, per-instance allowlists and secret examples, and the gateway certificate (openssl, SAN from
  the manifest). Bind-mounted files are rewritten in place so their inode survives.
- Subnet allocation: one /24 per sandbox from a pool, recorded in `~/.local/state/lyngvi/subnets.txt`,
  avoiding prefixes Podman already uses.
- `build` (base image `localhost/lyngvi-base:<version>`), `up`, `down`, `logs <proxy|gateway>`,
  `domain list|add|remove|reload|blocked`, `resources machine` (Podman machine resources and advice).
- `doctor` now also checks the Podman machine (macOS), generated files and their freshness, subnet
  collisions, `/work` mounts in the stack, the gateway certificate and the base image.

### Changed
- `doctor` on a freshly initialised sandbox reports the missing generated files; run `rebuild-config`.

## 2.0.0-M1 - 2026-09-04

### Added
- Configuration model: `profile.json` (organisation profile, unknown fields rejected), `sandbox.config`
  (local state, written atomically by the core only), `.env` bootstrap from `templates/.env.example`.
- `init`: creates a sandbox directory from a profile pack (bundled default or `--profile <dir>`),
  registers repositories (`--repo`), writes bundled resource files, creates `state/` and `.sessions/`.
- `status`, `doctor` (host checks: podman, compose, git >= 2.30, openssl, UID/GID, profile, `.env`,
  state directories, bundled files, each repository), `repo list|add|remove`, `resources show|set`,
  `profile show`. All commands support `--json`; `doctor` exits 1 when any check fails.
- `--sandbox <dir>` global option; `setup.sh` exports `LYNGVI_HOME` so commands run against the
  directory the launcher lives in.
- Messages in English (base) and Norwegian bokmål (`--lang nb`); a test enforces translation parity.
- Cross-process locking on `state/locks/*.lock` (`FileChannel.lock`).
- `ProcessRunner` with PATH augmentation for Finder-launched apps, streaming output and timeouts.

## 2.0.0-M0 - 2026-09-04

### Added
- Maven multi-module skeleton: `lyngvi-core`, `lyngvi-cli`.
- `Version`, `ErrorCode`, `SandboxException`, JSON output envelope.
- CLI with global flags (`--json`, `--quiet`, `--lang`, `--no-color`, `--yes`) and the `version` command.
- `setup.sh` launcher (POSIX sh, no logic) that locates a runtime and starts the CLI.
- MIT licence, README, CI build on Ubuntu and macOS.
