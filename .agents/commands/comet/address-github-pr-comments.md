# Address GitHub PR Comments

**Command**: `cursor address-github-pr-comments`

## Overview

Given the current working branch (which must include an Opik ticket number), fetch the open GitHub PR for this branch in `comet-ml/opik`, collect comments/discussions that are not addressed yet, list them clearly, and propose how to address each one (or suggest closing when appropriate). Provide options to proceed with fixes or mark items as not needed.

- **Execution model**: Always runs from scratch. Each invocation re-checks MCP availability, re-validates the branch, re-detects the PR, and re-collects pending comments.

This workflow will:

- Validate branch name and extract the Opik ticket number
- Verify GitHub MCP availability (stop if not available)
- Find the existing open PR for the branch (stop if none)
- Fetch pending/unaddressed comments or review threads
- Summarize findings and propose solutions or next actions
- Ask for confirmation before proceeding with fixes, skipping, or replying
- Post threaded replies on the PR using `gh api` (immediate for skips, deferred for fixes)

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

- **Fetch review comments**: Use GitHub MCP to retrieve PR review comments and/or discussion threads
- **Fetch PR reviews**: Get all reviews to understand the review context
- **Determine pending/unaddressed items**:
  - Prefer unresolved review threads when available
  - Otherwise, treat comments as pending if they are not from the latest code line (not "outdated") or explicitly unresolved, and have no author follow-up confirmation
  - **Include AI-posted review comments**: Comments containing AI markers (e.g., `🤖 *Review posted via /review-github-pr*`) are external review feedback even if posted from the same GitHub account. Never skip them based on the commenter's identity — only skip if the comment already has a threaded "Fixed" or "Skipping" reply with the `/address-github-pr-comments` marker.
  - Group by file and topic
- **If none pending**: Print: "No pending PR comments to address." and stop

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

Reply to PR review comments using `gh api` with threaded replies via `in_reply_to`.

#### Reply Command Format

```bash
gh api repos/comet-ml/opik/pulls/{pr_number}/comments \
  -f body="<reply text>" \
  -F in_reply_to={comment_id}
```

#### AI Marker

All auto-posted replies **must** include a footer marker to distinguish them from human-written replies:

```
🤖 *Reply posted via /address-github-pr-comments*
```

#### Immediate Replies ("Skipping")

When the user opts to skip a comment, post the reply immediately:

```
Skipping — <brief rationale why this is not being addressed>

🤖 *Reply posted via /address-github-pr-comments*
```

#### Deferred Replies ("Fixed")

When the user opts to fix a comment, **defer the reply** until the fix is pushed to the remote:

1. Track comments that need deferred replies (comment ID + description of fix)
2. After all fixes are applied, prompt the user to commit and push
3. Once `git push` completes, capture the commit SHA from the push output
4. Post deferred replies referencing the commit:

```
Fixed in <commit_sha> — <brief description of what was changed>

🤖 *Reply posted via /address-github-pr-comments*
```

If the user declines to push immediately, remind them which comments still need deferred replies and provide the reply commands they can run manually later.

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
4. ✅ Pending/unaddressed comments are listed (or we clearly state none)
5. ✅ Proposed solutions are provided per item
6. ✅ User can choose actions (fix, skip, reply) per comment
7. ✅ "Skipping" replies posted immediately with AI marker
8. ✅ "Fixed" replies posted after push with commit SHA and AI marker

---

## Notes

- **Repository**: Always targets `comet-ml/opik`
- **Replies via gh CLI**: Uses `gh api` with `in_reply_to` to post threaded replies on PR review comments. GitHub MCP is used for reading; `gh` CLI is used for posting replies.
- **AI marker required**: Every auto-posted reply must include the `🤖 *Reply posted via /address-github-pr-comments*` footer — never omit it
- **Deferred replies**: "Fixed" replies are only posted after the fix commit is pushed to remote. Never post a "Fixed" reply before the code is on the remote.
- **Heuristics**: If unresolved review thread flags are not available via MCP, use best-effort heuristics (latest commit context, lack of author confirmation, not marked as outdated) and clearly label them
- **User control**: Ask before proceeding with any fixes, skipping, or posting replies
- **Stateless**: Re-runs discovery and analysis on every invocation
- **No Delete Operations**: This command only creates and updates content; it never deletes files, comments, or other content

---

**End Command**
