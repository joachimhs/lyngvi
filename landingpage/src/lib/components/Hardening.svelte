<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';
</script>

<section id="hardening" class="section">
	<div class="page">
		<SectionHead kicker={i18n.t.hardening.kicker} title={i18n.t.hardening.title} />

		<div class="split">
			<Reveal>
				<dl class="table">
					{#each i18n.t.hardening.rows as row (row.k)}
						<div class="row">
							<dt class="mono">{row.k}</dt>
							<dd>{row.v}</dd>
						</div>
					{/each}
				</dl>
			</Reveal>

			<Reveal delay={90}>
				<aside class="limits frame">
					<div class="limits-inner">
						<h3>{i18n.t.hardening.limitsTitle}</h3>
						<ul>
							{#each i18n.t.hardening.limits as limit (limit)}
								<li>{limit}</li>
							{/each}
						</ul>
					</div>
				</aside>
			</Reveal>
		</div>
	</div>
</section>

<style>
	.split {
		display: grid;
		grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
		gap: 2rem;
		align-items: start;
	}

	.table {
		margin: 0;
		border-top: 1px solid var(--line);
	}

	.row {
		display: grid;
		grid-template-columns: 11rem minmax(0, 1fr);
		gap: 1rem;
		padding: 0.8rem 0;
		border-bottom: 1px solid var(--line);
	}

	dt {
		font-size: 0.76rem;
		letter-spacing: 0.04em;
		color: var(--cyan);
		padding-top: 0.15rem;
	}

	dd {
		margin: 0;
		color: var(--dim);
		font-size: 0.9rem;
	}

	/* The honest list of what the sandbox does not protect against, in amber. */
	.limits {
		--tick-color: var(--warn);
	}

	.limits-inner {
		padding: 1.4rem 1.3rem;
		border: 1px solid rgba(245, 192, 78, 0.22);
		background: linear-gradient(180deg, rgba(60, 46, 16, 0.35), rgba(10, 20, 34, 0.6));
	}

	.limits h3 {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		color: var(--warn);
		font-size: 0.95rem;
	}

	.limits h3::before {
		content: '';
		width: 6px;
		height: 6px;
		background: var(--warn);
		box-shadow: 0 0 10px 1px rgba(245, 192, 78, 0.5);
	}

	.limits ul {
		margin: 1rem 0 0;
		padding-left: 1.1rem;
	}

	.limits li {
		margin-bottom: 0.7rem;
		color: var(--dim);
		font-size: 0.88rem;
	}

	.limits li::marker {
		color: var(--warn);
	}

	@media (max-width: 860px) {
		.split {
			grid-template-columns: minmax(0, 1fr);
		}

		.row {
			grid-template-columns: minmax(0, 1fr);
			gap: 0.2rem;
		}
	}
</style>
