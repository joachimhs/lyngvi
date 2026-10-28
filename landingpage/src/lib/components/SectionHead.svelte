<script>
	let { kicker, title, lead = '', align = 'start' } = $props();
</script>

<!-- The index comes from a CSS counter on `.section`, so it follows the order
     the sections appear in on the page without each one having to know it. -->
<header class="head" class:center={align === 'center'}>
	<span class="index" aria-hidden="true"></span>
	<div class="text">
		<p class="kicker">{kicker}</p>
		<h2>{title}</h2>
		{#if lead}
			<p class="lead">{lead}</p>
		{/if}
	</div>
</header>

<style>
	.head {
		display: grid;
		grid-template-columns: 5.5rem minmax(0, 1fr);
		align-items: start;
		gap: 1rem;
		margin-bottom: clamp(2.25rem, 4.5vw, 3.5rem);
	}

	.index::before {
		content: counter(section, decimal-leading-zero);
		display: block;
		font-family: var(--serif);
		font-size: clamp(2.6rem, 4.6vw, 3.6rem);
		font-variant-numeric: tabular-nums;
		line-height: 1;
		letter-spacing: -0.03em;
		color: var(--faint);
		opacity: 0.55;
		translate: 0 -0.08em;
	}

	.head h2 {
		max-width: 22ch;
	}

	.head .lead {
		margin-top: 1.1rem;
	}

	.center {
		text-align: center;
	}

	.center h2,
	.center .lead {
		margin-inline: auto;
	}

	@media (max-width: 720px) {
		.head {
			grid-template-columns: minmax(0, 1fr);
			gap: 0.5rem;
		}

		.index::before {
			font-size: 1.6rem;
		}
	}
</style>
