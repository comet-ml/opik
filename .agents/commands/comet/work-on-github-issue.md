# Work on GitHub Issue

**Command**: `cursor work-on-github-issue`

## Overview

Fetch a GitHub issue by link, build full context (title, description, comments, labels, assignees, milestone), and generate an actionable implementation plan.  
This workflow will:

- Verify GitHub MCP availability (or instruct how to install it).
- Fetch and parse the issue details.
- If not found, list your assigned **open** issues.
- Check local git status in the Opik repository and propose a branch if on `main`.
- Suggest moving the issue to **In Progress** if it's currently **open**.
- Validate all operations and provide clear success/failure feedback.

---

## Inputs

- **GitHub issue link (required)**: e.g., `https://github.com/comet-ml/opik/issues/1234`

---

## Steps

### 1. Preflight & Environment Check

- **Check GitHub MCP**: If unavailable, respond with:
  > "This command needs GitHub MCP configured. Set MCP config/env, run `make cursor` (Cursor) or `make claude` (Claude CLI), then retry."  
  > Stop here.
- **Check development environment**: Verify project dependencies, build tools, and project structure are ready.
- **Check local git branch** in the Opik repository:
  - If on `main`, propose a new branch following Opik naming convention:
    ```
    {USERNAME}/issue-{ISSUE-NUMBER}-{ISSUE-SUMMARY}
    ```
    Example:
    - Issue: `https://github.com/comet-ml/opik/issues/1234`
    - Title: "Add cursor git workflow rule"
    - Branch: `andrescrz/issue-1234-add-cursor-git-workflow-rule`

---

### 2. Fetch Issue

- Extract issue number from link.
- Fetch with GitHub MCP: title, body, state, labels, assignees, milestone, comments, created_at, updated_at.
- **If fetch fails**: Show error message and suggest troubleshooting steps.
- **If not found**: Search for open issues assigned to current user: `is:open assignee:@me` (max 10).
- **Show list**: `#1234 — Title (State)` and stop if issue not found.

---

### 3. Build GitHub Context

- **Number, Title, State, Labels, Assignees, Milestone**
- **Description**: verbatim if short, otherwise concise summary + key quotes.
- **If no description**: Note this and suggest adding context for better implementation planning.
- **Comments**: newest → oldest, `[author @ date] summary` with important snippets.
- **Linked PRs**: Include any linked pull requests if available.
- **Project context**: Include project board information if available.

---

### 4. Determine Implementation Scope

- **Analyze issue scope**: Determine which Opik components are affected:
  - **Backend only**: Java API changes, database migrations, services
  - **Frontend only**: React components, UI changes, state management
  - **SDK only**: Python or TypeScript SDK changes
  - **Cross-component**: Changes affecting multiple layers
  - **Infrastructure**: Docker, deployment, configuration changes
- **Identify affected areas**: Map issue requirements to specific Opik modules and files
- **Estimate complexity**: Consider if changes require database migrations, API versioning, or breaking changes

---

### 5. Task Plan

- **Bugfix**: repro steps, root cause hypothesis, affected files, fix approach, risks, tests, verification.
- **Feature**: user story recap, acceptance criteria, implementation plan, tests, rollout notes.
- **Always reference `.agents/rules` for tech stack guidance and Opik-specific patterns**:
  - **Global rules**: General development guidelines, git workflow, project structure
  - **Backend rules**: API design, architecture, business logic, database migrations, error handling, logging, MySQL transactions, testing
  - **Frontend rules**: Tech stack, performance, UI components, API data fetching, state management, forms, code quality, accessibility testing, unit testing
  - **SDK rules**: API design, architecture, code structure, dependency management, design principles, documentation, error handling, logging, testing
- **Component-specific guidance**: Use the appropriate rule set based on the implementation scope identified in step 4

---

### 6. Git & Branch Setup

- Repo: Opik repository (current workspace)
- **CRITICAL**: Handle working directory state BEFORE branching:
  - If working directory has changes:
    - **Option 1**: Stash changes: `git stash push -m "WIP: before issue-{ISSUE-NUMBER}"`
    - **Option 2**: Ask user what to do with uncommitted changes
  - **NEVER commit directly to main** (following Opik git workflow)
- If on `main`, create branch following Opik conventions:
  ```bash
  # Ensure you're on main and pull latest
  git checkout main
  git pull origin main
  
  # Create task-specific branch
  git checkout -b {USERNAME}/issue-{ISSUE-NUMBER}-{ISSUE-SUMMARY}
  ```
