# JC label → Jira sync

Applying the **`JC`** label to a GitHub issue in this repo opens a matching
ticket in the `OPIK` Jira project and comments the link back on the issue.

This directory holds the resolution logic for that sync. See
[OPIK-7833](https://comet-ml.atlassian.net/browse/OPIK-7833).

> **Status: step 1 of 5 — read-only.** Nothing here writes to Jira or GitHub.
> The create path, the workflow, and the n8n cutover come later.

## Files

| File | What it does |
|---|---|
| `resolve.mjs` | Pure logic: issue-type resolution, dedupe, Markdown→wiki. No I/O. |
| `resolve.test.mjs` | Unit tests. `node --test .github/scripts/jc-sync/resolve.test.mjs` |
| `dryrun.mjs` | Replays the resolver over every `JC`-labeled issue and reports what it *would* do. |

## The two decisions

### Issue type

Precedence: **GitHub labels** → **title prefix** → default to `Task`.

Labels come first because a human applied them. The previous flow keyed only on
the title prefix and silently defaulted to `Bug`, which mis-typed 5 of the first
50 synced tickets.

### Has this issue already been synced?

Three tiers, any hit meaning "do not create":

1. **Remote link** by deterministic `globalId`
   (`github-issue-<repoId>-<issueNumber>`) — exact and indexed. The fast path
   for everything created from now on.
2. **JQL** over the GitHub URL in the ticket description — catches the ~50
   tickets created before remote links existed. Text search, so it can lag right
   after a create; never the primary key.
3. **The `Jira Ticket Created:` comment** on the GitHub issue — catches tickets
   whose description was edited, and lets an interrupted run recover on retry.

Tier 3 exists because runs really do get interrupted: GitHub issue #7000 was
labeled three times in eight minutes and produced two tickets (OPIK-6834 and
OPIK-6835), only one of which was ever commented back.

## Running the dry run

```bash
GITHUB_TOKEN="$(gh auth token)" node .github/scripts/jc-sync/dryrun.mjs
```

Optional flags: `--limit=N` to sample, `--json=out.json` for the full per-issue
record.

Jira credentials are optional. Without them tiers 1–2 are skipped and the replay
exercises tier 3 only — still useful, and it needs no Jira access at all:

```bash
export JIRA_BASE_URL=https://comet-ml.atlassian.net
export JIRA_USER_EMAIL=you@comet.com
export JIRA_API_TOKEN=...
```

### Reading the output

- `match OPIK-1234 (via)` — an existing ticket was found; the live sync would
  skip creation.
- `WOULD-CREATE` — no ticket found.
- `[DUPES ...]` — more than one ticket points at this issue. Pre-existing data
  problems, surfaced rather than hidden.
- `[type:unconfident]` — no label and no title prefix; type fell back to `Task`.

## Latest replay

79 labeled issues, tier 3 only (no Jira credentials):

```
matched existing  65
would create      14
duplicate pairs    1     (#4440 → OPIK-3453/3454/3455/3456)
unconfident type   4
```

The 14 unmatched are **not** resolver failures. Verified by hand:

- **4 are test issues** — "[Bug]: This is a test", "[FR]: test", "Test", "Test2".
- **2 have tickets created manually**, with a reworded summary and no
  comment-back (#6619 → OPIK-7647, #6344 → OPIK-6058). Tier 2 catches these once
  Jira credentials are supplied; tier 3 cannot, because nothing was ever posted
  to the issue.
- **The rest were never synced** — the label was applied and nothing happened.
  That silent-failure mode is the core thing this rewrite fixes.

Re-run with Jira credentials before the cutover to confirm tier 2 closes the
gap on the second group.
