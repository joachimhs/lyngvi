#!/bin/sh
# Mock agent adapter (external form of the adapter contract, spec §6.2).
# Emits a deterministic event stream and writes MOCK.md into the first repository.
# The working copies live in the session's volume: inside the container the file is written with
# podman exec, outside it by mounting AS_VOLUME in a throwaway container. Only an adapter harness
# with no sandbox at all (AS_VOLUME unset) falls back to writing under AS_SESSION_DIR.
set -eu

action=${1:-}
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | awk 'BEGIN{ORS="\\n"} {print}' | sed 's/\\n$//'; }

case "$action" in
  ask)
    prompt=$(cat "${AS_PROMPT_FILE:-/dev/null}")
    session=${AS_AGENT_SESSION_ID:-}
    [ -n "$session" ] || session="mock-$(date +%s)"
    printf '{"type":"start","agent":"mock-sh","agentSessionId":"%s","model":"%s"}\n' "$session" "${AS_MODEL:-mock-1}"
    case "$prompt" in
      *fail*)
        printf '{"type":"error","code":"MOCK_FAILURE","message":"the prompt asked for a failure"}\n'
        printf '{"type":"done","stopReason":"error"}\n'
        exit 0 ;;
      *hang*)
        sleep 30 ;;
    esac
    printf '{"type":"text","delta":"I will look at the repository."}\n'
    first_repo=$(printf '%s' "${AS_REPOS:-}" | cut -d, -f1)
    if [ -n "$first_repo" ]; then
      printf '{"type":"tool_start","id":"t1","name":"write_file","input":"/work/%s/MOCK.md"}\n' "$first_repo"
      written=1
      if [ "${AS_LOCAL:-0}" != "1" ]; then
        podman exec "$AS_CONTAINER" sh -c "printf 'Written by the mock-sh adapter.\n' > /work/$first_repo/MOCK.md" || written=0
      elif [ -n "${AS_VOLUME:-}" ]; then
        podman run --rm -v "$AS_VOLUME":/work -e WORK=/work -e REPO="$first_repo" \
          --entrypoint sh "${AS_IMAGE:-localhost/lyngvi-base}" \
          -c 'printf "Written by the mock-sh adapter.\n" > "$WORK/$REPO/MOCK.md"' || written=0
      else
        printf 'Written by the mock-sh adapter.\n' > "$AS_SESSION_DIR/work/$first_repo/MOCK.md" || written=0
      fi
      if [ "$written" = "1" ]; then
        printf '{"type":"tool_end","id":"t1","output":"wrote MOCK.md","isError":false}\n'
      else
        printf '{"type":"tool_end","id":"t1","output":"could not write MOCK.md","isError":true}\n'
      fi
      printf '{"type":"text","delta":" Done: wrote MOCK.md in %s."}\n' "$first_repo"
    fi
    printf '{"type":"usage","input":10,"output":20,"cacheRead":0,"cacheWrite":0,"costUsd":null,"contextUsed":30,"contextMax":200000}\n'
    printf '{"type":"done","stopReason":"end_turn"}\n'
    ;;
  suggest)
    printf '{"branch":"agent/mock-change","message":"Mock: apply the requested change"}\n' ;;
  models)
    printf '[{"id":"mock-1","label":"Mock model 1","default":true},{"id":"mock-2","label":"Mock model 2","default":false}]\n' ;;
  usage)
    # A fixed sample account, so the usage view has something to show without a real agent.
    printf '{"supported":true,"plan":"Mock Pro","resetAt":"2026-01-01T00:00:00Z","limits":['
    printf '{"id":"session","label":"Session quota","used":18,"limit":40,"unit":"requests","resetAt":"2026-01-01T00:00:00Z"},'
    printf '{"id":"weekly","label":"Weekly quota","used":380,"limit":400,"unit":"requests","resetAt":"2026-01-05T00:00:00Z"}'
    printf ']}\n' ;;
  auth-status)
    printf '{"loggedIn":true,"mode":"none","account":"mock","expiresAt":null}\n' ;;
  login|logout|prepare)
    printf '{"ok":true}\n' ;;
  version)
    printf '{"adapter":"1.0.0","agent":"mock"}\n' ;;
  *)
    echo "unknown action: $action" >&2; exit 2 ;;
esac
