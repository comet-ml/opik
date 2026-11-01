# Work on Jira Ticket

**Command**: `cursor work-on-jira-ticket`

## Overview

Fetch a Jira ticket by link, build full context (title, description, comments, type, status, priority, assignee, labels), and generate an actionable implementation plan.  
This workflow will:

- Verify Jira MCP availability (or instruct how to install it).
- Fetch and parse the ticket details.
- If not found, list your assigned **To Do** issues.
- Read code from other comet-ml repos and external dependencies from their latest upstream state, prompting before touching any local clone.
- Check local git status in the Opik repository and propose a branch if on `main`.
- Suggest moving the ticket to **In Progress** if it's currently in **To Do**.
- Validate all operations and provide clear success/failure feedback.

---

## Inputs

- **Jira link (required)**: e.g., `https://comet-ml.atlassian.net/browse/OPIK-1234`
- **Worktree (optional)**: Pass `worktree` to work in an isolated git worktree. Defaults to no worktree when the argument is omitted — the command never prompts about worktrees.

---

## Steps

### 1. Preflight & Environment Check

- **Check Jira MCP**: If unavailable, respond with:
  > "This command needs Jira MCP configured. Set MCP config/env, run `make cursor` (Cursor) or `make claude` (Claude CLI), then retry."  
  > Stop here.
- **Check development environment**: Verify project dependencies, build tools, and project structure are ready.
- **Check local git branch** in the Opik repository:
  - If on `main`, propose a new branch following Opik naming convention:
    ```
    {USERNAME}/OPIK-{TICKET-NUMBER}-{TICKET-SUMMARY}
    ```
    Example:
    - Ticket: `https://comet-ml.atlassian.net/browse/OPIK-2180`
    - Title: "Add cursor git workflow rule"
    - Branch: `andrescrz/OPIK-2180-add-cursor-git-workflow-rule`

---

### 2. Fetch Ticket

- Extract key from link (`OPIK-<number>`).
- Fetch with Jira MCP: summary, description, issue type, status, priority, assignee, labels, reporter, comments.
- **If fetch fails**: Show error message and suggest troubleshooting steps.
- **If not found**: Search JQL: `assignee = currentUser() AND status = "To Do" ORDER BY updated DESC` (max 10).
- **Show list**: `OPIK-#### — Summary (Status)` and stop if ticket not found.

---

### 3. Build Jira Context

- **Key, Title, Type, Status, Priority, Assignee, Labels**
- **Description**: verbatim if short, otherwise concise summary + key quotes. The description contains the **WHY** (motivation) and **WHAT** (high-level scope + acceptance criteria); implementation details ("HOW") live in a separate comment — see below.
- **If no description**: Note this and suggest adding context for better implementation planning.
- **Comments**: newest → oldest, `[author @ date] summary` with important snippets.
- **HOW comment**: scan comments for the most recent one whose body matches `^#\s*HOW\b` (case-insensitive). If found, surface it as the **implementation suggestion** — clearly framed as a note from when the ticket was filed, not a plan of record. The current code state is authoritative; the HOW is one input. If no HOW comment exists, proceed without one — many tickets won't have one, and that's fine (older tickets predate the convention; some tickets have nothing worth adding beyond the WHAT).
- **Epic/Story context**: Include parent issue information if available.

---

### 4. Determine Implementation Scope

- **Analyze ticket scope**: Determine which Opik components are affected:
  - **Backend only**: Java API changes, database migrations, services
  - **Frontend only**: React components, UI changes, state management
  - **SDK only**: Python or TypeScript SDK changes
  - **Cross-component**: Changes affecting multiple layers
  - **Infrastructure**: Docker, deployment, configuration changes
- **Identify affected areas**: Map ticket requirements to specific Opik modules and files. If the ticket needs code from other comet-ml repos or from external dependencies, gather it per step 4a.
- **Estimate complexity**: Consider if changes require database migrations, API versioning, or breaking changes

#### 4a. Context From Outside the Opik Repo

Context read from outside the Opik repo must come from the **latest upstream state**, not from whatever a local clone happens to have checked out or from training-time knowledge of a library. Plans built on stale context look correct and then fail in review or CI. This mirrors step 6, which fetches `origin` and branches off `origin/main` for the Opik repo itself.

**Other comet-ml repos**

