---
name: blocked-host
description: Use the moment a network call fails in the sandbox — a hanging install, a TLS error, "could not resolve host", a 403 or 503 from a proxy, an SDK that says it is offline. Tells you whether the proxy denied it, which host to ask for, and what to do instead while you wait.
---

# A network call failed: find out whether it was the proxy

All HTTP(S) traffic out of this container goes through a proxy with an allowlist. A host that is not on the
list comes back as a proxy error, not as a connection failure — so "the network is down" is almost never the
right conclusion here, and neither is "the package is broken".

## 1. Find the host, and ask the proxy directly

```bash
env | grep -i proxy                       # HTTPS_PROXY / NO_PROXY: what every tool should be using
curl -sS -o /dev/null -w '%{http_code}\n' -v https://<host>/ 2>&1 | tail -20
```

What you see tells you which case you are in:

| Sign | What it means |
|---|---|
| `403` or `503` from the proxy, body naming the host | **Denied by the allowlist.** The host has to be allowed; go to step 3. |
| `200`/`301`/… | The host is allowed. The failure is in the tool or the URL — read the real error again. |
| `Could not resolve proxy` / no proxy variables | The tool is bypassing the proxy. Make it use `HTTPS_PROXY`, or give it the setting it wants. |
| TLS error naming an internal CA | Traffic to an internal system; it goes through the gateway, not through the proxy (step 2). |

A tool with its own idea of the network needs telling: `npm config set proxy`, `pip --proxy`, `JAVA_TOOL_OPTIONS`
(already set for the JVM), `git config --global http.proxy`. Check that first before concluding anything.

## 2. Internal systems go through the gateway

Internal systems are never reached directly, by host name or by IP. They are reached through the gateway, at
the URLs in the environment: `GATEWAY_URL` and one `*_URL` variable per configured system.

```bash
env | grep -E 'GATEWAY|_URL='
curl -sS "$GATEWAY_URL/health" || true
```

If the system you need has no variable, it is not configured in this sandbox — that is something the human
adds, and no amount of retrying from here will change it.

## 3. Report it so the human can act in one step

Do not retry in a loop, and do not vendor a copy of the dependency to get around the block. Say, in one place
in your answer:

- **the exact host** (`registry.example.com`, not "the registry"),
- **what needed it** (which command, which dependency, which step),
- **what you did instead** — and keep working on everything that does not depend on it.

The human allows a host from the sandbox's egress page in a few seconds, and it takes effect without
restarting your conversation. A precise one-line request gets you unblocked; a wall of retry output does not.

## 4. Keep going

A blocked host rarely blocks the whole task. Finish the parts that do not need the network, state plainly what
is left waiting on the host, and do not report the task as done while that part is untouched.
