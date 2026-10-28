#!/bin/sh
# The platform installer next to the app image: a .dmg on macOS, a .deb on Linux. Usage: package-gui-installer.sh [output-dir]
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out=${1:-$root/dist}
case "$(uname -s)" in
  Darwin) exec "$root/packaging/package-gui.sh" "$out" dmg ;;
  Linux) if command -v dpkg-deb >/dev/null 2>&1; then exec "$root/packaging/package-gui.sh" "$out" deb; else echo "no dpkg-deb; skipping the installer" >&2; fi ;;
  *) echo "no installer type for $(uname -s); app image only" >&2 ;;
esac
