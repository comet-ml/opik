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
- Ask for confirmation before proceeding with fixes or marking items as not needed

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
  - Apply the proposed fix now (you will then make edits or create follow-up todos)
  - Skip / mark as not needed (with justification)
- **If user opts to fix**: Proceed with the proposed code changes or create follow-up todos
- **If user opts to skip**: Confirm and log it as "won't fix" decision

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
6. ✅ User can choose actions (fix, skip) per comment

---

## Notes

- **Repository**: Always targets `comet-ml/opik`
- **Comment Context**: This command focuses on analyzing and proposing fixes rather than replying to comments
- **Reply Limitation**: GitHub MCP doesn't support proper replies to existing comment threads, so this option is not provided
- **Heuristics**: If unresolved review thread flags are not available via MCP, use best-effort heuristics (latest commit context, lack of author confirmation, not marked as outdated) and clearly label them
- **User control**: Ask before proceeding with any fixes or marking items as not needed
- **Stateless**: Re-runs discovery and analysis on every invocation
- **No Delete Operations**: This command only creates and updates content; it never deletes files, comments, or other content

---

**End Command**
