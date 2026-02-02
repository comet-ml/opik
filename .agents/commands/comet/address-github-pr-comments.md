# Address GitHub PR Comments

**Command**: `cursor address-github-pr-comments`

## Overview

Given the current working branch (which must include an Opik ticket number), fetch the open GitHub PR for this branch in `comet-ml/opik`, collect comments/discussions that are not addressed yet, list them clearly, and propose how to address each one (or suggest closing when appropriate). Provide options to proceed with fixes or mark items as not needed.

- **Execution model**: Always runs from scratch. Each invocation re-validates the branch, re-detects the PR, and re-collects pending comments.

This workflow will:

- Validate branch name and extract the Opik ticket number
- Verify `gh` CLI is authenticated and working (stop if not available)
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

- **Check `gh` CLI**: Test availability by running `gh auth status`
  > If unavailable or not authenticated, respond with: "This command needs the GitHub CLI (`gh`). Please install it (`brew install gh` on macOS) and authenticate with `gh auth login`."
  > If installed via Homebrew but not found, add `/opt/homebrew/bin` to PATH in `~/.zshenv`: `echo 'export PATH="/opt/homebrew/bin:$PATH"' >> ~/.zshenv` and restart Claude Code/Cursor.
  > Stop here.
- **Check Git repository**: Verify we're in a Git repository
- **Check current branch**: Ensure we're not on `main`
- **Validate branch format**: Confirm branch follows pattern: `<username>/OPIK-<ticket-number>-<kebab-short-description>` and extract `OPIK-<number>`

---

### 2. Locate Existing PR

- **Search PRs**: Use `gh pr list --head <branch-name> --state open --json number,url,title` to find an open PR for the current branch
- **If no PR exists**: Print: "No open PR found for this branch." and stop
- **If PR exists**: Capture PR number, URL, and title for later use

---

### 3. Collect Pending Comments

- **Fetch review comments**: Use `gh api repos/comet-ml/opik/pulls/<PR_NUMBER>/comments` to retrieve PR review comments
- **Fetch PR reviews**: Use `gh api repos/comet-ml/opik/pulls/<PR_NUMBER>/reviews` to get all reviews and understand the review context
- **Fetch review threads**: Use `gh pr view <PR_NUMBER> --json reviewThreads` to get review thread information including resolved status
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

### **CLI Availability Errors**

- **`gh` CLI unavailable**: Stop immediately after testing and provide setup instructions (`gh auth login`)
- **Authentication failures**: Stop and request to run `gh auth login`

### **Branch Validation Errors**

- Invalid format: Show expected pattern and current branch
- On main: Explain this command is for feature branches only

### **PR Discovery Errors**

- No PR found for branch: Print and stop the flow
- API limitation locating unresolved state: Fall back to heuristics and clearly label items as "potentially pending"

---

## Success Criteria

The command is successful when:

1. ✅ `gh` CLI is available and authenticated
2. ✅ Feature branch is validated with Opik ticket number
3. ✅ An open PR for the branch is found (or we clearly stop if not)
4. ✅ Pending/unaddressed comments are listed (or we clearly state none)
5. ✅ Proposed solutions are provided per item
6. ✅ User can choose actions (fix, skip) per comment

---

## Notes

- **Repository**: Always targets `comet-ml/opik`
- **Comment Context**: This command focuses on analyzing and proposing fixes rather than replying to comments
- **Reply Limitation**: Adding replies to comment threads requires additional API calls; this command focuses on analysis
- **Heuristics**: If unresolved review thread flags are not available, use best-effort heuristics (latest commit context, lack of author confirmation, not marked as outdated) and clearly label them
- **User control**: Ask before proceeding with any fixes or marking items as not needed
- **Stateless**: Re-runs discovery and analysis on every invocation
- **No Delete Operations**: This command only creates and updates content; it never deletes files, comments, or other content

---

**End Command**
