# Writing an agent adapter

An agent is a directory `agents/<id>/` in a sandbox (bundled ones ship with the product). Two kinds exist.

## 1. Declarative container agents (`agent.json`)

Claude Code and OpenCode are defined this way: the CLI runs inside the session container and the core drives it
through command templates. No code is needed to change a flag or a version.

```json
{
  "id": "my-agent", "label": "My Agent", "pinnedCli": "1.2.3",
  "egress": ["api.example.com"], "optionalEgress": [],
  "configDir": "/home/agent/.my-agent", "skillsDir": "skills", "instructionFile": "AGENTS.md",
  "env": { "MY_AGENT_NO_TELEMETRY": "1" },
  "auth": { "modes": ["subscription", "api-key"], "default": "subscription" },
  "requires": ["node22"], "needsContainer": true,
  "models": [ { "id": "fast", "label": "Fast", "default": true } ],
  "commands": {
    "ask": ["my-agent", "run", "--json", "{model:--model}", "{session:--session-id|--resume}"],
    "suggest": ["my-agent", "run", "--json"],
    "models": ["my-agent", "models"],
    "login": { "subscription": ["my-agent", "auth", "login"], "api-key": [] },
    "logout": ["my-agent", "auth", "logout"]
  },
  "authFiles": { "subscription": "credentials.json", "api-key": "env:MY_AGENT_API_KEY" }
}
```

- `image.Containerfile` next to it installs the CLI on top of the base image (`RUN npm install -g my-agent@1.2.3`).
- `AGENTS.d/*.md` are appended to the sandbox's `AGENTS.md` when the agent is enabled.
- `skillsDir` is optional: the directory *inside* `configDir` where the agent reads skills from — `skills` for
  Claude Code, `config/opencode/skills` for OpenCode, whose global skills live under `$XDG_CONFIG_HOME`
  (check with the CLI itself, e.g. `opencode debug skill`, rather than from memory of its docs).
  The sandbox's switched-on skills are mirrored into
  `state/agents/<id>/<skillsDir>/` before every message, so they are in place in the containers that are
  already running. Leave it out and the agent is simply not given skills.
- `{model:--model}` expands to `--model <value>` when a model is set; `{session:--session-id|--resume}` to the first
  flag on a new conversation and the second when resuming one. The prompt arrives on stdin.
- The stream mapper turns the CLI's JSON lines into the event contract (`start`, `text`, `reasoning`,
  `tool_start`, `tool_end`, `notice`, `usage`, `error`, `done`); Claude Code's and OpenCode's mappers live in
  `lyngvi-core` (`agents/ClaudeStreamMapper`, `agents/OpenCodeStreamMapper`). A new CLI with a different
  stream needs a mapper class; everything else is `agent.json`.
- An empty login command means the mode is configured with a credential file in `state/agents/<id>/env`
  (`authFiles` names the variable).
- `commands.models` is optional. Without it the `models` array is the whole list. With it, the command runs in a
  throwaway container of the agent's image and its output becomes the catalogue: one model per line, the first
  whitespace-separated token the id and the rest, when present, the label. Lines that are not ids (headings, hints,
  blank lines, ANSI colour) are skipped. The `models` array stays in front of the result — it carries the curated
  labels and the `default` — and is the fallback when the command cannot run. The catalogue is cached in
  `state/agents/<id>/models.json`; `agent models <id> --refresh` re-reads it. An adapter class can override
  `ContainerCliAdapter.toModel` when its CLI prints something else; `OpenCodeAdapter` does, to require the
  `provider/model` slash and to label the two parts.

## 2. External adapters (`adapter.sh`)

