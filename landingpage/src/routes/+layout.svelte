<script>
	// Bundled with the page: the site still makes no outside requests.
	import '@fontsource/instrument-serif/latin-400.css';
	import '@fontsource/instrument-serif/latin-400-italic.css';
	import '@fontsource-variable/geist/wght.css';
	import '@fontsource-variable/geist-mono/wght.css';
	import '../app.css';
	import { onMount } from 'svelte';
	import { asset } from '$app/paths';
	import { i18n } from '#lib/i18n.svelte.js';

	let { children } = $props();

	// Absolute against the site URL (https://lyngvi.no by default), which is what social
	// cards need; relative only when the build cleared SITE_URL.
	const ogImage = __SITE_URL__ ? `${__SITE_URL__}/og-image.png` : asset('og-image.png');

	onMount(() => i18n.restore());
</script>

<svelte:head>
	<title>{i18n.t.meta.title}</title>
	<meta name="description" content={i18n.t.meta.description} />
	<meta property="og:title" content={i18n.t.meta.title} />
	<meta property="og:description" content={i18n.t.meta.description} />
	<meta property="og:type" content="website" />
	<meta property="og:image" content={ogImage} />
	<meta name="twitter:card" content="summary_large_image" />
	{#if __SITE_URL__}
		<link rel="canonical" href="{__SITE_URL__}/" />
		<meta property="og:url" content="{__SITE_URL__}/" />
	{/if}
</svelte:head>

<div class="rails" aria-hidden="true"></div>

{@render children()}
