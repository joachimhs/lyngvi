#!/bin/sh
# Builds the CLI runtime image (spec §4.6): a jlink image with the core and CLI modules, a launcher that
# uses an AppCDS archive, and a tar.gz + .sha256 for the current platform.
# Usage: scripts/package-cli.sh [output-dir]   (run after 'mvn -q package'; needs a JDK 25 with jmods)
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out=${1:-$root/dist}
version=$(cat "$root/VERSION")
java_home=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}
m2=${M2_REPO:-$HOME/.m2/repository}
os=$(uname -s | tr '[:upper:]' '[:lower:]'); case "$os" in darwin) os=macos ;; esac
arch=$(uname -m); case "$arch" in x86_64|amd64) arch=x86_64 ;; arm64|aarch64) arch=arm64 ;; esac
image="$out/lyngvi-cli-$version-$os-$arch"
mods="$root/lyngvi-cli/target/original-lyngvi-cli-$version.jar"
for j in "$root/lyngvi-core/target/lyngvi-core-$version.jar" "$root/lyngvi-gateway-api/target/lyngvi-gateway-api-$version.jar" \
         "$m2/com/fasterxml/jackson/core/jackson-databind/2.19.2/jackson-databind-2.19.2.jar" \
         "$m2/com/fasterxml/jackson/core/jackson-core/2.19.2/jackson-core-2.19.2.jar" \
         "$m2/com/fasterxml/jackson/core/jackson-annotations/2.19.2/jackson-annotations-2.19.2.jar" \
         "$m2/info/picocli/picocli/4.7.7/picocli-4.7.7.jar"; do
  [ -f "$j" ] || { echo "missing $j (run mvn -q package first)" >&2; exit 1; }
  mods="$mods:$j"
done
rm -rf "$image"; mkdir -p "$out"
"$java_home/bin/jlink" --module-path "$mods:$java_home/jmods" \
  --add-modules no.syntakserror.lyngvi.cli,jdk.crypto.ec \
  --strip-debug --no-man-pages --no-header-files --compress zip-6 \
  --output "$image"
# Our own launcher: finds the image relative to itself and uses the class-data archive when present.
cat > "$image/bin/lyngvi" <<'LAUNCH'
#!/bin/sh
here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cds=""
[ -f "$here/lib/lyngvi.jsa" ] && cds="-XX:SharedArchiveFile=$here/lib/lyngvi.jsa -Xshare:auto"
exec "$here/bin/java" $cds -m no.syntakserror.lyngvi.cli/no.syntakserror.lyngvi.cli.Main "$@"
LAUNCH
chmod 755 "$image/bin/lyngvi"
echo "$version" > "$image/VERSION"
# AppCDS archive from one warm-up run (JDK 19+): faster start for every later run.
"$image/bin/java" -XX:ArchiveClassesAtExit="$image/lib/lyngvi.jsa" -m no.syntakserror.lyngvi.cli/no.syntakserror.lyngvi.cli.Main version >/dev/null 2>&1 || true
# Smoke: the image must report its own version.
got=$("$image/bin/lyngvi" version)
[ "$got" = "lyngvi $version" ] || { echo "image smoke test failed: '$got'" >&2; exit 1; }
tar -C "$out" -czf "$image.tar.gz" "$(basename "$image")"
( cd "$out" && { command -v sha256sum >/dev/null && sha256sum "$(basename "$image").tar.gz" || shasum -a 256 "$(basename "$image").tar.gz"; } > "$(basename "$image").tar.gz.sha256" )
echo "built $image.tar.gz ($(du -h "$image.tar.gz" | cut -f1))"
