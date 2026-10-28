<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';

	const icons = {
		'01': 'M12 2 3 6v6c0 5 3.8 9 9 10 5.2-1 9-5 9-10V6l-9-4Z',
		'02': 'M6 3h7l5 5v13H6V3Zm7 0v5h5',
		'03': 'M12 2a5 5 0 0 1 5 5v3h1.5A1.5 1.5 0 0 1 20 11.5v8A1.5 1.5 0 0 1 18.5 21h-13A1.5 1.5 0 0 1 4 19.5v-8A1.5 1.5 0 0 1 5.5 10H7V7a5 5 0 0 1 5-5Zm3 8V7a3 3 0 1 0-6 0v3h6Z',
		'04': 'M4 12.5 9 18 20 6'
	};
</script>

<section id="premises" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.premises.kicker}
			title={i18n.t.premises.title}
			lead={i18n.t.premises.lead}
		/>

		<div class="grid">
			{#each i18n.t.premises.items as item, idx (item.n)}
				<Reveal delay={idx * 70}>
					<article class="premise">
						<span class="num" aria-hidden="true">{item.n}</span>
						<span class="icon" aria-hidden="true">
							<svg
								viewBox="0 0 24 24"
								width="20"
								height="20"
								fill="none"
								stroke="currentColor"
								stroke-width="1.5"
								stroke-linecap="round"
								stroke-linejoin="round"
							>
								<path d={icons[/** @type {keyof typeof icons} */ (item.n)]} />
							</svg>
						</span>
						<h3>{item.title}</h3>
						<p>{item.body}</p>
						<span class="chip">{item.chip}</span>
					</article>
				</Reveal>
			{/each}
		</div>
	</div>
</section>

<style>
	/* Four cells on a hairline grid rather than four boxes: the rules do the
	   separating, and the big serif numeral is the ornament. */
	.grid {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 1px;
		background: var(--line);
		border: 1px solid var(--line);
	}

	.grid :global(.reveal) {
		height: 100%;
		background: var(--bg);
	}

	.premise {
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 0.8rem;
		height: 100%;
		padding: 2rem 1.8rem 1.8rem;
		background: linear-gradient(180deg, rgba(10, 20, 34, 0.5), rgba(5, 11, 20, 0.2));
		overflow: hidden;
		transition: background 0.3s ease;
	}

	.premise:hover {
		background: linear-gradient(180deg, rgba(14, 27, 44, 0.8), rgba(5, 11, 20, 0.3));
	}

	.num {
		font-family: var(--serif);
		font-size: 4.2rem;
		line-height: 0.8;
		letter-spacing: -0.04em;
		color: var(--cyan);
		opacity: 0.28;
		font-variant-numeric: tabular-nums;
		transition: opacity 0.3s ease;
	}

	.premise:hover .num {
		opacity: 0.6;
	}

	.icon {
		position: absolute;
		top: 1.5rem;
		right: 1.5rem;
		display: grid;
		place-items: center;
		width: 2.3rem;
		height: 2.3rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		color: var(--cyan);
		background: rgba(5, 11, 20, 0.5);
	}

	.premise h3 {
		margin-top: 0.6rem;
		font-size: 1.2rem;
		max-width: 22ch;
	}

	.premise p {
		color: var(--dim);
		font-size: 0.93rem;
		flex: 1;
		max-width: 48ch;
	}

	@media (max-width: 720px) {
		.grid {
			grid-template-columns: minmax(0, 1fr);
		}

		.premise {
			padding: 1.5rem 1.2rem;
		}
	}
</style>
