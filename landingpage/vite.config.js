import process from 'node:process';
import { sveltekit } from '@sveltejs/kit/vite';
import adapter from '@sveltejs/adapter-static';
import { defineConfig } from 'vite';

// SvelteKit 3 takes its configuration here instead of in svelte.config.js.
export default defineConfig({
	plugins: [
		sveltekit({
			// A plain static site: the single route is prerendered into build/.
			adapter: adapter({ precompress: false, strict: true }),
			paths: {
				// Set BASE_PATH=/lyngvi when publishing under a sub-path (GitHub Pages).
				base: /** @type {'' | `/${string}`} */ (process.env.BASE_PATH ?? '')
			},
			prerender: { handleHttpError: 'fail' }
		})
	],
	define: {
		// Social cards need absolute URLs, so the site's own host is the default; set
		// SITE_URL to build for another host, or SITE_URL= (empty) for relative card tags.
		__SITE_URL__: JSON.stringify((process.env.SITE_URL ?? 'https://lyngvi.no').replace(/\/$/, ''))
	},
	server: { port: 5173 }
});
