<script>
	/**
	 * A terminal window that types its lines out once it scrolls into view.
	 * Without JavaScript, or with reduced motion, the whole transcript is
	 * simply there from the start.
	 *
	 * @typedef {{ kind?: 'cmd' | 'out' | 'ok' | 'warn' | 'bad' | 'dim' | 'blank', text?: string }} Line
	 */
	let {
		/** @type {Line[]} */ lines = [],
		title = '',
		/** typing speed in ms per character */ speed = 14,
		loop = false
	} = $props();

	let animating = $state(false);
	let typed = $state(0);
	let partial = $state('');

	/** How many lines are on screen: everything until the animation takes over. */
	let shown = $derived(animating ? typed : lines.length);

	// The typing loop deliberately lives outside `$effect`: it reads and writes
	// the same state, which inside an effect is a self-invalidating loop.
	let cancelled = false;
	let started = false;

	const sleep = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms));

	async function type() {
		if (started) return;
		started = true;
		do {
			typed = 0;
			for (let i = 0; i < lines.length; i++) {
				if (cancelled) return;
				const line = lines[i];
				if (line.kind === 'cmd') {
					let text = '';
					for (const ch of line.text ?? '') {
						if (cancelled) return;
						text += ch;
						partial = text;
						await sleep(speed);
					}
					partial = '';
					typed = i + 1;
					await sleep(260);
				} else {
					typed = i + 1;
					await sleep(line.kind === 'blank' ? 60 : 150);
				}
			}
			if (loop) await sleep(6000);
		} while (loop && !cancelled);
	}

	/** @param {HTMLElement} node */
	function whenSeen(node) {
		const motionOk =
			typeof matchMedia === 'function' && !matchMedia('(prefers-reduced-motion: reduce)').matches;
		if (!motionOk || typeof IntersectionObserver !== 'function') return;
		animating = true;
		const observer = new IntersectionObserver(
			(entries) => {
				for (const entry of entries) {
					if (entry.isIntersecting) {
						observer.disconnect();
						type();
					}
				}
			},
			{ threshold: 0.25 }
		);
		observer.observe(node);
		return {
			destroy() {
				cancelled = true;
				observer.disconnect();
			}
		};
	}
</script>

<div class="term" use:whenSeen>
	<div class="chrome">
		<span class="live" class:on={!animating || typed > 0} aria-hidden="true"></span>
		{#if title}<span class="title">{title}</span>{/if}
		<span class="shell mono" aria-hidden="true">sh</span>
	</div>
	<!-- prettier-ignore -->
	<pre class="body" aria-label={title}>{#each lines as line, i (i)}{#if i < shown}<span class="line {line.kind ?? 'out'}">{line.text ?? ''}</span>{:else if i === shown && partial}<span class="line cmd">{partial}<span class="caret"></span></span>{/if}{/each}</pre>
</div>

<style>
	.term {
		overflow: hidden;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: linear-gradient(180deg, rgba(7, 16, 27, 0.96), rgba(3, 8, 15, 0.98));
		box-shadow: var(--shadow);
	}

	.chrome {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		padding: 0.55rem 0.95rem;
		border-bottom: 1px solid var(--line);
		background: rgba(10, 20, 34, 0.7);
	}

	.live {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: var(--faint);
		transition:
			background 0.3s ease,
			box-shadow 0.3s ease;
	}

	.live.on {
		background: var(--cyan);
		box-shadow: 0 0 10px 1px rgba(34, 211, 238, 0.6);
	}

	.title {
		font-family: var(--mono);
		font-size: 0.72rem;
		letter-spacing: 0.02em;
		color: var(--dim);
	}

	.shell {
		margin-left: auto;
		font-size: 0.66rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--faint);
	}

	.body {
		margin: 0;
		padding: 1.15rem 1.2rem 1.4rem;
		min-height: 20.5rem;
		overflow-x: auto;
		font-size: 0.8rem;
		line-height: 1.75;
		color: var(--dim);
		white-space: pre;
		tab-size: 2;
	}

	.line {
		display: block;
		min-height: 1.3em;
	}

	.cmd {
		color: var(--text);
	}

	/* The prompt is drawn by CSS so the markup inside <pre> stays whitespace-exact. */
	.cmd::before {
		content: '$ ';
		color: var(--cyan);
	}

	.ok {
		color: var(--ok);
	}

	.warn {
		color: var(--warn);
	}

	.bad {
		color: var(--bad);
	}

	.dim {
		color: var(--faint);
	}

	.caret {
		display: inline-block;
		width: 0.55em;
		height: 1em;
		margin-left: 1px;
		translate: 0 0.15em;
		background: var(--cyan);
		animation: blink 1s steps(1) infinite;
	}

	@keyframes blink {
		50% {
			opacity: 0;
		}
	}

	@media (max-width: 560px) {
		.body {
			font-size: 0.72rem;
		}
	}
</style>
