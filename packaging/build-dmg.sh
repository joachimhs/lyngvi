#!/bin/sh
# One command for the macOS disk image we hand out: builds the jars, makes sure the app icon is the
# Lyngvi logo, runs the jlink + jpackage pipeline in package-gui.sh, and leaves a .dmg named the way
# the release publishes it with a .sha256 beside it.
# Usage: packaging/build-dmg.sh [-o out-dir] [-i icon.icns|icon.png] [-n]   (macOS, JDK 25 with jmods)
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out=$root/dist
icon=$root/packaging/icons/lyngvi.icns
# The mark padded into a square (docs/branding/README.md): the source when the .icns has to be rebuilt.
logo=$root/packaging/icons/lyngvi.png
build=yes
layout=yes
relayout=""

usage() {
  cat <<USAGE
usage: packaging/build-dmg.sh [-o out-dir] [-i icon] [-n] [-p] | -l <image.dmg>
  -o  output directory (default: $root/dist)
  -i  application icon: an .icns, or a PNG that is converted to one (default: packaging/icons/lyngvi.icns)
  -n  skip Maven and package the jars already in the target directories
  -p  plain image: leave the disk image as jpackage wrote it, with no Finder window of our own
  -l  lay the window out on an image already built and stop — the retry after Finder was allowed in
USAGE
}
while [ $# -gt 0 ]; do
  case $1 in
    -o) [ $# -ge 2 ] || { usage >&2; exit 2; }; out=$2; shift 2 ;;
    -i) [ $# -ge 2 ] || { usage >&2; exit 2; }; icon=$2; shift 2 ;;
    -l) [ $# -ge 2 ] || { usage >&2; exit 2; }; relayout=$2; shift 2 ;;
    -n) build=no; shift ;;
    -p) layout=no; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) out=$1; shift ;;  # positional output directory, as in the other packaging scripts
  esac
done

# A .dmg is made by jpackage through hdiutil, so this one really does need macOS.
[ "$(uname -s)" = Darwin ] || {
  echo "a .dmg can only be built on macOS; elsewhere use packaging/package-gui.sh [out] [app-image|deb|rpm]" >&2
  exit 1; }

# -l: the window on an image that is already built, which is the whole of the retry once macOS has been told
# this terminal may control Finder. Seconds, where a build is minutes.
if [ -n "$relayout" ]; then
  status=0
  "$root/packaging/macos/dmg/layout-dmg.sh" "$relayout" || status=$?
  sums="$relayout.sha256"
  [ ! -f "$sums" ] || ( cd "$(dirname "$relayout")" && shasum -a 256 "$(basename "$relayout")" > "$(basename "$sums")" )
  exit $status
fi
if [ -n "${JAVA_HOME:-}" ]; then java_home=$JAVA_HOME
else
  command -v java >/dev/null || { echo "no java on PATH and no JAVA_HOME; a JDK 25 with jmods is needed" >&2; exit 1; }
  java_home=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
fi
for tool in jlink jpackage; do
  [ -x "$java_home/bin/$tool" ] || { echo "no $tool in $java_home/bin; point JAVA_HOME at a JDK 25" >&2; exit 1; }
done
[ -d "$java_home/jmods" ] || { echo "no jmods in $java_home; jlink needs a JDK that ships them (Temurin does)" >&2; exit 1; }

# An .icns from any square PNG, with the tools macOS ships. sips -z keeps the alpha channel, so the
# transparent artwork stays transparent; 1024 is upscaled, the mark it comes from being 902 px square.
make_icns() {
  src=$1; dst=$2
  [ -f "$src" ] || { echo "no icon source: $src" >&2; exit 1; }
  command -v sips >/dev/null && command -v iconutil >/dev/null \
    || { echo "need sips and iconutil to build $dst from $src; pass -i with a ready .icns instead" >&2; exit 1; }
  iconset=$(mktemp -d)/lyngvi.iconset
  mkdir -p "$iconset"
  for spec in 16x16:16 16x16@2x:32 32x32:32 32x32@2x:64 128x128:128 128x128@2x:256 \
              256x256:256 256x256@2x:512 512x512:512 512x512@2x:1024; do
    sips -z "${spec#*:}" "${spec#*:}" "$src" --out "$iconset/icon_${spec%:*}.png" >/dev/null
  done
  iconutil -c icns "$iconset" -o "$dst"
  rm -rf "$(dirname "$iconset")"
  echo "icon: $dst from $src"
}

