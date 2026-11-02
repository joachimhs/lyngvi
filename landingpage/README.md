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
- **Social cards**: the site is `https://lyngvi.no`, and that is the default
  `SITE_URL`: the `og:image` is an absolute URL and `og:url` and a canonical link
  point at it. Set `SITE_URL` to build for another host, or `SITE_URL=` to leave the
  card image relative — which most scrapers will not follow.

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

| File                               | What it is                                                                                                       |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `lyngvi-mark-light-{26,32,48}.png` | the light mark at the three sizes the page shows it at: the hero's 26 px, the bar's 32 px and the footer's 48 px |
| `lyngvi-mark-light-{52,64,96}.png` | the same three, twice the size, for the `2x` half of each `srcset`                                               |
| `lyngvi-wordmark-{18,23}.png`      | the wordmark at the heights the bar and the footer set, 73 × 18 and 94 × 23                                      |
| `lyngvi-wordmark-{36,46}.png`      | the same two at `2x`, 146 × 36 and 188 × 46                                                                      |
| `favicon.png`, `favicon-dark.png`  | the navy mark and the light one; `app.html` picks by `prefers-color-scheme`                                      |
| `apple-touch-icon.png`             | the light mark on the brand navy, since iOS fills transparency with black                                        |
| `og-image.png`                     | the social card, 1200 × 630: the **light** mark, the wordmark and the hero line on the brand navy (see below)    |

The mark and the wordmark are here at every size they are shown at, and nowhere
in a size the browser has to scale: fine line work — the segmented ring, the
wolf's muzzle — turns to mush when a 256 px PNG is drawn into 32 CSS px, and the
browser's filter cannot be told to sharpen afterwards. The `<img>` tags carry
`srcset` with the `1x` and the `2x` file and `width`/`height` equal to the `1x`
one, so a HiDPI screen gets its own pixels and neither screen resamples.

Regenerate them with `sharp` from `docs/branding/lyngvi-mark-light.png` and
`lyngvi-wordmark.png`: `resize(w, h, { kernel: 'lanczos3' })` then
`sharpen({ sigma: 0.5, m1: 0.6, m2: 0.9 })`, which puts back the edge contrast a
downscale takes out without a halo at these sizes. Sizes that are shown at a
fixed CSS height take their width from the master's 600 × 147, rounded, and the
`2x` file is exactly twice the `1x` one so no size lands on half a pixel. The
same pass clears the stray speck outside the ring (every blob under 20 px of the
master's), which at 26 px read as a smudge of dirt beside the mark.

## The social card

`og-image.png` is what Slack, LinkedIn, X and the rest show when the page is
pasted, so it is seen on their background, not ours — almost always a dark one.
The mark on it is therefore the **light** one: the navy wolf of
`docs/branding/lyngvi-mark.png` on the navy card was a cyan ring around a dark
blob, which is the same failure the app icons have a pale plate to avoid. Beside
it the card carries the white wordmark, the hero line (Instrument Serif, the
second line in cyan italic) and one Geist Mono line with the site and the
one-sentence description, on the page's own background: the navy, a cyan pool of
light at the top left, a hairline rail down each edge and the corner ticks.

It is made the way the screenshots are, by rendering it rather than drawing it,
so the type is the page's own: an HTML file at 1200 × 630 that links the
`@fontsource` packages from `node_modules`, screenshotted with
`chromium --headless --screenshot --window-size=1200,630 --force-device-scale-factor=2`
and downscaled from that 2× render to 1200 × 630 with `sharp`
(`lanczos3`, then `sharpen({ sigma: 0.4, m1: 0.5, m2: 0.8 })`). The light mark it
places is cut at 600 px from the 902 px master with the recipe in
`docs/branding/README.md` — map every pixel by how cyan it is, the artwork's cyan
(42, 216, 242) staying and everything else becoming `#c9dfef` — with the stray
blobs outside the ring cleared first: the biggest is 112 px and the smallest real
detail, a sparkle, is 648, so dropping every blob of 200 px or less is safe.
Keep the card's hero line and description in step with `content.js`; the card is
English, because that is the language the page prerenders and the one the
`og:` tags carry.

Text changes belong in `src/lib/content.js`, and both languages have to be kept
in step — the components read the same keys from either. The three typefaces
(Instrument Serif for headings, Geist for text, Geist Mono for commands) are
bundled from their `@fontsource` packages at build time, so the page still makes
no outside requests at all.

## The screenshots in `static/screens/`

They are the real JavaFX app, not mock-ups: `chat`, `deliver`, `diff`, `traffic` and
`agents` are whole windows, `wizard` is the «New sandbox» dialog, and the `x-*` files
are excerpts of them (the denied-hosts table, the review part of the deliver dialog,
the session container table). All are WebP at 2× and the components pass the 1× size
as `width`/`height`. `Shot.svelte` is the frame they sit in; `Screens.svelte` is the
gallery section, the hero opens with `chat` (the app is what the page leads with, and
the terminal comes under it), and the Egress, Delivery and Install sections each show
one next to the thing it illustrates.

To retake them: run the GUI (`mvn -pl lyngvi-gui javafx:run`) with
`-Dglass.gtk.uiScale=2.0` (or on a HiDPI display) against a sandbox named `shop`
with one repository, three conversations and an undelivered change that includes an
executable, dark theme, window 1280 × 900. Crop to the window and convert with
`sharp` (`.webp({ quality: 80 })`). The copy in `content.js` under `screens` and the
`figure` keys describes what is in the pictures, so retake the pictures and the copy
together.

`wizard` is the one that is not a window of the running app: it is «New sandbox» on
its first page, 780 × 460, with the folder and the repository filled in as a user
would have filled them and nothing created yet. The Install section shows it at
exactly 780 CSS px, so the 2× file lands pixel for pixel. The whole app is in
English in every picture, including on the Norwegian page — the Norwegian copy
translates what it describes but quotes the labels as they appear, «Create» and the
`Read`/`Search`/`Run`/`Edit` rows alike.

Two interactive pieces mirror the real product rather than illustrating it:
the egress demo applies the bundled block- and allowlists the way Squid would
(blocklist first, leading dot matches subdomains), and the delivery table applies
the apply/hold/reject rules from the delivery contract — held files are only
counted once you name them, rejected ones never are.

Keep the facts here in step with `README.md`, `CHANGELOG.md` and the
specification when the product moves; the page states version numbers, limits and
guarantees that are meant to be true.
