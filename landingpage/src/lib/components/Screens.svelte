<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';
	import Shot from './Shot.svelte';

	/** The image behind each tab; the copy comes from content.js under the same id. */
	const images = {
		chat: { name: 'chat', width: 1280, height: 900, title: 'Lyngvi — Agentic coding' },
		deliver: { name: 'deliver', width: 1120, height: 760, title: 'Deliver changes' },
		diff: { name: 'diff', width: 1100, height: 720, title: 'Git Diff — Fix the login redirect' },
		traffic: { name: 'traffic', width: 1280, height: 900, title: 'Lyngvi — Traffic' },
		sessions: { name: 'x-sessions', width: 1200, height: 194, title: 'Lyngvi — Sandbox' },
		agents: { name: 'agents', width: 1280, height: 900, title: 'Lyngvi — Agents' }
	};

	/** @type {keyof typeof images} */
	let selected = $state('chat');

	let current = $derived(
		i18n.t.screens.items.find((item) => item.id === selected) ?? i18n.t.screens.items[0]
	);
</script>

<section id="screens" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.screens.kicker}
			title={i18n.t.screens.title}
			lead={i18n.t.screens.lead}
		/>

		<Reveal>
			<div class="tabs" role="tablist" aria-label={i18n.t.screens.title}>
				{#each i18n.t.screens.items as item (item.id)}
					<button
						class="tab"
						class:on={selected === item.id}
						role="tab"
						aria-selected={selected === item.id}
						onclick={() => (selected = /** @type {keyof typeof images} */ (item.id))}
					>
						{item.label}
					</button>
				{/each}
			</div>

			<div class="stage frame">
				{#key selected}
					<Shot
						name={images[selected].name}
						width={images[selected].width}
						height={images[selected].height}
						title={images[selected].title}
						alt={current.alt}
					/>
				{/key}
			</div>

			<div class="detail" aria-live="polite">
				<div class="text">
					<h3>{current.title}</h3>
					<p>{current.body}</p>
				</div>
				<p class="note mono">{i18n.t.screens.note}</p>
			</div>
		</Reveal>
	</div>
</section>

<style>
	.tabs {
		display: flex;
		flex-wrap: wrap;
		gap: 0.35rem;
		margin-bottom: 1.25rem;
	}

	.tab {
		padding: 0.34rem 0.7rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: transparent;
		color: var(--faint);
		font: inherit;
		font-family: var(--mono);
		font-size: 0.7rem;
		letter-spacing: 0.02em;
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

	.detail {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: end;
		gap: 1rem 2rem;
		margin-top: 1.4rem;
	}

	.detail h3 {
		font-size: 1.1rem;
	}

	.detail .text p {
		margin-top: 0.5rem;
		max-width: 68ch;
		color: var(--dim);
		font-size: 0.93rem;
	}

	.note {
		font-size: 0.68rem;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: var(--faint);
		white-space: nowrap;
	}

	@media (max-width: 720px) {
		.detail {
			grid-template-columns: minmax(0, 1fr);
		}

		.note {
			white-space: normal;
		}
	}
</style>
