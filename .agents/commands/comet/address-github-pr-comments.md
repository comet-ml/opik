# Address GitHub PR Comments

**Command**: `cursor address-github-pr-comments`

## Overview

Given the current working branch (which must include an Opik ticket number), fetch the open GitHub PR for this branch in `comet-ml/opik`, collect comments/discussions that are not addressed yet, list them clearly, and propose how to address each one (or suggest closing when appropriate). Provide options to proceed with fixes or mark items as not needed.

- **Execution model**: Always runs from scratch. Each invocation re-checks MCP availability, re-validates the branch, re-detects the PR, and re-collects pending comments.

This workflow will:

- Validate branch name and extract the Opik ticket number
- Verify GitHub MCP availability (stop if not available)
- Find the existing open PR for the branch (stop if none)
- Fetch pending/unaddressed feedback across **three categories**: inline review comments, issue thread comments, and PR-level review bodies (including reviews with no inline comments)
- Summarize findings and propose solutions or next actions
- Ask for confirmation before proceeding with fixes, skipping, or replying
- Post replies on the PR using `gh api` (immediate for skips, deferred for fixes): threaded replies for inline comments, quote-replies on the issue thread for PR-level review bodies

---

## Inputs

- **None required**: Uses the current Git branch and workspace repository

---

## Steps

### 1. Preflight & Environment Check

- **Check GitHub MCP**: Test availability by attempting to fetch basic repository information for `comet-ml/opik`
  > If unavailable, respond with: "This command needs GitHub MCP configured. Set MCP config/env, run `make cursor` (Cursor) or `make claude` (Claude CLI), then retry."  
  > Stop here.
- **Check Git repository**: Verify we're in a Git repository
- **Check current branch**: Ensure we're not on `main`
- **Validate branch format**: Confirm branch follows pattern: `<username>/OPIK-<ticket-number>-<kebab-short-description>` and extract `OPIK-<number>`

---

### 2. Locate Existing PR

- **Search PRs**: Use GitHub MCP to find an open PR for the current branch in `comet-ml/opik`
- **If no PR exists**: Print: "No open PR found for this branch." and stop
- **If PR exists**: Capture PR number, URL, and title for later use

---

### 3. Collect Pending Comments

There are **three distinct categories** of feedback to collect — missing any one of them silently drops reviewer comments on the floor:

1. **Inline review comments** — code-line-anchored comments (`pulls/{N}/comments`)
2. **Issue thread comments** — general PR comments not tied to code (`issues/{N}/comments`)
3. **PR-level review bodies** — top-level review submissions with feedback in the review `body` (`pulls/{N}/reviews`), including reviews with **zero inline comments**

> **Why category 3 matters**: A reviewer can submit `state=CHANGES_REQUESTED` (or `COMMENTED`) with all their feedback in the review body and no inline comments. Such reviews do **not** appear in the GraphQL `reviewThreads` query (which only surfaces reviews containing inline comments) and they do **not** appear in `pulls/{N}/comments` or `issues/{N}/comments`. They are only visible via `pulls/{N}/reviews`. Past incident: PR #6507 had a `CHANGES_REQUESTED` review whose body asked us to investigate a regression; it was missed on re-runs of this skill because the body wasn't surfaced.

- **Fetch all three sources**: Use GitHub MCP / `gh api` for each. When using `gh api` for any list endpoint, **always** use `--paginate` to ensure all results are fetched:
  ```bash
  # 1. Inline review comments (code-line-anchored)
  gh api repos/comet-ml/opik/pulls/{pr_number}/comments --paginate

  # 2. Issue thread comments (general PR comments)
  gh api repos/comet-ml/opik/issues/{pr_number}/comments --paginate

  # 3. PR-level reviews (includes review BODY text — required for category 3)
  gh api repos/comet-ml/opik/pulls/{pr_number}/reviews --paginate
  ```
  > **Why pagination is required**: GitHub API returns 30 items per page by default. Opik PRs regularly exceed this — 17 CI test group comments + deployment bot comments + reviewer comments can push past 30 total. Without `--paginate`, the agent silently gets only the first page and may miss real review feedback.
