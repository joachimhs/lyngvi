<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';

	/** @type {'gui'|'cli'|'core'|'state'|'proxy'|'gateway'|'session'} */
	let selected = $state('session');

	const order = /** @type {const} */ ([
		'gui',
		'cli',
		'core',
		'state',
		'proxy',
		'gateway',
		'session'
	]);

	/** @param {typeof order[number]} id */
	function pick(id) {
		selected = id;
	}
</script>

<section id="architecture" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.architecture.kicker}
			title={i18n.t.architecture.title}
			lead={i18n.t.architecture.lead}
		/>

		<Reveal>
			<div class="layout">
				<div class="diagram frame" role="group" aria-label={i18n.t.architecture.title}>
					<div class="host">
						<span class="frame-label">{i18n.t.architecture.hostLabel}</span>

						<div class="row two">
							<button class="node" class:on={selected === 'gui'} onclick={() => pick('gui')}>
								{i18n.t.architecture.nodes.gui.title}
							</button>
							<button class="node" class:on={selected === 'cli'} onclick={() => pick('cli')}>
								{i18n.t.architecture.nodes.cli.title}
							</button>
						</div>

						<div class="link merge" aria-hidden="true"></div>

						<div class="row two tight">
							<button class="node core" class:on={selected === 'core'} onclick={() => pick('core')}>
								{i18n.t.architecture.nodes.core.title}
								<span class="sub mono">git · podman · state</span>
							</button>
							<button class="node" class:on={selected === 'state'} onclick={() => pick('state')}>
								{i18n.t.architecture.nodes.state.title}
							</button>
						</div>

						<div class="link" aria-hidden="true"><span class="packet"></span></div>

						<div class="stack">
							<span class="frame-label">{i18n.t.architecture.stackLabel}</span>
							<div class="row two">
								<button class="node" class:on={selected === 'proxy'} onclick={() => pick('proxy')}>
									{i18n.t.architecture.nodes.proxy.title}
									<span class="out mono">→ {i18n.t.architecture.internetLabel}</span>
								</button>
								<button
									class="node"
									class:on={selected === 'gateway'}
									onclick={() => pick('gateway')}
								>
									{i18n.t.architecture.nodes.gateway.title}
									<span class="out mono">→ {i18n.t.architecture.intranetLabel}</span>
								</button>
							</div>
						</div>

						<div class="link net" aria-hidden="true">
							<span class="packet up"></span>
						</div>
						<p class="netlabel mono">{i18n.t.architecture.internalNet}</p>

						<div class="sessions">
							<span class="frame-label">{i18n.t.architecture.sessionLabel}</span>
							<div class="row three">
								{#each [0, 1, 2] as n (n)}
									<button
										class="node session"
										class:on={selected === 'session'}
										onclick={() => pick('session')}
									>
										<span class="mono small">/work/&lt;repo&gt;</span>
										<span class="sub mono">cap_drop ALL</span>
									</button>
								{/each}
							</div>
						</div>
					</div>
				</div>

				<aside class="detail" aria-live="polite">
					<p class="detail-kicker mono">{i18n.t.architecture.detailLabel}</p>
					<h3>{i18n.t.architecture.nodes[selected].title}</h3>
					<p>{i18n.t.architecture.nodes[selected].body}</p>
					<div class="tabs">
						{#each order as id (id)}
							<button class="tab" class:on={selected === id} onclick={() => pick(id)}>
								{i18n.t.architecture.nodes[id].title}
							</button>
						{/each}
					</div>
				</aside>
			</div>
		</Reveal>
	</div>
</section>

<style>
	.layout {
		display: grid;
		grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
		gap: 2rem;
		align-items: start;
	}

	.diagram {
		padding: 1.5rem;
		background-color: var(--bg);
	}

	.host,
	.stack,
	.sessions {
		position: relative;
		padding: 1.5rem 1rem 1rem;
		border: 1px dashed var(--line-strong);
		border-radius: var(--r-sm);
	}

	.host {
		padding-inline: clamp(0.5rem, 2vw, 1.1rem);
	}

	.frame-label {
		position: absolute;
		top: -0.62rem;
		left: 0.9rem;
		padding: 0 0.5rem;
		background: var(--bg);
		font-family: var(--mono);
		font-size: 0.66rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--faint);
	}

	.row {
		display: grid;
		gap: 0.75rem;
	}

	.row.two {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}

	.row.three {
		grid-template-columns: repeat(3, minmax(0, 1fr));
	}

	.node {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 0.3rem;
		padding: 0.8rem 0.9rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: linear-gradient(180deg, rgba(14, 27, 44, 0.9), rgba(9, 19, 32, 0.9));
		color: var(--text);
		font: inherit;
		font-size: 0.85rem;
		font-weight: 550;
		text-align: left;
		cursor: pointer;
		transition:
			border-color 0.2s ease,
			box-shadow 0.2s ease,
			background 0.2s ease;
	}

	.node:hover {
		border-color: var(--line-strong);
		background: linear-gradient(180deg, rgba(20, 37, 57, 0.95), rgba(11, 24, 38, 0.95));
	}

	.node.on {
		border-color: var(--cyan);
		box-shadow: var(--glow);
	}

	.node .sub,
	.node .out {
		font-family: var(--mono);
		font-size: 0.66rem;
		font-weight: 400;
		color: var(--faint);
	}

	.node .out {
		color: var(--cyan);
		opacity: 0.85;
	}

	.node .small {
		font-size: 0.72rem;
		font-weight: 400;
		color: var(--dim);
	}

	.core {
		border-color: var(--line-strong);
	}

	.link {
		position: relative;
		height: 2.1rem;
		margin: 0.1rem 0;
	}

	.link::before {
		content: '';
		position: absolute;
		left: 50%;
		top: 0;
		bottom: 0;
		width: 1px;
		background: linear-gradient(180deg, transparent, var(--line-strong), transparent);
	}

	.link.merge::before {
		top: 0.35rem;
		bottom: 0.35rem;
	}

	.link.merge::after {
		content: '';
		position: absolute;
		left: 25%;
		right: 25%;
		top: 0.35rem;
		height: 1px;
		background: var(--line-strong);
	}

	.link.net::before {
		background: linear-gradient(180deg, var(--cyan), transparent);
		opacity: 0.55;
	}

	.packet {
		position: absolute;
		left: calc(50% - 2.5px);
		width: 5px;
		height: 5px;
		background: var(--cyan);
		box-shadow: 0 0 10px 2px rgba(34, 211, 238, 0.6);
		animation: travel 3.2s linear infinite;
	}

	.packet.up {
		animation-direction: reverse;
		animation-duration: 2.4s;
	}

	@keyframes travel {
		from {
			top: -2px;
			opacity: 0;
		}
		10%,
		90% {
			opacity: 1;
		}
		to {
			top: calc(100% - 3px);
			opacity: 0;
		}
	}

	.netlabel {
		margin: 0 0 0.7rem;
		text-align: center;
		font-size: 0.66rem;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: var(--cyan);
		opacity: 0.75;
	}

	.detail {
		position: sticky;
		top: calc(var(--bar) + 1.25rem);
		padding: 0.5rem 0 0;
	}

	.detail-kicker {
		margin-bottom: 0.9rem;
		font-size: 0.68rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		color: var(--faint);
	}

	.detail h3 {
		margin-bottom: 0.8rem;
		font-family: var(--serif);
		font-weight: 400;
		font-size: 2rem;
		line-height: 1;
		letter-spacing: -0.01em;
		color: var(--text);
	}

	.detail p {
		color: var(--dim);
		font-size: 0.95rem;
	}

	.tabs {
		display: flex;
		flex-wrap: wrap;
		gap: 0.35rem;
		margin-top: 1.4rem;
		padding-top: 1.2rem;
		border-top: 1px solid var(--line);
	}

	.tab {
		padding: 0.28rem 0.6rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: transparent;
		color: var(--faint);
		font: inherit;
		font-family: var(--mono);
		font-size: 0.68rem;
		cursor: pointer;
		transition:
			color 0.15s ease,
			border-color 0.15s ease;
	}

	.tab:hover {
		color: var(--text);
		border-color: var(--line-strong);
	}

	.tab.on {
		border-color: var(--cyan);
		color: var(--cyan);
		background: var(--cyan-soft);
	}

	@media (max-width: 900px) {
		.layout {
			grid-template-columns: minmax(0, 1fr);
		}

		.detail {
			position: static;
		}

		.row.three {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