Tool order: use local git against `origin` first. Fall back to `gh api` / GitHub code search only when no local clone exists. Run each `git -C <clone> …` as its own plain command; some agent sandboxes refuse chained or nested git calls that target another repo.

1. Locate the clone. Repos are usually cloned side by side under one projects folder, so look for a sibling of the Opik root folder first (e.g. `<projects-folder>/<repo>`). Resolve the Opik root from git rather than the current directory, which may be a worktree:
   ```bash
   git rev-parse --path-format=absolute --git-common-dir   # e.g. ~/Projects/opik/.git → siblings live in ~/Projects/
   ```
   Confirm the match with `git -C <clone> remote get-url origin`. If it isn't there, ask the user for the path before falling back to step 5 below.
2. Resolve the default branch and fetch:
   ```bash
   git -C <clone> fetch origin
   git -C <clone> symbolic-ref --short refs/remotes/origin/HEAD   # e.g. origin/main or origin/master
   # If origin/HEAD is unset: gh api repos/comet-ml/<repo> --jq .default_branch
   ```
3. Check whether the clone is on the default branch and up to date:
   ```bash
   git -C <clone> branch --show-current
   git -C <clone> rev-list --left-right --count origin/<default>...HEAD   # "<behind> <ahead>"
   git -C <clone> status --porcelain
   ```
4. If the clone is off the default branch or behind it, use `AskUserQuestion`. **Never switch, pull, stash, or reset without confirmation.**
   - **Read from `origin/<default>` (Recommended)** — leave the working tree untouched and read with `git -C <clone> show origin/<default>:<path>` and `git -C <clone> grep <pattern> origin/<default> -- <pathspec>`.
   - **Switch and pull** — `git -C <clone> switch <default> && git -C <clone> pull --ff-only`. Offer only when the working tree is clean.
   - **Stash, switch and pull, re-apply** — offer instead of the previous option when the working tree is dirty. The stash stack is shared across worktrees, so never use bare `git stash` / `git stash pop`: push with a unique message, capture that entry's SHA, `git stash apply <sha>` after pulling, and drop the entry only once the apply succeeded. If the apply conflicts, stop and tell the user; the stash entry is still there.
   - **Use the local checkout as-is** — note in the plan which branch/commit the context came from and that it may be stale.
5. Without a local clone, read from the default branch on GitHub (`gh api repos/comet-ml/<repo>/contents/<path>?ref=<default>`, GitHub code search). Don't clone silently; offer it if the ticket needs more than a few files.

**External dependencies**

1. Find the pinned version in the relevant manifest (`pom.xml`, `package.json` / lockfile, `pyproject.toml`, `requirements*.txt`, …).
2. Read docs for **that** version and for the **latest upstream release**, via the library-docs MCP when available, plus release notes / changelog. Don't rely on training-time knowledge of the library.
3. If the pinned version lags the latest release in a way that matters for the ticket (changed or deprecated API, behavior change, relevant fix), flag the drift in the plan. Don't upgrade dependencies as part of the ticket unless it asks for that.

---

### 5. Task Plan

- **Bugfix**: repro steps, root cause hypothesis, affected files, fix approach, risks, tests, verification.
- **Feature**: user story recap, acceptance criteria, implementation plan, tests, rollout notes.
- **HOW comment, if one exists**: weave its suggestions into the plan **after** independently reading the current code state. If the HOW conflicts with what the code looks like today, trust the code and note the divergence in your plan. The HOW is a hint from when the ticket was filed, not a contract.
- **Outside context, if any (step 4a)**: state which ref each cross-repo file was read from (e.g. `<repo>@origin/master`), and list any pinned-vs-latest dependency drift that affects the work.
- **Always reference shared + domain guidance in the right place**:
  - **Global policy**: `.agents/rules/*` (git workflow, security, code style, routing)
  - **Backend guidance**: `.agents/skills/opik-backend/*`
  - **Frontend guidance**: `.agents/skills/opik-frontend/*`
  - **SDK guidance**: `.agents/skills/python-sdk/*` and `.agents/skills/typescript-sdk/*`
- **Component-specific guidance**: Use the appropriate guidance set based on the implementation scope identified in step 4

---

### 6. Git & Branch Setup

- Repo: Opik repository (current workspace)
- **NEVER commit directly to main** (following Opik git workflow)

#### 6a. Worktree Decision

Worktree usage is strictly opt-in:

- **If `worktree` was passed as an argument**: Use a worktree (continue to 6b).
- **Otherwise (default)**: Skip 6b and go straight to 6c (normal path). Do not prompt the user.

