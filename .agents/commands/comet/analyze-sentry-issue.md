# Analyze Sentry Issue

**Command**: `cursor analyze-sentry-issue`

## Overview

Drive an end-to-end triage of a Sentry issue: pull all events directly from Sentry's REST API, aggregate, locate the emitting code, diagnose root cause and observability gaps, and propose concrete fixes (with code locations) the engineer can apply or ticket. This is a **guided runbook**, not just a data dump — at each step the workflow gives the engineer something to decide on, not just numbers.

This is the canonical entry point for Sentry analysis in this repo. It uses the `SENTRY_ACCESS_TOKEN` from `.env.local` and Sentry's REST API directly.

## Inputs

- **Issue (required)**: a Sentry issue URL (e.g. `https://<org>.sentry.io/issues/<id>/?...`) **or** the bare numeric issue ID.

## Workflow

### Phase 1 — Preflight

1. Confirm `SENTRY_ACCESS_TOKEN` is present in `.env.local`. **Never echo the token.** If missing, point the engineer at [.agents/docs/SENTRY_MCP_SETUP.md](../../docs/SENTRY_MCP_SETUP.md) and stop.
2. If a URL was supplied, extract:
   - numeric issue ID,
   - organization slug (host prefix before `.sentry.io`).
3. **Token-handling rules** (apply for the entire workflow):
   - Read the token from `.env.local` directly, not from `.mcp.json`.
   - Never put the token in argv (e.g., `curl -H "Authorization: Bearer $TOKEN"` leaks it via `ps`). Set the header in code via the language's HTTP client (e.g. Python `urllib.request`, Node `fetch`), or use a temporary header file (`curl -H @file`, mode 600) deleted afterward.
   - Never log the token, never paste it into reports.

### Phase 2 — Fetch all events

Page through `GET https://us.sentry.io/api/0/issues/<issue_id>/events/?full=false&limit=100` with `Authorization: Bearer $SENTRY_ACCESS_TOKEN`, following the `Link: rel="next"; results="true"; cursor=...` header.

> ⚠️ The host is hardcoded to the US region (`us.sentry.io`) because that's where Comet's Sentry org lives. If you're on the EU region (`de.sentry.io`) or a self-hosted Sentry, swap in `$SENTRY_HOST` from `.env.local` — otherwise the call will silently hit the wrong API and either fail auth or return empty results.

