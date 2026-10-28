<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';
</script>

<section id="features" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.features.kicker}
			title={i18n.t.features.title}
			lead={i18n.t.features.lead}
		/>

		<ol class="list">
			{#each i18n.t.features.items as item, idx (item.title)}
				<li>
					<Reveal delay={Math.min(idx, 4) * 50}>
						<div class="feature">
							<span class="n mono num">{String(idx + 1).padStart(2, '0')}</span>
							<h3>{item.title}</h3>
							<p>{item.body}</p>
						</div>
					</Reveal>
				</li>
			{/each}
		</ol>
	</div>
</section>

<style>
	/* An index rather than a grid of tiles: number, title, body on one rule each. */
	.list {
		margin: 0;
		padding: 0;
		list-style: none;
		border-top: 1px solid var(--line);
	}

	.feature {
		display: grid;
		grid-template-columns: 3.5rem minmax(0, 0.9fr) minmax(0, 1.6fr);
		gap: 1rem 1.5rem;
		align-items: baseline;
		padding: 1.15rem 0;
		border-bottom: 1px solid var(--line);
		transition: background 0.2s ease;
	}

	.feature:hover {
		background: linear-gradient(90deg, rgba(34, 211, 238, 0.05), transparent 60%);
	}

	.n {
		font-size: 0.74rem;
		letter-spacing: 0.1em;
		color: var(--cyan);
	}

	.feature h3 {
		font-size: 1rem;
	}

	.feature p {
		color: var(--dim);
		font-size: 0.9rem;
	}

	@media (max-width: 720px) {
		.feature {
			grid-template-columns: 2.5rem minmax(0, 1fr);
			gap: 0.4rem 0.75rem;
		}

		.feature p {
			grid-column: 2;
		}
	}
</style>
