<script>
	import { asset } from '$app/paths';
	import { i18n } from '#lib/i18n.svelte.js';
	import { links } from '#lib/links.js';
	import Terminal from './Terminal.svelte';
	import Shot from './Shot.svelte';
	import Ring from './Ring.svelte';

	const script = [
		{ kind: 'cmd', text: './setup.sh --sandbox ~/sandboxes/shop init --repo ~/src/shop' },
		{ kind: 'ok', text: '✔ sandbox created · profile "default" · 1 repository' },
		{ kind: 'cmd', text: './setup.sh build && ./setup.sh up' },
		{ kind: 'ok', text: '✔ localhost/lyngvi-base:2.0.0' },
		{ kind: 'ok', text: '✔ proxy and gateway up on 10.89.3.0/24 (internal)' },
		{ kind: 'cmd', text: './setup.sh session new --title "Fix the login redirect" --preset dev' },
		{ kind: 'ok', text: '✔ session 7f3a2c · container lyngvi-shop-7f3a2c · 8g / 4.0 cpus' },
		{
			kind: 'cmd',
			text: 'echo "Find and fix the login redirect bug" | ./setup.sh session ask 7f3a2c'
		},
		{ kind: 'dim', text: '  … reading src/auth.ts, running the test suite' },
		{ kind: 'warn', text: '  ✗ DENIED api.telemetry.example → not on the allowlist' },
		{ kind: 'out', text: '  3 files changed, tests green' },
		{ kind: 'cmd', text: './setup.sh session review 7f3a2c' },
		{ kind: 'out', text: '  apply    src/auth.ts            +12 −3' },
		{ kind: 'out', text: '  apply    tests/auth.spec.ts     +58 −0' },
		{ kind: 'warn', text: '  hold     bin/run.sh             EXECUTABLE' },
		{ kind: 'bad', text: '  reject   ../secrets.env         PATH_TRAVERSAL' },
		{ kind: 'cmd', text: './setup.sh session apply 7f3a2c --branch agent/fix-login' },
		{ kind: 'ok', text: '✔ 2 files committed on agent/fix-login' }
	];
</script>

