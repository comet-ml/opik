# Code Review section (shared sub-skill)

**Command**: not user-invocable. Renders the `## Code Review` section of a PR body from the
local pre-PR review receipt. Called by `/comet:create-pr` (Step 7) and `_pr-description-sync`
(Step 3). Keep both callers identical by editing only this file.

## What it is

`/comet-cra-review` (the Opik pre-PR reviewer) writes a **gitignored** receipt into the checkout
at `.cra/reviews/<branch-slug>.json` when a developer reviews their branch — usually *before* the
PR exists. This sub-skill turns that receipt into a compact `## Code Review` section (+ a hidden
machine marker) so reviewers see what the reviewer flagged.

The section is **informational, not a gate** — never block PR creation on it, and never fabricate
counts when no receipt exists.

## 1. Locate the receipt

```bash
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
SLUG="$(printf '%s' "$BRANCH" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' | cut -c1-50)"
RECEIPT="$(git rev-parse --show-toplevel)/.cra/reviews/${SLUG}.json"
CURRENT_HEAD="$(git rev-parse HEAD)"
[ -f "$RECEIPT" ] && cat "$RECEIPT" || echo "NO_RECEIPT"
```

The slug transform above MUST stay byte-identical to the reviewer's writer (same
`[^a-z0-9]+ → -`, lowercased, `cut -c1-50` convention) or the receipt is never found. `.cra/` is
gitignored, so the receipt never appears in the PR diff.

## 2. Determine state

- **`NO_RECEIPT`** (file absent), or not valid JSON, or missing `git.head_sha` → **none**.
- Valid receipt and `git.head_sha == CURRENT_HEAD` → **fresh**.
- Valid receipt and `git.head_sha != CURRENT_HEAD` (commits pushed after the review) → **stale**.

## 3. Render

Read counts from the receipt's `counts` object, the human line from `blurb`, findings from
`findings[]` (`severity`, `title`, `file`, `line`), and the marker payload from `marker`. Never
paste a finding's `message`/`suggestion` — only `title` + `file:line`. Cap the details list at 5.

### fresh
```markdown
## Code Review

✅ Reviewed with `/comet-cra-review` — {findings} findings — {high} high · {medium} medium · {low} low · {suppressed} suppressed · {fixed} fixed

| High | Medium | Low | Suppressed | Fixed |
|:----:|:------:|:---:|:----------:|:-----:|
| {high} | {medium} | {low} | {suppressed} | {fixed} |

{blurb}

<details><summary>Top findings ({findings})</summary>

- **[{severity}]** {title} — `{file}:{line}`
  … up to 5; if there are more, add: `- …and {N} more (see the full review report)`

</details>

<!-- cra-review sha={marker.sha} rules={marker.rules} findings={marker.findings} high={marker.high} medium={marker.medium} low={marker.low} suppressed={marker.suppressed} fixed={marker.fixed} ts={marker.ts} status=fresh -->
```
- Omit the `{blurb}` line if the receipt has none. Omit the `<details>` block when there are no
  findings (`findings=0`) — keep the ✅ line + table; a clean review is a positive signal.

### stale
```markdown
## Code Review

⚠️ Reviewed with `/comet-cra-review` at an earlier commit — {findings} findings — {high} high · {medium} medium · {low} low · {suppressed} suppressed · {fixed} fixed

> **Stale review:** last reviewed `{git.head_short}` but HEAD is `{CURRENT_HEAD short}` — commits were pushed after the review. Re-run `/comet-cra-review` and refresh the PR to update this section.

| High | Medium | Low | Suppressed | Fixed |
|:----:|:------:|:---:|:----------:|:-----:|
| {high} | {medium} | {low} | {suppressed} | {fixed} |

<!-- cra-review sha={marker.sha} rules={marker.rules} findings={marker.findings} high={marker.high} medium={marker.medium} low={marker.low} suppressed={marker.suppressed} fixed={marker.fixed} ts={marker.ts} status=stale head={CURRENT_HEAD} -->
```
- `sha=` stays the **reviewed** SHA (`marker.sha`); add `head=<CURRENT_HEAD>` (full). Drop the
  blurb (may be inaccurate now). The `<details>` block is optional here.
- In `/comet:create-pr` only: after rendering stale, surface a **non-blocking** nudge — "you
  committed after reviewing; re-run `/comet-cra-review` to refresh, or continue." Never block.

### none
Leave the template's `## Code Review` placeholder exactly as shipped in
`.github/pull_request_template.md` — no marker, no table, no invented counts.

## Rules

- Exactly **one** `<!-- cra-review … -->` marker, always the **last line** of the section. On
  regeneration, replace it in place — never append a second.
- This section is **not** a pr-lint-required heading — never add it to the required-sections check.
- Secrecy (same as `/comet:create-pr` Step 7): `title`/`file`/`line` are identifier-level and
  safe; treat `blurb` like the Details section (redact customer names, internal URLs, secrets).

---

**End sub-skill**
