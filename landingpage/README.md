# Lyngvi landing page

The public page for Lyngvi: what it is, how the sandbox is built, and why the
boundaries hold. A SvelteKit app that prerenders to a plain static site — no
server, no runtime data, no third-party requests.

```sh
npm install
npm run dev       # http://localhost:5173
npm run build     # static site in build/
npm run preview   # serve build/ locally
npm run check     # svelte-check (types and a11y)
npm run format    # prettier
```

`build/` is the deliverable: HTML, CSS, JS and the images, servable by anything
that can hand out files.

## Deploying

- **A sub-path** (GitHub Pages project sites, for instance): build with
  `BASE_PATH=/lyngvi npm run build`, so asset URLs are prefixed.
- **Social cards**: `SITE_URL=https://lyngvi.example npm run build` turns the
  `og:image` into an absolute URL and adds `og:url` and a canonical link. Without
  it the card image stays relative, which most scrapers will not follow.

## How it is put together

| Path                      | What it is                                                                                                                         |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `src/routes/+page.svelte` | the page: one section component after another                                                                                      |
| `src/lib/components/`     | one component per section, plus `Terminal`, `Reveal` and `SectionHead`                                                             |
| `src/lib/content.js`      | **all** user-visible copy, in English and Norwegian                                                                                |
| `src/lib/i18n.svelte.js`  | the language state; English is prerendered, the stored or browser preference is applied on mount                                   |
| `src/lib/egress.js`       | the proxy rules the egress demo answers from, copied from `proxy/blocked-domains.txt`, the bundled profile and the agent manifests |
| `src/lib/links.js`        | every outbound link and the version shown on the page                                                                              |
| `src/app.css`             | design tokens and base styles: the logo's navy and cyan, the ruled page, the corner-tick frames                                    |
| `static/`                 | the artwork, all derived from `docs/branding/` (see below)                                                                         |
| `static/screens/`         | screenshots of the GUI, taken from the running app (see below)                                                                     |

## The artwork in `static/`

All of it comes from `docs/branding/`, and none of it is edited by hand:

| File                              | What it is                                                                                                                                                           |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lyngvi-mark-light.png`           | the mark with the navy repainted light and the cyan kept, because the navy wolf on a navy page is a dark blob at 26–54 px. This is the mark the page uses everywhere |
| `lyngvi-wordmark.png`             | the wordmark cut off the bottom of `lyngvi-logo.png` and recoloured white through its own alpha                                                                      |
| `favicon.png`, `favicon-dark.png` | the navy mark and the light one; `app.html` picks by `prefers-color-scheme`                                                                                          |
| `apple-touch-icon.png`            | the light mark on the brand navy, since iOS fills transparency with black                                                                                            |
| `og-image.png`                    | the social card: mark and wordmark on the brand navy with a cyan pool of light                                                                                       |

Regenerate them with `sharp` if the artwork changes; the recipe for the light
mark is to map every pixel by how cyan it is — cyan stays, everything else
becomes `#c9dfef` — at 4× and then downscale once.

Text changes belong in `src/lib/content.js`, and both languages have to be kept
in step — the components read the same keys from either. The three typefaces
(Instrument Serif for headings, Geist for text, Geist Mono for commands) are
bundled from their `@fontsource` packages at build time, so the page still makes
no outside requests at all.

## The screenshots in `static/screens/`

They are the real JavaFX app, not mock-ups: `chat`, `deliver`, `diff`, `traffic` and
`agents` are whole windows, the `x-*` files are excerpts of them (the denied-hosts
table, the review part of the deliver dialog, the session container table). All are
WebP at 2× and the components pass the 1× size as `width`/`height`. `Shot.svelte` is
the frame they sit in; `Screens.svelte` is the gallery section, and the Egress and
Delivery sections each show one excerpt next to the thing it illustrates.

To retake them: run the GUI (`mvn -pl lyngvi-gui javafx:run`) with
`-Dglass.gtk.uiScale=2.0` (or on a HiDPI display) against a sandbox named `shop`
with one repository, three conversations and an undelivered change that includes an
executable, dark theme, window 1280 × 900. Crop to the window and convert with
`sharp` (`.webp({ quality: 80 })`). The copy in `content.js` under `screens` and the
`figure` keys describes what is in the pictures, so retake the pictures and the copy
together.

Two interactive pieces mirror the real product rather than illustrating it:
the egress demo applies the bundled block- and allowlists the way Squid would
(blocklist first, leading dot matches subdomains), and the delivery table applies
the apply/hold/reject rules from the delivery contract — held files are only
counted once you name them, rejected ones never are.

Keep the facts here in step with `README.md`, `CHANGELOG.md` and the
specification when the product moves; the page states version numbers, limits and
guarantees that are meant to be true.
