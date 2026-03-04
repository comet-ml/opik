# Review GitHub PR

**Command**: `cursor review-github-pr`

## Overview

Given a PR number/URL (or auto-detected from the current branch), fetch the PR diff from `comet-ml/opik`, perform a thorough code review, present findings to the user, and post approved review comments directly on the PR. This is the complement of `/address-github-pr-comments` — that command *responds* to reviewer feedback, this command *generates* it.

- **Execution model**: Always runs from scratch. Each invocation re-fetches the PR, re-analyzes the diff, and re-generates findings.

This workflow will:

- Verify `gh` CLI is available and authenticated (stop if not)
- Optionally use GitHub MCP for richer reading (but `gh` CLI alone is sufficient)
- Locate the PR (from argument, current branch, or prompt)
- Fetch the full PR diff and changed files
- Analyze changes using Opik domain knowledge (backend, frontend, SDK rules)
- Generate categorized review findings with inline code suggestions
- Present findings to the user for approval before posting
- Post approved comments on the PR using `gh api` with AI watermark
- **Never** submit a formal review approval or "request changes" — only post individual comments

---

## Inputs

- **PR identifier (optional)**: PR number (e.g., `5395`), full URL (e.g., `https://github.com/comet-ml/opik/pull/5395`), or omitted to auto-detect from current branch

---

## Steps

### 1. Preflight & Environment Check

- **Check `gh` CLI**: Verify `gh` is installed and authenticated (required for both reading and posting)
  ```bash
  gh auth status
  ```
  > If not authenticated, respond with: "Please run `gh auth login` first."
  > Stop here.
- **Check GitHub MCP (optional)**: If GitHub MCP is available, use it for richer PR data. If not, fall back to `gh` CLI for everything — no setup instructions needed.

---

### 2. Locate the PR

- **If argument provided**:
  - If numeric (e.g., `5395`): Use as PR number directly
  - If URL (e.g., `https://github.com/comet-ml/opik/pull/5395`): Extract PR number from URL
- **If no argument provided**:
  - Check current Git branch
  - Search for an open PR for this branch in `comet-ml/opik`:
    ```bash
    gh pr list --repo comet-ml/opik --head <branch-name> --state open --json number,title,url
    ```
  - If GitHub MCP is available, use it as an alternative to `gh pr list`
  - If no PR found, prompt: "No open PR found for this branch. Enter a PR number or URL:"
- **Fetch PR metadata**: Get PR title, description, author, base branch, and state
  ```bash
  gh pr view {pr_number} --repo comet-ml/opik --json title,body,author,baseRefName,state,headRefOid
  ```
- **If PR is merged or closed**: Print warning and ask if user wants to continue reviewing anyway

---

### 3. Fetch PR Diff, Changed Files, and Existing Reviews

- **Fetch existing review comments**: Before analyzing, collect any comments already posted by this command on this PR
  ```bash
  gh api repos/comet-ml/opik/pulls/{pr_number}/comments --paginate
  ```
  - Filter for comments containing the `🤖 *Review posted via /review-github-pr*` marker
  - Index them by `path` + `start_line` + `line` for deduplication in step 6 (for single-line comments, `start_line` equals `line`)
  - This prevents posting duplicate comments when the command is run multiple times on the same PR

- **Get changed files**: List all files changed in the PR
  ```bash
  gh pr diff {pr_number} --repo comet-ml/opik --name-only
  ```
- **Get full diff**:
  ```bash
  gh pr diff {pr_number} --repo comet-ml/opik
  ```
- **If GitHub MCP is available**: Use `get_pull_request_files` for structured file data (additions, deletions, patch)
- **Categorize files by domain**:
  - **Backend**: `apps/opik-backend/**` → Java, API, services, DAOs, migrations
  - **Frontend**: `apps/opik-frontend/**` → React, TypeScript, components, hooks
  - **Python SDK**: `sdks/python/**` → Python SDK patterns
  - **TypeScript SDK**: `sdks/opik-typescript/**` → TypeScript SDK patterns
  - **E2E Tests**: `tests_end_to_end/**` → Playwright tests
  - **Docs**: `*.md`, `docs/**` → Documentation
  - **Config/Infra**: Docker, CI/CD, configuration files
