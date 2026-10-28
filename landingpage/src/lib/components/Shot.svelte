<script>
	/**
	 * A screenshot of the desktop app, in a window chrome like the terminal's so the two read as
	 * the same kind of object. The images are 2× (see README, "Screenshots"); `width` and `height`
	 * are the 1× size, so the box is reserved before the image arrives.
	 */
	import { asset } from '$app/paths';

	let { name, width, height, alt, title = '', caption = '' } = $props();

	// `asset` is typed to the files under static/; the screenshot set is listed in the README.
	let src = $derived(asset(/** @type {Parameters<typeof asset>[0]} */ (`screens/${name}.webp`)));
</script>

<figure class="shot">
	<div class="chrome">
		<span class="live" aria-hidden="true"></span>
		{#if title}<span class="title mono">{title}</span>{/if}
		<span class="app mono" aria-hidden="true">gui</span>
	</div>
	<img {src} {width} {height} {alt} loading="lazy" decoding="async" />
	{#if caption}<figcaption>{caption}</figcaption>{/if}
</figure>

<style>
	.shot {
		margin: 0;
		overflow: hidden;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: rgba(7, 16, 27, 0.96);
		box-shadow: var(--shadow);
	}

	.chrome {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		padding: 0.55rem 0.95rem;
		border-bottom: 1px solid var(--line);
		background: rgba(10, 20, 34, 0.7);
	}

	.live {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: var(--cyan);
		box-shadow: 0 0 10px 1px rgba(34, 211, 238, 0.6);
	}

	.title {
		font-size: 0.72rem;
		letter-spacing: 0.02em;
		color: var(--dim);
	}

	.app {
		margin-left: auto;
		font-size: 0.66rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--faint);
	}

	img {
		display: block;
		width: 100%;
		height: auto;
	}

	figcaption {
		padding: 0.8rem 0.95rem 0.9rem;
		border-top: 1px solid var(--line);
		color: var(--dim);
		font-size: 0.86rem;
		line-height: 1.5;
	}
</style>