- **Determine pending/unaddressed items** — evaluate each category separately:

  **Category 1 — Inline review comments** (`pulls/{N}/comments`):
  - Prefer unresolved review threads when available (via GraphQL `reviewThreads`)
  - Otherwise, treat comments as pending if they are not from the latest code line (not "outdated") or explicitly unresolved, and have no author follow-up confirmation
  - Skip if the comment already has a threaded "Fixed" or "Skipping" reply with the `/address-github-pr-comments` marker

  **Category 2 — Issue thread comments** (`issues/{N}/comments`):
  - Treat as pending if from a reviewer (not the PR author) and there's no follow-up author response addressing the point
  - Skip the skill's own auto-posted replies (those carrying the `/address-github-pr-comments` marker)

  **Category 3 — PR-level review bodies** (`pulls/{N}/reviews`):
  - **Per-reviewer latest-review selection (required)**: First, sort all reviews by `submitted_at` descending and group by `user.login`. For each reviewer, keep **only the newest review** as the authoritative one — older reviews from the same reviewer are always considered superseded and are never pending, regardless of state. All checks below evaluate against this latest-per-reviewer set only.
  - **Inline-comment association gate (required for COMMENTED)**: Build a map from `pull_request_review_id` (present on each item in `pulls/{N}/comments`) to the count of inline comments. A `COMMENTED` review whose id has any associated inline comments is **not** Category 3 — its feedback lives in Category 1 (those inline comments) and is handled there. Apply this gate before evaluating pending status, so a review body is never double-counted.
  - A reviewer's **latest** review is pending when it has a non-empty `body` AND meets one of:
    - `state=CHANGES_REQUESTED`, AND not later dismissed (no `dismissed_at` or `state=DISMISSED`). Per-reviewer dedup already handles supersession (e.g., a later `APPROVED` from the same reviewer wins because it's the newest).
    - `state=COMMENTED` with non-empty `body`, AND the inline-comment association gate above shows zero inline comments for this `review.id`, AND no follow-up addressing it (no later quote-reply on the issue thread carrying the `/address-github-pr-comments` marker that quotes this review body — see "Idempotency on re-runs" below)
  - Note: these reviews do **not** appear in GraphQL `reviewThreads`, so they must be sourced from `pulls/{N}/reviews` directly
  - Idempotency on re-runs: a PR-level review body has been "addressed" if there exists an issue-thread comment carrying the `/address-github-pr-comments` AI marker whose quote block, normalized per the "Quote-body normalization" rule in Step 6, matches the same normalized form of this review body. Both quote generation and the addressed check **must** use the identical normalization function — otherwise a long body's truncated quote on first run won't match the full body on re-run, causing duplicate replies.

  **All categories**:
  - **Include AI-posted review comments**: Comments/reviews containing AI markers (e.g., `🤖 *Review posted via /review-github-pr*`) are external review feedback even if posted from the same GitHub account. Never skip them based on the commenter's identity — only skip if the item already has the matching `/address-github-pr-comments` reply marker.
  - Group by file and topic for inline; group PR-level reviews under their reviewer
- **If none pending across all three categories**: Print: "No pending PR comments to address." and stop

---

### 4. Analyze and Propose Solutions

- **Categorize** comments (style, naming, missing types, logic bug, tests, docs, nit, question)
- **Propose** per-item actions:
  - Concrete code changes with short rationale
  - Clarifications when code is already correct
  - Mark as "not needed" candidates with justification (ask for confirmation)
- **Format output** with a clear list:
  - Item id, file:line (if available), short quote of feedback, proposed action

---

### 5. Ask for Decisions and Next Steps

- **Prompt**: For each item, ask whether to:
  - **Apply fix**: Make the code change now (you will then make edits or create follow-up todos)
  - **Skip**: Mark as not needed with justification
  - **Reply on PR**: Post a threaded reply to the comment on GitHub
- **If user opts to fix**: Proceed with the proposed code changes or create follow-up todos. Track this comment for a deferred "Fixed" reply (see Step 6).
- **If user opts to skip**: Post an immediate "Skipping" reply on the PR thread with the rationale (see Step 6), then log it as "won't fix" decision.
- **If user opts to reply only**: Post a custom reply without making code changes.

---

### 6. Post Replies to PR

Replies are posted differently depending on the feedback category. Inline review comments use threaded replies via `in_reply_to`; PR-level review bodies (which have no thread to attach to) use a quote-reply on the issue thread.

#### Inline Review Comments — Threaded Reply

```bash
gh api repos/comet-ml/opik/pulls/{pr_number}/comments \
  -f body="<reply text>" \
  -F in_reply_to={comment_id}
```

#### PR-level Review Replies — Quote Reply on Issue Thread

PR-level review bodies (Category 3) have no thread to attach to (`in_reply_to` is not applicable). Acknowledge them by posting a **quote-reply** on the issue thread. The quoted body provides explicit linkage back to the review and makes the response visible in the conversation timeline; reviewer is notified via the standard PR-comment notification.

##### Quote-body normalization (shared rule)

Both quote generation here AND the addressed-check in Step 3 (Category 3 idempotency) MUST use this exact same `normalize(body)` function — otherwise a long body's truncated quote on first run won't match the full body on a re-run and the skill will post duplicate "Fixed"/"Skipping" replies.

`normalize(body)`:
1. Trim leading/trailing whitespace from the whole body
2. Strip any leading `>` quote markers and one optional space (so previously quoted text inside the body doesn't double-prefix on re-quoting)
3. Collapse runs of internal whitespace to a single space within each line; preserve line breaks between lines
4. If the resulting string is longer than **280 characters**, truncate at 280 chars and append `…` (single Unicode horizontal ellipsis, U+2026)
5. Return the normalized string

For the **on-the-wire quote block** in the comment body, prefix each line of the normalized string with `> ` (greater-than + single space). For the **addressed-check**, extract candidate quote blocks from existing issue-thread comments by stripping that same `> ` prefix per line, then compare the resulting string to `normalize(<review body>)` — exact equality.

The 280-char limit is a hard contract, not a heuristic: changing it without updating both call sites silently breaks idempotency for previously-addressed long reviews.

##### Reply template

```bash
gh api repos/comet-ml/opik/issues/{pr_number}/comments \
  -f body="$(cat <<'EOF'
> @<reviewer-login> wrote in their review (<review-state>):
>
<each line of normalize(<review body>) prefixed with "> ">

<response: "Fixed in <sha> — ..." or "Skipping — ...">

🤖 *Reply posted via /address-github-pr-comments*
EOF
)"
```

The quoted, normalized body is the idempotency key for re-runs: on the next invocation, Step 3's Category 3 "addressed" check looks for an issue-thread comment carrying the `/address-github-pr-comments` marker whose extracted quote block equals `normalize(<review body>)`. Always include the quote — it's the matching key.

#### AI Marker

All auto-posted replies **must** include a footer marker to distinguish them from human-written replies:

```
🤖 *Reply posted via /address-github-pr-comments*
```

#### Immediate Replies ("Skipping")

When the user opts to skip an item, post the reply immediately. The body format is the same for inline and PR-level; only the endpoint differs (per the sections above):

```
Skipping — <brief rationale why this is not being addressed>

🤖 *Reply posted via /address-github-pr-comments*
```

For a PR-level review, wrap with the quote prefix shown in "PR-level Review Replies".

#### Deferred Replies ("Fixed")

When the user opts to fix an item, **defer the reply** until the fix is pushed to the remote. This applies to both inline and PR-level items:

1. Track items that need deferred replies. For inline: comment ID + description. For PR-level: reviewer login + review id + raw review body (the quote is regenerated at post time via `normalize(body)` from the "Quote-body normalization" rule above) + description of fix.
2. After all fixes are applied, prompt the user to commit and push
3. Once `git push` completes, capture the commit SHA from the push output
4. Post deferred replies referencing the commit:

   For inline (threaded):
   ```
   Fixed in <commit_sha> — <brief description of what was changed>

   🤖 *Reply posted via /address-github-pr-comments*
   ```

   For PR-level (quote-reply on issue thread, using the "Quote-body normalization" rule and reply template above):
   ```
   > @<reviewer-login> wrote in their review (<review-state>):
   >
   <each line of normalize(<review body>) prefixed with "> ">

   Fixed in <commit_sha> — <brief description of what was changed>

   🤖 *Reply posted via /address-github-pr-comments*
   ```

If the user declines to push immediately, remind them which items still need deferred replies and provide the reply commands they can run manually later.

---

### 7. Resolve Addressed Review Threads (Optional)

After all replies are posted, offer to resolve the GitHub review threads that were addressed in this run.

> **Scope**: This step applies only to **inline review comments** (Category 1). PR-level review bodies (Category 3) have no review thread to resolve — their acknowledgement is the quote-reply posted in Step 6, and they are not included here.

- **Ask**: "Would you like to resolve all addressed review threads?"
- **If no**: Skip and finish
- **If yes**: Proceed with thread resolution

#### Fetch Review Threads

Use the GitHub GraphQL API to fetch review threads for the PR:

```bash
gh api graphql -f query='
  query {
    repository(owner: "comet-ml", name: "opik") {
      pullRequest(number: PR_NUMBER) {
        reviewThreads(first: 100) {
          nodes {
            id
            isResolved
            comments(first: 1) {
              nodes {
                databaseId
              }
            }
          }
        }
      }
    }
  }
'
```

#### Match Threads to Addressed Comments

- Filter to unresolved threads only (`isResolved: false`)
- Match each thread to comments addressed in this run by comparing `databaseId` from the thread's first comment against the comment IDs that received "Fixed" or "Skipping" replies
- Only resolve threads that were actually addressed — never resolve unrelated threads

#### Resolve Threads

For each matched thread, resolve via GraphQL mutation:

```bash
gh api graphql -f query='
  mutation {
    resolveReviewThread(input: {threadId: "THREAD_NODE_ID"}) {
      thread { isResolved }
    }
  }
'
```

#### Report Results

- Report success/failure count (e.g., "Resolved 7/8 threads")
- If a resolution fails, log the error and continue with remaining threads (non-blocking)
- Gracefully handle permission errors without failing the whole command

---

## Error Handling

### **MCP Availability Errors**

- **GitHub MCP unavailable**: Stop immediately after testing and provide setup instructions
- **Connection failures**: Stop and request to verify MCP server status

### **Branch Validation Errors**

- Invalid format: Show expected pattern and current branch
- On main: Explain this command is for feature branches only

### **PR Discovery Errors**

- No PR found for branch: Print and stop the flow
- API limitation locating unresolved state: Fall back to heuristics and clearly label items as "potentially pending"

---

## Success Criteria

The command is successful when:

1. ✅ GitHub MCP is available and accessible
2. ✅ Feature branch is validated with Opik ticket number
3. ✅ An open PR for the branch is found (or we clearly stop if not)
4. ✅ Pending/unaddressed feedback is listed across **all three categories** — inline review comments, issue thread comments, and PR-level review bodies — or we clearly state none
5. ✅ Proposed solutions are provided per item
6. ✅ User can choose actions (fix, skip, reply) per item
7. ✅ "Skipping" replies posted immediately with AI marker (threaded for inline; quote-reply on issue thread for PR-level review bodies)
8. ✅ "Fixed" replies posted after push with commit SHA and AI marker (same per-category posting)
9. ✅ Addressed inline review threads resolved (if user opted in); PR-level review bodies are not part of thread resolution

---

## Notes

- **Repository**: Always targets `comet-ml/opik`
- **Three feedback sources**: Always fetch from `pulls/{N}/comments` (inline), `issues/{N}/comments` (issue thread), AND `pulls/{N}/reviews` (PR-level review bodies). Skipping any source silently drops feedback.
- **Replies via gh CLI**: Uses `gh api` for posting. Inline comments → threaded reply via `in_reply_to` on `pulls/{N}/comments`. PR-level review bodies → quote-reply on `issues/{N}/comments` (no thread exists to attach to). GitHub MCP is used for reading; `gh` CLI is used for posting.
- **Quote-reply quoting**: For PR-level review replies, the reviewer's body must be quoted in the reply — it's how re-runs detect that the review has already been addressed (idempotency).
- **AI marker required**: Every auto-posted reply must include the `🤖 *Reply posted via /address-github-pr-comments*` footer — never omit it
- **Deferred replies**: "Fixed" replies are only posted after the fix commit is pushed to remote. Never post a "Fixed" reply before the code is on the remote.
- **Heuristics**: If unresolved review thread flags are not available via MCP, use best-effort heuristics (latest commit context, lack of author confirmation, not marked as outdated) and clearly label them
- **User control**: Ask before proceeding with any fixes, skipping, or posting replies
- **Stateless**: Re-runs discovery and analysis on every invocation
- **No Delete Operations**: This command only creates and updates content; it never deletes files, comments, or other content

---

**End Command**