case $icon in
  *.icns)
    # The committed .icns holds the logo already (docs/branding/README.md), so it is normally just used.
    # Only a checkout without it is rebuilt here; after editing the artwork, pass '-i packaging/icons/lyngvi.png'.
    [ -f "$icon" ] || make_icns "$logo" "$icon" ;;
  *)
    png=$icon
    icon=$(mktemp -d)/lyngvi.icns
    make_icns "$png" "$icon" ;;
esac

# Ask about Finder before the build rather than after it: the question is a dialog from macOS, and it belongs
# at the start of the minutes it takes to get to the image it is about. A refusal is not the end of the run,
# and having been told once is enough — the layout step says the short version after that.
if [ "$layout" = no ] || "$root/packaging/macos/dmg/layout-dmg.sh" --check; then :
else LYNGVI_DMG_NOTE_SHOWN=1; export LYNGVI_DMG_NOTE_SHOWN
fi

if [ "$build" = yes ]; then
  command -v mvn >/dev/null || { echo "no mvn on PATH; build the jars yourself and pass -n" >&2; exit 1; }
  echo "building the jars (mvn package -DskipTests)"
  ( cd "$root" && mvn -B -q -DskipTests package )
fi

version=$(cat "$root/VERSION")
appversion=$(echo "$version" | sed 's/-.*//')  # jpackage only takes the numeric part, as in package-gui.sh
arch=$(uname -m); case $arch in x86_64|amd64) arch=x86_64 ;; arm64|aarch64) arch=arm64 ;; esac
mkdir -p "$out"
rm -f "$out/lyngvi-$appversion.dmg"  # jpackage will not overwrite an image left by an earlier run
LYNGVI_ICON=$icon LYNGVI_DMG_LAYOUT=$layout "$root/packaging/package-gui.sh" "$out" dmg

dmg="$out/lyngvi-$appversion.dmg"
[ -f "$dmg" ] || { echo "jpackage produced no $dmg" >&2; exit 1; }
final="$out/lyngvi-gui-$version-macos-$arch.dmg"  # the name release.yml publishes
mv -f "$dmg" "$final"
( cd "$out" && shasum -a 256 "$(basename "$final")" > "$(basename "$final").sha256" )

# Look inside what we are about to hand out: the app with its icon, and the drag-to-Applications window.
mnt=$(mktemp -d)
if hdiutil attach "$final" -nobrowse -quiet -mountpoint "$mnt"; then
  missing=""; plain=""
  [ -d "$mnt/lyngvi.app" ] || missing="$missing lyngvi.app"
  [ -f "$mnt/lyngvi.app/Contents/Resources/lyngvi.icns" ] || missing="$missing the application icon"
  # Cosmetic, and the work of a Finder that a build without a desktop session does not have: worth saying,
  # not worth failing over, since the image installs either way.
  [ -e "$mnt/Applications" ] || [ -L "$mnt/Applications" ] || plain="$plain the Applications alias,"
  [ -f "$mnt/.background/background.tiff" ] || plain="$plain the window background,"
  hdiutil detach "$mnt" -quiet || true
  [ -z "$plain" ] || echo "note: the image is without${plain%,} — Finder did not lay the window out" >&2
  [ -z "$missing" ] || { echo "the image is missing:$missing" >&2; exit 1; }
else
  echo "could not mount $final to check it; the image was still written" >&2
fi
rmdir "$mnt" 2>/dev/null || true

echo "built $final ($(du -h "$final" | cut -f1))"
echo "unsigned and not notarised: first run needs right-click -> Open, or 'xattr -dr com.apple.quarantine /Applications/lyngvi.app'"