<section class="hero" id="top">
	<div class="orbit" aria-hidden="true">
		<Ring opacity={0.42} />
	</div>

	<div class="page inner">
		<p class="badge mono"><span class="pulse"></span>{i18n.t.hero.badge}</p>

		<h1>
			{i18n.t.hero.title}<br /><span class="accent">{i18n.t.hero.titleAccent}</span>
		</h1>

		<div class="row">
			<div class="copy">
				<p class="lead">{i18n.t.hero.lead}</p>
				<div class="cta">
					<a class="btn btn-primary" href="#install">
						{i18n.t.hero.ctaPrimary}
						<svg
							viewBox="0 0 16 16"
							width="14"
							height="14"
							fill="none"
							stroke="currentColor"
							stroke-width="1.8"
							stroke-linecap="round"
							stroke-linejoin="round"
							aria-hidden="true"><path d="M2.5 8h11M9 3.5 13.5 8 9 12.5" /></svg
						>
					</a>
					<a class="btn" href="#screens">{i18n.t.hero.ctaSecondary}</a>
				</div>
			</div>

			<ul class="stats">
				{#each i18n.t.hero.stats as stat (stat.label)}
					<li>
						<span class="value">{stat.value}</span>
						<span class="label">{stat.label}</span>
					</li>
				{/each}
			</ul>
		</div>

		<!-- The app first: it is how Lyngvi is used. The terminal below shows the same
		     run as commands, for the half of the audience that would rather see that. -->
		<div class="visual frame">
			<Shot
				name="chat"
				width={1280}
				height={900}
				title="Lyngvi — Agentic coding"
				alt={i18n.t.hero.shot.alt}
				caption={i18n.t.hero.shot.caption}
			/>
		</div>

		<div class="second">
			<p class="second-lead">{i18n.t.hero.terminalLead}</p>
			<div class="frame">
				<Terminal lines={script} title={i18n.t.hero.terminalTitle} />
			</div>
		</div>

		<div class="below">
			<p class="origin">
				<img
					src={asset('lyngvi-mark-light-26.png')}
					srcset="{asset('lyngvi-mark-light-26.png')} 1x, {asset('lyngvi-mark-light-52.png')} 2x"
					alt=""
					width="26"
					height="26"
				/>
				<span>{i18n.t.hero.origin}</span>
			</p>
			<a class="repo-link mono" href={links.repo} rel="noreferrer noopener"
				>{links.repo.replace('https://', '')}</a
			>
		</div>
	</div>

	<div class="ticker" aria-hidden="true">
		<div class="track">
			{#each [0, 1] as copy (copy)}
				<ul>
					{#each i18n.t.hero.ticker as item (item)}
						<li>{item}</li>
					{/each}
				</ul>
			{/each}
		</div>
	</div>
</section>

<style>
	.hero {
		position: relative;
		padding-top: clamp(2.5rem, 7vw, 5.5rem);
		overflow: hidden;
	}

	.inner {
		position: relative;
		z-index: 1;
	}

	/* The ring sits high and to the right, half off the page, and fades out
	   before it reaches the terminal. */
	.orbit {
		position: absolute;
		top: -9%;
		right: -14%;
		z-index: 0;
		width: min(68vw, 880px);
		pointer-events: none;
		mask-image: linear-gradient(180deg, #000 35%, transparent 85%);
	}

	.badge {
		display: inline-flex;
		align-items: center;
		gap: 0.6rem;
		margin: 0 0 2rem;
		padding: 0.35rem 0.8rem 0.35rem 0.65rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: rgba(5, 11, 20, 0.7);
		font-size: 0.72rem;
		letter-spacing: 0.04em;
		color: var(--dim);
	}

	.pulse {
		width: 6px;
		height: 6px;
		background: var(--cyan);
		box-shadow: 0 0 0 0 rgba(34, 211, 238, 0.7);
		animation: pulse 2.6s ease-out infinite;
	}

	@keyframes pulse {
		70% {
			box-shadow: 0 0 0 8px rgba(34, 211, 238, 0);
		}
		100% {
			box-shadow: 0 0 0 0 rgba(34, 211, 238, 0);
		}
	}

	h1 {
		max-width: 16ch;
	}

	h1 .accent {
		color: var(--cyan);
	}

	.row {
		display: grid;
		grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
		gap: clamp(2rem, 5vw, 5rem);
		align-items: end;
		margin-top: clamp(2rem, 4vw, 3rem);
	}

	.lead {
		font-size: 1.1rem;
		max-width: 56ch;
	}

	.cta {
		display: flex;
		flex-wrap: wrap;
		gap: 0.75rem;
		margin-top: 2rem;
	}

	.stats {
		display: grid;
		gap: 0;
		margin: 0;
		padding: 0;
		list-style: none;
		border-top: 1px solid var(--line);
	}

	.stats li {
		display: grid;
		grid-template-columns: 3.2rem minmax(0, 1fr);
		align-items: baseline;
		gap: 1rem;
		padding: 0.7rem 0;
		border-bottom: 1px solid var(--line);
	}

	.value {
		font-family: var(--serif);
		font-size: 2.6rem;
		line-height: 1;
		color: var(--cyan);
		text-align: right;
		font-variant-numeric: tabular-nums;
	}

	.label {
		font-size: 0.86rem;
		line-height: 1.35;
		color: var(--dim);
	}

	.visual {
		margin-top: clamp(2.5rem, 5vw, 4rem);
	}

	.second {
		margin-top: clamp(2rem, 4vw, 3rem);
	}

	.second-lead {
		margin-bottom: 0.9rem;
		max-width: 62ch;
		color: var(--dim);
		font-size: 0.95rem;
	}

	.below {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-start;
		justify-content: space-between;
		gap: 1rem 2rem;
		margin-top: 1.4rem;
		padding-bottom: clamp(2.5rem, 5vw, 4rem);
	}

	.origin {
		display: flex;
		align-items: flex-start;
		gap: 0.7rem;
		max-width: 60ch;
		font-family: var(--serif);
		font-style: italic;
		font-size: 1.05rem;
		line-height: 1.45;
		color: var(--dim);
	}

	.origin img {
		flex: none;
		opacity: 0.8;
		translate: 0 1px;
	}

	.repo-link {
		font-size: 0.74rem;
		color: var(--faint);
		text-decoration: none;
		padding-top: 0.35rem;
	}

	.repo-link:hover {
		color: var(--cyan);
	}

	/* The ticker: the facts the rest of the page explains, going past slowly.
	   Two copies of the list, the track moves by one of them. */
	.ticker {
		position: relative;
		z-index: 1;
		overflow: hidden;
		border-top: 1px solid var(--line);
		border-bottom: 1px solid var(--line);
		background: rgba(3, 8, 15, 0.5);
		mask-image: linear-gradient(90deg, transparent, #000 8%, #000 92%, transparent);
	}

	.track {
		display: flex;
		width: max-content;
		animation: marquee 55s linear infinite;
	}

	.ticker:hover .track {
		animation-play-state: paused;
	}

	.track ul {
		display: flex;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.track li {
		display: flex;
		align-items: center;
		gap: 1.4rem;
		padding: 0.7rem 0.7rem 0.7rem 0;
		font-family: var(--mono);
		font-size: 0.7rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		white-space: nowrap;
		color: var(--faint);
	}

	.track li::after {
		content: '';
		width: 5px;
		height: 5px;
		background: var(--cyan);
		opacity: 0.7;
		margin-right: 0.7rem;
	}

	@keyframes marquee {
		to {
			transform: translateX(-50%);
		}
	}

	@media (max-width: 900px) {
		.row {
			grid-template-columns: minmax(0, 1fr);
			align-items: start;
		}

		.orbit {
			top: -4%;
			right: -30%;
			width: 90vw;
		}
	}
</style>
