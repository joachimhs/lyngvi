#!/bin/sh
# The Finder window of a built disk image: the application on the left, a link to /Applications on the right,
# our background behind them. Opens the image read-write, writes the link and the background, asks Finder for
# the layout, and closes it up again in place.
# Usage: layout-dmg.sh <image.dmg>   or   layout-dmg.sh --check   (only ask whether Finder may be scripted)
# Exit: 0 laid out, 3 everything but the layout — Finder refused, the image still installs by dragging.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# Laying a window out means scripting Finder, and macOS guards that with the Automation permission: the first
# attempt raises a dialog, a refused one fails with -1743 and never asks again. Everything else here works
# without it, so a no is reported and not fatal.
finder_note() {
  cat >&2 <<'NOTE'
  macOS has not let this terminal control Finder, so the disk image window is Finder's default rather than
  ours. The image installs by dragging all the same. To get the window: System Settings -> Privacy &
  Security -> Automation, find the application the build runs in (Terminal, iTerm, VS Code, ...) and switch
  Finder on. If it is not listed at all the permission was never asked for; 'tccutil reset AppleEvents' and
  run again, and the dialog comes back. Then 'packaging/build-dmg.sh -l <image.dmg>' lays the window out on
  the image you already built, without building it again.
NOTE
}
finder_check() {
  # The probe is also what raises the dialog, which is why a build asks for this before it starts rather than
  # after the jars are compiled.
  osascript -e 'tell application "Finder" to get name of startup disk' >/dev/null 2>&1
}
if [ "${1:-}" = --check ]; then
  finder_check && exit 0
  echo "Finder cannot be scripted from here:" >&2
  finder_note
  exit 3
fi

image=${1:?usage: layout-dmg.sh <image.dmg> | --check}
[ -f "$image" ] || { echo "no such disk image: $image" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

hdiutil convert "$image" -format UDRW -o "$work/rw" -quiet
# Room for the .DS_Store Finder is about to write: jpackage sizes its image with little to spare.
hdiutil resize -size "$(( $(du -m "$work/rw.dmg" | cut -f1) + 16 ))m" "$work/rw.dmg" >/dev/null 2>&1 || true
info=$(hdiutil attach "$work/rw.dmg" -readwrite -noverify -noautoopen)
# hdiutil prints one tab-separated line per entry; the mount point is the last field of the one that has one.
dev=$(printf '%s\n' "$info" | awk '/^\/dev\// { print $1; exit }')
vol=$(printf '%s\n' "$info" | awk -F'\t' 'NF > 1 && $NF ~ /^\// { print $NF; exit }')
[ -n "$vol" ] || { [ -z "$dev" ] || hdiutil detach "$dev" -quiet; echo "the disk image did not mount" >&2; exit 1; }

# The drop target, written here rather than by Finder: this is the part that has to be in the image.
[ -e "$vol/Applications" ] || ln -s /Applications "$vol/Applications"
mkdir -p "$vol/.background"
"$here/background-tiff.sh" "$vol/.background/background.tiff" >/dev/null || true
# The script is written for jpackage, which substitutes the volume name before running it; do the same.
sed "s/DEPLOY_ACTUAL_VOLUME_NAME/$(basename "$vol")/" "$here/lyngvi-dmg-setup.scpt" > "$work/setup.scpt"
status=0
if err=$(osascript "$work/setup.scpt" 2>&1); then
  echo "the disk image window was laid out by Finder"
else
  status=3
  echo "Finder did not lay the disk image window out: ${err:-no message}" >&2
  # LYNGVI_DMG_NOTE_SHOWN: a caller that has already said all this once.
  [ -n "${LYNGVI_DMG_NOTE_SHOWN:-}" ] || finder_note
fi

sleep 1 && sync  # Finder writes the .DS_Store as the window closes
hdiutil detach "$dev" -quiet || hdiutil detach "$dev" -force -quiet
hdiutil convert "$work/rw.dmg" -format UDZO -imagekey zlib-level=9 -o "$work/final" -quiet
mv -f "$work/final.dmg" "$image"
exit $status
