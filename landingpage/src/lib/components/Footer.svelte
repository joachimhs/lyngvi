<script>
	import { asset } from '$app/paths';
	import { i18n } from '#lib/i18n.svelte.js';
	import { links, version } from '#lib/links.js';
	import Ring from './Ring.svelte';
</script>

<footer class="foot">
	<div class="orbit" aria-hidden="true">
		<Ring opacity={0.22} />
	</div>

	<div class="page inner">
		<div class="top">
			<div class="brand">
				<img
					src={asset('lyngvi-mark-light-48.png')}
					srcset="{asset('lyngvi-mark-light-48.png')} 1x, {asset('lyngvi-mark-light-96.png')} 2x"
					alt=""
					width="48"
					height="48"
				/>
				<div>
					<img
						class="word"
						src={asset('lyngvi-wordmark-23.png')}
						srcset="{asset('lyngvi-wordmark-23.png')} 1x, {asset('lyngvi-wordmark-46.png')} 2x"
						alt="Lyngvi"
						width="94"
						height="23"
					/>
					<p class="tagline">{i18n.t.footer.tagline}</p>
				</div>
			</div>

			<nav class="links" aria-label="Project">
				<a href={links.repo} rel="noreferrer noopener">{i18n.t.nav.source}</a>
				<a href={links.releases} rel="noreferrer noopener">Releases</a>
				<a href={links.changelog} rel="noreferrer noopener">Changelog</a>
				<a href={links.licence} rel="noreferrer noopener">MIT</a>
			</nav>
		</div>

		<p class="origin">{i18n.t.footer.origin}</p>

		<div class="meta">
			<p class="small mono">
				v{version} · {i18n.t.footer.status} · {i18n.t.footer.licence}
			</p>
			<p class="small">{i18n.t.footer.colophon}</p>
		</div>
	</div>
</footer>

<style>
	.foot {
		position: relative;
		margin-top: var(--section-gap);
		padding-block: 3.5rem 2.5rem;
		border-top: 1px solid var(--line);
		background: rgba(2, 6, 12, 0.6);
		overflow: hidden;
	}

	/* The ring again, sinking below the edge of the page. */
	.orbit {
		position: absolute;
		left: 50%;
		bottom: -62%;
		width: min(70vw, 760px);
		translate: -50% 0;
		pointer-events: none;
		mask-image: linear-gradient(180deg, transparent, #000 40%);
	}

	.inner {
		position: relative;
	}

	.top {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-start;
		justify-content: space-between;
		gap: 1.5rem 3rem;
	}

	.brand {
		display: flex;
		align-items: center;
		gap: 0.9rem;
	}

	.brand .word {
		display: block;
		height: 23px;
		width: auto;
		opacity: 0.95;
	}

	.tagline {
		margin-top: 0.4rem;
		max-width: 30ch;
		color: var(--dim);
		font-size: 0.85rem;
	}

	.links {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem 1.5rem;
		padding-top: 0.6rem;
	}

	.links a {
		font-size: 0.85rem;
		font-weight: 500;
		text-decoration: none;
		color: var(--dim);
		transition: color 0.15s ease;
	}

	.links a:hover {
		color: var(--cyan);
	}

	/* The island story, set as the one line on the page in large serif italic. */
	.origin {
		margin-top: clamp(2.5rem, 6vw, 4.5rem);
		max-width: 34ch;
		font-family: var(--serif);
		font-style: italic;
		font-size: clamp(1.4rem, 2.6vw, 2rem);
		line-height: 1.25;
		color: var(--text);
		text-wrap: balance;
	}

	.meta {
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
		gap: 0.5rem 2rem;
		margin-top: clamp(2.5rem, 6vw, 4.5rem);
		padding-top: 1.2rem;
		border-top: 1px solid var(--line);
	}

	.small {
		color: var(--faint);
		font-size: 0.76rem;
	}
</style>
