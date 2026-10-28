---
name: deliver-change
description: Use before reporting that work is done in a Lyngvi sandbox, and whenever the human asks you to prepare, wrap up or deliver a change. Checks what is actually in the working copies, keeps the patch reviewable file by file, and writes the handover the human reads.
---

# Deliver a change from the sandbox

The human does not pull your branch. They review what you did as a patch, **file by file**, and apply the
files they approve to a working branch on their own machine. Everything below follows from that.

## Before you say you are done

Run these in each repository under `/work/<repo>/`, and read the output rather than assuming it:

```bash
git -C /work/<repo> status --short
git -C /work/<repo> diff --stat
git -C /work/<repo> log --oneline @{u}.. 2>/dev/null || git -C /work/<repo> log --oneline -5
```

Check, in this order:

1. **Every change you describe is there.** A file you meant to write and did not is the most common way a
   report goes wrong. If `status` is empty, you have not changed anything — say so instead of summarising
   intentions.
2. **Nothing else is there.** Formatter churn, a stray debug line, an editor backup, a lockfile you did not
   mean to regenerate: each one costs the human a decision. Revert it (`git checkout -- <file>`) or explain
   why it has to be in.
3. **No new symlinks, and no new executable bits** unless the task needs them. Those files are held back for
   explicit approval, so they slow the whole patch down. `git diff --summary` names them.
4. **Nothing secret.** No tokens, no `.env` values, no customer data in fixtures or test output.
5. **Files that are not source go in `/work/output/`**, not into a repository: reports, screenshots, scratch
   data, anything generated for the human to look at once.

## Commit or leave uncommitted

Both are delivered — the patch is measured from the session's baseline, so your own commits are included.
Commit when the change has a natural order to read it in (a refactor, then the behaviour on top of it), and
keep the messages short and factual. Do not rewrite history you did not create, and never force anything.

# Leave out from changes

- The .claude directory should not be part of any delivery

## What to write in the final message

- One line per file group: what changed and why, in the order the human should read them.
- How you verified it: the command you ran and what it printed. "Tests pass" without the command is not a
  verification, and if something fails, say so with the output.
- What you did **not** do: scope you left out, a test you could not run, a decision you made under an
  assumption. Say which assumption.
- Anything that needs a hand outside the sandbox: a blocked host, a missing credential, a service only the
  human can restart.

Keep it to what changes the human's next action. No restating of the task, no apologising.
