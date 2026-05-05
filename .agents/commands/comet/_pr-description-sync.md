# PR Description Sync (shared sub-skill)

**Command**: not user-invocable. Called by `/comet:create-pr`, `/comet:work-on-jira-ticket`, and `/comet:address-github-pr-comments` after a `git push` succeeds against a branch with an open PR.

## Overview

Keep the GitHub PR description in sync with what was actually pushed, so reviewers don't read stale claims about endpoint paths, method names, emitted events, or other implementation details that the review cycle reshaped after the description was first written.

This sub-skill:

- Reads the canonical structure from `.github/pull_request_template.md` and the validation rules from `.github/workflows/pr-lint.yml`.
- Regenerates the description from the current `git diff origin/main...HEAD` and commit history using the same logic as `/comet:create-pr` Step 6/7.
- Preserves all media (images, GIFs, video links, Loom embeds) verbatim, and uses a hidden section-hash marker to detect which `##` sections the user has hand-edited so those sections are kept as-is rather than overwritten.
- Auto-applies by default — if the regenerated body differs and passes pr-lint, the sub-skill updates the PR description without prompting. There is no opt-out flag; if a refresh ever produces unwanted content, the user edits the body in the GitHub UI or via `gh pr edit`, and the marker check (Step 4) leaves those edits alone on subsequent runs.
- Is a no-op when the branch has no open PR, when no PR matches the local HEAD, or when the regenerated body is semantically identical to the current body (sha1 of body excluding the marker).

## Inputs

- `branch` (required): branch name to look up the PR against. Caller passes `git rev-parse --abbrev-ref HEAD`.
- `pr_number` (optional): if the caller already located the PR (e.g., `/comet:address-github-pr-comments`), pass it as a hint. The sub-skill **always validates** that the PR's `headRefName` matches `branch` before any read or write — never trust `pr_number` blindly.
- `repo` (optional, defaults to `comet-ml/opik`): the GitHub repo to operate against.

## Steps

### 1. Locate and validate the PR (no-op gates)

Return without prompting or making any API call when any of the failure conditions below are reached. Determine which PR to operate on:

- **If `pr_number` was passed**: run `gh pr view {pr_number} --repo {repo} --json number,headRefName,headRefOid,state`. Verify `state == "OPEN"` and `headRefName == branch`. If either check fails, **fail closed**: log `PR description sync skipped: pr_number={N} does not match branch={branch} (headRefName={X}, state={S}); refusing to switch PRs silently` and return. Do not fall through to the branch lookup — a caller passing a mismatching `pr_number` is a bug or stale state, not a request to switch PRs.
- **Branch lookup**: run `gh pr list --repo {repo} --head {branch} --state open --json number,url,headRefName,headRefOid`. Filter to PRs whose `headRefOid` matches the local `git rev-parse HEAD`.
  - **0 matches**: no open PR for this exact HEAD — return silently.
  - **Exactly 1 match**: use it.
  - **More than 1 match** (e.g., multiple forks share the same head branch name): log `PR description sync skipped: {N} open PRs match branch={branch} and HEAD={oid}; refusing to guess` and return silently. Don't edit any of them.

### 2. Fetch current state

Use the PR number resolved in Step 1 (whether from the validated `pr_number` hint or the branch lookup).

```bash
TMP=$(mktemp)
gh pr view {resolved_pr_number} --repo {repo} --json body,title,headRefOid > "$TMP"
git diff origin/main...HEAD
git log origin/main..HEAD --pretty=format:'%h %s'
cat .github/pull_request_template.md
```

Clean up `$TMP` on exit.

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

### 4. Detect user edits via the section-hash marker

The sub-skill stores a hidden HTML comment at the bottom of every body it writes:

```html
<!-- pr-sync: {"Details":"<sha1>","Change checklist":"<sha1>","Issues":"<sha1>","AI-WATERMARK":"<sha1>","Testing":"<sha1>","Documentation":"<sha1>"} -->
```

Each value is `sha1(<section content with leading/trailing whitespace trimmed>)`. The marker lets the next run distinguish *agent-generated content the user hasn't touched* from *content the user has edited*.

**Marker present:** for each `## <Section>` heading defined in `.github/pull_request_template.md`:

1. Compute `sha1(current section content)`.
2. If it equals the stored hash → section is *unmodified since the last refresh* → safe to overwrite with the regenerated content.
3. If it differs → *user has edited this section* → keep the current section content verbatim in the regenerated body; do not regenerate it.

This per-section decision is the merge algorithm. There is no "fuzzy match" or "append on conflict" — the marker is the source of truth.

**Marker absent** (first run on a PR that pre-dates this skill, or a PR whose body was edited externally to strip the marker): regenerate every section per Step 3 — we have no way to know which sections the user touched, so the auto-apply in Step 7 will install the marker on this run and adopt managed mode. Subsequent runs use the per-section logic above. Users who want specific sections preserved through that first overwrite can edit the body in the GitHub UI ahead of the next push; the post-edit content becomes the new baseline once the marker is reinstalled.