If the `EnterWorktree` tool is not available (e.g., running in Cursor or another editor) even when `worktree` was passed, fall back to 6c without prompting.

#### 6b. Worktree Path (if using worktree)

1. Slugify `{TICKET-SUMMARY}` for the worktree name: replace any character not in `[A-Za-z0-9._-]` with `-`, collapse consecutive `-` into one, and trim leading/trailing `-`. Then call `EnterWorktree` with name `{USERNAME}-OPIK-{TICKET-NUMBER}-{SLUGIFIED-SUMMARY}`.
2. Inside the worktree, fetch the latest remote state and create the properly named branch based on `origin/main` (using the same slugified summary):
   ```bash
   git fetch origin
   git checkout -b {USERNAME}/OPIK-{TICKET-NUMBER}-{SLUGIFIED-SUMMARY} origin/main
   ```
   The worktree intentionally branches off `origin/main` regardless of the parent checkout's current branch or local `main` state, and skips the rebase-strategy prompt — isolation is the whole point of the worktree.

   Do not inspect or prompt about the parent checkout's working tree (staged, unstaged, or untracked files; current branch). Worktrees are physically isolated, so parent state has no effect on the worktree and is not the agent's concern in this path.
3. Continue with implementation in the worktree directory.

#### 6c. Normal Path (if not using worktree)

- **Handle working directory state BEFORE branching**:
  - If working directory has changes:
    - **Option 1**: Stash changes: `git stash push -m "WIP: before OPIK-{TICKET-NUMBER}"`
    - **Option 2**: Ask user what to do with uncommitted changes
- Slugify `{TICKET-SUMMARY}` the same way as step 6b: replace any character not in `[A-Za-z0-9._-]` with `-`, collapse consecutive `-` into one, and trim leading/trailing `-`.
- If on `main`, create branch following Opik conventions:
  ```bash
  git checkout main
  git pull origin main
  git checkout -b {USERNAME}/OPIK-{TICKET-NUMBER}-{SLUGIFIED-SUMMARY}
  ```
- **After branch creation**: Apply stashed changes if any: `git stash pop`
- **Verify branch creation**: Confirm new branch is active and clean.

---

### 7. Status Management (Optional)

- **Move ticket to "In Progress"** if currently in "To Do":
  - Use Jira MCP to transition status
  - **Verify transition success**: Confirm status actually changed
  - **Handle transition failures**: Provide error details and retry options

---

### 8. Implementation Suggestion

- **Based on Jira context and Opik agent guidance**, suggest implementing the feature/bugfix:
  - Reference global policy in `.agents/rules/*` and domain guidance in `.agents/skills/*`
  - Provide specific implementation steps based on the task plan
  - Include code examples or file paths where appropriate
  - Suggest testing approaches and quality checks
  - Follow Opik architecture patterns (Resources → Services → DAOs → Models for backend)
