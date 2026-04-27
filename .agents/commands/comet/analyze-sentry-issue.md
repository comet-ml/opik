# Analyze Sentry Issue

**Command**: `cursor analyze-sentry-issue`

## Overview

Given a Sentry issue (URL or numeric ID), pull every event in the issue directly from Sentry's REST API, group by exception message and tags, and report what the issue actually contains — including failure modes that Sentry's UI hides behind the issue title (Sentry groups by log message *template*, so a single "issue" often contains many distinct exception types).

This bypasses the natural-language Sentry MCP search tools (`search_issues`, `search_events`, `search_issue_events`, `analyze_issue_with_seer`), which route through Sentry's OpenAI account and are frequently rate-limited. The direct REST path uses the same `SENTRY_ACCESS_TOKEN` from `.env.local` that the Sentry MCP uses.

## Inputs

- **Issue (required)**: a Sentry issue URL (e.g. `https://comet-or.sentry.io/issues/7367090448/?...`) **or** the bare numeric issue ID (e.g. `7367090448`).
- **Region host (optional)**: the Sentry region hostname (default `us.sentry.io`).

## Steps

### 1. Preflight

- Verify `SENTRY_ACCESS_TOKEN` is present in `.env.local` (do not echo the value).
- If missing, point the user at `.agents/docs/SENTRY_MCP_SETUP.md` for token creation and stop.
- Extract the numeric issue ID from the URL if a URL was provided.

### 2. Fetch all events

Page through `GET https://<region>/api/0/issues/<issue_id>/events/?full=false&limit=100` with the `Authorization: Bearer $SENTRY_ACCESS_TOKEN` header until the `Link` header reports no more pages (or a sane page cap is hit). Pagination cursor format follows Sentry's `cursor=...` convention in the `Link: rel="next"` header.

**Token handling rules:**
- Read the token from `.env.local` directly, not from `.mcp.json`.
- Never write the token to argv (e.g., `curl -H "Authorization: Bearer $TOKEN"` puts it in `ps`). Use Python `urllib.request` with the header in code, or a temporary header file (`curl -H @hdr_file`) that's deleted afterward.
- Never log the token.

### 3. Aggregate

For each event, extract `message`, `user.id`, `release`, and any tags of interest (`cli_command`, `installation_type`, `os_type`, `python_version`, `environment`, etc.). Compute:

- Distribution of distinct event messages (top 15) — this exposes the per-message diversity Sentry's UI hides.
- For each message, regex-match common patterns to extract structured info (e.g. `KeyError: '<X>'` → which key was missing).
- Top users (by event count).
- Top releases.
- Tag distributions for any tags the user calls out or that are obviously relevant.

### 4. Report

Present a compact summary:
- Total events fetched.
- Top distinct messages and their counts.
- For pattern-matched groups (e.g., KeyErrors): the structured breakdown (which keys / which exception types).
- Top affected users — flag named accounts vs. anonymous `default_*` IDs.
- Whether the events carry traceback / stderr / exit_code data, or only the log message (i.e., is there an SDK-side observability gap?).

If the analysis surfaces a likely SDK-side fix (missing `exc_info=`, identical message template grouping unrelated failure modes, missing context attached to events), flag it as a follow-up — but do not implement without explicit ask.

### 5. Suggest next steps

Offer (do not auto-execute):
- Filing a Jira ticket for SDK fixes.
- Opening a PR for trivial changes (e.g. adding `exc_info=` to specific log calls).
- Running the analysis again with different filters or against a different issue.

## Notes

- This command exists *because* the NL-backed Sentry MCP tools are unreliable (OpenAI quota gating). When those tools are available and quick, the direct MCP tools (`get_sentry_resource`, `get_issue_tag_values`) are sufficient for individual lookups — this command's value is the cross-event aggregation.
- Self-hosted Sentry: substitute the region host appropriately (e.g. `sentry.example.com`).
- See `.agents/docs/SENTRY_MCP_SETUP.md` for token setup and scope requirements.
