# Work on Jira Ticket

**Command**: `cursor work-on-jira-ticket`

## Overview

Fetch a Jira ticket by link, build full context (title, description, comments, type, status, priority, assignee, labels), and generate an actionable implementation plan.  
This workflow will:

- Verify Jira MCP availability (or instruct how to install it).
- Fetch and parse the ticket details.
- If not found, list your assigned **To Do** issues.
- Check local git status in the Opik repository and propose a branch if on `main`.
- Suggest moving the ticket to **In Progress** if it's currently in **To Do**.
- Validate all operations and provide clear success/failure feedback.

---

## Inputs

- **Jira link (required)**: e.g., `https://comet-ml.atlassian.net/browse/OPIK-1234`

---

## Steps

### 1. Preflight & Environment Check

- **Check Jira MCP**: If unavailable, respond with:
  > "This command needs the Jira MCP server. Please enable it, then run: `npm run install-mcp`."  
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
- **Description**: verbatim if short, otherwise concise summary + key quotes.
- **If no description**: Note this and suggest adding context for better implementation planning.
- **Comments**: newest → oldest, `[author @ date] summary` with important snippets.
- **Epic/Story context**: Include parent issue information if available.

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
- **Always reference `.cursor/rules` for tech stack guidance and Opik-specific patterns**:
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
    - **Option 1**: Stash changes: `git stash push -m "WIP: before OPIK-{TICKET-NUMBER}"`
    - **Option 2**: Ask user what to do with uncommitted changes
  - **NEVER commit directly to main** (following Opik git workflow)
- If on `main`, create branch following Opik conventions:
  ```bash
  # Ensure you're on main and pull latest
  git checkout main
  git pull origin main
  
  # Create task-specific branch
  git checkout -b {USERNAME}/OPIK-{TICKET-NUMBER}-{TICKET-SUMMARY}
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

- **Based on Jira context and Opik cursor rules**, suggest implementing the feature/bugfix:
  - Reference relevant `.cursor/rules` for tech stack guidance (Java backend, React frontend, Python/TypeScript SDKs)
  - Provide specific implementation steps based on the task plan
  - Include code examples or file paths where appropriate
  - Suggest testing approaches and quality checks
  - Follow Opik architecture patterns (Resources → Services → DAOs → Models for backend)
- **Commit Message Format**: Always suggest commits with ticket number prefix following Opik conventions:

  **Initial Task Commits:**
  ```
  [OPIK-####] [BE/FE/SDK/DOCS] <description>
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
  [OPIK-1234] [BE] Add create trace endpoint
  [OPIK-1234] [FE] Add project custom metrics UI dashboard
  [OPIK-1234] [DOCS] Update API documentation
  [OPIK-1234] [SDK] Add new Python SDK method
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
  - Commit changes with proper ticket number prefix
- **If user declined**: Provide manual implementation guidance and stop

---

### 11. Completion Summary & Validation

- **Confirm all steps completed**:
  - ✅ Jira MCP available and working
  - ✅ Ticket fetched and analyzed successfully
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
2. ✅ Feature branch is created and active following Opik naming convention
3. ✅ Ticket status is updated (if requested)
4. ✅ Implementation suggestion provided based on context and Opik rules
5. ✅ User confirmation received (proceed or decline)
6. ✅ Implementation executed (if confirmed) or manual guidance provided (if declined)
7. ✅ All operations complete without errors
8. ✅ Clear next steps are provided to the user

---

## Troubleshooting

### **Common Issues**

- **Jira MCP not available**: Run `npm run install-mcp`
- **Git branch conflicts**: Resolve conflicts before proceeding
- **Permission errors**: Check user access and project settings
- **Network issues**: Verify connectivity to Atlassian services

### **Fallback Options**

- If ticket fetch fails: List user's assigned tickets
- If branch creation fails: Provide manual git commands
- If status update fails: Continue with development setup

---

**End Command**