This means the **first refresh of a managed-mode PR is the most invasive** — it overwrites every section since none are flagged as user-edited yet. After that, the marker tracks state and refreshes are surgical: only sections whose hash still matches get regenerated. Users who want to lock in specific content before the first auto-refresh can edit the body in the GitHub UI; the marker check leaves user-edited sections alone on subsequent runs.

### 4b. Preserve media (template-managed sections only)

Scope: media extraction and reinsertion apply **only to template-managed sections** — the `##` headings defined in `.github/pull_request_template.md`. Media inside custom (non-template) `##` sections is preserved verbatim as part of the section's intact appended block (see below) and is **never** lifted out, scanned, or independently reinserted. This prevents duplicate-write and reorder hazards when a custom section contains the same image syntax that the scanner would otherwise pick up.

For each template-managed `##` section in the *current* body, scan it for media and lift it verbatim into the regenerated version of the same section:

- **Markdown images**: any `![…](…)` line.
- **HTML img tags**: any `<img …>` element (single-line or multi-line).
- **Video / embed links**: any URL matching `user-images.githubusercontent.com`, `github.com/.../assets/`, `*.loom.com/share/`, `youtube.com/watch`, `youtu.be/`, `vimeo.com/`.

Reinsert each piece of media at the same relative position within its template-managed section. Sections preserved verbatim under the marker check (Step 4) carry their media along automatically — no separate lift step is needed for them.

**Custom (non-template) sections**: if the user adds an entirely new top-level `##` section that isn't in the template (e.g., `## Migration plan`), keep it intact — content, media, and all — and append it at the bottom of the regenerated body, *above* the `<!-- pr-sync: ... -->` marker. The media scan above must not touch it.

### 5. Idempotence check (semantic, not byte-equal)

After Steps 3, 4, and 4b produce the regenerated body, compute `sha1(regenerated body without the marker)`. Compare against `sha1(current body without the marker)`. If equal → no semantic change → return silently. Do not prompt, do not call `gh pr edit`.

This handles the "every push regenerates Testing, but nothing meaningful changed" case: if user-touched sections are preserved verbatim (because their hashes still match the marker) and agent-owned sections regenerate to the same content as before, the body hashes match.

The check applies in both marker-present and marker-absent modes, so a PR whose regenerated body happens to match the current body byte-for-byte (excluding the marker) is a no-op even on first run.

### 6. Validate against pr-lint

Run the same checks as `/comet:create-pr` Step 8 against the regenerated body — title regex (unchanged here, body only), required `##` sections present, `## Details` non-empty, `## Issues` references a ticket, no leftover template placeholders. If any check fails, auto-fix and re-validate. Never push a body that would fail pr-lint.

**Placeholder check details**: a "leftover template placeholder" means the literal HTML comment block from `.github/pull_request_template.md` appearing as the *only* content of a section (i.e., the user replaced nothing). Match the comment text from the template, not arbitrary `<!-- REPLACE ME` substrings — bodies that legitimately quote the placeholder string while documenting the skill itself must pass.

### 7. Apply (auto, no prompt)

The default is **silent auto-apply** — refreshing the PR description is a routine bookkeeping action, not a decision that needs user confirmation each time. By the time we reach this step, the body has already passed pr-lint (Step 6), preservation rules have already protected user edits (Step 4), and idempotence has already filtered out no-op cases (Step 5).

Before writing, **append (or replace) the marker** at the bottom of the regenerated body:

```html

<!-- pr-sync: {"<Section>":"<sha1 of regenerated section content>", ...} -->
```

The marker is computed from the *final* body that will be written (post Step 4 user-section preservation, post Step 4b media reinsertion). One marker per body; if a previous marker exists, replace it.

Then write the body to a `mktemp` file and:

```bash
gh pr edit {pr_number} --repo {repo} --body-file <tmpfile>
```

After applying, log:

- `PR description refreshed in sync with HEAD ({headRefOid}).`
- If the body contains any media (image / video / Loom embed), append: `Note: this PR has screenshots/videos — verify they still match the current behavior; you may need to re-record.` This is informational only; the apply has already happened.

**Recovery**: if a refresh produces something the user didn't want, the body can be edited in the GitHub UI or via `gh pr edit`. The marker check in Step 4 then treats those edits as user-owned on subsequent runs and leaves them alone — no permanent opt-out is required.

## Caller contract

Each caller invokes this sub-skill at the moment immediately after a successful `git push` (or `git push --force-with-lease`). Callers pass `branch = git rev-parse --abbrev-ref HEAD` and, if known, `pr_number`. The sub-skill itself never pushes and never modifies code — only the PR description on GitHub.

## Failure modes

- **`gh` unavailable**: log `PR description sync skipped: gh CLI unavailable` and return. Do not attempt MCP fallback — refresh is a quality-of-life feature, not a correctness gate.
- **PR description fetch fails (404, network)**: log the error and return. Don't block the caller.
- **`gh pr edit` fails**: surface the error in the log; the caller continues normally. The next push will re-attempt the sync.

---

**End sub-skill**
