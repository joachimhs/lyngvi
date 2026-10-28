# Instructions for the agent

You are running inside **Lyngvi**, a hardened container. Read this file first.

## Where you are

- Your working directories are under `/work/<repo>/`. Each is a git repository
  with its own history; use `git status` and `git diff` there to see your work.
- Files you want the human to keep outside the repositories go in `/work/output/`.
- Attachments the human sent with a message are under `/work/_attachments/`.
- There are no other sessions in this container. Everything under `/work` is yours.
- A repository can be attached to the conversation after it started, so a new
  directory may appear under `/work` while you work. The human says in a message
  what it is for; nothing else about the session changes.

## What you cannot do

- Reach the internet directly. All HTTP(S) traffic goes through a proxy with an
  allowlist. A blocked host returns a proxy error; tell the human which host you
  need and why, and continue with what you can do.
- Reach the host machine or its `localhost` services.
- Use `sudo` or gain privileges; all capabilities are dropped.
- Reach internal systems except through the gateway (see below).

## Internal systems (gateway)

No internal systems are configured in this sandbox.

## How your work is delivered

The human reviews your changes as a patch, file by file, and applies the
approved files to a working branch on their machine. Therefore:

- Keep changes focused; unrelated edits make review harder.
- Do not create symlinks or make files executable unless the task requires it;
  such changes are held back for explicit approval.
- Commit in `/work/<repo>` as often as you like; the delivery is measured from
  the session's baseline, so your own commits are included.
- Before you report that you are done, run `git status` in each repository and
  make sure the changes you describe are actually there.

## Claude Code

You are Claude Code running with all permissions granted: the container is the
permission boundary, so there are no interactive approvals. `/work/CLAUDE.md`
is a link to this file. Do not try to log in or change your own configuration;
the human manages credentials outside the container.

## OpenCode

You are OpenCode. This file is read as `AGENTS.md`. Providers and credentials
are configured outside the container; do not run `opencode auth`.

You run with `--auto`, so permissions are granted without asking: the container
is the permission boundary. Paths outside `/work` — your own home directory, for
instance — are yours to write to as the task requires.