**Default cap: ~3 pages (300 events).** Distinct-message and tag distributions converge fast; pulling thousands of events per analysis is rarely necessary and slows the workflow. Bump the cap (and tell the engineer you're doing so) only when:

- the early sample looks unrepresentative — e.g., one message dominates and the tail is unclear, or top users haven't stabilized;
- the issue's reported `count` is large *and* the question being asked actually depends on the long tail (e.g., "which rare exception types are hiding in here?").

Capture per event: `eventID`, `message`, `title`, `user.id` (nullable — treat missing as `<no-user>`), `release` (nullable — treat missing as `<no-release>`), and `tags`. Note: Sentry returns `tags` as an array of `{key, value}` objects; normalize it into a `{key: value}` dict before any tag aggregation in Phase 3.

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

Sentry's title is often a log string emitted by the application, not the actual exception. Find where it comes from.

The Sentry project the issue belongs to (returned by the API as `project.slug`, or visible in the issue's "Project" field) tells you which codebase area emitted the event. Map it to the corresponding subtree by purpose: backend service → `apps/opik-backend/` (Java), frontend app → `apps/opik-frontend/` (TypeScript/React), Python SDKs → `sdks/python/` or `sdks/opik_optimizer/`, TypeScript SDK → `sdks/typescript/`. If the project name doesn't make the mapping obvious, ask the engineer.

Steps:

1. Take the most-common message template (replace specific values like IDs, paths, and exception strings with placeholders) and `grep` it inside the project's subtree. Use a substring distinctive enough to land on one or two callsites.
2. Open the file and read ~30 lines of context around the match.
3. Identify:
   - **What kind of call** emits the event:
     - Python: `logger.error / .warning / .exception(...)` (logging integration) or `sentry_sdk.capture_exception(...)`.
     - Java: `log.error("msg", e)` (SLF4J) or `Sentry.captureException(e)`.
     - TypeScript: `console.error(...)`, `logger.error(...)`, `Sentry.captureException(e)`, or `Sentry.captureMessage(...)`.
   - **Whether the exception object is attached** to the event:
     - Python: `exc_info=<exc>` argument present?
     - Java: is the exception passed as the second SLF4J argument? (`log.error("msg", e)` attaches; `log.error("msg " + e)` does not.)
     - TypeScript: is the exception passed to `captureException`, or just stringified into a message?
   - **What contextual data is in scope at the callsite** (exception object, stderr tail, exit code, request payload, dataset item, user identifier) but **not** being attached to the event.

If multiple distinct messages share the same log template at the callsite, that's the **fingerprint-collision** pattern: Sentry buckets unrelated failure modes together because the template hash is identical.

### Phase 5 — Diagnose

Lead the engineer through these decisions, citing concrete events and code locations:

1. **Observability gap?** Three sub-questions, expressed in the language of the project from Phase 4:
   - **Is the exception attached?** Tracebacks are missing when the exception object is in scope at the log site but not passed to the SDK's exception sink. Python: missing `exc_info=`. Java: passed by string concatenation instead of as the second SLF4J argument. TypeScript: stringified into a message instead of passed to `Sentry.captureException`.
   - **Is structured context attached?** Things like stderr, exit code, request payload, dataset item, or HTTP method might be in scope at the callsite but absent on the event. Python: `extra=` / `sentry_sdk.set_context(...)`. Java: `Sentry.setExtra(...)` / MDC. TypeScript: `Sentry.setContext(...)` / `Sentry.withScope(...)`.
   - **Are unrelated exception types fingerprint-colliding?** If yes, the log template is too generic — distinct exception types end up in one Sentry issue. The fix is to include the exception type/class name *as part of the message template* so each type fingerprints separately, **and** to attach the exception object so the traceback shows up.

2. **SDK bug, user error, or infra?**
   - **SDK / app bug**: traceback (when present) points at first-party code; reproducible from a documented use case.
   - **User error pattern**: traceback ends in caller code; error is consistent with a common API misuse (missing dataset key, wrong return shape, malformed request body). Spread across many users / releases / platforms is a strong signal — the failure mode is the SDK's contract being violated, not the SDK breaking.
   - **Infrastructure**: connection errors, timeouts, rate-limits, downstream dependency failures. Concentrated in a tight time window = upstream incident; spread out evenly = endemic and worth a retry/back-off fix.

3. **Per-customer concentration?** If one named org/server/user dominates, flag it for direct outreach — they're hitting a recoverable wall.

### Phase 6 — Propose fixes

For each finding produced in Phase 5, give the engineer a **specific, actionable** proposal with file paths and line numbers. Two layers in order of priority:

1. **Observability fix (almost always cheap, ship first).** Patch the identified callsites to attach the exception object and/or structured context using the idioms from the project's language (see Phase 5). If template collision is the issue, change the log template so distinct exception types fingerprint distinctly. State the expected outcome explicitly: future Sentry events will carry the data the engineer just spent time discovering wasn't there.

2. **Behavior fix.**
   - For user-error patterns (especially in SDK code paths): pre-flight validation that catches the misuse early with a structured, copy-pasteable error message — instead of letting the same broken call iterate across N items and emit N events.
   - For first-party bugs: outline the change. Do not auto-edit code without explicit approval.
   - For infrastructure: retries / circuit breakers / surfacing the upstream cause.

### Phase 7 — Verify and ship

Offer (do not auto-execute):

- **Local repro**: a one-liner or short script the engineer can run to trigger the failure and confirm the observability fix attaches the missing data. Use the project's existing test infrastructure (e.g. `sdks/python/tests/`, `sdks/typescript/`, `apps/opik-backend/src/test/java/`, `apps/opik-frontend/`).
- **Branch and PR**: if the engineer wants to ship, propose a branch name (`<user>/OPIK-<ticket>-<slug>` per [.claude/rules/git-workflow.md](../../../.claude/rules/git-workflow.md)) and the first-commit message format `[OPIK-####] [<COMPONENT>] <type>: …` where `<COMPONENT>` matches the project (e.g. `[SDK]`, `[BE]`, `[FE]`).
- **Jira ticket(s)**: separate observability and behavior fixes if they're meaningfully independent. Reference the Sentry issue ID in the description so it auto-links.
- **Commit-message hint**: `Fixes <SENTRY-ISSUE-SHORT-ID>` (the short ID is shown on the issue page, format like `<PROJECT>-XYZ`) auto-resolves the Sentry issue when the commit ships — call this out explicitly so the engineer doesn't have to remember.

### Phase 8 — Report

Final summary should be **short and decision-oriented**, not a data dump:

- **What this issue actually is** (1–2 sentences, with the dominant exception type and root cause hypothesis).
- **Why it's noisy** (1 sentence — usually fingerprint collision or per-run amplification).
- **Recommended next action** with the file:line and the change to make.
- **Open questions / unknowns** the engineer needs to resolve before shipping.
- Followed by the raw aggregations from Phase 3 for reference.

## Notes

- See [.agents/docs/SENTRY_MCP_SETUP.md](../../docs/SENTRY_MCP_SETUP.md) for token setup and scope requirements.

### Python SDK priors

Use these as starting hypotheses when the issue's emitting code is in the Python SDK subtree (`sdks/python/` or `sdks/opik_optimizer/`) — they recur often enough to be worth checking first:

- `LOGGER.error/.warning(..., exception)` without `exc_info=`: very common; roughly half of `LOGGER.{error,warning,exception}` calls in `sdks/python/src/opik/` lack `exc_info`. Always check this — fixing it usually unblocks triage on its own.
- Generic templates like `"Evaluation task failed (group=%s): %s: %s"` or `"Task failed for item %s: %s"` group every distinct task error into one Sentry issue. The fingerprint-collision diagnosis applies whenever the issue title cites one exception type but the message-distribution shows many.
- Process-supervisor logs (e.g. `runner/supervisor.py`) capture child crashes via the parent — `stderr_tail` and `exit_code` are in scope but only the message gets logged. Look for `extra=` opportunities, not `exc_info=`, on these.

For backend (Java) and frontend / TypeScript SDK projects, no project-specific priors have been collected yet — fall back to the language-agnostic checks in Phases 4 and 5.
