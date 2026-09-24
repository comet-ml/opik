# Work on Jira Ticket

**Command**: `cursor work-on-jira-ticket`

## Overview

Fetch a Jira ticket by link, build full context (title, description, comments, type, status, priority, assignee, labels), and generate an actionable implementation plan.  
This workflow will:

- Verify Jira MCP availability (or instruct how to install it).
- Fetch and parse the ticket details.
- Normalize the ticket to the WHY / WHAT / HOW format when it doesn't already follow it.
- If not found, list your assigned **To Do** issues.
- Check local git status in the Opik repository and propose a branch if on `main`.
- Suggest moving the ticket to **In Progress** if it's currently in **To Do**.
- Confirm the WHY and WHAT with the user after the plan is shared (skippable via memory).
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

### 3b. Normalize the Ticket to WHY / WHAT / HOW

Tickets filed before the convention — or filed in a hurry — often arrive as a one-line summary, a pasted Slack thread, or a description with no structure at all. Restructuring the ticket **before** planning is itself the first pass of planning: it forces the short version of *why we are doing this* and *what changes* to exist in writing, where the reviewer and QA can see it, instead of only in the plan.

#### When to normalize

Assess the fetched ticket:

- **Description already has both `## WHY` and `## WHAT` sections** → it conforms. Skip to step 4, no edit.
- **Description is missing one or both sections** (unstructured prose, empty, bullet dump, pasted thread) → normalize it.

The HOW is never part of the description. If the ticket's description contains implementation detail under a `HOW` heading or equivalent, move it into the HOW comment as part of normalizing.

#### How to normalize

1. **Derive, don't invent.** Build WHY and WHAT from what the ticket already contains — summary, description, comments, parent epic, linked issues. Where the existing text is genuinely ambiguous about scope or motivation, say so rather than inventing a rationale: a WHY nobody actually holds is worse than a thin one. Ask the user when the gap blocks planning.
2. **Draft the description** using the same structure `/comet:create-jira-ticket` produces:

   ```
   ## WHY

   [Why this ticket needs to exist — the motivation and context. 2-6 sentences.]

   ## WHAT

   [High-level description of the changes, phrased so QA can derive test cases.]

   ### Acceptance Criteria

   - [ ] [Observable behavior or outcome]
   ```

   Keep the reporter's own words where they're already clear.
3. **Never destroy the original description.** When the ticket had any description text, the normalized version is *prepended*, not substituted: WHY and WHAT go on top, and everything that was there before is retained **verbatim** underneath, under its own heading:

   ```
   ## WHY

   ...

   ## WHAT

   ...

   ### Acceptance Criteria

   - [ ] ...

   ---

   ## Original Description

   [The ticket's previous description, copied verbatim — same text, same formatting, same media, same links.]
   ```

   This is a strict rule, not a fallback. WHY and WHAT are a reading aid added on top; they are an agent's interpretation of the ticket, and the reporter's own account stays available to anyone who scrolls down. Do not summarize, reword, re-order, or trim the original block, and do not drop content on the grounds that WHY/WHAT already covers it — that judgment is exactly what the preserved copy exists to let a human re-check. Retain media (screenshots, embeds, tables, links) as-is.

   If the ticket had **no** description at all, there is nothing to preserve — omit the `## Original Description` section entirely rather than emitting an empty one. On a **re-run** against a ticket that already carries an `## Original Description` block, keep that existing block as the original and regenerate only WHY / WHAT above it, so the oldest text survives repeated passes instead of each run preserving the previous run's output.
4. **Check the title against the WHAT.** The summary is the one-line version of the WHAT. If they disagree on scope, flag the mismatch to the user and propose a corrected title — but don't silently rewrite the summary.
5. **Write the description back** with `mcp__Jira__home___jira_update_issue`.
6. **Post the HOW as a comment**, not in the description — the same rules `/comet:create-jira-ticket` uses: only when there's real substance (stable landmarks, an existing pattern worth mirroring, a non-obvious constraint, open questions). A missing HOW is better than a filler one. Follow the same edit-don't-pile-up rule: fetch comments, find the most recent whose body matches `^#\s*HOW\b` (case-insensitive) **and** whose `author.email` matches the authenticated user, `jira_edit_comment` if found, `jira_add_comment` otherwise.

#### Guardrails