- **After branch creation**: Apply stashed changes if any: `git stash pop`
- **Verify branch creation**: Confirm new branch is active and clean.

---

### 7. Status Management (Optional)

- **Move issue to "In Progress"** if currently **open**:
  - Use GitHub MCP to add appropriate labels (e.g., "in-progress")
  - **Verify label addition**: Confirm labels were added successfully
  - **Handle label failures**: Provide error details and retry options

---

### 8. Implementation Suggestion

- **Based on GitHub context and Opik cursor rules**, suggest implementing the feature/bugfix:
  - Reference relevant `.agents/rules` for tech stack guidance (Java backend, React frontend, Python/TypeScript SDKs)
  - Provide specific implementation steps based on the task plan
  - Include code examples or file paths where appropriate
  - Suggest testing approaches and quality checks
  - Follow Opik architecture patterns (Resources → Services → DAOs → Models for backend)
- **Commit Message Format**: Always suggest commits with issue number prefix following Opik conventions:

  **Initial Task Commits:**
  ```
  [issue-####] [BE/FE/SDK/DOCS] <description>
  ```

  **Revision Commits:**
  ```
  Revision 2: <description>
  Revision 3: <description>
  ```

  **Test Commits:**
  ```
  Revision 4: Add comprehensive tests for <feature>
  Revision 5: Fix failing test cases
  ```

  **Component Types:**
  - `[BE]` - Backend changes (Java, API endpoints, services)
  - `[FE]` - Frontend changes (React, TypeScript, UI components)
  - `[SDK]` - SDK changes (Python, TypeScript SDKs)
  - `[DOCS]` - Documentation updates, README changes, comments, swagger/OpenAPI documentation

  **Examples:**
  ```
  [issue-1234] [BE] Add create trace endpoint
  [issue-1234] [FE] Add project custom metrics UI dashboard
  [issue-1234] [DOCS] Update API documentation
  [issue-1234] [SDK] Add new Python SDK method
  Revision 2: Add comprehensive tests for the project metrics endpoint
  Revision 3: Add get metrics endpoint
  ```

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
  - Commit changes with proper issue number prefix
- **If user declined**: Provide manual implementation guidance and stop

---

### 11. Completion Summary & Validation

- **Confirm all steps completed**:
  - ✅ GitHub MCP available and working
  - ✅ Issue fetched and analyzed successfully
  - ✅ Labels updated (if applicable)
  - ✅ Feature branch created and active following Opik naming convention
  - ✅ Development environment ready
  - ✅ Implementation suggestion provided
- **Next steps**: Provide clear guidance on what to do next
- **Error summary**: If any steps failed, provide troubleshooting guidance

---

## Error Handling

### **GitHub MCP Failures**

- Connection issues: Check network and authentication
- Permission errors: Verify user access to the repository
- Rate limiting: Wait and retry
- API errors: Check GitHub API status and retry

### **Git Operation Failures**

- Branch creation fails: Check for conflicts, verify permissions
- Pull fails: Resolve merge conflicts, check remote status
- Working directory dirty: **NEVER commit to main** - stash changes first
- **CRITICAL SAFETY**: Always verify current branch before any commits
- Uncommitted changes: Stash before branching, pop after branch creation

### **Label Management Failures**

- Invalid label: Check available labels in the repository
- Permission denied: Verify user can modify issue labels
- Label doesn't exist: Create the label or use existing ones

---

## Success Criteria

The command is successful when:

1. ✅ GitHub issue is successfully fetched and analyzed
2. ✅ Feature branch is created and active following Opik naming convention
3. ✅ Issue labels are updated (if requested)
4. ✅ Implementation suggestion provided based on context and Opik rules
5. ✅ User confirmation received (proceed or decline)
6. ✅ Implementation executed (if confirmed) or manual guidance provided (if declined)
7. ✅ All operations complete without errors
8. ✅ Clear next steps are provided to the user

---

## Troubleshooting

### **Common Issues**

- **GitHub MCP not available**: Configure MCP and run `make cursor` (Cursor) or `make claude` (Claude CLI)
- **Git branch conflicts**: Resolve conflicts before proceeding
- **Permission errors**: Check user access and repository settings
- **Network issues**: Verify connectivity to GitHub services

### **Fallback Options**

- If issue fetch fails: List user's assigned open issues
- If branch creation fails: Provide manual git commands
- If label update fails: Continue with development setup

---

**End Command**