- **Commit Message Format**: Use semantic commits. The first commit on a branch is critical because PR title is derived from it:

  **First Commit (PR-title source, required):**
  ```
  [OPIK-####] [BE/FE/SDK/DOCS] <type>: <description>
  ```

  **Allowed ticket-key variants (when applicable):**
  ```
  [issue-####] [BE/FE/SDK/DOCS] <type>: <description>
  [NA] [BE/FE/SDK/DOCS] <type>: <description>
  ```

  **Follow-up Commits (preferred):**
  ```
  <type>(<scope>): <description>
  ```
  where `<type>` is one of: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`.

  **Last-resort fallback (discouraged):**
  ```
  Revision N: <description>
  ```

  **Component Types:**
  - `[BE]` - Backend changes (Java, API endpoints, services)
  - `[FE]` - Frontend changes (React, TypeScript, UI components)
  - `[SDK]` - SDK changes (Python, TypeScript SDKs)
  - `[DOCS]` - Documentation updates, README changes, comments, swagger/OpenAPI documentation

  **Examples:**
  ```
  [OPIK-1234] [BE] feat: add create trace endpoint
  [OPIK-1234] [FE] feat: add project custom metrics UI dashboard
  [OPIK-1234] [DOCS] docs: update API documentation
  [issue-1234] [SDK] feat: add new Python SDK method
  fix(metrics): handle empty dashboard responses
  test(api): cover project metrics endpoint pagination
  Revision 2: small follow-up rename after emergency patch
  ```

  **Jira key convention in commit messages** (see git-workflow rule): the GitHub for Jira app links any `OPIK-<digits>` it finds in a commit message to that ticket's Development panel, and the link can't be removed. The ticket this branch resolves keeps the hyphen (`OPIK-1234`) — that's the prefix. But any **other** ticket a commit message mentions without resolving (an escalation, a reference to an older ticket) must be written with an underscore (`OPIK_7000`) and with no Jira URL, so the scanner doesn't link it.

### 9. User Confirmation

- **Ask for user approval** before proceeding with implementation:
  - Present the implementation plan clearly
  - Ask: "Would you like me to proceed with implementing this feature/fix now?"
  - **Wait for explicit user confirmation** before making any code changes
  - If user declines: Stop here and provide guidance for manual implementation
  - If user confirms: Proceed to implementation phase

### 10. Implementation Phase (Optional)

- **Only proceed if user confirmed** in previous step
- Execute the implementation plan:
  - Create/modify necessary files following Opik patterns
  - Apply code changes according to the plan
  - Run quality checks and tests
  - Commit changes with proper ticket number prefix
- **Post-push PR description sync**: Whenever this skill (or any follow-up step the user runs from this conversation) invokes `git push` or `git push --force-with-lease` to a branch with an open PR in `comet-ml/opik`, invoke the `_pr-description-sync` sub-skill (`.agents/commands/comet/_pr-description-sync.md`) immediately after the push completes. The sub-skill is a no-op when no PR exists, when the description is already in sync, or when the user has opted out of refreshes for this repo. This keeps the PR description aligned with what was actually shipped instead of what was claimed when the PR was opened.
- **If user declined**: Provide manual implementation guidance and stop

---

### 11. Completion Summary & Validation

- **Confirm all steps completed**:
  - ✅ Jira MCP available and working
  - ✅ Ticket fetched and analyzed successfully
  - ✅ Outside context (other repos, dependencies) read from latest upstream, or its staleness noted (if applicable)
  - ✅ Status updated (if applicable)
  - ✅ Feature branch created and active following Opik naming convention
  - ✅ Development environment ready
  - ✅ Implementation suggestion provided
- **Next steps**: Provide clear guidance on what to do next
- **Error summary**: If any steps failed, provide troubleshooting guidance

---

## Error Handling

### **Jira MCP Failures**

- Connection issues: Check network and authentication
- Permission errors: Verify user access to the ticket
- Rate limiting: Wait and retry

### **Git Operation Failures**

- Branch creation fails: Check for conflicts, verify permissions
- Pull fails: Resolve merge conflicts, check remote status
- Working directory dirty: **NEVER commit to main** - stash changes first
- **CRITICAL SAFETY**: Always verify current branch before any commits
- Uncommitted changes: Stash before branching, pop after branch creation

### **Outside-Context Failures**

- `git fetch` fails in a sibling clone (offline, auth): say so, and mark any context read from that clone as possibly stale in the plan. Don't proceed silently.
- Stash re-apply conflicts in a sibling clone: stop, report the conflicting files, and leave the stash entry in place.

### **Status Transition Failures**

- Invalid transition: Check available transitions for current status
- Permission denied: Verify user can modify ticket status
- Workflow restrictions: Check project workflow configuration

---

## Success Criteria

The command is successful when:

1. ✅ Jira ticket is successfully fetched and analyzed
2. ✅ Context from other repos and dependencies comes from latest upstream, with no local clone changed without confirmation
3. ✅ Feature branch is created and active following Opik naming convention
4. ✅ Ticket status is updated (if requested)
5. ✅ Implementation suggestion provided based on context and Opik rules
6. ✅ User confirmation received (proceed or decline)
7. ✅ Implementation executed (if confirmed) or manual guidance provided (if declined)
8. ✅ All operations complete without errors
9. ✅ Clear next steps are provided to the user

---

## Troubleshooting

### **Common Issues**

- **Jira MCP not available**: Configure MCP and run `make cursor` (Cursor) or `make claude` (Claude CLI)
- **Git branch conflicts**: Resolve conflicts before proceeding
- **Permission errors**: Check user access and project settings
- **Network issues**: Verify connectivity to Atlassian services

### **Fallback Options**

- If ticket fetch fails: List user's assigned tickets
- If branch creation fails: Provide manual git commands
- If status update fails: Continue with development setup

---

**End Command**
