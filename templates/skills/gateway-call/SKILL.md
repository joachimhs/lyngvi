---
name: gateway-call
description: Use when the task needs something from an internal system — a wiki page, a ticket, an internal REST API — or when a call to one fails with 403, a TLS error or an unknown host. Shows how to find which systems this sandbox has, how to call them, and what a refusal means.
---

# Reach an internal system through the gateway

Internal systems are never reached directly from here: not by their real hostname, not by IP, not with a
credential of your own. They are reached through the gateway, a sidecar that holds the credentials and serves a
read-only, allowlisted slice of each system. You get a hostname and an API; you never get the token.

## 1. Find out which systems exist

They are in the environment, one variable per configured system, and nowhere else:

```bash
env | grep -E 'GATEWAY|_URL='
curl -sS "$GATEWAY_URL/health" | head -20
```

`AGENTS.md` names them too, with what each one is for. If the system you need has no variable, **it is not
configured in this sandbox** — that is something the human adds, and no amount of retrying changes it. Say which
system you need and why, and carry on with the rest of the task.

## 2. Call it

Use the variable, not a hostname you remember:

```bash
curl -sS "$WIKI_GATEWAY_URL/page/<id>" | head -40
```

- Reads are plain HTTP against the gateway. No tokens, no `Authorization` header: the gateway adds them.
- Write methods are refused unless that instance was given write access on purpose. Do not try to work around a
  refusal with a different verb or path.
- Paths are the plugin's own: `gateway status` on the host lists each instance's routes. When a path is not
  accepted, read the error body — it says what the instance serves.

## 3. What a refusal means

| Answer | What it is | What to do |
|---|---|---|
| `403` with the item named | The item is not on that instance's allowlist | Ask the human to allow exactly that item (space, page id, project key) — one line, naming it |
| `404` | The path or id does not exist on the upstream | Check the id; do not retry variations blindly |
| `502`/`503` | The upstream or the sidecar is down | Say so; it is the human's to restart |
| TLS error on a real hostname | You bypassed the gateway | Use the `*_URL` variable instead |
| Connection refused / unknown host | The system is not configured here | See step 1 |

A 403 is the design working, not a bug to engineer around: the allowlist is how the human keeps the agent to the
part of the system the task needs.

## 4. Keep the credentials out of your work

Never copy a token into a file, a test fixture, a commit or your answer, and never add a credential of your own
to reach a system directly. If a task seems to need one, that is the point at which to stop and say so.
