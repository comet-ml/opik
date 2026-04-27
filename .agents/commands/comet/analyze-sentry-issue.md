# Analyze Sentry Issue

**Command**: `cursor analyze-sentry-issue`

## Overview

Drive an end-to-end triage of a Sentry issue: pull all events directly from Sentry's REST API, aggregate, locate the emitting code, diagnose root cause and observability gaps, and propose concrete fixes (with code locations) the engineer can apply or ticket. This is a **guided runbook**, not just a data dump — at each step the workflow gives the engineer something to decide on, not just numbers.

This bypasses the natural-language Sentry MCP tools (`search_issues`, `search_events`, `search_issue_events`, `analyze_issue_with_seer`), which route through Sentry's own OpenAI account and are frequently rate-limited. The direct REST path uses the same `SENTRY_ACCESS_TOKEN` from `.env.local` that the Sentry MCP uses.

## Inputs

- **Issue (required)**: a Sentry issue URL (e.g. `https://comet-or.sentry.io/issues/7367090448/?...`) **or** the bare numeric issue ID (e.g. `7367090448`).
- **Region host (optional)**: Sentry region hostname. Default `us.sentry.io`. For self-hosted Sentry, pass the host (no scheme).

## Workflow

### Phase 1 — Preflight

1. Confirm `SENTRY_ACCESS_TOKEN` is present in `.env.local`. **Never echo the token.** If missing, point the engineer at [.agents/docs/SENTRY_MCP_SETUP.md](../../docs/SENTRY_MCP_SETUP.md) and stop.
2. If a URL was supplied, extract:
   - numeric issue ID,
   - organization slug (host prefix before `.sentry.io`),
   - region host.
3. **Token-handling rules** (apply for the entire workflow):
   - Read the token from `.env.local` directly, not from `.mcp.json`.
   - Never put the token in argv (e.g., `curl -H "Authorization: Bearer $TOKEN"` leaks it via `ps`). Use Python `urllib.request` with the header set in code, or a temporary header file (`curl -H @file`) deleted afterward.
   - Never log the token, never paste it into reports.

### Phase 2 — Fetch all events

Page through `GET https://<region>/api/0/issues/<issue_id>/events/?full=false&limit=100` with `Authorization: Bearer $SENTRY_ACCESS_TOKEN`. Follow the `Link: rel="next"; results="true"; cursor=...` header until exhausted (use a sane cap, e.g. 12 pages = 1,200 events).

Capture per event: `eventID`, `message`, `title`, `user.id`, `release`, `tags` (as a `{key: value}` dict).

### Phase 3 — Aggregate

Compute and present:

- **Total events fetched** vs. issue's reported `count` (mismatch hints at unindexed pagination or pruning).
- **Distinct event messages** (top 15 with counts). **This is the most important output** — Sentry groups by log message *template*, so a single "issue" often contains many distinct exception types whose differences are hidden in the issue title.
- **Pattern extraction** for common shapes:
  - `KeyError: '<X>'` → which key was missing (count by key).
  - `<ExceptionType>: ...` → exception-type distribution.
  - HTTP status codes, file paths, named entities — only when an obvious pattern stands out.
