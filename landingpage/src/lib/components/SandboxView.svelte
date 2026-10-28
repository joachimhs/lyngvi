<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';
</script>

<section id="sandbox" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.sandbox.kicker}
			title={i18n.t.sandbox.title}
			lead={i18n.t.sandbox.lead}
		/>

		<div class="split">
			<Reveal>
				<article class="panel sees frame">
					<div class="panel-inner">
						<header>
							<span class="badge ok">{i18n.t.sandbox.seesTitle}</span>
							<p>{i18n.t.sandbox.seesIntro}</p>
						</header>
						<ul>
							{#each i18n.t.sandbox.sees as item (item.path)}
								<li>
									<code>{item.path}</code>
									<span>{item.note}</span>
								</li>
							{/each}
						</ul>
					</div>
				</article>
			</Reveal>

			<Reveal delay={90}>
				<article class="panel never frame">
					<div class="panel-inner">
						<header>
							<span class="badge bad">{i18n.t.sandbox.neverTitle}</span>
							<p>{i18n.t.sandbox.neverIntro}</p>
						</header>
						<ul class="plain">
							{#each i18n.t.sandbox.never as item (item)}
								<li>
									<span class="cross" aria-hidden="true">
										<svg
											viewBox="0 0 16 16"
											width="12"
											height="12"
											fill="none"
											stroke="currentColor"
											stroke-width="2"
											stroke-linecap="round"
										>
											<path d="M4 4l8 8M12 4l-8 8" />
										</svg>
									</span>
									<span>{item}</span>
								</li>
							{/each}
						</ul>
					</div>
				</article>
			</Reveal>
		</div>

		<Reveal delay={60}>
			<div class="agents">
				<div class="agents-copy">
					<h3>{i18n.t.sandbox.agentsTitle}</h3>
					<p>{i18n.t.sandbox.agentsBody}</p>
				</div>
				<ul class="agent-list">
					{#each i18n.t.sandbox.agents as agent (agent.name)}
						<li>
							<span class="name">{agent.name}</span>
							<span class="detail mono">{agent.detail}</span>
						</li>
					{/each}
				</ul>
			</div>
		</Reveal>
	</div>
</section>

<style>
	.split {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
		gap: 1.5rem;
	}

	.split :global(.reveal) {
		height: 100%;
	}

	.panel {
		height: 100%;
	}

	.sees {
		--tick-color: var(--ok);
	}

	.never {
		--tick-color: var(--bad);
	}

	.panel-inner {
		height: 100%;
		padding: 1.5rem 1.4rem;
		border: 1px solid var(--line);
		background: linear-gradient(180deg, rgba(10, 20, 34, 0.6), rgba(5, 11, 20, 0.3));
	}

	.panel header p {
		margin-top: 0.8rem;
		color: var(--faint);
		font-size: 0.88rem;
	}

	.badge {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		font-family: var(--mono);
		font-size: 0.72rem;
		font-weight: 500;
		letter-spacing: 0.14em;
		text-transform: uppercase;
	}

	.badge::before {
		content: '';
		width: 6px;
		height: 6px;
		background: currentColor;
		box-shadow: 0 0 10px 1px currentColor;
	}

	.badge.ok {
		color: var(--ok);
	}

	.badge.bad {
		color: var(--bad);
	}

	ul {
		margin: 1.2rem 0 0;
		padding: 0;
		list-style: none;
	}

	.sees li {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.15rem;
		padding: 0.6rem 0;
		border-top: 1px solid var(--line);
	}

	.sees code {
		color: var(--text);
		font-size: 0.8rem;
	}

	.sees li span {
		color: var(--dim);
		font-size: 0.86rem;
	}

	.plain li {
		display: flex;
		align-items: flex-start;
		gap: 0.65rem;
		padding: 0.55rem 0;
		color: var(--dim);
		font-size: 0.9rem;
		border-top: 1px solid var(--line);
	}

	.cross {
		display: grid;
		place-items: center;
		flex: none;
		width: 1.25rem;
		height: 1.25rem;
		margin-top: 0.15rem;
		border: 1px solid rgba(255, 122, 122, 0.4);
		border-radius: var(--r-sm);
		color: var(--bad);
	}

	.agents {
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
		gap: 2rem;
		margin-top: 2rem;
		padding-top: 2rem;
		border-top: 1px solid var(--line);
	}

	.agents-copy h3 {
		font-size: 1.2rem;
	}

	.agents-copy p {
		margin-top: 0.7rem;
		color: var(--dim);
		font-size: 0.93rem;
		max-width: 46ch;
	}

	.agent-list {
		display: flex;
		flex-direction: column;
		margin: 0;
		border-top: 1px solid var(--line);
	}

	.agent-list li {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		justify-content: space-between;
		gap: 0.2rem 1rem;
		padding: 0.7rem 0;
		border-bottom: 1px solid var(--line);
	}

	.agent-list .name {
		font-weight: 600;
		font-size: 0.93rem;
	}

	.agent-list .detail {
		font-size: 0.72rem;
		color: var(--faint);
	}

	@media (max-width: 820px) {
		.agents {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
