#!/bin/sh
# Lyngvi launcher. No logic lives here: it finds a runtime and starts lyngvi-cli.
# Lookup order: $LYNGVI_CLI, ./cli/bin/lyngvi, an installed GUI app, java 25+ on PATH.
set -eu

dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export LYNGVI_HOME="${LYNGVI_HOME:-$dir}"

[ "${1:-}" = "--download" ] && exec sh "$dir/scripts/fetch-cli.sh" "$dir"

if [ -n "${LYNGVI_CLI:-}" ] && [ -x "$LYNGVI_CLI" ]; then
  exec "$LYNGVI_CLI" "$@"
fi
[ -x "$dir/cli/bin/lyngvi" ] && exec "$dir/cli/bin/lyngvi" "$@"
for app in /Applications/lyngvi.app/Contents/runtime/bin/lyngvi /opt/lyngvi/lib/runtime/bin/lyngvi; do
  [ -x "$app" ] && exec "$app" "$@"
done

# Fallback for developers: a Java 25+ runtime on PATH (or JAVA_HOME) plus the CLI jar.
jar=""
for candidate in "$dir/cli/lyngvi-cli.jar" "$dir"/lyngvi-cli/target/lyngvi-cli-*.jar; do
  if [ -f "$candidate" ]; then jar=$candidate; break; fi
done
java_bin=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_bin=$JAVA_HOME/bin/java
elif command -v java >/dev/null 2>&1; then
  java_bin=$(command -v java)
fi
if [ -n "$jar" ] && [ -n "$java_bin" ]; then
  major=$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
  if [ "${major:-0}" -ge 25 ]; then
    exec "$java_bin" -jar "$jar" "$@"
  fi
fi

[ -f "$dir/scripts/fetch-cli.sh" ] && sh "$dir/scripts/fetch-cli.sh" --print "$dir" >&2 || echo "lyngvi: no runtime found (see README)" >&2
exit 3