- **Skip binary files and lock files** from review
- **Flag sensitive files**: Files matching patterns like `.env`, `.pem`, `.key`, `credentials.*`, `*.secret` should be flagged. Do not include raw content from these files in posted comments — instead, post a high-level note referencing the file without quoting secret values

---

### 4. Analyze Changes

Review the diff using Opik domain knowledge from `.agents/skills/` and `.agents/rules/`. For each domain touched by the PR, apply the relevant review criteria:

#### General (all files)
- Security: SQL injection, XSS, hardcoded secrets, input validation at boundaries
- Error handling: Appropriate error propagation, no swallowed exceptions
- Naming: Clear, consistent variable/function/class naming
- Logic: Off-by-one errors, null/undefined handling, race conditions
- Tests: Are new code paths covered by tests?

#### Backend (`apps/opik-backend/**`)
- Architecture: Resources → Services → DAOs → Models pattern
- SQL: Parameterized queries, proper transaction handling
- API: RESTful conventions, proper HTTP status codes, input validation
- Migrations: Backward-compatible schema changes, proper rollback support
- Logging: Appropriate log levels, no sensitive data in logs

#### Frontend (`apps/opik-frontend/**`)
- Components: Proper React patterns, hook dependencies, memoization where needed
- State: Appropriate state management, no prop drilling
- Types: TypeScript type safety, no unnecessary `any`
- Performance: Unnecessary re-renders, large bundle imports
- Accessibility: Proper ARIA attributes, keyboard navigation

#### SDKs (`sdks/**`)
- API compatibility: Breaking changes flagged
- Error handling: Clear error messages, proper exception hierarchies
- Documentation: Public API methods have proper docs

---

### 5. Generate Review Findings

Organize findings into categories with severity levels:

#### Severity Levels
- 🚫 **blocker**: Must fix before merge — bugs, security issues, data loss risks
- 💡 **suggestion**: Recommended improvement — better patterns, performance, readability
- 🧹 **nit**: Minor style/preference — naming, formatting, minor simplifications
- ❓ **question**: Needs clarification — unclear intent, missing context, design decisions

#### Finding Format
For each finding, prepare:
- **File and line range**: Where in the diff the issue is
- **Category**: Security, Logic, Performance, Style, Architecture, Testing, etc.
- **Severity**: blocker / suggestion / nit / question
- **Description**: Clear explanation of the issue
- **Suggestion** (if applicable): Concrete code suggestion using GitHub suggestion syntax

---

### 6. Present Findings and Get Approval

- **Deduplicate**: Compare findings against existing review comments fetched in step 3. If a finding targets the same `path + start_line + line` as an existing `/review-github-pr` comment, mark it as "already posted" and exclude it from the list. If all findings are duplicates, print: "All findings were already posted in a previous run." and stop.
- **Display summary**: Show total count by severity (e.g., "Found 2 blockers, 3 suggestions, 1 nit, 1 question — 1 already posted, skipped")
- **For each finding, show**:
  - The relevant code snippet from the diff
  - The review comment that would be posted
  - The code suggestion (if applicable)
- **Ask user for decisions**: Offer bulk and per-item options:
  - **Post all**: Post every finding
  - **Post blockers only**: Post only blocker-severity findings
  - **Post all except nits**: Post blockers, suggestions, and questions
  - **Cherry-pick**: Let user select individual findings to post, skip, or edit
- **For cherry-pick mode**, per finding:
  - **Post**: Post this comment as-is
  - **Edit**: Modify the comment text before posting
  - **Skip**: Don't post this one

---

### 7. Post Review Comments

Post approved comments as a **single batched review** to minimize notifications to the PR author. All inline comments are grouped into one review submission.

