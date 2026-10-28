<script>
	/**
	 * Fades its contents in when they first scroll into view. Respects
	 * prefers-reduced-motion, and shows everything when IntersectionObserver
	 * is missing or JavaScript never runs — the hidden state is only ever set
	 * from the action, which runs in the browser.
	 */
	let { delay = 0, children } = $props();

	let hidden = $state(false);

	/** @param {HTMLElement} node */
	function reveal(node) {
		const motionOk =
			typeof matchMedia === 'function' && !matchMedia('(prefers-reduced-motion: reduce)').matches;
		if (!motionOk || typeof IntersectionObserver !== 'function') return;

		hidden = true;
		const observer = new IntersectionObserver(
			(entries) => {
				for (const entry of entries) {
					if (entry.isIntersecting) {
						hidden = false;
						observer.disconnect();
					}
				}
			},
			{ rootMargin: '0px 0px -8% 0px', threshold: 0.08 }
		);
		observer.observe(node);
		return { destroy: () => observer.disconnect() };
	}
</script>

<div use:reveal class="reveal" class:hidden style="--delay: {delay}ms">
	{@render children()}
</div>

<style>
	.reveal {
		transition:
			opacity 0.6s cubic-bezier(0.2, 0.7, 0.3, 1) var(--delay),
			transform 0.6s cubic-bezier(0.2, 0.7, 0.3, 1) var(--delay);
	}

	.reveal.hidden {
		opacity: 0;
		transform: translateY(14px);
	}
</style>
