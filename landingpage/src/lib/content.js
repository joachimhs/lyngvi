/**
 * All user-visible copy for the landing page, in the two languages Lyngvi itself
 * ships with: English as the default, Norwegian as a full translation.
 *
 * The shape of `en` and `no` is identical; components read `i18n.t.<section>`.
 */

export const en = {
	meta: {
		title: 'Lyngvi — a hardened sandbox for autonomous coding agents',
		description:
			'Lyngvi runs Claude Code, OpenCode or your own agent against your repositories inside a hardened container: no direct network, no access to your .git, secrets behind a gateway, and every change delivered as a reviewed patch.'
	},
	nav: {
		links: [
			{ href: '#premises', label: 'Premises' },
			{ href: '#architecture', label: 'Architecture' },
			{ href: '#screens', label: 'Screens' },
			{ href: '#sandbox', label: 'The sandbox' },
			{ href: '#egress', label: 'Egress' },
			{ href: '#delivery', label: 'Delivery' },
			{ href: '#gateway', label: 'Gateway' },
			{ href: '#features', label: 'Features' },
			{ href: '#install', label: 'Install' }
		],
		source: 'Source',
		langLabel: 'Norsk',
		skip: 'Skip to content'
	},
	hero: {
		badge: 'v2.0.0 · MIT · macOS and Linux · Podman',
		title: 'Give the agent your code.',
		titleAccent: 'Not your machine.',
		lead: 'Lyngvi is a hardened, agent-agnostic sandbox for running autonomous coding agents against your own repositories. The agent gets a working copy, a toolchain and a controlled way out. It never gets your credentials, your git history or a route to the internet.',
		ctaPrimary: 'Get started',
		ctaSecondary: 'How it is built',
		origin:
			'Named after the island in Norse mythology where the wolf Fenrir was bound: a place where great power is kept within defined boundaries.',
		stats: [
			{ value: '0', label: 'direct network routes out of the container' },
			{ value: '1', label: 'container per conversation' },
			{ value: '3', label: 'buckets every changed file is classified into' }
		],
		terminalTitle: 'a first run, start to finish',
		ticker: [
			'cap_drop ALL',
			'no-new-privileges',
			'your UID, never root',
			'no default route',
			'blocklist before allowlist',
			'a fresh .git',
			'read-only gateway',
			'memory · cpu · pid limits',
			'apply · hold · reject',
			'never to main'
		]
	},
	premises: {
		kicker: 'The four premises',
		title: 'The product is the boundary',
		lead: 'Everything else in Lyngvi is shell around these four. They are the reason an agent can run unattended in a repository you care about.',
		items: [
			{
				n: '01',
				title: 'No direct network access',
				body: 'The session container sits on an internal network with no default route. Every outbound connection goes through a Squid proxy with an allowlist, and an explicit blocklist of known exfiltration endpoints is evaluated first.',
				chip: 'squid · allowlist + blocklist'
			},
			{
				n: '02',
				title: 'No access to your .git',
				body: 'The agent works in a copy with a fresh .git — no hooks, no config, no remotes, no credential helper. The whole git attack surface is removed structurally, not by policy.',
				chip: 'copied working tree'
			},
			{
				n: '03',
				title: 'Secrets live in a gateway sidecar',
				body: 'Internal systems are reached through a read-only, allowlisted API that holds the token. The agent only ever sees a hostname. A plugin cannot read the secret: the runtime injects it into a locked upstream object.',
				chip: 'never a token in the container'
			},
			{
				n: '04',
				title: 'Validated, human-approved delivery',
				body: 'Changes come back as a patch, classified on the host file by file, reviewed by you, and committed to a working branch. Never to main, never silently.',
				chip: 'apply · hold · reject'
			}
		]
	},
	architecture: {
		kicker: 'Architecture',
		title: 'One core, two front-ends, three layers of containment',
		lead: 'All logic lives in a Java core that both the CLI and the JavaFX GUI call in-process. The core is the only thing that talks to git and Podman. Select a part of the diagram to see what it guarantees.',
		hostLabel: 'Your machine',
		stackLabel: 'Shared stack (podman compose)',
		sessionLabel: 'One container per conversation',
		internetLabel: 'Internet',
		intranetLabel: 'Internal systems',
		internalNet: 'internal network · no default route',
		detailLabel: 'Selected',
		nodes: {
			gui: {
				title: 'JavaFX GUI',
				body: 'A thin shell over the core: every button calls a core API, long calls run on virtual threads, and the GUI never runs git or podman itself — an architecture test enforces it.'
			},
			cli: {
				title: 'setup.sh → lyngvi-cli',
				body: 'The launcher has no logic of its own; it finds a runtime and starts the CLI against the sandbox directory. Every command accepts --json, so the whole product is scriptable.'
			},
			core: {
				title: 'lyngvi-core',
				body: 'Java 25. Owns all state, every git and podman call, patch classification, streaming with interruption, and locking. One implementation of the security-critical delivery flow, shared by both front-ends.'
			},
			state: {
				title: 'state/ and .sessions/',
				body: 'Conversations, sessions, working copies, patches and agent credentials — on your disk, outside the container, owned by your user. Credentials are mounted only into the containers of the agent that owns them.'
			},
			proxy: {
				title: 'Squid proxy',
				body: 'The only route out. Three allowlist sections (profile, agent, your own additions) plus a blocklist that is evaluated first. Edits reload live; denied hosts show up in domain blocked.'
			},
			gateway: {
				title: 'Gateway sidecar',
				body: 'Holds the credentials for internal systems and exposes a read-only, allowlisted API. Runs read-only, non-root, cap_drop ALL. Every system type is a plugin jar loaded through ServiceLoader.'
			},
			session: {
				title: 'Session container',
				body: 'cap_drop ALL, no-new-privileges, unprivileged user with your UID, memory, CPU and PID limits, and nothing mounted but the working copy, the output folder, the attachments and AGENTS.md.'
			}
		}
	},
	screens: {
		kicker: 'The desktop app',
		title: 'The same core, with a window on it',
		lead: 'Every screen below is the real JavaFX app, run against a sandbox with one repository, three conversations and a change waiting to be reviewed. No mock-ups, no retouching.',
		note: 'Lyngvi 2.0.0 · dark theme · sandbox «shop»',
		items: [
			{
				id: 'chat',
				label: 'Agentic coding',
				title: 'A conversation is a work log',
				body: 'Every reply shows what the agent read, searched, ran and edited — with +/− counts on each edit — and what the turn cost in tokens and dollars. The strip above the composer says what is waiting for delivery, and that one file is held back.',
				alt: 'The Agentic coding screen: a conversation list on the left and a transcript where the agent’s tool calls are listed as Read, Search, Run and Edit rows.'
			},
			{
				id: 'deliver',
				label: 'Deliver changes',
				title: 'Review on your side of the wall',
				body: 'The patch is classified on your machine: files that will be applied, files held back by policy — an executable here, ticked in only if you say so — and rejected paths that never get through. Then a working branch and a commit message; protected branches are refused.',
				alt: 'The Deliver changes dialog: lists for applied, held back and rejected files, the diff of src/auth.ts, a branch field and a commit message.'
			},
			{
				id: 'diff',
				label: 'Git Diff',
				title: 'Hunks, or the whole file',
				body: 'Changed files on the left, the selected one on the right, measured from the session start or from any delivery since. «Whole file» shows the file in full with the changes coloured in place.',
				alt: 'The Git Diff window with three changed files and the hunks of src/auth.ts.'
			},
			{
				id: 'traffic',
				label: 'Traffic',
				title: 'What the proxy refused',
				body: 'Every denied host with a count, and the allowlist beneath it with the origin of each entry — profile, agent or you. Allow one and the proxy reloads without a restart.',
				alt: 'The Traffic screen: a table of denied hosts with status and count, and the allowlist with its source column.'
			},
			{
				id: 'sessions',
				label: 'Sandbox',
				title: 'One container per conversation',
				body: 'Every session container with its state and image, under the shared stack. Containers idle longer than the profile allows are stopped while the app runs; containers whose conversation is gone are removed.',
				alt: 'The session container table: three conversations, their container names, state and image.'
			},
			{
				id: 'agents',
				label: 'Agents',
				title: 'Log in once, per agent',
				body: 'Credentials live under the sandbox’s state directory and are mounted only into that agent’s containers. The Usage tab next to it shows quotas and spend per agent, model and conversation.',
				alt: 'The Agents screen: Claude Code logged in with a subscription, OpenCode not logged in.'
			}
		]
	},
	sandbox: {
		kicker: 'Inside the sandbox',
		title: 'A complete workspace, and nothing more',
		lead: 'The agent gets a real development environment — git, a toolchain, a package registry through the proxy — with a strictly bounded view of the world.',
		seesTitle: 'What the agent sees',
		seesIntro: 'Mounted into the container, and nothing else from the host:',
		sees: [
			{ path: '/work/<repo>', note: 'a working copy with a fresh .git, one mount per repository' },
			{ path: '/work/output', note: 'files meant for you, outside the repositories' },
			{ path: '/work/_attachments', note: 'files you sent with a message, read-only' },
			{ path: '/work/AGENTS.md', note: 'the house rules, generated from the sandbox config' },
			{ path: 'HTTP_PROXY, HTTPS_PROXY', note: 'the only way out, to the allowlist' },
			{ path: '<SYSTEM>_GATEWAY_URL', note: 'a hostname per configured internal system' }
		],
		neverTitle: 'What it never sees',
		neverIntro: 'Structurally out of reach, not merely discouraged:',
		never: [
			'Your .git — hooks, config, remotes, credential helpers',
			'Anything else on your filesystem; no home directory, no SSH keys',
			'The host machine or services on its localhost',
			'A token for an internal system — the gateway holds those',
			'sudo, new privileges or any Linux capability',
			'A default route: unknown hosts fail at the proxy, not at your firewall'
		],
		agentsTitle: 'Agents are interchangeable',
		agentsBody:
			'Claude Code and OpenCode ship as reference adapters, with subscription login or a token. An adapter is an agent.json plus a command contract, so a third agent is a config file, not a fork. Models running on your own machine (Ollama, LM Studio, MLX) are reached through the proxy on the ports your profile names.',
		agents: [
			{
				name: 'Claude Code',
				detail: 'subscription, token or API key · streaming · reasoning · attachments'
			},
			{ name: 'OpenCode', detail: 'subscription or key · streaming · local and hosted models' },
			{ name: 'Your own', detail: 'agent.json, or an adapter.sh in any language' }
		]
	},
	egress: {
		kicker: 'Egress control',
		title: 'Every request the agent makes, answered by a list you own',
		lead: 'The blocklist is evaluated first, then the allowlist; anything that matches neither is denied. Try a hostname and see what the proxy would answer.',
		placeholder: 'try a hostname…',
		inputLabel: 'Hostname to test against the proxy rules',
		examples: 'Examples:',
		verdicts: {
			blocked: 'DENIED — on the blocklist',
			allowed: 'ALLOWED — on the allowlist',
			gateway: 'ROUTED — to the gateway, which holds the secret',
			denied: 'DENIED — not on the allowlist'
		},
		reasons: {
			blocked:
				'A known exfiltration or tunnelling endpoint. The bundled blocklist covers request-capture services, paste sites, file drops, tunnels and IP reconnaissance, and it is checked before anything else.',
			allowedProfile: 'Allowed by the profile your organisation ships.',
			allowedAgent:
				'Allowed because the configured agent needs it — agent allowlists come from agent.json, not from you.',
			allowedUser: 'Allowed because someone added it with ./setup.sh domain add.',
			gateway:
				'A gateway hostname. The request never leaves on its own: the sidecar adds the credential, enforces the item allowlist and returns read-only data.',
			denied:
				'Not on any list, so the proxy answers with an error and the attempt is recorded. Allow it when you mean to: ./setup.sh domain add <host>.'
		},
		sections: { profile: 'profile', agent: 'agent', user: 'yours' },
		listTitle: 'The bundled blocklist, by category',
		categories: [
			{
				name: 'Request capture',
				hosts: 'webhook.site · pipedream.com · requestbin.com · beeceptor.com'
			},
			{ name: 'Paste sites', hosts: 'pastebin.com · hastebin.com · rentry.co · dpaste.org' },
			{ name: 'File sharing', hosts: 'transfer.sh · file.io · 0x0.st · catbox.moe · gofile.io' },
			{ name: 'Tunnels', hosts: 'ngrok.io · localtunnel.me · serveo.net · localhost.run' },
			{ name: 'IP reconnaissance', hosts: 'ipinfo.io · ifconfig.me · icanhazip.com · ipify.org' }
		],
		cmdNote: 'Live from the command line:',
		figure: {
			caption:
				'In the GUI the same list is a table: every host the proxy denied, with a count, and an Allow button that adds it to your section of the allowlist and reloads the proxy.',
			alt: 'The Traffic table in the GUI: four denied hosts with status 403 and their counts, and the buttons Allow, Refresh, Follow and Stop.'
		}
	},
	delivery: {
		kicker: 'Delivery',
		title: 'Nothing leaves the sandbox unseen',
		lead: 'A delivery is measured from a baseline — the session start, the last delivery, or any delivery in between — built into a patch inside the container, and then classified on the host. Classification is yours, not the agent’s.',
		steps: [
			{
				title: 'Baseline',
				body: 'Pick where the diff starts: session start, the last delivery, or an earlier one.'
			},
			{
				title: 'Patch',
				body: 'Built with a throwaway index, so uncommitted, unstaged and agent-committed work all come along.'
			},
			{ title: 'Classify', body: 'Three buckets on the host, by rule: apply, hold, reject.' },
			{ title: 'Review', body: 'File by file, as hunks or whole files, in the GUI or the CLI.' },
			{
				title: 'Branch',
				body: 'Commit or stage on a working branch. Protected branches are refused.'
			}
		],
		tableTitle: 'session review 7f3a2c',
		tableHint:
			'Click a held file to include it — held files are only delivered when you name them.',
		columns: { file: 'File', change: 'Change', bucket: 'Bucket' },
		buckets: {
			apply: 'apply',
			hold: 'hold',
			reject: 'reject'
		},
		bucketRules: {
			apply: 'Ordinary text changes, applied when you approve them.',
			hold: 'Executables, symlinks, binaries and oversized files. Delivered only when named explicitly.',
			reject:
				'Path traversal, absolute paths, .git segments, invalid UTF-8, unparseable patches. Never delivered, whatever you click.'
		},
		files: [
			{ path: 'src/auth.ts', change: 'modified', bucket: 'apply', stat: '+12 −3' },
			{ path: 'src/session/cookie.ts', change: 'modified', bucket: 'apply', stat: '+4 −1' },
			{ path: 'tests/auth.spec.ts', change: 'added', bucket: 'apply', stat: '+58 −0' },
			{
				path: 'bin/run.sh',
				change: 'added',
				bucket: 'hold',
				stat: 'mode 100755',
				reason: 'EXECUTABLE'
			},
			{
				path: 'docs/screenshot.png',
				change: 'added',
				bucket: 'hold',
				stat: 'binary',
				reason: 'BINARY'
			},
			{
				path: '../secrets.env',
				change: 'added',
				bucket: 'reject',
				stat: '+1 −0',
				reason: 'PATH_TRAVERSAL'
			}
		],
		applyLabel: 'session apply --branch',
		branch: 'agent/fix-login',
		summary: (/** @type {number} */ n) =>
			`${n} file${n === 1 ? '' : 's'} will be committed on agent/fix-login.`,
		protectedNote:
			'main, master and develop are refused by the profile; the branch prefix is yours to set.',
		figure: {
			caption:
				'The same review in the GUI: what will be applied, what is held back until you tick it, what is rejected — and the diff of the selected file beside it.',
			alt: 'The review part of the Deliver changes dialog: a warning that one of three files is held back, the lists Will be applied, Held back and Rejected, and the diff of src/auth.ts.'
		}
	},
	gateway: {
		kicker: 'The gateway',
		title: 'Reach the intranet without handing over the key',
		lead: 'Some work needs the wiki, the issue tracker or an internal API. The gateway is a sidecar that holds the credential and exposes a narrow, read-only, allowlisted view of the system. The agent gets a hostname and a 403 when it asks for something you have not allowed.',
		guaranteesTitle: 'What the runtime guarantees, whatever a plugin does',
		guarantees: [
			'Unknown hostname → 404, with no upstream call at all',
			'GET and HEAD only, unless the instance and the profile both allow writes',
			'The upstream object is pinned to one origin; .. and absolute URLs are refused',
			'The secret is injected by the runtime and exists as a string nowhere a plugin can read',
			'The item allowlist is re-read on every lookup and enforced before the handler runs',
			'An empty allowlist means deny everything',
			'no-store on every response; set-cookie and hop-by-hop headers stripped',
			'Masked logging: Authorization values and *_TOKEN variables are never written',
			'A broken instance fails alone; /healthz reports it as configured: false'
		],
		pluginsTitle: 'Every system type is a plugin',
		pluginsBody:
			'http is built in and covers any REST API behind a static secret. confluence and jira ship as jars. Your own is a class implementing GatewayPlugin plus a service entry, dropped in with gateway plugin install. The codebase ships with no systems configured — a profile pack adds what your organisation needs.',
		exchangeTitle: 'What it looks like from inside',
		allowedComment: 'allowed: space OPS is on the list',
		deniedComment: 'not allowed yet — the human must add it',
		deniedBody: 'the gateway answers before any upstream call'
	},
	features: {
		kicker: 'The rest of it',
		title: 'Built to be lived in',
		lead: 'The boundaries are the point, but a sandbox you avoid is a sandbox that protects nothing.',
		items: [
			{
				title: 'One container per conversation',
				body: 'Sessions cannot see or edit each other’s work, and resource limits mean something. Start, stop, shell in, garbage-collect the idle ones.'
			},
			{
				title: 'Profiles instead of hard-coding',
				body: 'Hostnames, allowlists, mirrors, CA names, protected branches, toolchains and language live in profile.json and a profile pack. No organisation is baked into the source.'
			},
			{
				title: 'Toolchains as fragments',
				body: 'A slim Debian base plus the fragments you pick: Java 17/21/25, Node 22, Python 3.12, Go, .NET 8, Maven, Gradle, Kotlin.'
			},
			{
				title: 'Resource presets',
				body: 'review (2g, 1 cpu), dev (8g, 4), build (16g, 6) — or one derived from your machine. Chosen per session, by what the session is about to do.'
			},
			{
				title: '--json on every command',
				body: 'One JSON document on stdout, documented exit codes, and a line-delimited event stream from session ask. The CLI contract is the public, language-independent API.'
			},
			{
				title: 'A GUI that is only a shell',
				body: 'Nav rail, streaming chat with tool calls and usage, a file pane, a diff viewer with whole-file mode, and the same review and apply flow as the CLI.'
			},
			{
				title: 'Pinned and reproducible',
				body: 'Base image, Squid and the gateway base are pinned by digest; the agent CLI version is pinned in its fragment. doctor --deep proves the egress rules from a throwaway container.'
			},
			{
				title: 'Attachments and local models',
				body: 'Drop files or paste an image into the composer. Point the sandbox at Ollama, LM Studio or an MLX server on your own machine; the proxy opens just those ports.'
			},
			{
				title: 'English and Norwegian',
				body: 'Every user-visible string comes from a resource bundle, in the CLI and the GUI alike. --lang picks one per command.'
			}
		]
	},
	hardening: {
		kicker: 'Hardening, in one table',
		title: 'What is switched off, everywhere',
		rows: [
			{ k: 'Network', v: 'Internal network only; no default route from a session container' },
			{
				k: 'Capabilities',
				v: 'cap_drop ALL and no-new-privileges on every container, session containers included'
			},
			{
				k: 'User',
				v: 'Unprivileged agent user with your UID and GID, so mounted files keep their owner'
			},
			{
				k: 'Filesystem',
				v: 'Copied working tree; the gateway runs read-only; nothing else from the host is mounted'
			},
			{
				k: 'Package managers',
				v: 'Lifecycle scripts off: npm_config_ignore_scripts, YARN_ENABLE_SCRIPTS=false'
			},
			{
				k: 'git',
				v: 'No system config, no terminal prompt, no signing, identity fixed by the profile'
			},
			{
				k: 'Credentials',
				v: 'Agent credentials under state/agents/<id>/, mounted only into that agent’s containers; no tokens in .env'
			},
			{
				k: 'Branches',
				v: 'Protected branches from the profile plus the repository default; delivery always lands on a working branch'
			},
			{
				k: 'Images',
				v: 'Base image, Squid and gateway base pinned by digest; CI verifies the pins'
			}
		],
		limitsTitle: 'And what it does not solve',
		limits: [
			'The agent’s own credential lives in its container — a documented limitation, with gateway token injection designed in as future work.',
			'Session containers share one internal network, so an agent that starts a service could be reached by another session. Nothing listens by default.'
		]
	},
	install: {
		kicker: 'Install',
		title: 'From nothing to a running sandbox',
		lead: 'The CLI ships as a jlink image with its own runtime, and the GUI as a jpackage app with the CLI inside it. You do not install Java.',
		requirements:
			'Requirements: macOS or Linux · Podman 5+ · Java 25 only if you build from source',
		steps: [
			{
				title: 'Get the CLI',
				body: 'Create a folder, drop setup.sh in it, and let it fetch the runtime for your platform. The download is checksum-verified.'
			},
			{
				title: 'Create a sandbox',
				body: 'Point it at a repository. init writes the sandbox its own launcher and a copy of the CLI.'
			},
			{
				title: 'Build and start',
				body: 'Render the config, build the image, bring up the proxy and gateway, and let doctor check the result.'
			},
			{
				title: 'Work',
				body: 'A conversation gets a container. Ask, read the diff, deliver what you approve.'
			}
		],
		docsTitle: 'Further reading',
		docs: [
			{ label: 'README', note: 'commands, layout and the first run' },
			{ label: 'docs/agents.md', note: 'writing an agent adapter and the event contract' },
			{ label: 'docs/gateway-plugins.md', note: 'writing and packaging a gateway plugin' },
			{ label: 'docs/profiles.md', note: 'every section of a profile pack' },
			{ label: 'acceptance/', note: 'the acceptance suite and checklist' }
		]
	},
	footer: {
		tagline: 'A hardened, agent-agnostic sandbox for autonomous coding agents.',
		licence: 'MIT licensed.',
		origin:
			'Lyngvi is the island where Fenrir was bound. The wolf in the mark is the agent’s power, the barrier its boundaries, and the three sparkles its artificial intelligence.',
		colophon: 'This page is static: no trackers, no analytics, no third-party requests.',
		status: 'Status: pre-release'
	}
};

