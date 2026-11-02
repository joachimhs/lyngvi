#!/bin/sh
# Downloads the CLI runtime image for this platform into <sandbox>/cli/ and verifies its SHA-256 (spec §4.6).
# Usage: scripts/fetch-cli.sh [--print] [sandbox-dir]     Env: LYNGVI_RELEASE_URL (base URL of the release assets)
set -eu
print=0; [ "${1:-}" = "--print" ] && { print=1; shift; }
dir=${1:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
version=$(cat "$dir/VERSION" 2>/dev/null || echo "")
[ -n "$version" ] || { echo "lyngvi: no VERSION file in $dir; run this from a sandbox" >&2; exit 2; }
os=$(uname -s | tr '[:upper:]' '[:lower:]'); case "$os" in darwin) os=macos ;; esac
arch=$(uname -m); case "$arch" in x86_64|amd64) arch=x86_64 ;; arm64|aarch64) arch=arm64 ;; esac
name="lyngvi-cli-$version-$os-$arch"
base=${LYNGVI_RELEASE_URL:-https://github.com/joachimhs/lyngvi/releases/download}
url="$base/v$version/$name.tar.gz"
if [ "$print" = 1 ]; then
  cat <<MSG
lyngvi: no runtime found. Download the CLI image for this platform:
  curl -fsSL -o /tmp/$name.tar.gz $url
  curl -fsSL -o /tmp/$name.tar.gz.sha256 $url.sha256
  (cd /tmp && shasum -a 256 -c $name.tar.gz.sha256)   # or: sha256sum -c
  mkdir -p "$dir/cli" && tar -xzf /tmp/$name.tar.gz --strip-components=1 -C "$dir/cli"
or let the launcher do it: ./setup.sh --download
MSG
  exit 0
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "lyngvi: downloading $url"
curl -fsSL -o "$tmp/$name.tar.gz" "$url"
curl -fsSL -o "$tmp/$name.tar.gz.sha256" "$url.sha256"
( cd "$tmp" && if command -v sha256sum >/dev/null 2>&1; then sha256sum -c "$name.tar.gz.sha256"; else shasum -a 256 -c "$name.tar.gz.sha256"; fi ) >/dev/null
rm -rf "$dir/cli.new"; mkdir -p "$dir/cli.new"
tar -xzf "$tmp/$name.tar.gz" --strip-components=1 -C "$dir/cli.new"
got=$("$dir/cli.new/bin/lyngvi" version)
[ "$got" = "lyngvi $version" ] || { echo "lyngvi: downloaded image reports '$got', expected $version" >&2; exit 1; }
rm -rf "$dir/cli"; mv "$dir/cli.new" "$dir/cli"
echo "lyngvi: installed $name into $dir/cli (checksum verified)"