- **Never normalize silently.** Show the drafted WHY / WHAT and get the user's confirmation before writing to Jira. A ticket description is shared state — the reporter, QA, and the epic owner all read it.
- **Don't normalize someone else's ticket without saying so.** If the reporter isn't the current user, note that the rewrite will be visible to them.
- **If the user declines**, continue to step 4 with the ticket as-is. Normalization is a convenience, not a gate on doing the work.

---

### 4. Determine Implementation Scope

- **Analyze ticket scope**: Determine which Opik components are affected:
  - **Backend only**: Java API changes, database migrations, services
  - **Frontend only**: React components, UI changes, state management
  - **SDK only**: Python or TypeScript SDK changes
  - **Cross-component**: Changes affecting multiple layers
  - **Infrastructure**: Docker, deployment, configuration changes
- **Identify affected areas**: Map ticket requirements to specific Opik modules and files
- **Estimate complexity**: Consider if changes require database migrations, API versioning, or breaking changes

---

### 5. Task Plan

- **Bugfix**: repro steps, root cause hypothesis, affected files, fix approach, risks, tests, verification.
- **Feature**: user story recap, acceptance criteria, implementation plan, tests, rollout notes.
- **HOW comment, if one exists**: weave its suggestions into the plan **after** independently reading the current code state. If the HOW conflicts with what the code looks like today, trust the code and note the divergence in your plan. The HOW is a hint from when the ticket was filed, not a contract.
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
  - **Then run the WHY / WHAT confirmation below** — it comes *after* the plan is on screen, not before.
  - **Wait for explicit user confirmation** before making any code changes
  - If user declines: Stop here and provide guidance for manual implementation
  - If user confirms: Proceed to implementation phase

#### 9a. WHY / WHAT Confirmation

Once the plan has been shared, restate the ticket's **WHY** and **WHAT** and confirm them against the plan the user just read. The point is the comparison: the plan is the concrete proposal, and this is the last cheap moment to catch that it solves a different problem than the ticket describes, or has quietly grown past the ticket's scope. Checking before the plan exists would only re-read the ticket back to the user.

**Skip check (first):** if the user's memory (global `CLAUDE.md`, project memory, or a stated preference earlier in the session) says to skip this confirmation, **skip it entirely** — no prompt, no summary — and go straight to the proceed/decline decision. Users opt out by adding a line to their memory, e.g.:

```
Skip the WHY/WHAT confirmation in /comet:work-on-jira-ticket — go straight to the plan approval.
```

**Otherwise, prompt.** Display the WHY and WHAT in a compact form — a few lines each, the short version, not the full ticket description:

```
**WHY**: [1-2 sentence motivation]
**WHAT**: [1-2 sentence description of the changes]
```

Then use `AskUserQuestion` with these options:

1. **Looks right — proceed** — WHY and WHAT match the plan; continue to implementation.
2. **Adjust the scope** — something is off. The user's free-text answer says what.
3. **Don't ask me again** — proceed, and offer to persist the skip preference to the user's memory so the prompt doesn't appear on future runs. Persist it only if the user agrees; don't write to memory silently.

`AskUserQuestion` always offers a free-text "Other" option, so the user can correct the WHY / WHAT in their own words instead of picking one of the above — treat any free-text answer as option 2.

**If the user adjusts** (option 2 or free text): update the plan to match the corrected WHY / WHAT, and if the correction reveals the ticket description itself is wrong, offer to update the ticket via the step 3b flow. Re-present the revised plan before proceeding — a scope correction invalidates the approval the user hasn't given yet.

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
  - ✅ Ticket normalized to WHY / WHAT / HOW (if it didn't already follow the format)
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

### **Status Transition Failures**

- Invalid transition: Check available transitions for current status
- Permission denied: Verify user can modify ticket status
- Workflow restrictions: Check project workflow configuration

---

## Success Criteria

The command is successful when:

1. ✅ Jira ticket is successfully fetched and analyzed
2. ✅ Ticket follows the WHY / WHAT / HOW format — normalized if it didn't, with the original description preserved verbatim (or the user declined)
3. ✅ Feature branch is created and active following Opik naming convention
4. ✅ Ticket status is updated (if requested)
5. ✅ Implementation suggestion provided based on context and Opik rules
6. ✅ WHY / WHAT confirmed after the plan was shared (or skipped per the user's memory)
7. ✅ User confirmation received (proceed or decline)
8. ✅ Implementation executed (if confirmed) or manual guidance provided (if declined)
9. ✅ All operations complete without errors
10. ✅ Clear next steps are provided to the user

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