- **Top users** (top 10). Flag named accounts vs. anonymous `default_*` IDs. Per-user-per-day clustering matters: if one user fires N events in one minute, it's typically a single broken run iterating a dataset, not N independent failures.
- **Release distribution** — does the issue track a regression, or is it spread across many releases (suggesting it's not version-gated)?
- **Tag distributions** for any tag the URL filter mentions or that is obviously relevant: `cli_command`, `installation_type`, `os_type`, `python_version`, `environment`, etc.

### Phase 4 — Locate the emitting code

Sentry's title is often a log string from the SDK, not the actual exception. Find where it comes from:

1. Take the most-common message template (with placeholders normalized — replace specific values with `%s`/`{}`) and `grep` the repo for it. Search across `sdks/`, `apps/`, and any other code paths. Use a substring distinctive enough to land on one or two callsites.
2. Open the file and read 30 lines of context around the match.
3. Identify:
   - Whether it's `LOGGER.error / .warning / .exception` (logging integration) or `sentry_sdk.capture_exception(...)` (direct capture).
   - Whether the call has `exc_info=` (presence/absence is a key observability signal).
   - What contextual data is in scope at the callsite (exception object, stderr tail, exit code, dataset item, request, etc.) but **not** being attached to the event.

If multiple distinct messages share the same `LOGGER.<level>(...)` template, that's the **fingerprint-collision** pattern: Sentry buckets unrelated failure modes together because the template hash is identical.

### Phase 5 — Diagnose

Lead the engineer through these decisions, citing concrete events and code locations:

1. **Observability gap?**
   - Are tracebacks attached? If not and the SDK had the exception object in scope: missing `exc_info=`.
   - Is structured context (stderr, exit code, request payload) absent? If so: missing `extra=` / `sentry_sdk.set_context(...)`.
   - Are unrelated exception types fingerprint-colliding under one issue? If so: the log template is too generic — recommend including `type(exc).__name__` *as part of the message template* so each type fingerprints separately, **and** logging via `exc_info` so the actual exception is attached.

2. **SDK bug, user error, or infra?**
   - **SDK bug**: traceback points at SDK code; reproducible from a documented use case.
   - **User error pattern**: traceback (when present) ends in user code, error is consistent with a common API misuse (missing dataset key, wrong return shape, etc.). Spread across many users / releases / platforms is a strong signal.
   - **Infrastructure**: connection errors, timeouts, rate-limits, dependency failures (`APIConnectionError`, `httpx` errors). Concentrated in time = upstream incident; spread out = endemic.

3. **Per-customer concentration?** If one named org/server/user dominates, flag it for direct outreach — they're hitting a recoverable wall.

### Phase 6 — Propose fixes

For each finding produced in Phase 5, give the engineer a **specific, actionable** proposal with file paths and line numbers. Two layers in order of priority:

1. **Observability fix (almost always cheap, ship first):**
   - Patch the identified log calls to include `exc_info=<exc>` and/or `extra={"key": value, ...}`.
   - If template collision is the issue: change the message template so distinct types fingerprint distinctly (e.g., from `"Task failed: %s"` to `"Task failed: %s: %s"` carrying `type(exc).__name__`).
   - State the expected outcome: future Sentry events will carry the data the engineer just spent time discovering wasn't there.

2. **Behavior fix:**
   - For user-error patterns: pre-flight validation that catches the misuse on the first item with a structured, copy-pasteable error message instead of failing N times across the dataset.
   - For SDK bugs: outline the change. Do not auto-edit code without explicit approval.
   - For infrastructure: retries / circuit breakers / surfacing the upstream cause.

### Phase 7 — Verify and ship

Offer (do not auto-execute):

- **Local repro**: a one-liner or short script the engineer can run to trigger the failure and confirm the observability fix attaches the missing data. For SDK changes, an integration test path under `sdks/python/tests/`.
- **Branch and PR**: if the engineer wants to ship, propose a branch name (`<user>/OPIK-<ticket>-<slug>` per [.claude/rules/git-workflow.md](../../../.claude/rules/git-workflow.md)) and the first-commit message format `[OPIK-####] [SDK] fix: …`.
- **Jira ticket(s)**: separate observability and behavior fixes if they're meaningfully independent. Reference the Sentry issue ID in the description so it auto-links.
- **Commit-message hint**: `Fixes <SENTRY-ISSUE-SHORT-ID>` (e.g. `Fixes OPIK-PYTHON-SDK-H35`) auto-resolves the Sentry issue when the commit ships — call this out explicitly so the engineer doesn't have to remember.

### Phase 8 — Report

Final summary should be **short and decision-oriented**, not a data dump:

- **What this issue actually is** (1–2 sentences, with the dominant exception type and root cause hypothesis).
- **Why it's noisy** (1 sentence — usually fingerprint collision or per-run amplification).
- **Recommended next action** with the file:line and the change to make.
- **Open questions / unknowns** the engineer needs to resolve before shipping.
- Followed by the raw aggregations from Phase 3 for reference.

## Notes

- This command exists *because* the NL-backed Sentry MCP tools are unreliable (OpenAI quota gating). When those tools are working, the direct MCP tools (`get_sentry_resource`, `get_issue_tag_values`) are sufficient for individual lookups; this command's value is **the cross-event aggregation plus the guided diagnosis-to-fix path**.
- Self-hosted Sentry: pass the region host (no scheme).
- See [.agents/docs/SENTRY_MCP_SETUP.md](../../docs/SENTRY_MCP_SETUP.md) for token setup and scope requirements.
- **Recurring patterns observed in this codebase** (use these as priors when diagnosing):
  - `LOGGER.error/.warning(..., exception)` without `exc_info=`: very common SDK pattern; ~56% of `LOGGER.{error,warning,exception}` calls in `sdks/python/src/opik/` lack `exc_info`. Always check this.
  - Generic templates like `"Evaluation task failed (group=%s): %s: %s"` or `"Task failed for item %s: %s"` group every distinct task error into one Sentry issue. The fingerprint-collision diagnosis applies whenever the issue title says one exception type but the message-distribution shows many.
  - Process-supervisor logs (e.g. `runner/supervisor.py`) capture child crashes via the parent — they have `stderr_tail` and `exit_code` in scope but log only the message; the engineer should look for `extra=` opportunities, not `exc_info=`.