#### Batched Review (primary approach)

Collect all approved inline comments into a JSON payload and submit as one review:

```bash
gh api repos/comet-ml/opik/pulls/{pr_number}/reviews \
  --input - <<'EOF'
{
  "commit_id": "<head_sha>",
  "event": "COMMENT",
  "comments": [
    {
      "path": "<file_path>",
      "line": <line_number>,
      "side": "RIGHT",
      "body": "<comment text with AI marker>"
    },
    {
      "path": "<file_path>",
      "start_line": <start_line>,
      "line": <end_line>,
      "start_side": "RIGHT",
      "side": "RIGHT",
      "body": "<multi-line comment text with AI marker>"
    }
  ]
}
EOF
```

This sends **one notification** to the PR author regardless of how many comments are included.

#### Fallback: Individual Comments

If the batched review fails (e.g., a comment references a line not in the diff), fall back to posting comments individually. Identify which comments caused the failure and post the valid ones one by one:

```bash
gh api repos/comet-ml/opik/pulls/{pr_number}/comments \
  -f body="<comment text>" \
  -f path="<file_path>" \
  -f commit_id="<head_sha>" \
  -F line=<line_number> \
  -f side="RIGHT"
```

Log any comments that still fail individually (e.g., line not in diff) and fall back to posting them as general PR comments instead.

#### Code Suggestions

When suggesting specific code changes, use GitHub suggestion syntax in the comment body:

````
<description of the suggestion>

```suggestion
<replacement code>
```
````

#### General Comments (PR-level)

For comments not tied to a specific line (overall architecture, missing tests, etc.), post as issue comments. These cannot be included in the batched review and are posted separately:

```bash
gh api repos/comet-ml/opik/issues/{pr_number}/comments \
  -f body="<comment text>"
```

#### Secret Sanitization

Before posting any comment, scan the comment body for common secret patterns (API keys, bearer tokens, private key blocks, long hex/base64 strings, `export VAR=value` assignments). Replace any detected values with `[REDACTED]`. For files flagged as sensitive in Step 3, never include raw file content in comment bodies — reference the file and line range without quoting the actual values.

#### AI Marker

All posted comments **must** include a footer marker to distinguish them from human-written comments:

```
🤖 *Review posted via /review-github-pr*
```

#### Example Posted Comments

````
🚫 **blocker** | Security

This query concatenates user input directly. Use parameterized queries instead.

```suggestion
String query = "SELECT * FROM traces WHERE id = ?";
jdbi.withHandle(h -> h.createQuery(query).bind(0, traceId).mapTo(Trace.class).one());
```

🤖 *Review posted via /review-github-pr*
````

````
💡 **suggestion** | Performance

Consider using batch insert here to avoid N+1 queries.

🤖 *Review posted via /review-github-pr*
````

````
🧹 **nit** | Style

This variable name could be more descriptive.

🤖 *Review posted via /review-github-pr*
````

````
❓ **question** | Architecture

Is this intentionally bypassing the service layer? The other endpoints go through SpanService first.

🤖 *Review posted via /review-github-pr*
````

---

### 8. Generate & Approve Summary Comment

After inline comments are handled, generate a **general PR summary comment** that gives the author a high-level view of the review.

#### What to include

1. **Positive observations** (always lead with these): Call out things the PR does well — clean architecture, good test coverage, clear naming, smart abstractions, thorough error handling, well-structured migrations, etc. Be specific and genuine; don't fabricate praise.
2. **Solution assessment**: A brief, honest take on the PR as a whole — does the approach make sense? Is the scope appropriate? Are there any structural concerns not captured by inline comments?
3. **Findings recap**: One-line summary of what was flagged inline (e.g., "Left 2 suggestions and 1 nit — nothing blocking").
4. **Tone**: Supportive and constructive. The goal is to make the author feel their work is seen and valued, while still being honest about improvements.

#### Format

````
👋 **Review summary**

**What looks good**
- <specific positive observation>
- <another positive observation>