export const no = {
	meta: {
		title: 'Lyngvi — en herdet sandkasse for autonome kodeagenter',
		description:
			'Lyngvi kjører Claude Code, OpenCode eller din egen agent mot repoene dine inne i en herdet container: ingen direkte nettilgang, ingen tilgang til .git, hemmeligheter bak en gateway, og hver endring levert som en gjennomgått patch.'
	},
	nav: {
		links: [
			{ href: '#premises', label: 'Premisser' },
			{ href: '#architecture', label: 'Arkitektur' },
			{ href: '#screens', label: 'Skjermbilder' },
			{ href: '#sandbox', label: 'Sandkassen' },
			{ href: '#egress', label: 'Utgående' },
			{ href: '#delivery', label: 'Uttak' },
			{ href: '#gateway', label: 'Gateway' },
			{ href: '#features', label: 'Funksjoner' },
			{ href: '#install', label: 'Kom i gang' }
		],
		source: 'Kildekode',
		langLabel: 'English',
		skip: 'Hopp til innholdet'
	},
	hero: {
		badge: 'v2.0.0 · MIT · macOS og Linux · Podman',
		title: 'Gi agenten koden din.',
		titleAccent: 'Ikke maskinen din.',
		lead: 'Lyngvi er en herdet, agentuavhengig sandkasse for å kjøre autonome kodeagenter mot dine egne repoer. Agenten får en arbeidskopi, en verktøykjede og én kontrollert vei ut. Den får aldri legitimasjonen din, git-historikken din eller en rute til internett.',
		ctaPrimary: 'Kom i gang',
		ctaSecondary: 'Slik er det bygget',
		origin:
			'Oppkalt etter øya i norrøn mytologi der ulven Fenrir ble bundet: et sted der stor kraft holdes innenfor definerte grenser.',
		stats: [
			{ value: '0', label: 'direkte nettverksruter ut av containeren' },
			{ value: '1', label: 'container per samtale' },
			{ value: '3', label: 'bøtter hver endret fil klassifiseres i' }
		],
		terminalTitle: 'en første kjøring, fra start til slutt',
		ticker: [
			'cap_drop ALL',
			'no-new-privileges',
			'din UID, aldri root',
			'ingen standardrute',
			'blokkliste før allowlist',
			'en ny .git',
			'skrivebeskyttet gateway',
			'minne · cpu · pid-grenser',
			'apply · hold · reject',
			'aldri til main'
		]
	},
	premises: {
		kicker: 'De fire premissene',
		title: 'Produktet er grensen',
		lead: 'Alt annet i Lyngvi er skall rundt disse fire. De er grunnen til at en agent kan kjøre uten tilsyn i et repo du bryr deg om.',
		items: [
			{
				n: '01',
				title: 'Ingen direkte nettverkstilgang',
				body: 'Samtalecontaineren ligger på et internt nett uten default route. All utgående trafikk går gjennom en Squid-proxy med allowlist, og en eksplisitt blocklist over kjente eksfiltrasjonsendepunkter sjekkes først.',
				chip: 'squid · allowlist + blocklist'
			},
			{
				n: '02',
				title: 'Ingen tilgang til .git-en din',
				body: 'Agenten jobber i en kopi med en fersk .git — ingen hooks, ingen config, ingen remotes, ingen credential helper. Hele git-angrepsflaten er fjernet strukturelt, ikke med en regel.',
				chip: 'kopiert arbeidskopi'
			},
			{
				n: '03',
				title: 'Hemmeligheter bor i en gateway-sidecar',
				body: 'Interne systemer nås gjennom et read-only API med allowlist som holder tokenet. Agenten ser aldri annet enn et vertsnavn. En plugin kan ikke lese hemmeligheten: kjøretiden injiserer den i et låst upstream-objekt.',
				chip: 'aldri et token i containeren'
			},
			{
				n: '04',
				title: 'Validert uttak med menneskelig godkjenning',
				body: 'Endringer kommer tilbake som en patch, klassifisert på hosten fil for fil, gjennomgått av deg og committet på en arbeidsgren. Aldri på main, aldri i det stille.',
				chip: 'anvendes · holdes · avvises'
			}
		]
	},
	architecture: {
		kicker: 'Arkitektur',
		title: 'Én kjerne, to front-ender, tre lag med innestenging',
		lead: 'All logikk ligger i en Java-kjerne som både CLI-en og JavaFX-GUI-et kaller i samme prosess. Kjernen er det eneste som snakker med git og Podman. Velg en del av diagrammet for å se hva den garanterer.',
		hostLabel: 'Maskinen din',
		stackLabel: 'Felles stakk (podman compose)',
		sessionLabel: 'Én container per samtale',
		internetLabel: 'Internett',
		intranetLabel: 'Interne systemer',
		internalNet: 'internt nett · ingen default route',
		detailLabel: 'Valgt',
		nodes: {
			gui: {
				title: 'JavaFX-GUI',
				body: 'Et tynt skall over kjernen: hver knapp kaller et kjerne-API, lange kall går på virtuelle tråder, og GUI-et kjører aldri git eller podman selv — en arkitekturtest håndhever det.'
			},
			cli: {
				title: 'setup.sh → lyngvi-cli',
				body: 'Launcheren har ingen egen logikk; den finner en kjøretid og starter CLI-en mot sandkassemappen. Hver kommando tar --json, så hele produktet kan skriptes.'
			},
			core: {
				title: 'lyngvi-core',
				body: 'Java 25. Eier all tilstand, alle git- og podman-kall, patch-klassifisering, strømming med avbrudd og låsing. Én implementasjon av den sikkerhetskritiske uttaksflyten, delt av begge front-ender.'
			},
			state: {
				title: 'state/ og .sessions/',
				body: 'Samtaler, sesjoner, arbeidskopier, patcher og agentlegitimasjon — på din disk, utenfor containeren, eid av brukeren din. Legitimasjon monteres bare inn i containerne til agenten som eier den.'
			},
			proxy: {
				title: 'Squid-proxy',
				body: 'Den eneste veien ut. Tre allowlist-seksjoner (profil, agent, dine egne tillegg) pluss en blocklist som sjekkes først. Endringer lastes inn live; nektede verter dukker opp i domain blocked.'
			},
			gateway: {
				title: 'Gateway-sidecar',
				body: 'Holder legitimasjonen til interne systemer og tilbyr et read-only API med allowlist. Kjører read-only, non-root, cap_drop ALL. Hver systemtype er en plugin-jar lastet via ServiceLoader.'
			},
			session: {
				title: 'Samtalecontainer',
				body: 'cap_drop ALL, no-new-privileges, uprivilegert bruker med din UID, grenser for minne, CPU og prosesser, og ingenting montert utover arbeidskopien, output-mappa, vedleggene og AGENTS.md.'
			}
		}
	},
	screens: {
		kicker: 'Skrivebordsappen',
		title: 'Samme kjerne, med et vindu på',
		lead: 'Hvert skjermbilde under er den ekte JavaFX-appen, kjørt mot en sandkasse med ett repo, tre samtaler og en endring som venter på gjennomgang. Ingen mockups, ingen retusj.',
		note: 'Lyngvi 2.0.0 · mørkt tema · sandkassen «shop»',
		items: [
			{
				id: 'chat',
				label: 'Agentisk koding',
				title: 'En samtale er en arbeidslogg',
				body: 'Hvert svar viser hva agenten leste, søkte i, kjørte og redigerte — med +/−-tall på hver redigering — og hva runden kostet i tokens og dollar. Stripen over skrivefeltet sier hva som venter på uttak, og at én fil holdes tilbake.',
				alt: 'Skjermen Agentisk koding: samtaleliste til venstre og et transkript der agentens verktøykall vises som rader av typen Read, Search, Run og Edit.'
			},
			{
				id: 'deliver',
				label: 'Lever endringer',
				title: 'Gjennomgang på din side av muren',
				body: 'Patchen klassifiseres på din maskin: filer som anvendes, filer som holdes tilbake av policy — her en kjørbar fil, som bare blir med om du krysser den av — og avviste stier som aldri slipper gjennom. Så en arbeidsgren og en commit-melding; beskyttede grener nektes.',
				alt: 'Dialogen Deliver changes: lister for anvendte, tilbakeholdte og avviste filer, diffen av src/auth.ts, et grenfelt og en commit-melding.'
			},
			{
				id: 'diff',
				label: 'Git Diff',
				title: 'Hunks, eller hele filen',
				body: 'Endrede filer til venstre, den valgte til høyre, målt fra sesjonsstart eller fra et hvilket som helst uttak siden. «Whole file» viser hele filen med endringene farget inn.',
				alt: 'Vinduet Git Diff med tre endrede filer og hunkene i src/auth.ts.'
			},
			{
				id: 'traffic',
				label: 'Trafikk',
				title: 'Det proxyen nektet',
				body: 'Hver nektede vert med antall, og allowlisten under med opphavet til hver oppføring — profil, agent eller deg. Tillat én, og proxyen laster på nytt uten omstart.',
				alt: 'Skjermen Traffic: en tabell over nektede verter med status og antall, og allowlisten med kildekolonne.'
			},
			{
				id: 'sessions',
				label: 'Sandkasse',
				title: 'Én container per samtale',
				body: 'Hver sesjonscontainer med tilstand og image, under den delte stacken. Containere som har stått lenger enn profilen tillater, stoppes mens appen kjører; containere uten samtale fjernes.',
				alt: 'Tabellen over sesjonscontainere: tre samtaler med containernavn, tilstand og image.'
			},
			{
				id: 'agents',
				label: 'Agenter',
				title: 'Logg inn én gang, per agent',
				body: 'Påloggingen ligger under sandkassens state-katalog og monteres bare inn i den agentens containere. Fanen Usage ved siden av viser kvoter og forbruk per agent, modell og samtale.',
				alt: 'Skjermen Agents: Claude Code pålogget med abonnement, OpenCode ikke pålogget.'
			}
		]
	},
	sandbox: {
		kicker: 'Inne i sandkassen',
		title: 'Et komplett arbeidsmiljø, og ikke noe mer',
		lead: 'Agenten får et ekte utviklingsmiljø — git, en verktøykjede, et pakkeregister gjennom proxyen — med et strengt avgrenset syn på verden.',
		seesTitle: 'Det agenten ser',
		seesIntro: 'Montert inn i containeren, og ellers ingenting fra hosten:',
		sees: [
			{ path: '/work/<repo>', note: 'en arbeidskopi med fersk .git, én mount per repo' },
			{ path: '/work/output', note: 'filer til deg, utenfor repoene' },
			{ path: '/work/_attachments', note: 'filer du sendte med en melding, read-only' },
			{ path: '/work/AGENTS.md', note: 'husreglene, generert fra sandkasseoppsettet' },
			{ path: 'HTTP_PROXY, HTTPS_PROXY', note: 'den eneste veien ut, til allowlisten' },
			{ path: '<SYSTEM>_GATEWAY_URL', note: 'ett vertsnavn per konfigurert internt system' }
		],
		neverTitle: 'Det den aldri ser',
		neverIntro: 'Strukturelt utenfor rekkevidde, ikke bare frarådet:',
		never: [
			'.git-en din — hooks, config, remotes, credential helpers',
			'Noe annet på filsystemet ditt; ingen hjemmekatalog, ingen SSH-nøkler',
			'Hostmaskinen eller tjenester på dens localhost',
			'Et token til et internt system — gatewayen holder dem',
			'sudo, nye privilegier eller noen Linux-capability',
			'En default route: ukjente verter stopper i proxyen, ikke i brannmuren din'
		],
		agentsTitle: 'Agenten er utskiftbar',
		agentsBody:
			'Claude Code og OpenCode følger med som referanseadaptere, med abonnementsinnlogging eller token. En adapter er en agent.json pluss en kommandokontrakt, så en tredje agent er en konfigfil, ikke en fork. Modeller som kjører på din egen maskin (Ollama, LM Studio, MLX) nås gjennom proxyen på portene profilen navngir.',
		agents: [
			{
				name: 'Claude Code',
				detail: 'abonnement, token eller API-nøkkel · strømming · resonnering · vedlegg'
			},
			{
				name: 'OpenCode',
				detail: 'abonnement eller nøkkel · strømming · lokale og skybaserte modeller'
			},
			{ name: 'Din egen', detail: 'agent.json, eller en adapter.sh i hvilket språk du vil' }
		]
	},
	egress: {
		kicker: 'Kontroll på utgående',
		title: 'Hvert kall agenten gjør, besvart av en liste du eier',
		lead: 'Blocklisten sjekkes først, så allowlisten; alt som ikke står på noen av dem, nektes. Prøv et vertsnavn og se hva proxyen ville svart.',
		placeholder: 'prøv et vertsnavn…',
		inputLabel: 'Vertsnavn å teste mot proxy-reglene',
		examples: 'Eksempler:',
		verdicts: {
			blocked: 'NEKTET — står på blocklisten',
			allowed: 'TILLATT — står på allowlisten',
			gateway: 'RUTET — til gatewayen, som holder hemmeligheten',
			denied: 'NEKTET — står ikke på allowlisten'
		},
		reasons: {
			blocked:
				'Et kjent eksfiltrasjons- eller tunnelendepunkt. Den medfølgende blocklisten dekker request-capture-tjenester, paste-sider, fildeling, tunneler og IP-rekognosering, og sjekkes før alt annet.',
			allowedProfile: 'Tillatt av profilen organisasjonen din leverer.',
			allowedAgent:
				'Tillatt fordi den konfigurerte agenten trenger den — agentallowlister kommer fra agent.json, ikke fra deg.',
			allowedUser: 'Tillatt fordi noen la den til med ./setup.sh domain add.',
			gateway:
				'Et gateway-vertsnavn. Kallet går aldri ut på egen hånd: sidecaren legger på legitimasjonen, håndhever allowlisten for elementer og returnerer read-only data.',
			denied:
				'Står ikke på noen liste, så proxyen svarer med en feil og forsøket blir loggført. Tillat den når du mener det: ./setup.sh domain add <vert>.'
		},
		sections: { profile: 'profil', agent: 'agent', user: 'dine' },
		listTitle: 'Den medfølgende blocklisten, etter kategori',
		categories: [
			{
				name: 'Request capture',
				hosts: 'webhook.site · pipedream.com · requestbin.com · beeceptor.com'
			},
			{ name: 'Paste-sider', hosts: 'pastebin.com · hastebin.com · rentry.co · dpaste.org' },
			{ name: 'Fildeling', hosts: 'transfer.sh · file.io · 0x0.st · catbox.moe · gofile.io' },
			{ name: 'Tunneler', hosts: 'ngrok.io · localtunnel.me · serveo.net · localhost.run' },
			{ name: 'IP-rekognosering', hosts: 'ipinfo.io · ifconfig.me · icanhazip.com · ipify.org' }
		],
		cmdNote: 'Fra kommandolinjen:',
		figure: {
			caption:
				'I GUI-et er den samme listen en tabell: hver vert proxyen nektet, med antall, og en Allow-knapp som legger den til i din del av allowlisten og laster proxyen på nytt.',
			alt: 'Trafikk-tabellen i GUI-et: fire nektede verter med status 403 og antall, og knappene Allow, Refresh, Follow og Stop.'
		}
	},
	delivery: {
		kicker: 'Uttak',
		title: 'Ingenting forlater sandkassen usett',
		lead: 'Et uttak måles fra et utgangspunkt — sesjonsstart, forrige uttak, eller et uttak midt imellom — bygges til en patch inne i containeren, og klassifiseres deretter på hosten. Klassifiseringen er din, ikke agentens.',
		steps: [
			{
				title: 'Utgangspunkt',
				body: 'Velg hvor diffen starter: sesjonsstart, forrige uttak, eller et tidligere ett.'
			},
			{
				title: 'Patch',
				body: 'Bygget med en midlertidig indeks, så ucommitet, uindeksert og agent-committet arbeid blir med.'
			},
			{
				title: 'Klassifiser',
				body: 'Tre bøtter på hosten, etter regel: anvendes, holdes tilbake, avvises.'
			},
			{
				title: 'Gjennomgang',
				body: 'Fil for fil, som hunks eller hele filer, i GUI-et eller CLI-en.'
			},
			{ title: 'Gren', body: 'Commit eller staging på en arbeidsgren. Beskyttede grener nektes.' }
		],
		tableTitle: 'session review 7f3a2c',
		tableHint:
			'Klikk en holdt fil for å ta den med — holdte filer leveres bare når du navngir dem.',
		columns: { file: 'Fil', change: 'Endring', bucket: 'Bøtte' },
		buckets: {
			apply: 'anvendes',
			hold: 'holdes',
			reject: 'avvises'
		},
		bucketRules: {
			apply: 'Vanlige tekstendringer, anvendt når du godkjenner dem.',
			hold: 'Kjørbare filer, symlinker, binærfiler og for store filer. Leveres bare når de navngis eksplisitt.',
			reject:
				'Stier med .., absolutte stier, .git-segmenter, ugyldig UTF-8, patcher som ikke kan parses. Leveres aldri, uansett hva du klikker.'
		},
		files: [
			{ path: 'src/auth.ts', change: 'endret', bucket: 'apply', stat: '+12 −3' },
			{ path: 'src/session/cookie.ts', change: 'endret', bucket: 'apply', stat: '+4 −1' },
			{ path: 'tests/auth.spec.ts', change: 'ny', bucket: 'apply', stat: '+58 −0' },
			{
				path: 'bin/run.sh',
				change: 'ny',
				bucket: 'hold',
				stat: 'mode 100755',
				reason: 'EXECUTABLE'
			},
			{
				path: 'docs/screenshot.png',
				change: 'ny',
				bucket: 'hold',
				stat: 'binær',
				reason: 'BINARY'
			},
			{
				path: '../secrets.env',
				change: 'ny',
				bucket: 'reject',
				stat: '+1 −0',
				reason: 'PATH_TRAVERSAL'
			}
		],
		applyLabel: 'session apply --branch',
		branch: 'agent/fix-login',
		summary: (/** @type {number} */ n) =>
			`${n} fil${n === 1 ? '' : 'er'} blir committet på agent/fix-login.`,
		protectedNote: 'main, master og develop nektes av profilen; grenprefikset bestemmer du selv.',
		figure: {
			caption:
				'Samme gjennomgang i GUI-et: det som anvendes, det som holdes tilbake til du krysser det av, det som avvises — og diffen av den valgte filen ved siden av.',
			alt: 'Gjennomgangsdelen av dialogen Deliver changes: en advarsel om at én av tre filer holdes tilbake, listene Will be applied, Held back og Rejected, og diffen av src/auth.ts.'
		}
	},
	gateway: {
		kicker: 'Gatewayen',
		title: 'Nå intranettet uten å gi fra deg nøkkelen',
		lead: 'Noe arbeid trenger wikien, issue-trackeren eller et internt API. Gatewayen er en sidecar som holder legitimasjonen og tilbyr et smalt, read-only utsnitt med allowlist. Agenten får et vertsnavn, og en 403 når den spør om noe du ikke har tillatt.',
		guaranteesTitle: 'Hva kjøretiden garanterer, uansett hva en plugin gjør',
		guarantees: [
			'Ukjent vertsnavn → 404, uten noe upstream-kall i det hele tatt',
			'Kun GET og HEAD, med mindre både instansen og profilen tillater skriving',
			'Upstream-objektet er låst til én origin; .. og absolutte URL-er avvises',
			'Hemmeligheten injiseres av kjøretiden og finnes ikke som streng noe sted en plugin kan lese',
			'Allowlisten leses på nytt ved hvert oppslag og håndheves før handleren kjører',
			'Tom allowlist betyr nekt alt',
			'no-store på hvert svar; set-cookie og hop-by-hop-headere fjernes',
			'Maskert logging: Authorization-verdier og *_TOKEN-variabler skrives aldri',
			'En instans som feiler, feiler alene; /healthz viser den som configured: false'
		],
		pluginsTitle: 'Hver systemtype er en plugin',
		pluginsBody:
			'http er innebygd og dekker ethvert REST-API bak en statisk hemmelighet. confluence og jira følger med som jar-er. Din egen er en klasse som implementerer GatewayPlugin pluss en service-oppføring, lagt inn med gateway plugin install. Kodebasen leveres uten konfigurerte systemer — en profilpakke legger til det organisasjonen din trenger.',
		exchangeTitle: 'Slik ser det ut innenfra',
		allowedComment: 'tillatt: space OPS står på lista',
		deniedComment: 'ikke tillatt ennå — mennesket må legge den til',
		deniedBody: 'gatewayen svarer før noe upstream-kall'
	},
	features: {
		kicker: 'Resten av det',
		title: 'Bygget for å bo i',
		lead: 'Grensene er poenget, men en sandkasse du unngår, beskytter ingenting.',
		items: [
			{
				title: 'Én container per samtale',
				body: 'Sesjoner kan ikke se eller redigere hverandres arbeid, og ressursgrenser betyr noe. Start, stopp, shell inn, rydd bort de inaktive.'
			},
			{
				title: 'Profiler i stedet for hardkoding',
				body: 'Vertsnavn, allowlister, speil, CA-navn, beskyttede grener, verktøykjeder og språk ligger i profile.json og en profilpakke. Ingen organisasjon er bakt inn i kildekoden.'
			},
			{
				title: 'Verktøykjeder som fragmenter',
				body: 'Et slankt Debian-basisimage pluss fragmentene du velger: Java 17/21/25, Node 22, Python 3.12, Go, .NET 8, Maven, Gradle, Kotlin.'
			},
			{
				title: 'Ressursprofiler',
				body: 'review (2g, 1 cpu), dev (8g, 4), build (16g, 6) — eller én utledet fra maskinen din. Valgt per samtale, etter hva samtalen skal gjøre.'
			},
			{
				title: '--json på hver kommando',
				body: 'Ett JSON-dokument på stdout, dokumenterte exit-koder, og en linjedelt hendelsesstrøm fra session ask. CLI-kontrakten er det offentlige, språkuavhengige grensesnittet.'
			},
			{
				title: 'Et GUI som bare er et skall',
				body: 'Nav-rail, strømmende chat med verktøykall og forbruk, et filpanel, en diff-visning med helfilsmodus, og samme gjennomgangs- og uttaksflyt som CLI-en.'
			},
			{
				title: 'Pinnet og reproduserbart',
				body: 'Basisimage, Squid og gateway-basen er pinnet med digest; agentens CLI-versjon er pinnet i fragmentet. doctor --deep beviser egress-reglene fra en engangscontainer.'
			},
			{
				title: 'Vedlegg og lokale modeller',
				body: 'Slipp filer eller lim inn et bilde i skrivefeltet. Pek sandkassen mot Ollama, LM Studio eller en MLX-server på din egen maskin; proxyen åpner bare de portene.'
			},
			{
				title: 'Engelsk og norsk',
				body: 'Hver brukervendt streng kommer fra en ressursfil, i CLI-en som i GUI-et. --lang velger én per kommando.'
			}
		]
	},
	hardening: {
		kicker: 'Herding, i én tabell',
		title: 'Hva som er skrudd av, overalt',
		rows: [
			{ k: 'Nett', v: 'Kun internt nett; ingen default route fra en samtalecontainer' },
			{
				k: 'Capabilities',
				v: 'cap_drop ALL og no-new-privileges på alle containere, samtalecontainere inkludert'
			},
			{
				k: 'Bruker',
				v: 'Uprivilegert agent-bruker med din UID og GID, så monterte filer beholder eieren'
			},
			{
				k: 'Filsystem',
				v: 'Kopiert arbeidskopi; gatewayen kjører read-only; ellers monteres ingenting fra hosten'
			},
			{
				k: 'Pakkeverktøy',
				v: 'Livssyklus-skript av: npm_config_ignore_scripts, YARN_ENABLE_SCRIPTS=false'
			},
			{
				k: 'git',
				v: 'Ingen systemconfig, ingen terminalprompt, ingen signering, identitet satt av profilen'
			},
			{
				k: 'Legitimasjon',
				v: 'Agentlegitimasjon under state/agents/<id>/, montert bare inn i den agentens containere; ingen tokens i .env'
			},
			{
				k: 'Grener',
				v: 'Beskyttede grener fra profilen pluss repoets default; uttak lander alltid på en arbeidsgren'
			},
			{
				k: 'Images',
				v: 'Basisimage, Squid og gateway-base pinnet med digest; CI verifiserer pinningen'
			}
		],
		limitsTitle: 'Og hva det ikke løser',
		limits: [
			'Agentens egen legitimasjon ligger i containeren — en dokumentert begrensning, med token-injeksjon via gatewayen designet inn som fremtidig arbeid.',
			'Samtalecontainere deler ett internt nett, så en agent som starter en tjeneste kan nås fra en annen sesjon. Ingenting lytter som standard.'
		]
	},
	install: {
		kicker: 'Kom i gang',
		title: 'Fra ingenting til en kjørende sandkasse',
		lead: 'CLI-en leveres som et jlink-bilde med egen kjøretid, og GUI-et som en jpackage-app med CLI-en inni. Du installerer ikke Java.',
		requirements: 'Krav: macOS eller Linux · Podman 5+ · Java 25 kun hvis du bygger fra kildekode',
		steps: [
			{
				title: 'Hent CLI-en',
				body: 'Lag en mappe, legg setup.sh i den, og la den hente kjøretiden for plattformen din. Nedlastingen sjekksumverifiseres.'
			},
			{
				title: 'Lag en sandkasse',
				body: 'Pek den mot et repo. init skriver sandkassens egen launcher og en kopi av CLI-en.'
			},
			{
				title: 'Bygg og start',
				body: 'Render konfigurasjonen, bygg imaget, start proxy og gateway, og la doctor sjekke resultatet.'
			},
			{
				title: 'Jobb',
				body: 'En samtale får en container. Spør, les diffen, lever det du godkjenner.'
			}
		],
		docsTitle: 'Mer lesing',
		docs: [
			{ label: 'README', note: 'kommandoer, oppbygning og den første kjøringen' },
			{ label: 'docs/agents.md', note: 'å skrive en agentadapter og hendelseskontrakten' },
			{ label: 'docs/gateway-plugins.md', note: 'å skrive og pakke en gateway-plugin' },
			{ label: 'docs/profiles.md', note: 'hver seksjon i en profilpakke' },
			{ label: 'acceptance/', note: 'akseptansesuiten og sjekklisten' }
		]
	},
	footer: {
		tagline: 'En herdet, agentuavhengig sandkasse for autonome kodeagenter.',
		licence: 'MIT-lisensiert.',
		origin:
			'Lyngvi er øya der Fenrir ble bundet. Ulven i merket er agentens kraft, barrieren dens grenser, og de tre glimtene dens kunstige intelligens.',
		colophon: 'Denne siden er statisk: ingen sporing, ingen analyse, ingen tredjepartskall.',
		status: 'Status: førlansering'
	}
};

export const content = { en, no };
