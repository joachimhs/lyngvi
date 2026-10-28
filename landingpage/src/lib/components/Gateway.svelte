<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';
</script>

<section id="gateway" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.gateway.kicker}
			title={i18n.t.gateway.title}
			lead={i18n.t.gateway.lead}
		/>

		<div class="split">
			<Reveal>
				<div class="exchange frame">
					<div class="inner">
						<h3 class="panel-title">{i18n.t.gateway.exchangeTitle}</h3>

						<div class="flow">
							<div class="side agent">
								<span class="who mono">agent</span>
								<code>GET https://wiki.gateway.local/page/48219</code>
							</div>
							<div class="arrow ok" aria-hidden="true"></div>
							<div class="side gw">
								<span class="who mono">gateway</span>
								<code
									>200 · text/markdown<span class="note">{i18n.t.gateway.allowedComment}</span
									></code
								>
							</div>
						</div>

						<div class="flow">
							<div class="side agent">
								<span class="who mono">agent</span>
								<code>GET https://wiki.gateway.local/space/FINANCE</code>
							</div>
							<div class="arrow bad" aria-hidden="true"></div>
							<div class="side gw">
								<span class="who mono">gateway</span>
								<code class="deny"
									>403 · not allowed<span class="note">{i18n.t.gateway.deniedComment}</span></code
								>
							</div>
						</div>
						<p class="footnote">{i18n.t.gateway.deniedBody}</p>

						<pre class="snippet"><code
								>./setup.sh gateway instance add wiki --plugin confluence \
    --hostname wiki.gateway.local
printf '%s\n' "$TOKEN" | ./setup.sh gateway secret set wiki \
    --url https://wiki.example.com --token-stdin
./setup.sh gateway allow wiki add space:OPS</code
							></pre>

						<div class="plugins">
							<h4>{i18n.t.gateway.pluginsTitle}</h4>
							<p>{i18n.t.gateway.pluginsBody}</p>
							<div class="tags">
								<span class="chip">http · built in</span>
								<span class="chip">confluence.jar</span>
								<span class="chip">jira.jar</span>
								<span class="chip">GatewayPlugin · your own</span>
							</div>
						</div>
					</div>
				</div>
			</Reveal>

			<Reveal delay={90}>
				<div class="guarantees">
					<h3 class="panel-title">{i18n.t.gateway.guaranteesTitle}</h3>
					<ul>
						{#each i18n.t.gateway.guarantees as item, idx (item)}
							<li>
								<span class="n mono num">{String(idx + 1).padStart(2, '0')}</span>
								<span>{item}</span>
							</li>
						{/each}
					</ul>
				</div>
			</Reveal>
		</div>
	</div>
</section>

<style>
	.split {
		display: grid;
		grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.8fr);
		gap: 2.5rem;
		align-items: start;
	}

	.inner {
		padding: 1.5rem 1.4rem;
		border: 1px solid var(--line);
		background: linear-gradient(180deg, rgba(10, 20, 34, 0.6), rgba(5, 11, 20, 0.3));
	}

	.panel-title {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		margin-bottom: 1.2rem;
		font-family: var(--mono);
		font-size: 0.72rem;
		font-weight: 500;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		color: var(--cyan);
	}

	.panel-title::before {
		content: '';
		width: 6px;
		height: 6px;
		background: var(--cyan);
		box-shadow: 0 0 10px 1px rgba(34, 211, 238, 0.55);
	}

	.flow {
		display: grid;
		grid-template-columns: minmax(0, 1fr) 2rem minmax(0, 1fr);
		align-items: center;
		gap: 0.4rem;
		margin-bottom: 0.7rem;
	}

	.side {
		display: grid;
		gap: 0.3rem;
		padding: 0.7rem 0.8rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: rgba(3, 8, 15, 0.6);
		min-width: 0;
	}

	.who {
		font-size: 0.64rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		color: var(--faint);
	}

	.side code {
		font-size: 0.72rem;
		color: var(--text);
		word-break: break-all;
	}

	.side code.deny {
		color: var(--bad);
	}

	.note {
		display: block;
		margin-top: 0.3rem;
		font-family: var(--serif);
		font-style: italic;
		font-size: 0.9rem;
		color: var(--faint);
		word-break: normal;
	}

	.arrow {
		position: relative;
		height: 1px;
		background: var(--line-strong);
	}

	.arrow::after {
		content: '';
		position: absolute;
		right: -1px;
		top: -3px;
		border: 3.5px solid transparent;
		border-left-color: var(--line-strong);
	}

	.arrow.ok {
		background: var(--ok);
	}

	.arrow.ok::after {
		border-left-color: var(--ok);
	}

	.arrow.bad {
		background: var(--bad);
	}

	.arrow.bad::after {
		border-left-color: var(--bad);
	}

	.footnote {
		margin-top: 0.3rem;
		font-size: 0.8rem;
		color: var(--faint);
	}

	.snippet {
		margin: 1.2rem 0 0;
		padding: 0.9rem 1rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: rgba(3, 8, 15, 0.8);
		overflow-x: auto;
	}

	.snippet code {
		color: var(--dim);
		font-size: 0.74rem;
		line-height: 1.7;
		white-space: pre;
	}

	.plugins {
		margin-top: 1.4rem;
		padding-top: 1.2rem;
		border-top: 1px solid var(--line);
	}

	.plugins h4 {
		font-size: 0.95rem;
	}

	.plugins p {
		margin-top: 0.5rem;
		color: var(--dim);
		font-size: 0.88rem;
	}

	.tags {
		display: flex;
		flex-wrap: wrap;
		gap: 0.4rem;
		margin-top: 0.9rem;
	}

	.guarantees {
		padding-top: 0.75rem;
	}

	.guarantees ul {
		margin: 0;
		padding: 0;
		list-style: none;
		border-top: 1px solid var(--line);
	}

	.guarantees li {
		display: grid;
		grid-template-columns: 2.2rem minmax(0, 1fr);
		gap: 0.6rem;
		padding: 0.75rem 0;
		border-bottom: 1px solid var(--line);
		color: var(--dim);
		font-size: 0.88rem;
		line-height: 1.5;
	}

	.guarantees .n {
		font-size: 0.72rem;
		letter-spacing: 0.1em;
		color: var(--cyan);
		padding-top: 0.15rem;
	}

	@media (max-width: 900px) {
		.split {
			grid-template-columns: minmax(0, 1fr);
		}

		.flow {
			grid-template-columns: minmax(0, 1fr);
		}

		.arrow {
			width: 1px;
			height: 1.2rem;
			margin-left: 1rem;
		}

		.arrow::after {
			right: auto;
			left: -3px;
			top: auto;
			bottom: -1px;
			border-left-color: transparent !important;
			border-top-color: currentColor;
		}

		.arrow.ok::after {
			border-top-color: var(--ok);
		}

		.arrow.bad::after {
			border-top-color: var(--bad);
		}
	}
</style>
