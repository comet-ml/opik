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

### 3. Fetch PR Diff and Changed Files

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

- **Display summary**: Show total count by severity (e.g., "Found 2 blockers, 3 suggestions, 1 nit, 1 question")
- **List all findings**: Grouped by severity (blockers first), showing file, line, category, and description
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

Post approved comments using `gh api`. Two types of comments:

#### Inline Comments (file-specific)

For comments tied to specific lines in the diff, post as single-comment PR reviews:

```bash
gh api repos/comet-ml/opik/pulls/{pr_number}/comments \
  -f body="<comment text>" \
  -f path="<file_path>" \
  -f commit_id="<head_sha>" \
  -F line=<line_number> \
  -f side="RIGHT"
```

For multi-line comments:

```bash
gh api repos/comet-ml/opik/pulls/{pr_number}/comments \
  -f body="<comment text>" \
  -f path="<file_path>" \
  -f commit_id="<head_sha>" \
  -F start_line=<start_line> \
  -F line=<end_line> \
  -f start_side="RIGHT" \
  -f side="RIGHT"
```

#### Code Suggestions

When suggesting specific code changes, use GitHub suggestion syntax in the comment body:

````
<description of the suggestion>

```suggestion
<replacement code>
```
````

#### General Comments (PR-level)

For comments not tied to a specific line (overall architecture, missing tests, etc.), post as issue comments:

```bash
gh api repos/comet-ml/opik/issues/{pr_number}/comments \
  -f body="<comment text>"
```

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

### 8. Summary

After posting, display:
- Total comments posted (by severity)
- Total comments skipped
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
7. ✅ Approved comments are posted with AI marker
8. ✅ Summary is displayed with posted/skipped counts

---

## Notes

- **Repository**: Always targets `comet-ml/opik`
- **`gh` CLI is the only hard requirement**: GitHub MCP is optional and used for richer reading when available. All operations can be performed with `gh` CLI alone.
- **No formal review submission**: This command NEVER submits a GitHub review with "approve" or "request changes". It only posts individual review comments. Human reviewers must still formally approve.
- **Complementary to `/address-github-pr-comments`**: That command responds to existing review feedback. This command generates review feedback. Both post comments via `gh api` with AI markers.
- **AI marker required**: Every posted comment must include the `🤖 *Review posted via /review-github-pr*` footer — never omit it
- **Domain-aware**: Uses Opik-specific rules from `.agents/skills/` and `.agents/rules/` to provide relevant, project-specific feedback rather than generic code review
- **User control**: Every finding is presented for approval before posting. Nothing is posted without explicit user consent.
- **Stateless**: Re-runs full analysis on every invocation
- **Severity-driven**: Findings are organized by severity to help users prioritize what matters
- **Code suggestions**: Uses GitHub's suggestion syntax so PR authors can apply fixes with one click
- **No approval by design**: The command is intentionally restricted from submitting formal approvals. Code review approval must remain a human-in-the-loop action.
- **No Delete Operations**: This command only creates content; it never deletes files, comments, or other content

---

**End Command**
