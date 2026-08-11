# JC label → Jira sync

Applying the **`JC`** label to a GitHub issue in this repo opens a matching
ticket in the `OPIK` Jira project and comments the link back on the issue.

This directory holds the resolution logic for that sync. See
[OPIK-7833](https://comet-ml.atlassian.net/browse/OPIK-7833).

> **Status: complete and verified, not yet enabled.** The sync works end to end
> and is wired to the `JC` label. Two things are needed to turn it on, both
> outside this repo: the three config values below, and switching off the
> existing n8n workflow that reacts to the same label.
>
> **Do not merge this while n8n is still live** — both would react to the same
> label and each would create its own ticket.

## Files

| File | What it does |
|---|---|
| `resolve.mjs` | Pure logic: issue-type resolution, dedupe, Markdown→wiki. No I/O. |
| `resolve.test.mjs` | Unit tests. `node --test .github/scripts/jc-sync/resolve.test.mjs` |
| `sync.mjs` | The write path: ensure ticket, link, comment. Driven by the workflow. |
| `dryrun.mjs` | Replays the resolver over every `JC`-labeled issue and reports what it *would* do. |

The workflow is [`.github/workflows/jc_label_to_jira.yml`](../../workflows/jc_label_to_jira.yml).

## Configuration

| Name | Kind | Purpose |
|---|---|---|
| `JC_JIRA_BASE_URL` | variable | e.g. `https://comet-ml.atlassian.net` |
| `JC_JIRA_USER_EMAIL` | secret | Jira account the tickets are created as |
| `JC_JIRA_API_TOKEN` | secret | Atlassian API token for that account |

Use a service account, not a personal token: the reporter on every synced ticket
is whoever owns the credential, and a personal token breaks when it is rotated
or the person leaves.

## Idempotence

Safe to run repeatedly — a re-label, a retry, or a resumed run converges rather
than duplicating:

- dedupe runs before any create;
- the remote link is keyed on a deterministic `globalId`, and re-POSTing it
  returns the same link;
- the comment is skipped when that ticket is already announced;
- the failure notice is updated in place, and deleted once a run succeeds.

Order is **create → link → comment**. A process that dies midway leaves a ticket
the next run finds via tier 2 or 3 and backfills.

## When it fails

The old flow failed silently, which is why maintainers learned to toggle the
label as a retry — and how issue #7000 ended up with two tickets. Instead this:

- fails the job (visible in the Actions tab);
- posts a notice on the issue with the reason and a link to the run;
- says explicitly to re-run the workflow rather than re-label.

Re-run **JC Label to Jira** from the Actions tab with the issue number.

## Cutover checklist

1. Create the Jira service account and add the three config values above.
2. Disable the n8n workflow that currently reacts to the `JC` label (n8n lives
   at `n8n.dev.comet.com`; the deployment is in `comet-gitops`). Nothing in this
   repo can turn it off.
3. Merge this.
4. Apply `JC` to one issue and confirm a single ticket, a single remote link, and
   one comment.
5. Optionally backfill: run the workflow manually against previously-labeled
   issues to attach remote links to the ~50 legacy tickets, upgrading them to the
   tier 1 fast path. Idempotent, so it can be re-run freely.

### Verified before handover

- Live create, then two re-runs: one ticket, one remote link, one comment. The
  second and third runs matched via tier 1.
- Two consecutive failures left exactly one notice; the recovery run cleared it.
- Replay across all 79 labeled issues: 65 matched, 14 correctly unmatched
  (4 test issues, 4 with only a hand-written citing ticket, 6 never synced).
- `actionlint` and `zizmor` (offline, high severity) clean; 28 unit tests pass.

### Not yet exercised

The workflow has never run on GitHub's runners — it was driven directly. The
`if:` gate, concurrency group and dry-run wiring were checked against
representative event payloads, but the first real label event is still the first
true test of the YAML itself.

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

- `match OPIK-1234 (via)` — an existing synced ticket was found; the live sync
  would skip creation.
- `WOULD-CREATE` — no synced ticket found.
- `[DUPES ...]` — several **sync-created** tickets point at this issue. A real
  double-create; pre-existing, surfaced rather than hidden.
- `[related ...]` — a ticket cites this issue but was not created by the sync.
  Not a match: the sync would still create. Worth a human look.
- `[type:unconfident]` — no label and no title prefix; type fell back to `Task`.

## Latest replay

79 labeled issues, all three tiers live:

```
matched existing  65      (tier 2: 64, tier 3: 1)
would create      14
  ...referenced by a non-synced ticket: 4
duplicate pairs    6
unconfident type   4
```

### Duplicates: 6, not 33

The GitHub URL appears in more than one ticket whenever an engineer cites the
issue from a follow-up. Matching on the URL alone reported 33 "duplicates"; only
6 were real. Tier 2 therefore filters on the `github-sync` label — only tickets
this automation created count — and reports a `related` list for citing tickets
so they surface for triage without being mistaken for a double-create.

The 6 genuine ones (two sync-created tickets for one issue) are pre-existing data
problems, not resolver output:

```
#4639 OPIK-3825/3748   #4504 OPIK-3722/3721   #4440 OPIK-3456/3455
#4439 OPIK-3451/3450   #4379 OPIK-3400/3399   #3680 OPIK-3722/3721
```

### The 14 unmatched are all correct

Verified individually:

- **4 test issues** — "[Bug]: This is a test", "Test", "Test2", "[FR]: test".
  These were synced to the unrelated `YT` project, which the resolver correctly
  ignores.
- **4 have only a citing ticket** (`related=`), never a synced one — #6619,
  #6344, #4633, #4483. A human wrote those tickets by hand.
- **6 were never synced at all** — the label went on and nothing happened. That
  silent-failure mode is the core thing this rewrite fixes.

No issue that was genuinely synced is reported as WOULD-CREATE.
