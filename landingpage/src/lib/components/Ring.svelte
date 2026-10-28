<script>
	/**
	 * The ring from the logo, redrawn as line work: a segmented outer band,
	 * a thicker inner band and a dotted track, with a few circuit nodes.
	 * Pure decoration — the rings turn slowly, and stop under reduced motion.
	 */
	let { opacity = 1 } = $props();
</script>

<svg class="ring" viewBox="0 0 200 200" aria-hidden="true" style="opacity: {opacity}">
	<g class="outer">
		<circle cx="100" cy="100" r="94" pathLength="600" stroke-dasharray="88 12" />
		<g class="nodes">
			<circle cx="100" cy="6" r="2.4" transform="rotate(37 100 100)" />
			<circle cx="100" cy="6" r="2.4" transform="rotate(161 100 100)" />
			<circle cx="100" cy="6" r="2.4" transform="rotate(272 100 100)" />
		</g>
	</g>
	<g class="band">
		<circle cx="100" cy="100" r="84" pathLength="400" stroke-dasharray="82 18" />
		<path d="M100 16 v8 M100 176 v8 M16 100 h8 M176 100 h8" />
	</g>
	<g class="track">
		<circle cx="100" cy="100" r="66" pathLength="360" stroke-dasharray="0.5 5.5" />
	</g>
	<g class="trace">
		<path d="M100 100 m-28 -52 l10 -9 h26" />
		<circle cx="108" cy="39" r="2" />
		<circle cx="72" cy="48" r="1.6" />
	</g>
</svg>

<style>
	.ring {
		display: block;
		width: 100%;
		height: auto;
		fill: none;
		stroke: var(--cyan);
		stroke-linecap: round;
		overflow: visible;
	}

	.outer {
		stroke-width: 0.6;
		transform-origin: 100px 100px;
		animation: spin 160s linear infinite;
	}

	.nodes {
		fill: var(--cyan);
		stroke: none;
	}

	.band {
		stroke-width: 2.4;
		opacity: 0.45;
		transform-origin: 100px 100px;
		animation: spin 110s linear infinite reverse;
	}

	.band path {
		stroke-width: 0.8;
		opacity: 0.9;
	}

	.track {
		stroke-width: 0.9;
		opacity: 0.6;
		transform-origin: 100px 100px;
		animation: spin 240s linear infinite;
	}

	.trace {
		stroke-width: 0.7;
		opacity: 0.8;
	}

	.trace circle {
		fill: var(--bg);
	}

	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
</style>