Any executable can be an adapter: `agents/<id>/adapter.sh` receives `AS_ACTION=ask|suggest|login|logout|status|models`
and the request in `AS_*` variables (`AS_SESSION_DIR`, `AS_REPOS`, `AS_PROMPT_FILE`, `AS_MODEL`, `AS_AGENT_SESSION_ID`,
`AS_ATTACHMENTS` newline-separated, `AS_CONTAINER`, `AS_VOLUME`, `AS_IMAGE`), and prints events as JSON lines on
stdout. `agents/mock-sh/` is the reference. Set `"needsContainer": false` when the adapter runs on the host.

`AS_SESSION_DIR` is the host side of the session — the attachments and the prompt file, nothing the agent
writes. The working copies and `output/` are in the session's Podman volume, `AS_VOLUME`, which is `/work`
inside the session container. An adapter that runs on the host reaches them the way the core does, by
mounting that volume in a container of its own:

```sh
podman run --rm -v "$AS_VOLUME":/work -e WORK=/work -e REPO="$repo" \
  --network none --cap-drop ALL --security-opt no-new-privileges \
  --entrypoint sh "$AS_IMAGE" -c 'printf "hello\n" > "$WORK/$REPO/NOTES.md"'
```

## Event contract

Every event is one JSON object per line with a `type`; the core stamps `ts` and `seq`. `text` and `reasoning`
carry `delta`; `tool_start` has `id`, `name`, `input`; `tool_end` has `id`, `output`, `isError`; `usage` has
`input`, `output`, `cacheRead`, `cacheWrite`, `costUsd`, `contextUsed`, `contextMax`; `done` has `stopReason`
(`end_turn`, `cancelled`, `error`). The core persists the turn as an ordered timeline of text and tools.

Test an adapter without Podman: enable it in `profile.json`, create a session with `--agent <id> --no-repos`,
and run `session ask`. `session ask --json` shows the mapped events.

## Models from a server of your own

