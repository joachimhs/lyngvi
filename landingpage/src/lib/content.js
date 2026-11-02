/**
 * All user-visible copy for the landing page, in the two languages Lyngvi itself
 * ships with: English as the default, Norwegian as a full translation.
 *
 * The shape of `en` and `no` is identical; components read `i18n.t.<section>`.
 */

export const en = {
	meta: {
		title: 'Lyngvi — a hardened sandbox for autonomous coding agents',
		description: 'Lyngvi runs Claude Code, OpenCode or your own agent against your repositories inside a hardened container: no direct network, no access to your .git, secrets behind a gateway, and every change delivered as a reviewed patch.'
	},
	nav: {
		links: [
			{
				href: '#premises',
				label: 'Premises'
			},
			{
				href: '#architecture',
				label: 'Architecture'
			},
			{
				href: '#screens',
				label: 'Screens'
			},
			{
				href: '#sandbox',
				label: 'The sandbox'
			},
			{
				href: '#egress',
				label: 'Egress'
			},
			{
				href: '#delivery',
				label: 'Delivery'
			},
			{
				href: '#gateway',
				label: 'Gateway'
			},
			{
				href: '#features',
				label: 'Features'
			},
			{
				href: '#install',
				label: 'Install'
			}
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
		origin: 'Named after the island in Norse mythology where the wolf Fenrir was bound: a place where great power is kept within defined boundaries.',
		stats: [
			{
				value: '0',
				label: 'direct network routes out of the container'
			},
			{
				value: '1',
				label: 'container per conversation'
			},
			{
				value: '3',
				label: 'buckets every changed file is classified into'
			}
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
			{
				path: '/work/<repo>',
				note: 'a working copy with a fresh .git, one mount per repository'
			},
			{
				path: '/work/output',
				note: 'files meant for you, outside the repositories'
			},
			{
				path: '/work/_attachments',
				note: 'files you sent with a message, read-only'
			},
			{
				path: '/work/AGENTS.md',
				note: 'the house rules, generated from the sandbox config'
			},
			{
				path: 'HTTP_PROXY, HTTPS_PROXY',
				note: 'the only way out, to the allowlist'
			},
			{
				path: '<SYSTEM>_GATEWAY_URL',
				note: 'a hostname per configured internal system'
			}
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
		agentsBody: 'Claude Code and OpenCode ship as reference adapters, with subscription login or a token. An adapter is an agent.json plus a command contract, so a third agent is a config file, not a fork. Models running on your own machine (Ollama, LM Studio, MLX) are reached through the proxy on the ports your profile names.',
		agents: [
			{
				name: 'Claude Code',
				detail: 'subscription, token or API key · streaming · reasoning · attachments'
			},
			{
				name: 'OpenCode',
				detail: 'subscription or key · streaming · local and hosted models'
			},
			{
				name: 'Your own',
				detail: 'agent.json, or an adapter.sh in any language'
			}
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
			blocked: 'A known exfiltration or tunnelling endpoint. The bundled blocklist covers request-capture services, paste sites, file drops, tunnels and IP reconnaissance, and it is checked before anything else.',
			allowedProfile: 'Allowed by the profile your organisation ships.',
			allowedAgent: 'Allowed because the configured agent needs it — agent allowlists come from agent.json, not from you.',
			allowedUser: 'Allowed because someone added it with ./setup.sh domain add.',
			gateway: 'A gateway hostname. The request never leaves on its own: the sidecar adds the credential, enforces the item allowlist and returns read-only data.',
			denied: 'Not on any list, so the proxy answers with an error and the attempt is recorded. Allow it when you mean to: ./setup.sh domain add <host>.'
		},
		sections: {
			profile: 'profile',
			agent: 'agent',
			user: 'yours'
		},
		listTitle: 'The bundled blocklist, by category',
		categories: [
			{
				name: 'Request capture',
				hosts: 'webhook.site · pipedream.com · requestbin.com · beeceptor.com'
			},
			{
				name: 'Paste sites',
				hosts: 'pastebin.com · hastebin.com · rentry.co · dpaste.org'
			},
			{
				name: 'File sharing',
				hosts: 'transfer.sh · file.io · 0x0.st · catbox.moe · gofile.io'
			},
			{
				name: 'Tunnels',
				hosts: 'ngrok.io · localtunnel.me · serveo.net · localhost.run'
			},
			{
				name: 'IP reconnaissance',
				hosts: 'ipinfo.io · ifconfig.me · icanhazip.com · ipify.org'
			}
		],
		cmdNote: 'Live from the command line:',
		figure: {
			caption: 'In the GUI the same list is a table: every host the proxy denied, with a count, and an Allow button that adds it to your section of the allowlist and reloads the proxy.',
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
			{
				title: 'Classify',
				body: 'Three buckets on the host, by rule: apply, hold, reject.'
			},
			{
				title: 'Review',
				body: 'File by file, as hunks or whole files, in the GUI or the CLI.'
			},
			{
				title: 'Branch',
				body: 'Commit or stage on a working branch. Protected branches are refused.'
			}
		],
		tableTitle: 'session review 7f3a2c',
		tableHint: 'Click a held file to include it — held files are only delivered when you name them.',
		columns: {
			file: 'File',
			change: 'Change',
			bucket: 'Bucket'
		},
		buckets: {
			apply: 'apply',
			hold: 'hold',
			reject: 'reject'
		},
		bucketRules: {
			apply: 'Ordinary text changes, applied when you approve them.',
			hold: 'Executables, symlinks, binaries and oversized files. Delivered only when named explicitly.',
			reject: 'Path traversal, absolute paths, .git segments, invalid UTF-8, unparseable patches. Never delivered, whatever you click.'
		},
		files: [
			{
				path: 'src/auth.ts',
				change: 'modified',
				bucket: 'apply',
				stat: '+12 −3'
			},
			{
				path: 'src/session/cookie.ts',
				change: 'modified',
				bucket: 'apply',
				stat: '+4 −1'
			},
			{
				path: 'tests/auth.spec.ts',
				change: 'added',
				bucket: 'apply',
				stat: '+58 −0'
			},
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
		summary: (/** @type {number} */ n) => `${n} file${n === 1 ? '' : 's'} will be committed on agent/fix-login.`,
		protectedNote: 'main, master and develop are refused by the profile; the branch prefix is yours to set.',
		figure: {
			caption: 'The same review in the GUI: what will be applied, what is held back until you tick it, what is rejected — and the diff of the selected file beside it.',
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
		pluginsBody: 'http is built in and covers any REST API behind a static secret. confluence and jira ship as jars. Your own is a class implementing GatewayPlugin plus a service entry, dropped in with gateway plugin install. The codebase ships with no systems configured — a profile pack adds what your organisation needs.',
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
			{
				k: 'Network',
				v: 'Internal network only; no default route from a session container'
			},
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
		cliTitle: 'In the terminal',
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
		guiTitle: 'Or in the app',
		guiLead:
			'The desktop app covers the same ground in a «New sandbox» wizard, and there is nothing else to install: the CLI ships inside the app, and a sandbox made here links its own cli/ to it — so the same folder is yours to work in from the terminal whenever you want to.',
		guiSteps: [
			{
				title: 'Folder and repositories',
				body: 'Where the sandbox lives, and which repositories a conversation may be given a copy of.'
			},
			{
				title: 'Base image',
				body: 'The toolchains the profile asks for and the agents it enables, built once.'
			},
			{
				title: 'Shared stack',
				body: 'The proxy and the gateway, started and left running while the sandbox is in use.'
			},
			{
				title: 'Check',
				body: 'The same checks as doctor: the container runtime, the image, the stack, the repositories and the certificate.'
			},
			{
				title: 'Agent',
				body: 'Log in once, or point the sandbox at a model server of your own. Either can wait until later.'
			},
			{
				title: 'Ready',
				body: 'The sandbox is made, and the first conversation can open as the wizard closes.'
			}
		],
		guiShot: {
			alt: 'The New sandbox wizard: the six steps in a rail on the left, and on the first page the fields for the sandbox folder, an optional profile pack and the repositories it may copy.',
			caption:
				'Step one of six. Nothing has been made yet — «Create» writes the sandbox folder and renders its configuration, and the wizard moves on to the image.'
		},
		guiNote:
			'macOS: a .dmg, opened the first time with right-click → Open, since the app is not notarised yet. Linux: a .deb or the unpacked app image. Then the conversation, the diff and the delivery are the windows above.',
		guiLink: 'The app, screen by screen',
		docsTitle: 'Further reading',
		docs: [
			{
				label: 'README',
				note: 'commands, layout and the first run'
			},
			{
				label: 'docs/agents.md',
				note: 'writing an agent adapter and the event contract'
			},
			{
				label: 'docs/gateway-plugins.md',
				note: 'writing and packaging a gateway plugin'
			},
			{
				label: 'docs/profiles.md',
				note: 'every section of a profile pack'
			},
			{
				label: 'acceptance/',
				note: 'the acceptance suite and checklist'
			}
		]
	},
	footer: {
		tagline: 'A hardened, agent-agnostic sandbox for autonomous coding agents.',
		licence: 'MIT licensed.',
		origin: 'Lyngvi is the island where Fenrir was bound. The wolf in the mark is the agent’s power, the barrier its boundaries, and the three sparkles its artificial intelligence.',
		colophon: 'This page is static: no trackers, no analytics, no third-party requests.',
		status: 'Status: pre-release'
	}
};

export const no = {
	meta: {
		title: 'Lyngvi — en sandkasse med strenge sikkerhetsgrenser for autonome kodeagenter',
		description: 'La Claude Code, OpenCode eller din egen kodeagent arbeide i en isolert container med kontrollert nettverkstilgang. Din Git-historikk og tilgangsnøklene til interne systemer holdes utenfor, og du gjennomgår endringene før de overføres.'
	},
	nav: {
		links: [
			{
				href: '#premises',
				label: 'Sikkerhetsprinsipper'
			},
			{
				href: '#architecture',
				label: 'Arkitektur'
			},
			{
				href: '#screens',
				label: 'Skjermbilder'
			},
			{
				href: '#sandbox',
				label: 'Sandkassen'
			},
			{
				href: '#egress',
				label: 'Nettverkstilgang'
			},
			{
				href: '#delivery',
				label: 'Overfør endringer'
			},
			{
				href: '#gateway',
				label: 'Gateway'
			},
			{
				href: '#features',
				label: 'Funksjoner'
			},
			{
				href: '#install',
				label: 'Kom i gang'
			}
		],
		source: 'Kildekode',
		langLabel: 'English',
		skip: 'Hopp til innholdet'
	},
	hero: {
		badge: 'v2.0.0 · MIT · macOS og Linux · Podman',
		title: 'Gi agenten koden din.',
		titleAccent: 'Ikke maskinen din.',
		lead: 'Lyngvi lar autonome kodeagenter arbeide med repoene dine i en sandkasse med strenge sikkerhetsgrenser. Agenten får en arbeidskopi av koden, utviklingsverktøyene den trenger og kontrollert nettverkstilgang gjennom en proxy. Din Git-historikk og tilgangsnøklene til interne systemer holdes utenfor agentens rekkevidde. Bruk Claude Code, OpenCode eller en agent du selv velger.',
		ctaPrimary: 'Kom i gang',
		ctaSecondary: 'Slik fungerer Lyngvi',
		origin: 'Lyngvi er oppkalt etter øya i norrøn mytologi der Fenrisulven ble bundet – et sted der store krefter holdes innenfor klare grenser.',
		stats: [
			{
				value: '0',
				label: 'direkte forbindelser fra containeren til internett'
			},
			{
				value: '1',
				label: 'egen container per samtale'
			},
			{
				value: '3',
				label: 'kategorier for kontroll av endrede filer'
			}
		],
		terminalTitle: 'første kjøring, fra start til slutt',
		ticker: [
			'cap_drop ALL',
			'no-new-privileges',
			'din UID, aldri root',
			'ingen standardrute',
			'blokkeringsliste før tillatelsesliste',
			'et nytt Git-repo',
			'skrivebeskyttet gateway',
			'grenser for minne · CPU · prosesser',
			'klar · krever godkjenning · avvist',
			'aldri til main'
		]
	},
	premises: {
		kicker: 'Fire sikkerhetsprinsipper',
		title: 'Tydelige grenser for hva agenten får gjøre',
		lead: 'Lyngvi bygger på fire sikkerhetsprinsipper. Sammen gjør de det mulig å la agenten arbeide selvstendig, samtidig som du beholder kontrollen over kode, tilganger og endringer.',
		items: [
			{
				n: '01',
				title: 'Ingen direkte tilgang til internett',
				body: 'Hver samtale kjører i en container på et internt nettverk uten standardrute. All utgående trafikk må gjennom en Squid-proxy som bare slipper gjennom godkjente verter. En liste over kjente endepunkter for datalekkasje kontrolleres først.',
				chip: 'Squid · tillatelsesliste + blokkeringsliste'
			},
			{
				n: '02',
				title: 'Din Git-historikk holdes utenfor',
				body: 'Agenten arbeider i en kopi av koden med et nytt Git-repo. Dine hooks, innstillinger, eksterne repo-adresser og hjelpere for innloggingsopplysninger følger ikke med. Denne delen av Git-angrepsflaten fjernes gjennom selve oppsettet.',
				chip: 'egen arbeidskopi'
			},
			{
				n: '03',
				title: 'Tilgangsnøkler oppbevares i gatewayen',
				body: 'Agenten får begrenset lesetilgang til godkjent innhold i interne systemer gjennom en separat gateway. Gatewayen håndterer autentiseringen. Kjøremiljøet setter inn tilgangsnøkkelen i et låst objekt for kommunikasjon med systemet, slik at programtilleggene heller ikke får lese nøkkelen.',
				chip: 'interne tilgangsnøkler holdes utenfor agentcontaineren'
			},
			{
				n: '04',
				title: 'Du godkjenner endringene før de overføres',
				body: 'Endringene hentes ut som en patch og kontrolleres fil for fil på maskinen din. Du gjennomgår resultatet og velger hva som skal overføres til en arbeidsgren. Endringer legges aldri direkte på main eller overføres uten godkjenning.',
				chip: 'klar · krever ekstra godkjenning · avvist'
			}
		]
	},
	architecture: {
		kicker: 'Arkitektur',
		title: 'Én kjerne, to grensesnitt og tre lag med isolasjon',
		lead: 'Kommandolinjen og skrivebordsappen bruker den samme Java-kjernen, som kjører i samme prosess som grensesnittet. Bare kjernen kommuniserer med Git og Podman. Klikk på en del av diagrammet for å se hvilken rolle den har i sikkerhetsmodellen.',
		hostLabel: 'Maskinen din',
		stackLabel: 'Felles tjenester (podman compose)',
		sessionLabel: 'Én container per samtale',
		internetLabel: 'Internett',
		intranetLabel: 'Interne systemer',
		internalNet: 'internt nettverk · ingen standardrute',
		detailLabel: 'Valgt',
		nodes: {
			gui: {
				title: 'JavaFX-app',
				body: 'Skrivebordsappen bruker kjernens API-er for alle handlinger. Langvarige kall kjører på virtuelle tråder. En arkitekturtest sikrer at appen aldri kjører Git eller Podman direkte.'
			},
			cli: {
				title: 'setup.sh → lyngvi-cli',
				body: 'Oppstartsskriptet finner Java-kjøremiljøet og starter kommandolinjeverktøyet for den valgte sandkassemappen. All funksjonslogikk ligger i verktøyet. Alle kommandoer støtter --json, slik at Lyngvi kan brukes fra skript.'
			},
			core: {
				title: 'lyngvi-core',
				body: 'Bygget med Java 25. Kjernen håndterer all tilstand, alle kall til Git og Podman, kontroll av patcher, strømming med støtte for avbrudd og låsing. Begge brukergrensesnittene deler den samme implementasjonen av den sikkerhetskritiske overføringen av endringer.'
			},
			state: {
				title: 'state/ og .sessions/',
				body: 'Samtaler, sesjoner, arbeidskopier, patcher og agentens innloggingsopplysninger lagres på disken din med din bruker som eier. Innloggingsopplysningene monteres bare inn i containerne til agenten de tilhører.'
			},
			proxy: {
				title: 'Squid-proxy',
				body: 'All utgående nettverkstrafikk går gjennom proxyen. Tillatelseslisten har tre deler: profilens oppføringer, agentens oppføringer og dine egne tillegg. Blokkeringslisten kontrolleres først. Regelendringer tas i bruk uten omstart, og avviste verter vises med domain blocked.'
			},
			gateway: {
				title: 'Separat gateway-container',
				body: 'Gatewayen oppbevarer tilgangsnøklene til interne systemer og tilbyr et API med begrenset lesetilgang. Den kjører med skrivebeskyttet filsystem, uten root og med cap_drop ALL. Hver systemtype støttes av et programtillegg i en JAR-fil, lastet med ServiceLoader.'
			},
			session: {
				title: 'Container for samtalen',
				body: 'Containeren kjører med cap_drop ALL, no-new-privileges og en bruker uten administratorrettigheter, med din UID. Minne, CPU og antall prosesser er begrenset. Arbeidskopien, resultatmappen, vedleggene og AGENTS.md monteres inn som arbeidsgrunnlag. Agentens egne innloggingsopplysninger monteres separat.'
			}
		}
	},
	screens: {
		kicker: 'Skrivebordsappen',
		title: 'Full oversikt i skrivebordsappen',
		lead: 'Skjermbildene viser JavaFX-appen med ett repo, tre samtaler og endringer som venter på gjennomgang. Bildene er hentet direkte fra appen og er ikke retusjert.',
		note: 'Lyngvi 2.0.0 · mørkt tema · sandkassen «shop»',
		items: [
			{
				id: 'chat',
				label: 'Agentisk koding',
				title: 'Se hva agenten gjør, steg for steg',
				body: 'Se hvilke filer agenten leser, hvilke søk og kommandoer den kjører, og hva den endrer. Hver redigering viser antall linjer som er lagt til og fjernet. Du ser også tokenforbruk og kostnad i dollar for hver runde. Feltet over skriveområdet viser hvilke endringer som venter på overføring, og at én fil krever ekstra godkjenning.',
				alt: 'Visningen for agentisk koding med samtaleliste til venstre og en samtalelogg med verktøykall merket Read, Search, Run og Edit.'
			},
			{
				id: 'deliver',
				label: 'Overfør endringer',
				title: 'Gjennomgå endringene på din egen maskin',
				body: 'Lyngvi kontrollerer patchen på maskinen din og skiller mellom filer som er klare for overføring, filer som krever ekstra godkjenning, og avviste filstier. Den kjørbare filen i eksemplet tas bare med hvis du velger den uttrykkelig. Deretter velger du en arbeidsgren og skriver en commit-melding. Overføring til beskyttede grener avvises.',
				alt: 'Dialogen Deliver changes med lister over filer som er klare, tilbakeholdte eller avvist, en diff av src/auth.ts, et felt for grennavn og en commit-melding.'
			},
			{
				id: 'diff',
				label: 'Git Diff',
				title: 'Se endrede linjer eller hele filen',
				body: 'Listen over endrede filer vises til venstre, og innholdet i den valgte filen vises til høyre. Du kan sammenligne med starten på sesjonen eller en tidligere overføring. Velg «Whole file» for å se hele filen med endringene markert.',
				alt: 'Vinduet Git Diff med tre endrede filer og endrede linjer i src/auth.ts.'
			},
			{
				id: 'traffic',
				label: 'Trafikk',
				title: 'Se hvilke forbindelser som ble blokkert',
				body: 'Oversikten viser avviste verter og antall tilkoblingsforsøk. Under vises tillatte verter og hvor hver oppføring kommer fra: profilen, agenten eller dine egne tillegg. Når du tillater en vert, tas endringen i bruk uten omstart av proxyen.',
				alt: 'Visningen Traffic med avviste verter, status og antall forsøk, samt tillatelseslisten med kilde for hver oppføring.'
			},
			{
				id: 'sessions',
				label: 'Sandkasse',
				title: 'Én container per samtale',
				body: 'Se status og containerimage for hver samtale, sammen med de felles tjenestene. Mens appen kjører, stoppes containere som har vært inaktive lenger enn profilen tillater. Containere uten en tilhørende samtale fjernes.',
				alt: 'Tabell over containere for tre samtaler, med containernavn, status og containerimage.'
			},
			{
				id: 'agents',
				label: 'Agenter',
				title: 'Egen innlogging for hver agent',
				body: 'Innloggingsopplysningene lagres i sandkassens state-mappe og monteres bare inn i containerne til den aktuelle agenten. Fanen Usage viser kvoter og forbruk fordelt på agent, modell og samtale.',
				alt: 'Visningen Agents med Claude Code innlogget via abonnement og OpenCode uten aktiv innlogging.'
			}
		]
	},
	sandbox: {
		kicker: 'Inne i sandkassen',
		title: 'Alt agenten trenger for å kode',
		lead: 'Agenten får et komplett utviklingsmiljø med Git, valgte utviklingsverktøy og tilgang til pakkeregistre gjennom proxyen. Tilgangen til resten av systemet er strengt avgrenset.',
		seesTitle: 'Dette har agenten tilgang til',
		seesIntro: 'Arbeidsfiler, innstillinger og adresser som er tilgjengelige i containeren:',
		sees: [
			{
				path: '/work/<repo>',
				note: 'egen arbeidskopi med et nytt Git-repo, montert separat for hvert repo'
			},
			{
				path: '/work/output',
				note: 'filer til deg, utenfor repoene'
			},
			{
				path: '/work/_attachments',
				note: 'vedlegg fra meldingene dine, med kun lesetilgang'
			},
			{
				path: '/work/AGENTS.md',
				note: 'arbeidsregler generert fra sandkassens konfigurasjon'
			},
			{
				path: 'HTTP_PROXY, HTTPS_PROXY',
				note: 'proxyen som håndhever hvilke verter agenten får kontakte'
			},
			{
				path: '<SYSTEM>_GATEWAY_URL',
				note: 'ett vertsnavn per konfigurert internt system'
			}
		],
		neverTitle: 'Dette holdes utenfor',
		neverIntro: 'Tilgangen begrenses av det tekniske oppsettet:',
		never: [
			'Din opprinnelige .git-mappe med hooks, innstillinger, eksterne repo-adresser og hjelpere for innloggingsopplysninger',
			'Resten av filsystemet ditt, inkludert hjemmemappen og SSH-nøklene dine',
			'Direkte tilgang til vertsmaskinen og tjenester på dens localhost',
			'Tilgangsnøkler til interne systemer – disse oppbevares i gatewayen',
			'sudo, utvidede rettigheter og Linux-rettigheter av typen capabilities',
			'En standardrute til internett – verter som ikke er tillatt, avvises av proxyen'
		],
		agentsTitle: 'Velg agenten som passer oppgaven',
		agentsBody: 'Lyngvi leveres med adaptere for Claude Code og OpenCode, med støtte for innlogging via abonnement eller tilgangstoken. En adapter består av agent.json og et definert kommandogrensesnitt, slik at nye agenter kan legges til gjennom konfigurasjon. Ved behov kan du bruke et eget adapterskript. Lokale modeller i Ollama, LM Studio og MLX nås gjennom proxyen på portene profilen tillater.',
		agents: [
			{
				name: 'Claude Code',
				detail: 'abonnement, tilgangstoken eller API-nøkkel · strømming · resonnering · vedlegg'
			},
			{
				name: 'OpenCode',
				detail: 'abonnement eller API-nøkkel · strømming · lokale og skybaserte modeller'
			},
			{
				name: 'Din egen agent',
				detail: 'agent.json eller et adapter.sh-skript skrevet i valgfritt språk'
			}
		]
	},
	egress: {
		kicker: 'Nettverkstilgang',
		title: 'Du bestemmer hvilke verter agenten får kontakte',
		lead: 'Proxyen sjekker først om verten er blokkert, og deretter om den er tillatt. Alle andre forbindelser avvises. Skriv inn et vertsnavn for å se hvordan reglene slår ut.',
		placeholder: 'Prøv et vertsnavn …',
		inputLabel: 'Vertsnavn som skal testes mot proxyreglene',
		examples: 'Eksempler:',
		verdicts: {
			blocked: 'BLOKKERT – står på blokkeringslisten',
			allowed: 'TILLATT – står på tillatelseslisten',
			gateway: 'VIA GATEWAYEN – tilgangsnøkkelen håndteres der',
			denied: 'AVVIST – verten er ikke tillatt'
		},
		reasons: {
			blocked: 'Verten er et kjent endepunkt for datauthenting eller tunneltrafikk. Den medfølgende blokkeringslisten dekker tjenester for mottak av HTTP-forespørsler, deling av tekst og filer, tunneler og oppslag av offentlig IP-adresse. Listen kontrolleres før tillatelseslisten.',
			allowedProfile: 'Verten er tillatt i profilen organisasjonen din har satt opp.',
			allowedAgent: 'Verten er tillatt fordi den konfigurerte agenten trenger tilgang. Oppføringen hentes fra agent.json.',
			allowedUser: 'Verten er lagt til som et eget unntak med ./setup.sh domain add.',
			gateway: 'Forespørselen går gjennom gatewayen, som legger til innloggingsopplysningene og kontrollerer hvilke elementer agenten får lese. Agenten får kun lesetilgang til godkjent innhold.',
			denied: 'Verten er ikke tillatt. Proxyen avviser forespørselen og logger forsøket. Hvis tilgangen er nødvendig, kan du tillate verten med ./setup.sh domain add <vert>.'
		},
		sections: {
			profile: 'profil',
			agent: 'agent',
			user: 'egne tillegg'
		},
		listTitle: 'Den medfølgende blokkeringslisten, fordelt på kategori',
		categories: [
			{
				name: 'Mottak av HTTP-forespørsler',
				hosts: 'webhook.site · pipedream.com · requestbin.com · beeceptor.com'
			},
			{
				name: 'Tekstdeling',
				hosts: 'pastebin.com · hastebin.com · rentry.co · dpaste.org'
			},
			{
				name: 'Fildeling',
				hosts: 'transfer.sh · file.io · 0x0.st · catbox.moe · gofile.io'
			},
			{
				name: 'Tunneler',
				hosts: 'ngrok.io · localtunnel.me · serveo.net · localhost.run'
			},
			{
				name: 'Oppslag av offentlig IP-adresse',
				hosts: 'ipinfo.io · ifconfig.me · icanhazip.com · ipify.org'
			}
		],
		cmdNote: 'Fra kommandolinjen:',
		figure: {
			caption: 'I skrivebordsappen vises avviste verter i en tabell med antall tilkoblingsforsøk. Knappen Allow legger verten til i dine egne tillegg til tillatelseslisten og tar endringen i bruk uten omstart av proxyen.',
			alt: 'Trafikktabell med fire avviste verter, HTTP-status 403, antall forsøk og knappene Allow, Refresh, Follow og Stop.'
		}
	},
	delivery: {
		kicker: 'Overfør endringer',
		title: 'Du bestemmer hvilke endringer som tas inn',
		lead: 'Velg hvilke endringer du vil gjennomgå: fra starten på sesjonen eller siden en tidligere overføring. Lyngvi lager en patch i containeren og kontrollerer den på maskinen din. Kontrollen utføres av Lyngvi, uavhengig av agenten.',
		steps: [
			{
				title: 'Velg utgangspunkt',
				body: 'Sammenlign med starten på sesjonen, forrige overføring eller en tidligere overføring.'
			},
			{
				title: 'Samle endringene',
				body: 'Lyngvi lager en patch med en midlertidig Git-indeks. Både committede og ucommittede endringer tas med, også endringer som ikke er lagt i stagingområdet.'
			},
			{
				title: 'Kontroller filene',
				body: 'På maskinen din får hver fil en kategori basert på sikkerhetsreglene: klar for overføring, krever ekstra godkjenning eller avvist.'
			},
			{
				title: 'Gjennomgå resultatet',
				body: 'Se endrede linjer eller hele filer, fil for fil, i skrivebordsappen eller på kommandolinjen.'
			},
			{
				title: 'Overfør til en arbeidsgren',
				body: 'Legg godkjente endringer i stagingområdet eller opprett en commit på en arbeidsgren. Overføring til beskyttede grener avvises.'
			}
		],
		tableTitle: 'session review 7f3a2c',
		tableHint: 'Velg en tilbakeholdt fil for å ta den med. Slike filer overføres bare når du velger dem uttrykkelig.',
		columns: {
			file: 'Fil',
			change: 'Endring',
			bucket: 'Kategori'
		},
		buckets: {
			apply: 'klar for overføring',
			hold: 'krever ekstra godkjenning',
			reject: 'avvist'
		},
		bucketRules: {
			apply: 'Vanlige tekstendringer. Overføres når du godkjenner dem.',
			hold: 'Kjørbare filer, symbolske lenker, binærfiler og filer som overskrider størrelsesgrensen. Overføres bare når du velger dem uttrykkelig.',
			reject: 'Filstier med .., absolutte filstier, .git som stisegment, ugyldig UTF-8 og patcher som ikke kan tolkes. Disse overføres aldri.'
		},
		files: [
			{
				path: 'src/auth.ts',
				change: 'endret',
				bucket: 'apply',
				stat: '+12 −3'
			},
			{
				path: 'src/session/cookie.ts',
				change: 'endret',
				bucket: 'apply',
				stat: '+4 −1'
			},
			{
				path: 'tests/auth.spec.ts',
				change: 'ny',
				bucket: 'apply',
				stat: '+58 −0'
			},
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
		summary: (/** @type {number} */ n) => `${n} fil${n === 1 ? '' : 'er'} blir committet på agent/fix-login.`,
		protectedNote: 'Profilen beskytter main, master og develop mot overføring. Du velger selv prefikset for arbeidsgrenen.',
		figure: {
			caption: 'Den samme gjennomgangen i skrivebordsappen viser hvilke filer som er klare for overføring, hvilke som krever ekstra godkjenning, og hvilke som er avvist. Diffen for den valgte filen vises ved siden av.',
			alt: 'Dialogen Deliver changes med en advarsel om at én av tre filer er tilbakeholdt, listene Will be applied, Held back og Rejected, og en diff av src/auth.ts.'
		}
	},
	gateway: {
		kicker: 'Gatewayen',
		title: 'Bruk interne systemer uten å dele tilgangsnøklene',
		lead: 'Noen oppgaver krever informasjon fra en intern wiki, et saks- eller oppgavesystem eller et API. Gatewayen kjører i en separat container, håndterer autentiseringen og gir agenten begrenset lesetilgang til godkjent innhold. Forespørsler utenfor den tillatte tilgangen avvises med HTTP 403.',
		guaranteesTitle: 'Sikkerhetsregler som håndheves av gatewayen',
		guarantees: [
			'Ukjente vertsnavn gir HTTP 404 uten at det interne systemet kontaktes',
			'Bare GET og HEAD tillates, med mindre både instansen og profilen tillater skrivetilgang',
			'Objektet for kommunikasjon med systemet er låst til én origin; .. og absolutte URL-er avvises',
			'Kjøremiljøet setter inn tilgangsnøkkelen uten å gjøre den tilgjengelig som tekst for programtillegg',
			'Listen over tillatte elementer leses på nytt ved hver forespørsel og kontrolleres før programtillegget behandler den',
			'En tom tillatelsesliste betyr at all tilgang avvises',
			'Alle svar merkes med no-store; set-cookie og hop-by-hop-headere fjernes',
			'Sensitive loggverdier maskeres; innholdet i Authorization og *_TOKEN-variabler logges aldri',
			'Feil i én instans påvirker ikke de andre; /healthz rapporterer instansen med configured: false'
		],
		pluginsTitle: 'Ett programtillegg for hver systemtype',
		pluginsBody: 'Det innebygde programtillegget http støtter REST-API-er som bruker en fast tilgangsnøkkel. Programtilleggene confluence og jira følger med som JAR-filer. Du kan lage egne tillegg ved å implementere GatewayPlugin og registrere klassen med en serviceoppføring. Tillegget installeres med gateway plugin install. Lyngvi leveres uten forhåndskonfigurerte systemer; organisasjonens oppsett legges til gjennom en profilpakke.',
		exchangeTitle: 'Slik ser forespørslene ut fra agentens side',
		allowedComment: 'tillatt: området OPS står på tillatelseslisten',
		deniedComment: 'ikke tillatt ennå – du må legge området til i listen',
		deniedBody: 'gatewayen avviser forespørselen før det interne systemet kontaktes'
	},
	features: {
		kicker: 'Flere funksjoner',
		title: 'Laget for daglig bruk',
		lead: 'Gode sikkerhetsgrenser må være enkle å bruke i praksis. Lyngvi gir deg verktøyene du trenger for å jobbe med kodeagenter i hverdagen.',
		items: [
			{
				title: 'Én container per samtale',
				body: 'Hver samtale har en egen arbeidskopi og egne grenser for ressursbruk. Du kan starte og stoppe containere, åpne et skall og rydde bort inaktive containere.'
			},
			{
				title: 'Profiler i stedet for hardkoding',
				body: 'Vertsnavn, tillatelseslister, pakkespeil, navn på sertifikatutstedere (CA), beskyttede grener, utviklingsverktøy og språk settes i profile.json og en profilpakke. Organisasjonens oppsett holdes samlet i konfigurasjonen.'
			},
			{
				title: 'Sett sammen utviklingsmiljøet du trenger',
				body: 'Start med et lite Debian-basisimage og legg til verktøyene du trenger som egne byggfragmenter: Java 17/21/25, Node 22, Python 3.12, Go, .NET 8, Maven, Gradle og Kotlin.'
			},
			{
				title: 'Ressursprofiler',
				body: 'Velg review (2 GB minne, 1 CPU), dev (8 GB, 4 CPU-er), build (16 GB, 6 CPU-er) eller et oppsett tilpasset maskinen din. Ressursprofilen velges for hver sesjon ut fra oppgaven agenten skal løse.'
			},
			{
				title: '--json på hver kommando',
				body: 'Alle kommandoer kan returnere ett JSON-dokument på stdout og har dokumenterte avslutningskoder. session ask gir en hendelsesstrøm med én hendelse per linje. Kommandolinjegrensesnittet fungerer dermed som et offentlig API som kan brukes fra valgfritt programmeringsspråk.'
			},
			{
				title: 'Samme funksjoner i appen og på kommandolinjen',
				body: 'Skrivebordsappen har sidemeny, løpende visning av agentsvar, verktøykall og forbruk, filpanel og diffvisning. Du kan se hele filer og bruke den samme prosessen for gjennomgang og overføring som på kommandolinjen.'
			},
			{
				title: 'Faste versjoner og reproduserbare miljøer',
				body: 'Basisimaget, Squid og gatewayens basisimage er låst med digest. Versjonen av agentens kommandolinjeverktøy er fastsatt i byggfragmentet. doctor --deep tester reglene for utgående trafikk fra en midlertidig container.'
			},
			{
				title: 'Vedlegg og lokale modeller',
				body: 'Dra filer inn i skrivefeltet eller lim inn et bilde. Du kan koble sandkassen til Ollama, LM Studio eller en MLX-server på maskinen din. Proxyen tillater da tilgang til de angitte portene.'
			},
			{
				title: 'Engelsk og norsk',
				body: 'Alle tekster i både kommandolinjeverktøyet og skrivebordsappen hentes fra språkfiler. Bruk --lang for å velge språk for en enkelt kommando.'
			}
		]
	},
	hardening: {
		kicker: 'Sikkerhetsoppsettet samlet',
		title: 'Slik begrenses tilgang og rettigheter',
		rows: [
			{
				k: 'Nettverk',
				v: 'Samtalecontainere bruker bare det interne nettverket og har ingen standardrute'
			},
			{
				k: 'Linux-rettigheter',
				v: 'Alle containere, også samtalecontainere, kjører med cap_drop ALL og no-new-privileges'
			},
			{
				k: 'Bruker',
				v: 'Agenten kjører uten administratorrettigheter, med din UID og GID, slik at monterte filer beholder riktig eier'
			},
			{
				k: 'Filsystem',
				v: 'Agenten arbeider i en egen arbeidskopi. Gatewayen har skrivebeskyttet filsystem. Bare mapper og filer som inngår i sandkasseoppsettet, monteres fra vertsmaskinen'
			},
			{
				k: 'Pakkeverktøy',
				v: 'Automatiske livssyklusskript er deaktivert med npm_config_ignore_scripts og YARN_ENABLE_SCRIPTS=false'
			},
			{
				k: 'Git',
				v: 'Ingen systeminnstillinger, interaktive spørsmål eller signering; Git-identiteten bestemmes av profilen'
			},
			{
				k: 'Innloggingsopplysninger',
				v: 'Agentens innloggingsopplysninger lagres i state/agents/<id>/ og monteres bare inn i den aktuelle agentens containere. Ingen tilgangstokener lagres i .env'
			},
			{
				k: 'Grener',
				v: 'Profilens beskyttede grener og repoets standardgren er beskyttet. Endringer overføres alltid til en arbeidsgren'
			},
			{
				k: 'Containerimages',
				v: 'Basisimaget, Squid og gatewayens basisimage er låst med digest. CI kontrollerer at disse bindingene er på plass'
			}
		],
		limitsTitle: 'Kjente begrensninger',
		limits: [
			'Agentens egne innloggingsopplysninger er tilgjengelige i agentcontaineren. Dette er en dokumentert begrensning. En fremtidig løsning er planlagt der gatewayen setter inn agentens tilgangstoken.',
			'Samtalecontainerne deler et internt nettverk. Hvis en agent starter en nettverkstjeneste, kan den være tilgjengelig fra andre samtalecontainere. Ingen tjenester lytter som standard.'
		]
	},
	install: {
		kicker: 'Kom i gang',
		title: 'Kom i gang med din første sandkasse',
		lead: 'Både kommandolinjeverktøyet og skrivebordsappen leveres med Java inkludert. Kommandolinjeverktøyet pakkes med jlink, og appen pakkes med jpackage og inkluderer kommandolinjeverktøyet. Du trenger bare å installere Java selv hvis du vil bygge Lyngvi fra kildekoden.',
		requirements: 'Krav: macOS eller Linux · Podman 5+ · Java 25 kun hvis du bygger fra kildekode',
		cliTitle: 'I terminalen',
		steps: [
			{
				title: 'Last ned kommandolinjeverktøyet',
				body: 'Opprett en mappe og legg setup.sh i den. Skriptet laster ned kommandolinjeverktøyet med Java-kjøremiljø for plattformen din og kontrollerer nedlastingen mot en sjekksum.'
			},
			{
				title: 'Opprett en sandkasse',
				body: 'Velg repoet agenten skal arbeide med. init oppretter et eget oppstartsskript for sandkassen og kopierer inn kommandolinjeverktøyet.'
			},
			{
				title: 'Bygg og start',
				body: 'Generer konfigurasjonen, bygg containerimaget og start proxyen og gatewayen. Kjør deretter doctor for å kontrollere oppsettet.'
			},
			{
				title: 'Start en samtale og gjennomgå resultatet',
				body: 'Hver samtale får en egen container. Gi agenten en oppgave, gjennomgå endringene og overfør det du godkjenner til en arbeidsgren.'
			}
		],
		docsTitle: 'Dokumentasjon',
		guiLead:
			'Skrivebordsappen tar de samme stegene i veiviseren «Ny sandkasse», og mer skal ikke installeres: CLI-en ligger inni appen, og en sandkasse laget her peker sin egen cli/ til den — så samme mappe er din å jobbe i fra terminalen når du vil.',
		guiSteps: [
			{
				title: 'Mappe og repoer',
				body: 'Hvor sandkassen bor, og hvilke repoer en samtale kan få en kopi av.'
			},
			{
				title: 'Basisimage',
				body: 'Verktøykjedene profilen ber om og agentene den slår på, bygget én gang.'
			},
			{
				title: 'Felles stakk',
				body: 'Proxyen og gatewayen, startet og latt stå mens sandkassen er i bruk.'
			},
			{
				title: 'Sjekk',
				body: 'De samme sjekkene som doctor: containerkjøretiden, imaget, stakken, repoene og sertifikatet.'
			},
			{
				title: 'Agent',
				body: 'Logg inn én gang, eller pek sandkassen mot din egen modellserver. Begge kan vente.'
			},
			{
				title: 'Klar',
				body: 'Sandkassen er laget, og den første samtalen kan åpnes idet veiviseren lukkes.'
			}
		],
		guiShot: {
			alt: 'Veiviseren Ny sandkasse: de seks stegene i en skinne til venstre, og på første side feltene for sandkassemappen, en valgfri profilpakke og repoene den kan kopiere.',
			caption:
				'Steg én av seks. Ingenting er laget ennå — «Create» skriver sandkassemappen og renderer konfigurasjonen, og veiviseren går videre til imaget.'
		},
		guiNote:
			'macOS: en .dmg, åpnet første gang med høyreklikk → Åpne, siden appen ennå ikke er notarisert. Linux: en .deb eller det utpakkede appbildet. Derfra er samtalen, diffen og uttaket vinduene over.',
		guiLink: 'Appen, skjerm for skjerm',
		guiTitle: 'Eller i appen',
		docs: [
			{
				label: 'README',
				note: 'kommandoer, oppbygning og første kjøring'
			},
			{
				label: 'docs/agents.md',
				note: 'hvordan du lager en agentadapter, og hvordan hendelsesgrensesnittet fungerer'
			},
			{
				label: 'docs/gateway-plugins.md',
				note: 'hvordan du lager og pakker et programtillegg for gatewayen'
			},
			{
				label: 'docs/profiles.md',
				note: 'innholdet i en profilpakke'
			},
			{
				label: 'acceptance/',
				note: 'akseptansetester og sjekkliste'
			}
		]
	},
	footer: {
		tagline: 'En sandkasse med strenge sikkerhetsgrenser for autonome kodeagenter.',
		licence: 'MIT-lisensiert.',
		origin: 'Lyngvi er øya der Fenrisulven ble bundet. Ulven i logoen symboliserer agentens kraft, barrieren viser grensene rundt den, og de tre lysglimtene representerer kunstig intelligens.',
		colophon: 'Siden er statisk, uten sporing, trafikkanalyse eller forespørsler til tredjeparter.',
		status: 'Status: forhåndsversjon'
	}
};

export const content = { en, no };
