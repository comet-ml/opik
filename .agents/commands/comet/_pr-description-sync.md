# PR Description Sync (shared sub-skill)

**Command**: not user-invocable. Called by `/comet:create-pr`, `/comet:work-on-jira-ticket`, and `/comet:address-github-pr-comments` after a `git push` succeeds against a branch with an open PR.

## Overview

Keep the GitHub PR description in sync with what was actually pushed, so reviewers don't read stale claims about endpoint paths, method names, emitted events, or other implementation details that the review cycle reshaped after the description was first written.

This sub-skill:

- Reads the canonical structure from `.github/pull_request_template.md` and the validation rules from `.github/workflows/pr-lint.yml`.
- Regenerates the description from the current `git diff origin/main...HEAD` and commit history using the same logic as `/comet:create-pr` Step 6/7.
- Preserves all media (images, GIFs, video links, Loom embeds) and any hand-edits the user added that don't conflict with regenerated content.
- Confirms the proposed update with the user via `AskUserQuestion`, with per-repo `Always` / `Never` mute options persisted to local memory.
- Is a no-op when the branch has no open PR or when the regenerated body equals the current body (idempotence).

## Inputs

- `branch` (required): branch name to look up the PR against. Caller passes `git rev-parse --abbrev-ref HEAD`.
- `pr_number` (optional): if the caller already located the PR (e.g., `/comet:address-github-pr-comments`), pass it to skip the lookup.
- `repo` (optional, defaults to `comet-ml/opik`): the GitHub repo to operate against.

## Steps

### 1. No-op gates (silent return)

Return without prompting or making any API call when any of these are true:

- **No open PR for the branch.** If `pr_number` was not passed, run `gh pr list --repo {repo} --head {branch} --state open --json number,url --jq '.[0]'`. If empty, return silently.
- **Mute memory says "never for this repo".** Read the per-user memory file (see "Memory entry" below). If the normalized remote URL is in the `never` list, return silently.

### 2. Fetch current state

```bash
gh pr view {pr_number} --repo {repo} --json body,title,headRefOid > /tmp/pr-current.json
git diff origin/main...HEAD
git log origin/main..HEAD --pretty=format:'%h %s'
cat .github/pull_request_template.md
```

### 3. Regenerate the description

Apply the same logic as `/comet:create-pr` Step 6 (Extract Change Information) and Step 7 (Pre-fill PR Template). In particular:

- Fill every `##` section defined in `.github/pull_request_template.md`. Sections not applicable to this PR get `N/A`, never get removed.
- Re-derive the **Details** summary from the diff and commit messages.
- Re-derive the **Change checklist** from the file types changed (e.g., user-facing checked when UI files changed; documentation checked when `*.md` / `*.mdx` changed).
- Keep the existing **Issues** ticket reference if present (e.g., `OPIK-6296`); if missing, infer from branch name.
- Keep the existing **AI-WATERMARK** answers verbatim — never silently flip `yes`↔`no`.
- Re-derive **Testing** from commit messages and test files changed.
- Re-derive **Documentation** from docs files changed.

Apply the same secrecy rules as `/comet:create-pr` Step 7: never include customer/client names, internal hostnames, internal URLs, IPs, bucket names, or credentials.

### 4. Preserve media and hand-edits

Parse the *current* PR body. For each of the following, lift the matching content verbatim from the current body and reinsert it into the regenerated body under the same `##` section:

- **Markdown images**: any `![…](…)` line.
- **HTML img tags**: any `<img …>` element (single-line or multi-line).
- **Video / embed links**: any URL matching `user-images.githubusercontent.com`, `github.com/.../assets/`, `*.loom.com/share/`, `youtube.com/watch`, `youtu.be/`, `vimeo.com/`.
- **Hand-edited free-text under a known `##` section**: text the user added that does not match the regenerated content for that section. Append it under the same heading rather than overwriting.

Inside `## Testing`, treat the `Video evidence:` sub-section as a preservation island — keep its content as-is.

If the user adds an entirely new top-level `##` section that isn't in the template (e.g., `## Migration plan`), keep it intact at the bottom of the body.

### 5. Idempotence check

If the regenerated body, after preservation, is byte-equal to the current body (after trimming trailing whitespace on each line), return silently. Do not prompt, do not call `gh pr edit`.