Ollama, LM Studio and MLX servers (oMLX and the like, which speak LM Studio's OpenAI-compatible API) run outside
the sandbox. A container reaches them at `host.containers.internal`, and only there: Squid denies every private
address except that host on the ports in `profile.json` (`localModels`, `11434` and `1234` by default).

The same provider block serves an OpenAI-compatible server of your own out on the network — `https://llm.example.com/v1`
with an API key, say. Nothing about it is local then except that you configured it: the address is just an address,
and the proxy lets it out because its host is in the egress allowlist rather than because a port is open.

The agent still has to know the models. opencode builds its catalogue from models.dev plus its own configuration,
so a model your server has loaded is offered only when it is declared. The GUI does this for you: **Agents →
«Local models…»** edits the provider block, and «Test» asks the server itself — from a throwaway container on the
internal network, through the proxy — so every listed model is marked as on the server or not, and «Add from
server» takes the ones it has and you have not.

The offer is made where you would look for it rather than waiting to be found. The Agents page has an **«Own
models»** column: «not set up» for an agent that could use a server of yours, «N from your own server» for one
that does, and nothing at all for an agent the offer does not apply to — `AgentInfo.canUseLocalModels`, which the
button is enabled by, so it cannot open a dialog that could only fail on save. `agent list` prints the same.
The first-run wizard's **Agent** step offers the dialog beside «Log in…», and an agent that answers from a server
of yours finishes that step: logging in is one way to get an answer, and this is the other.

An agent gets that offer by declaring where its provider lives:

```json
"localModels": { "provider": "lmstudio", "configFile": "config/opencode/opencode.json",
                 "defaultBaseUrl": "http://host.containers.internal:1234/v1" }
```

The file it writes is `state/agents/<id>/<configFile>` — for opencode that directory is `XDG_CONFIG_HOME` in the
agent's containers, so the same file is read by `opencode models` and by the session. It can be written by hand
just as well; `agents/opencode/opencode.example.json` is the starting point:

```json
{ "provider": { "lmstudio": {
    "options": { "baseURL": "http://host.containers.internal:1234/v1" },
    "models": { "mlx-community/Qwen3-30B-A3B-Instruct-2507-4bit": { "name": "Qwen3 30B A3B (local)", "tool_call": true } } } } }
```

`agent models opencode` then lists them as `lmstudio/<model>`. They are in the catalogue because they are in the
configuration file, not because the agent's own listing worked: `ContainerCliAdapter` reads the provider block and
puts them right behind the models `agent.json` declares, so they are offered even when `opencode models` cannot be
run at all (no image built yet, no network), and they are never left at the end of a catalogue of hundreds where
the picker's first page does not reach them. They carry `Model.local`, so the model dropdown in «New conversation»
and in the composer offers them as a group of their own — **«Local LLM models»** over every model from your server,
then «Other models» over the short list of the rest. With nothing configured there is one group, which is no group,
and the dropdown is the short list it has always been.

An agent with models configured that way is also offered twice in «New conversation»: **«OpenCode (opencode)»** and
**«OpenCode (local models)»** (`AgentInfo.localModels` counts them, `AgentChoices` builds the rows). Both rows are
the same agent — the conversation is created with `--agent opencode` either way — but the second one answers with
the models from your own server and nothing else, so working against your own server is a choice you make once
rather than a model you have to find. That row says nothing about logging in, which is half the point of it, and
«All models…» is closed there: its list is already the whole of what the server offers. Saving from the dialog also stars them — a model you
configured yourself is one you mean to use. A provider declared this way counts as configured, so `OpenCodeAdapter`
leaves its models alone instead of routing them to OpenRouter the way it does with a bare `vendor/model` slug.

The model id must be exactly what the server reports — it is what goes on the wire — which is what the test is
for: a listed model the server does not have is the failure you would otherwise meet in the middle of an ask.

### A server the proxy denies

The proxy answers a server it is not allowed to reach with 403, and what to do about it is the sandbox's egress
policy, not the agent's — so «Test» offers the one action that helps, and which one that is follows from the
address (`LocalModelConfig.wayOut`):

- **On your own machine** — the host `localModels` names, a name with no dot, `.internal`, `.local`, a private
  address: Squid denies every private destination before it reads the allowlist, so the port is the door.
  «Open port N in the proxy» adds it to `localModels.ports` in `profile.json`, re-renders `squid.conf`, reloads
  the running proxy and tests again. By hand that is the same three steps — edit the profile,
  `setup.sh rebuild-config`, `setup.sh domain reload`.
- **Out on the network** — a model server of your own on a public name, say `https://llm.example.com/v1`: the
  allowlist is the door. «Allow `<host>` in the proxy» asks first, because allowing a host lets *every* agent in
  the sandbox reach it, and then adds it to the user section of the allowlist (`domain add <host>`, which reloads
  the proxy itself) and tests again. «Setup → Domains» takes it out again. Note that Squid only tunnels TLS on
  port 443: an `https` address on another port stays denied even once the host is allowed, and a plain `http`
  address is let out on any port.
- **Nothing to offer** when the door the address needs is open already — then the 403 came from the server, not
  from the proxy, and it is about the request: usually the API key.

«Save» asks the same question when the host is not on the allowlist yet, whether or not «Test» was ever pressed:
a host the proxy has never been told to let out is the next thing that would fail, and in the middle of an ask it
is a much harder failure to read. Declining saves the configuration all the same.

A bare IP address is not offered either way unless it is private: the allowlist is read as domain names
(`dstdomain`), so an address cannot be put in it.

A server that wants an API key gets one from `localModels.apiKeyEnv` — the variable the agent reads it from
(`LMSTUDIO_API_KEY` for opencode). The key is stored in `state/agents/<id>/env`, where the agent's other
credentials live and which is mounted only into that agent's containers, never in the model configuration file.
The dialog takes it in a password field, never reads it back out, and tests with it: a key typed but not yet saved
goes into a private file for that one run, so the command line the app logs never holds a secret. A server that
refuses (401, or 403 that did not come from the proxy) is reported as a key problem, not as a closed port.