**Overall**
<1–3 sentences on the PR as a solution>

**Inline comments**: <count by severity, e.g., "2 suggestions, 1 nit"> (or "None — looks clean!" if no findings were posted)

🤖 *Review posted via /review-github-pr*
````

#### User approval

- **Show the summary comment** to the user before posting
- **Offer options**:
  - **Post**: Post this summary as-is
  - **Edit**: Modify the summary text before posting
  - **Skip**: Don't post a summary comment

#### Deduplication

Before generating, check if a summary comment from a previous run already exists (look for comments containing both `👋 **Review summary**` and the AI marker). If found, inform the user: "A summary comment was already posted in a previous run." and skip this step.

#### Posting (if approved)

```bash
gh api repos/comet-ml/opik/issues/{pr_number}/comments \
  -f body="<summary comment>"
```

---

### 9. Local Summary

After all posting is complete, display to the user (not on GitHub):
- Total inline comments posted (by severity)
- Total inline comments skipped
- Whether the summary comment was posted, edited, or skipped
- Link to the PR
- Reminder: "This review does not constitute an approval. A human reviewer should still approve the PR."

---

## Error Handling

### **CLI Authentication Errors**

- **`gh` not installed**: Stop and provide installation instructions
- **`gh` not authenticated**: Stop and instruct to run `gh auth login`

### **PR Discovery Errors**

- **PR not found**: Prompt for manual input or stop
- **Invalid PR number/URL**: Show expected format and re-prompt
- **PR is in draft**: Warn but allow review to proceed

### **Diff Fetch Errors**

- **Diff too large**: Review files in batches, warn about potential missed context
- **Binary files**: Skip with note
- **API rate limiting**: Wait and retry, or stop with guidance

### **Comment Posting Errors**

- **Line not in diff**: Fall back to a general PR comment instead of inline
- **Permission denied**: Check repository access and `gh` auth
- **API errors**: Show error details, skip the failed comment, continue with remaining

---

## Success Criteria

The command is successful when:

1. ✅ `gh` CLI is available and authenticated
2. ✅ PR is located and metadata fetched
3. ✅ PR diff and changed files are retrieved
4. ✅ Changes are analyzed with domain-specific knowledge
5. ✅ Findings are presented to user with severity and categories
6. ✅ User approves which findings to post
7. ✅ Approved inline comments are posted with AI marker
8. ✅ Summary comment is presented for approval and posted (if approved)
9. ✅ Local summary is displayed with posted/skipped counts

---

## Notes

- **Repository**: Always targets `comet-ml/opik`
- **`gh` CLI is the only hard requirement**: GitHub MCP is optional and used for richer reading when available. All operations can be performed with `gh` CLI alone.
- **No formal review submission**: This command submits reviews with event `COMMENT` only — NEVER with "approve" or "request changes". Human reviewers must still formally approve.
- **Complementary to `/address-github-pr-comments`**: That command responds to existing review feedback. This command generates review feedback. Both post comments via `gh api` with AI markers.
- **AI marker required**: Every posted comment must include the `🤖 *Review posted via /review-github-pr*` footer — never omit it
- **Domain-aware**: Uses Opik-specific rules from `.agents/skills/` and `.agents/rules/` to provide relevant, project-specific feedback rather than generic code review
- **User control**: Every finding is presented for approval before posting. Nothing is posted without explicit user consent.
- **Idempotent**: Re-runs full analysis on every invocation but deduplicates against existing `/review-github-pr` comments on the PR (matched by `path + start_line + line`). Safe to run multiple times — only new findings are posted.
- **Severity-driven**: Findings are organized by severity to help users prioritize what matters
- **Code suggestions**: Uses GitHub's suggestion syntax so PR authors can apply fixes with one click
- **No approval by design**: The command is intentionally restricted from submitting formal approvals. Code review approval must remain a human-in-the-loop action.
- **No Delete Operations**: This command only creates content; it never deletes files, comments, or other content

---

**End Command**
