# Branding

The logo: a wolf inside a segmented barrier, with three sparkles. The wolf is the agent's power, the
barrier its boundaries, and the sparkles its artificial intelligence — the same idea the name carries,
Lyngvi being the island where Fenrir was bound.

| File | What it is |
|---|---|
| `lyngvi-logo.png` | the full lockup (mark + wordmark), 902 × 1151, transparent |
| `lyngvi-mark.png` | the mark on its own, square, 902 × 903, transparent |
| `lyngvi-mark-light.png` | the mark with the navy repainted `#c9dfef` and the cyan kept, 256 × 256 — the master the landing page's sizes are cut from, because the navy wolf on a navy page is a dark blob. The social card needs it larger and cuts its own 600 px copy from `lyngvi-mark.png` with the same recipe; `landingpage/README.md` has that pass |
| `lyngvi-wordmark.png` | the wordmark cut off the bottom of `lyngvi-logo.png` and recoloured white through its own alpha, 600 × 147 — the other landing page master |

The first two are trimmed to the artwork, so they can be placed without having to crop first. The colours
are a dark navy and a cyan; the artwork carries no background, which keeps it legible on light surfaces
through the navy and on dark ones through the cyan. The last two are recolourings of the same artwork,
kept here rather than in `landingpage/static/` because the page serves only the sizes it shows; the recipe
for the light mark is to map every pixel by how cyan it is — cyan stays, everything else becomes `#c9dfef`
— at 4× and then downscale once.

## Derived files

Two sets are generated from `lyngvi-mark.png` and committed, because the build needs them in fixed
places and neither jpackage nor JavaFX will resize for us:

- `lyngvi-gui/src/main/resources/no/syntakserror/lyngvi/gui/icon/icon-{16,32,64,128,256,512}.png` —
  the window icon. `App` adds every size it finds and lets the window manager choose.
- `packaging/icons/lyngvi.icns` and `packaging/icons/lyngvi.png` — the application icon that
  `packaging/package-gui.sh` hands to jpackage: the `.icns` on macOS, the PNG elsewhere.

Each is the mark, in its own colours, on a rounded square of a pale ice blue, `#e7f1f8`. The plate is
there because the artwork carries no background: on a dark dock or taskbar the navy wolf had nothing
to stand on and the icon was a cyan ring around a smudge. The pale blue is light enough for the navy
and dark enough for the cyan, so both survive, and a hairline of `rgba(1,25,50,0.14)` gives the plate
an edge on a white desktop as well. Its corners are rounded by 22.5% of the side, Apple's 185.4 of 824,
and the mark sits inside it with 10% padding on every side. The two sets differ in one thing: the PNGs
fill their canvas, which is what a window and a taskbar want, while the `.icns` insets the plate into
Apple's 824-of-1024 grid, so the application sits in the Dock at the size its neighbours do.

The icons are the one place the stray speck below is erased along the way. Regenerate them with `sharp`
if the artwork changes; `lyngvi-logo.png` and `lyngvi-mark.png` are the only sources worth editing by
hand.

- `lyngvi-gui/src/main/resources/no/syntakserror/lyngvi/gui/icon/splash-mark-512.png` — the mark on
  the splash screen, and the one derived file that is **not** an icon: no padding, because the splash
  draws it in a box of its own and the icon margin would only make it smaller, and 512 px so a HiDPI
  screen has a pixel per pixel at the 192 points it is shown at.

## The disk image

`packaging/macos/dmg/background.{svg,png}` and `background@2x.png` are the window the macOS `.dmg` opens
in: the name in Instrument Serif, the tagline in Geist, a hairline and the arrow from the application to
the `/Applications` alias — the landing page's type and its one cyan, on white. The SVG is the source and
the two PNGs are rendered from it, 640 × 400 and twice that, with those fonts; `packaging/package-gui.sh`
turns the pair into the `.tiff` jpackage asks for. The icons the window shows are the application icon
above and the system's folder, so nothing of the mark belongs in the background itself.

The artwork carries a stray blob of some 112 pixels outside the ring, bottom right, clipped by the edge
of the canvas — `lyngvi-mark.png` is trimmed to it, so it is in every file derived from the mark. The
splash copy and the icons have it erased, the icons since the plate under them turned it from a speck
on nothing into a speck on a surface. The landing page's sized files have it erased too, along with two
single stray pixels — `landingpage/README.md` has that pass. The two masters above still carry it, as
does the source artwork. Worth removing from the source at some point.
