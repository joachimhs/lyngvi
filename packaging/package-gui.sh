#!/bin/sh
# Builds the GUI application with jpackage (spec §4.6): a jlink runtime holding the GUI, CLI and JavaFX modules
# (so 'init' from the GUI can hand the CLI to a sandbox), packaged as an app image plus a dmg/pkg (macOS) or
# deb/rpm (Linux) when the platform tooling allows. Usage: scripts/package-gui.sh [output-dir] [app-image|dmg|pkg|deb|rpm]
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out=${1:-$root/dist}
type=${2:-app-image}
version=$(cat "$root/VERSION")
java_home=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}
m2=${M2_REPO:-$HOME/.m2/repository}
os=$(uname -s | tr '[:upper:]' '[:lower:]'); case "$os" in darwin) os=macos; fx=mac ;; *) fx=linux ;; esac
arch=$(uname -m); case "$arch" in x86_64|amd64) arch=x86_64; fxarch="" ;; arm64|aarch64) arch=arm64; fxarch="-aarch64" ;; esac
fxv=25.0.1
mods="$root/lyngvi-gui/target/lyngvi-gui-$version.jar:$root/lyngvi-cli/target/original-lyngvi-cli-$version.jar"
for j in "$root/lyngvi-core/target/lyngvi-core-$version.jar" "$root/lyngvi-gateway-api/target/lyngvi-gateway-api-$version.jar" \
         "$m2/com/fasterxml/jackson/core/jackson-databind/2.19.2/jackson-databind-2.19.2.jar" \
         "$m2/com/fasterxml/jackson/core/jackson-core/2.19.2/jackson-core-2.19.2.jar" \
         "$m2/com/fasterxml/jackson/core/jackson-annotations/2.19.2/jackson-annotations-2.19.2.jar" \
         "$m2/info/picocli/picocli/4.7.7/picocli-4.7.7.jar"; do
  [ -f "$j" ] || { echo "missing $j (run mvn -q package first)" >&2; exit 1; }
  mods="$mods:$j"
done
for fxm in base graphics controls fxml; do
  j="$m2/org/openjfx/javafx-$fxm/$fxv/javafx-$fxm-$fxv-$fx$fxarch.jar"
  [ -f "$j" ] || { echo "missing $j (JavaFX platform jar; run mvn -q package on this platform first)" >&2; exit 1; }
  mods="$mods:$j"
done
runtime="$out/lyngvi-gui-runtime-$os-$arch"
rm -rf "$runtime"; mkdir -p "$out"
"$java_home/bin/jlink" --module-path "$mods:$java_home/jmods" \
  --add-modules no.syntakserror.lyngvi.gui,no.syntakserror.lyngvi.cli,jdk.crypto.ec \
  --strip-debug --no-man-pages --no-header-files --compress zip-6 --output "$runtime"
# The CLI launcher inside the runtime, so a sandbox can symlink cli/ to it (spec §4.6).
cat > "$runtime/bin/lyngvi" <<'LAUNCH'
#!/bin/sh
here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec "$here/bin/java" -m no.syntakserror.lyngvi.cli/no.syntakserror.lyngvi.cli.Main "$@"
LAUNCH
chmod 755 "$runtime/bin/lyngvi"
echo "$version" > "$runtime/VERSION"
[ "$type" = app-image ] && rm -rf "$out/lyngvi.app" "$out/lyngvi"
# jpackage wants the platform's own icon format: .icns on macOS, a PNG everywhere else.
# LYNGVI_ICON overrides it, which is how build-dmg.sh passes an icon it generated.
case "$os" in macos) icon="$root/packaging/icons/lyngvi.icns" ;; *) icon="$root/packaging/icons/lyngvi.png" ;; esac
icon=${LYNGVI_ICON:-$icon}
# Files jpackage takes over its own defaults with, found by name: for the dmg, the Finder window of the
# mounted image — our background and the setup script it runs with osascript — so the user is shown the
# application and an alias to /Applications to drag it onto. The directory stays empty for the other types.
res="$out/jpackage-resources"
rm -rf "$res"; mkdir -p "$res"
if [ "$type" = dmg ]; then
  cp "$root/packaging/macos/dmg/lyngvi-dmg-setup.scpt" "$res/lyngvi-dmg-setup.scpt"
  "$root/packaging/macos/dmg/background-tiff.sh" "$res/lyngvi-background.tiff" \
    || echo "the image keeps jpackage's own background" >&2
fi
"$java_home/bin/jpackage" --type "$type" --name lyngvi --app-version "$(echo "$version" | sed 's/-.*//')" \
  --runtime-image "$runtime" --module no.syntakserror.lyngvi.gui/no.syntakserror.lyngvi.gui.Launcher \
  --dest "$out" --vendor "syntakserror" --description "A hardened sandbox for autonomous coding agents" \
  --icon "$icon" --resource-dir "$res" --java-options "-Dlyngvi.packaged=true"
rm -rf "$res"  # it is built from the repository every run; nothing downstream should pick it up as an artifact

# The window of the finished image. jpackage asks Finder for it too, on its own staging image, but it throws
# the answer away — so it is done here instead, where a Finder that refuses is reported rather than silently
# leaving a plain image. Exit 3 is that refusal, and nothing to stop a build over.
# LYNGVI_DMG_LAYOUT=no leaves the image exactly as jpackage wrote it.
if [ "$type" = dmg ] && [ "${LYNGVI_DMG_LAYOUT:-yes}" != no ]; then
  "$root/packaging/macos/dmg/layout-dmg.sh" "$out/lyngvi-$(echo "$version" | sed 's/-.*//').dmg" || [ $? = 3 ]
fi
echo "built $type in $out (runtime $runtime)"
