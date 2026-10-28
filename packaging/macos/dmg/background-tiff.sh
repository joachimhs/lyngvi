#!/bin/sh
# The window background as the one .tiff Finder and jpackage want, from the two PNGs beside this script.
# Usage: background-tiff.sh <destination.tiff>   (macOS; prints nothing and exits 1 when it cannot build one)
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
dst=${1:?usage: background-tiff.sh <destination.tiff>}
# tiffutil writes one file holding both resolutions, which is what a Retina screen needs; sips, which every
# macOS has, can only give the window the 1x image.
if command -v tiffutil >/dev/null 2>&1; then
  tiffutil -cathidpicheck "$here/background.png" "$here/background@2x.png" -out "$dst" >/dev/null
elif command -v sips >/dev/null 2>&1; then
  sips -s format tiff "$here/background.png" --out "$dst" >/dev/null
else
  echo "no tiffutil or sips: no window background" >&2
  exit 1
fi
