<script>
	import { asset } from '$app/paths';
	import { i18n } from '#lib/i18n.svelte.js';
	import { links } from '#lib/links.js';

	let scrolled = $state(false);
	let open = $state(false);

	function onScroll() {
		scrolled = window.scrollY > 24;
	}
</script>

<svelte:window on:scroll={onScroll} />

<a class="skip" href="#top">{i18n.t.nav.skip}</a>

<header class="bar" class:scrolled class:open>
	<div class="page inner">
		<a class="brand" href="#top" aria-label="Lyngvi">
			<img
				class="word"
				src={asset('icon-512.png')}
				srcset="{asset('icon-512.png')} 1x, {asset('icon-512.png')} 2x"
				alt="Lyngvi"
				width="73"
				height="18"
			/>
		</a>

		<nav class="links" aria-label="Sections">
			{#each i18n.t.nav.links as link (link.href)}
				<a href={link.href} onclick={() => (open = false)}>{link.label}</a>
			{/each}
		</nav>

		<div class="actions">
			<button
				class="lang"
				type="button"
				onclick={() => i18n.toggle()}
				title={i18n.t.nav.langLabel}
				aria-label={i18n.lang === 'en' ? 'Bytt til norsk' : 'Switch to English'}
			>
				<span class="globe" aria-hidden="true">
					<svg
						viewBox="0 0 24 24"
						width="15"
						height="15"
						fill="none"
						stroke="currentColor"
						stroke-width="1.6"
					>
						<circle cx="12" cy="12" r="9" />
						<path d="M3 12h18M12 3c2.6 2.6 2.6 15 0 18M12 3c-2.6 2.6-2.6 15 0 18" />
					</svg>
				</span>
				{i18n.lang === 'en' ? 'NO' : 'EN'}
			</button>
			<a class="src" href={links.repo} rel="noreferrer noopener">
				{i18n.t.nav.source}
				<svg
					viewBox="0 0 12 12"
					width="10"
					height="10"
					fill="none"
					stroke="currentColor"
					stroke-width="1.5"
					stroke-linecap="round"
					stroke-linejoin="round"
					aria-hidden="true"><path d="M2.5 9.5 9.5 2.5M4 2.5h5.5V8" /></svg
				>
			</a>
			<button
				class="burger"
				type="button"
				aria-expanded={open}
				aria-label="Menu"
				onclick={() => (open = !open)}
			>
				<span></span><span></span><span></span>
			</button>
		</div>
	</div>
</header>

<style>
	.skip {
		position: absolute;
		left: -9999px;
		top: 0;
		z-index: 100;
		padding: 0.6rem 1rem;
		background: var(--surface-2);
	}

	.skip:focus {
		left: 0.5rem;
		top: 0.5rem;
	}

	.bar {
		position: sticky;
		top: 0;
		z-index: 50;
		border-bottom: 1px solid transparent;
		transition:
			background 0.25s ease,
			border-color 0.25s ease;
	}

	.bar.scrolled,
	.bar.open {
		background: rgba(5, 11, 20, 0.78);
		backdrop-filter: blur(16px) saturate(140%);
		border-bottom-color: var(--line);
	}

	.inner {
		display: flex;
		align-items: center;
		gap: 1.25rem;
		height: var(--bar);
	}

	.brand {
		display: inline-flex;
		align-items: center;
		gap: 0.6rem;
		text-decoration: none;
		flex: none;
	}

	.brand img {
		display: block;
		height: 32px;
		width: auto;
	}

	.brand .word {
		height: 45px;
		opacity: 0.95;
	}

	/* One line, always: the labels are longer in Norwegian, and a second line
	   would fall out of a bar with a fixed height. Below the breakpoint the
	   links move into the menu instead. */
	.links {
		display: flex;
		align-items: center;
		gap: 0.15rem;
		margin-left: auto;
		flex-wrap: nowrap;
	}

	.links a {
		position: relative;
		padding: 0.4rem 0.55rem;
		color: var(--dim);
		font-size: 0.82rem;
		font-weight: 500;
		text-decoration: none;
		white-space: nowrap;
		transition: color 0.15s ease;
	}

	/* The underline grows from the left on hover: the one motion the bar has. */
	.links a::after {
		content: '';
		position: absolute;
		left: 0.55rem;
		right: 0.55rem;
		bottom: 0.15rem;
		height: 1px;
		background: var(--cyan);
		transform: scaleX(0);
		transform-origin: left;
		transition: transform 0.2s ease;
	}

	.links a:hover {
		color: var(--text);
	}

	.links a:hover::after {
		transform: scaleX(1);
	}

	.actions {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		flex: none;
	}

	.lang {
		display: inline-flex;
		align-items: center;
		gap: 0.35rem;
		padding: 0.38rem 0.6rem;
		font-family: var(--mono);
		font-variant-numeric: tabular-nums;
		letter-spacing: 0.06em;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: transparent;
		color: var(--dim);
		font-size: 0.74rem;
		cursor: pointer;
		transition:
			color 0.15s ease,
			border-color 0.15s ease;
	}

	.lang:hover {
		color: var(--text);
		border-color: var(--line-strong);
	}

	.globe {
		display: inline-flex;
		color: var(--cyan);
	}

	.src {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		padding: 0.42rem 0.8rem;
		border: 1px solid var(--line-strong);
		border-radius: var(--r-sm);
		color: var(--text);
		font-size: 0.82rem;
		font-weight: 500;
		text-decoration: none;
		transition: border-color 0.15s ease;
	}

	.src svg {
		color: var(--cyan);
	}

	.src:hover {
		border-color: var(--cyan);
		color: var(--text);
	}

	.burger {
		display: none;
		flex-direction: column;
		justify-content: center;
		gap: 4px;
		width: 38px;
		height: 32px;
		padding: 0 7px;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: transparent;
		cursor: pointer;
	}

	.burger span {
		display: block;
		height: 1.5px;
		background: var(--text);
		border-radius: 2px;
	}

	/* Below the page's own max width the row gets tight in Norwegian, so that is
	   where the links move into the menu. */
	@media (max-width: 1180px) {
		.links {
			display: none;
		}

		.burger {
			display: flex;
		}

		.bar.open .links {
			position: absolute;
			left: 0;
			right: 0;
			top: var(--bar);
			display: flex;
			flex-direction: column;
			align-items: stretch;
			gap: 0;
			padding: 0.5rem var(--gutter) 1rem;
			background: rgba(5, 11, 20, 0.96);
			border-bottom: 1px solid var(--line);
			backdrop-filter: blur(16px);
		}

		.bar.open .links a {
			padding: 0.75rem 0.25rem;
			border-top: 1px solid var(--line);
			font-size: 0.95rem;
		}

		.bar.open .links a::after {
			display: none;
		}
	}

	@media (max-width: 480px) {
		.src {
			display: none;
		}
	}
</style>
