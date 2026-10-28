#!/bin/sh
# Acceptance suite (spec §10, 1.1.2 Del F): runs the automatable criteria against a sandbox with a running stack
# and built images. Usage: acceptance/run.sh <sandbox-dir> [<host git repo registered in the sandbox>]
# Prints one PASS/FAIL line per criterion and exits non-zero when any fails. Needs Podman; uses the mock agent.
set -u
sb=${1:?sandbox dir}; repo=${2:-}
cd "$sb" || exit 2
S=./setup.sh; fails=0
pass() { echo "PASS $1  $2"; }
fail() { echo "FAIL $1  $2"; fails=$((fails+1)); }
check() { if eval "$3"; then pass "$1" "$2"; else fail "$1" "$2"; fi; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
prefix=$($S --json status | json "d['subnetPrefix']")
proj=$(basename "$sb" | tr 'A-Z' 'a-z' | sed 's/[^a-z0-9_-]/-/g')
PROXY="http://$prefix.5:3128"
curlbox() { podman run --rm --network "${proj}_internal" --cap-drop ALL --security-opt no-new-privileges --entrypoint sh "localhost/lyngvi-base:$(cat VERSION)" -c "$1" 2>/dev/null; }
echo "== egress (S1-S4)"
check S1 "no direct internet from the internal network" '[ "$(curlbox "curl -s -m 5 -o /dev/null -w %{http_code} https://registry.npmjs.org/")" = "000" ]'
check S2 "only allowlisted domains pass the proxy" '[ "$(curlbox "curl -s -m 10 -o /dev/null -w %{http_code} -x $PROXY https://example.invalid/")" = "000" ] && $S --json domain blocked | grep -q example.invalid'
check S4 "domain changes are live" '$S --json domain add acceptance.example.net >/dev/null && $S --json domain list | grep -q acceptance.example.net && $S --json domain remove acceptance.example.net >/dev/null'
check S3 "blocklist wins over allowlist" 'grep -q "http_access deny blocked_domains" proxy/squid.conf && grep -qn "deny blocked_domains" proxy/squid.conf && [ "$(grep -n "deny blocked_domains" proxy/squid.conf | cut -d: -f1)" -lt "$(grep -n "allow CONNECT allowed_domains" proxy/squid.conf | cut -d: -f1)" ]'
echo "== sessions (S5', S11, S17, S22, S23, G1, G3)"
id=$($S --json session new --title "acceptance A" --agent mock --history none | json "d['id']"); idb=$($S --json session new --title "acceptance B" --agent mock --history none | json "d['id']")
c=$($S --json session show "$id" | json "d['container']"); cb=$($S --json session show "$idb" | json "d['container']")
$S --json session start "$id" >/dev/null; $S --json session start "$idb" >/dev/null
# The working copies live in the session's volume, so the repository's name is read from inside the container.
r=$(podman exec "$c" sh -c 'cd /work && ls -d */ 2>/dev/null | tr -d / | grep -v "^output$" | grep -v "^_" | head -1')
check "S5'" "agent runs as the host UID without capabilities" '[ "$(podman exec "$c" id -u)" = "$(id -u)" ] && [ "$(podman exec "$c" sh -c "grep CapEff /proc/self/status | cut -f2")" = "0000000000000000" ] && ! podman exec "$c" sh -c "command -v sudo" >/dev/null 2>&1'
check S11 "the working copy carries none of the host .git" '[ -z "$(podman exec "$c" git -C /work/$r remote)" ] && [ -z "$(podman exec "$c" ls /work/$r/.git/hooks 2>/dev/null | grep -v sample)" ]'
check S22 "copy .git has no hooks, host config or remotes" '! podman exec "$c" git -C /work/$r config --list --local | grep -qi "credential\|remote\."'
check S23 "history none gives one baseline commit" '[ "$(podman exec "$c" git -C /work/$r log --oneline | wc -l | tr -d " ")" = "1" ]'
check S24 "nothing the agent writes is on the host" '[ ! -e ".sessions/$id/work" ] && [ ! -e ".sessions/$id/output" ] && podman volume exists "$c-work" && podman inspect "$c" --format "{{range .Mounts}}{{.Destination}} {{.RW}}{{println}}{{end}}" | grep -q "^/work true$"'
podman exec "$c" sh -c 'echo only-in-A > /work/output/marker-a.txt'
check S17 "two sessions cannot see each other" '! podman exec "$cb" test -e /work/output/marker-a.txt && [ "$(podman exec "$cb" ls /work | grep -c .)" -ge 2 ]'
check G1 "a conversation with the mock agent from the default profile" 'printf "add a file please" | $S --json session ask "$id" | grep -q "\"type\":\"done\""'
check G3 "switching agent needs only --agent" '$S --json session new --title "acceptance C" --agent mock --history none >/dev/null'
echo "== delivery (S12, S13, S14, S18, S19, S29, P2', P3')"
check S12 ".git paths never reach the host (classification of adapter patches is unit-tested)" 'podman exec "$c" sh -c "mkdir -p /work/$r/.git/hooks && echo x > /work/$r/.git/hooks/pre-commit"; ! $S --json session review "$id" | grep -q "pre-commit"'
check S13 "executables are held back" 'podman exec "$c" sh -c "printf \"#!/bin/sh\necho hi\n\" > /work/$r/run.sh; chmod +x /work/$r/run.sh"; $S --json session review "$id" | grep -q "\"bucket\":\"hold\""'
check S14 "protected branches are refused" 'out=$($S --json session apply "$id" --branch main --message x 2>&1); echo "$out" | grep -q PROTECTED_BRANCH'
check S19 "--include of a path outside the patch is refused" 'out=$($S --json session apply "$id" --branch agent/acc --message x --include ../x 2>&1); echo "$out" | grep -q "\"ok\":false" && echo "$out" | grep -q "REJECTED_FILE\|not in the patch"'
check "P3'" "review from CLI reports files and buckets" '$S --json session review "$id" | grep -q "\"files\":"'
podman exec "$c" sh -c "ln -sf /etc/passwd /work/$r/escape"
check S29 "export takes the whole session out, without symlinks and without applying anything" 'before=$($S --json session review "$id" | grep -c "\"path\":"); $S --json session export "$id" --to /tmp/lyngvi-acceptance.zip | grep -q "\"partial\":false" && unzip -l /tmp/lyngvi-acceptance.zip | grep -q "EXPORT.txt" && unzip -l /tmp/lyngvi-acceptance.zip | grep -q "$r/.git/" && ! unzip -l /tmp/lyngvi-acceptance.zip | grep -q "escape" && [ "$($S --json session review "$id" | grep -c "\"path\":")" = "$before" ]'
podman exec "$c" sh -c "rm -f /work/$r/escape"; rm -f /tmp/lyngvi-acceptance.zip
echo "== gateway (S6, S7, S8, G7)"
check S20 "no agent credentials in generated files" '! grep -Eq "ANTHROPIC_API_KEY=|CLAUDE_CODE_OAUTH_TOKEN=|_PAT=|_TOKEN=[^$]" docker-compose.yml Containerfile .env 2>/dev/null'
check S6 "session env carries no gateway secret" '! podman exec "$c" env | grep -Eq "_TOKEN=|_PAT=|API_KEY="'
$S --json gateway instance add acc --hostname acc.gateway.local --config "{\"restBase\":\"\",\"pathAllow\":[\"^/-/ping$\"]}" >/dev/null 2>&1
printf 'dummy\n' | $S --json gateway secret set acc --url https://registry.npmjs.org --token-stdin >/dev/null; $S --json gateway allow acc add /-/ping >/dev/null; $S gateway restart >/dev/null; sleep 6
check G7 "a new http instance works after gateway restart" '[ "$(curlbox "curl -s -m 20 -o /dev/null -w %{http_code} -x $PROXY http://acc.gateway.local/-/ping")" = "200" ]'
check S8 "allowlist enforced before upstream" '[ "$(curlbox "curl -s -m 20 -o /dev/null -w %{http_code} -x $PROXY http://acc.gateway.local/-/other")" = "403" ]'
check S7 "gateway is read-only" '[ "$(curlbox "curl -s -m 20 -o /dev/null -w %{http_code} -X POST -x $PROXY http://acc.gateway.local/-/ping")" = "405" ]'
printf "please hang" | $S --json session ask "$idb" >/dev/null 2>&1 & askpid=$!; sleep 3
check S18 "delivery refused while the agent works" '$S --json session apply "$idb" --branch agent/busy --message x 2>&1 | grep -q SESSION_BUSY'
kill $askpid 2>/dev/null; wait $askpid 2>/dev/null
echo "== cleanup (S26, S27, S28)"
podman volume create acceptance-foreign-volume >/dev/null 2>&1
check S26 "cleanup never touches anything outside this sandbox" '$S clean --all --yes >/dev/null 2>&1; podman volume exists acceptance-foreign-volume && podman image exists docker.io/library/alpine:3.20 2>/dev/null || podman volume exists acceptance-foreign-volume'
check S27 "a working copy with nothing delivered is kept" 'out=$($S clean --work --older-than 0 2>&1); ! echo "$out" | grep -q "$(ls .sessions | head -1)"'
check S28 "a retired container costs the session nothing" 'c2=$($S --json session show "$idb" | json "d[\"container\"]"); podman rm -f "$c2" >/dev/null 2>&1; [ -n "$($S --json session show "$idb" | json "d[\"id\"]")" ] && podman volume exists "$c2-work"'
podman volume rm -f acceptance-foreign-volume >/dev/null 2>&1
echo "== doctor"
check S10 "doctor refuses repo mounts in compose" '$S --json doctor | grep -q "\"id\":\"compose-mounts\",\"level\":\"ok\""'
check D1 "doctor --deep egress self-test" '$S --json doctor --deep | grep -q "\"id\":\"egress-direct\",\"level\":\"ok\""'
$S --json gateway instance remove acc >/dev/null 2>&1; $S --json session remove "$id" >/dev/null; $S --json session remove "$idb" >/dev/null
echo "== $fails failure(s)"; [ "$fails" -eq 0 ]