### 6. Validate against pr-lint

Run the same checks as `/comet:create-pr` Step 8 against the regenerated body — title regex (unchanged here, body only), required `##` sections present, `## Details` non-empty, `## Issues` references a ticket, no leftover `<!-- REPLACE ME` placeholders. If any check fails, auto-fix and re-validate. Never push a body that would fail pr-lint.

### 7. Confirm with user (unless silent mode is active)

**Silent mode**: the per-repo mute memory says `always` for this repo's normalized remote URL. Skip the prompt and apply directly.

**Otherwise**: emit an `AskUserQuestion` with:

- **Question header**: `Refresh PR description?`
- **Question body**: a unified diff (`diff -u`) between current and regenerated bodies, capped at ~80 lines (truncate the middle with `... [N lines elided] ...` if longer).
- **Options**:
  - `Update` — apply the change to this PR only.
  - `Skip` — do nothing this time.
  - `Always for this repo` — apply now, and silently apply on every future push to any PR in this repo.
  - `Never for this repo` — do nothing now, and skip the prompt entirely on future pushes in this repo.

**Media-stale reminder (conditional)**: if the *current* body contains at least one match from the media patterns in Step 4, append a one-line preface to the question body:

> Heads up — this PR has screenshots/videos. Verify they still match the current behavior; you may need to re-record after this update.

Do not show the reminder when there's no media — avoid noise.

### 8. Apply

- On `Update` or under silent `Always` mode: `gh pr edit {pr_number} --repo {repo} --body-file <tmpfile>`.
- On `Skip`: return.
- On `Always for this repo`: write the normalized remote URL into the `always` list of the memory file (Step 9), then apply.
- On `Never for this repo`: write the normalized remote URL into the `never` list of the memory file, return without applying.

After applying, log: `PR description refreshed in sync with HEAD ({headRefOid}).`

### 9. Memory entry — `feedback_pr_description_auto_refresh.md`

Persist `Always` / `Never` choices to a single user-memory file alongside the user's other feedback memories.

**Path**: `{user-memory-dir}/feedback_pr_description_auto_refresh.md`

**Format**:

```markdown
---
name: PR description auto-refresh per-repo preference
description: Per-repo opt-in/opt-out for the post-push PR-description sync prompt
type: feedback
---

When the post-push PR description sync prompt fires, the user has chosen the following per-repo behaviors. Apply these without prompting on future pushes in those repos.

**How to apply**: before invoking the sync prompt, normalize the current repo's `git config --get remote.origin.url` to `host/owner/repo` (lowercase, strip `git@`, `https://`, trailing `.git`). Look it up below.

## Always (silent refresh, no prompt)
- {host/owner/repo}

## Never (skip entirely, no prompt)
- {host/owner/repo}
```

Also add (or update) a one-line entry in the user's `MEMORY.md` index pointing at this file.

**Normalization rule** (applied identically when reading and writing): take the URL from `git config --get remote.origin.url`, strip `git@` / `https://` prefix, strip trailing `.git`, replace `:` with `/`, lowercase. Examples:

- `git@github.com:comet-ml/opik.git` → `github.com/comet-ml/opik`
- `https://github.com/comet-ml/opik.git` → `github.com/comet-ml/opik`

A repo can appear in `Always` *or* `Never`, never both. If the user picks the opposite later, move it.

## Caller contract

Each caller invokes this sub-skill at the moment immediately after a successful `git push` (or `git push --force-with-lease`). Callers pass `branch = git rev-parse --abbrev-ref HEAD` and, if known, `pr_number`. The sub-skill itself never pushes and never modifies code — only the PR description on GitHub.

## Failure modes

- **`gh` unavailable**: log `PR description sync skipped: gh CLI unavailable` and return. Do not attempt MCP fallback — refresh is a quality-of-life feature, not a correctness gate.
- **PR description fetch fails (404, network)**: log the error and return. Don't block the caller.
- **`gh pr edit` fails after user confirmation**: surface the error and prompt the user whether to retry. On retry-no, return without changing memory entries.
- **Memory file write fails**: surface the error but still apply the description update if the user said `Update` / `Always`.

---

**End sub-skill**
