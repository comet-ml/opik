# Create PR

**Command**: `cursor create-pr`

## Overview

Create a GitHub Pull Request for the current working branch with automatic Jira integration, quality checks, and template pre-filling. This command ensures code quality and proper workflow integration before creating PRs.

- **Execution model**: Always runs from scratch. Each invocation re-checks MCP availability, re-validates the branch, re-evaluates git status, re-runs quality checks, and re-generates summary/template content regardless of prior runs.

This workflow will:

- Validate the current branch follows Opik feature branch naming conventions
- Extract Jira ticket number from branch name
- Check for pending changes and remote branch status
- Run quality checks to ensure code quality
- Pre-fill PR template with extracted information
- Create GitHub draft PR using GitHub CLI
- Update Jira ticket status to "In Review"
- Document progress directly in Jira using the same analysis logic as `share-progress-in-jira`

---

## Inputs

- **None required**: Automatically uses current Git branch and working directory

---

## Steps

### 1. Preflight & Environment Check

- **Check Jira MCP**: Test Jira MCP availability by attempting to fetch user info using `atlassianUserInfo`
  > If unavailable, respond with: "This command needs the Jira MCP server. Please enable it, then run: `npm run install-mcp`."  
  > Stop here.
- **Check GitHub CLI**: Ensure `gh` is installed and authenticated (`gh auth status`)
  > If unavailable, respond with: "This command needs GitHub CLI auth. Install `gh`, run `gh auth login`, then rerun the command."  
  > Stop here.
- **Check Git repository**: Verify we're in a Git repository
- **Check current branch**: Ensure we're not on `main`
- **Tool Validation**: Jira MCP and GitHub CLI auth must be available before proceeding

---

### 2. Validate Feature Branch

- **Parse branch name**: Extract `OPIK-<number>` from current branch following Opik naming convention
- **Validate format**: Ensure ticket number can be extracted from format `{USERNAME}/{TICKET-NUMBER}-{TICKET-SUMMARY}`
- **If invalid format**: Show error and stop:
  > "Branch name doesn't follow Opik naming convention. Expected format: `{USERNAME}/{TICKET-NUMBER}-{TICKET-SUMMARY}`"  
  > Examples: `andrescrz/OPIK-2180-add-cursor-git-workflow-rule`, `someuser/issue-1234-some-task`, `someotheruser/NA-some-other-task`  
  > Current branch: `<actual-branch-name>`

---

### 3. Check Git Status

- **Commit pending changes (auto, with meaningful message)**: If the working directory is dirty, the command will stage changes and the Agent will generate a descriptive commit message that summarizes what was introduced (akin to `share-progress-in-jira`), then commit. Avoid file counts/line stats.

  ```bash
  # Stage everything
  git add -A

  # Agent: Generate commit message with Opik ticket prefix format:
  # [OPIK-####] [COMPONENT] <description>
  #
  # <detailed description>
  #
  # Implements OPIK-####: <ticket summary>
  #
  # Example: "[OPIK-2180] [DOCS] Add cursor git workflow rule"
  # Then commit (only if there are staged changes)
  if ! git diff --cached --quiet; then
    git commit -m "[OPIK-####] [COMPONENT] <AGENT_GENERATED_DESCRIPTION>"
  fi
  ```

- **Ensure remote branch exists (auto)**: Push local commits so the branch is on origin
  ```bash
  git push -u origin HEAD
  ```
- **Sync with `main` (base branch) (auto)**: Ensure the working branch is up to date before proceeding
  - Fetch latest refs and check divergence
    ```bash
    git fetch origin
    git rev-list --left-right --count origin/main...HEAD
    ```
  - If the branch is behind `main`, the command will perform a rebase. If rebase conflicts occur, it stops and reports them.
    ```bash
    git rebase origin/main
    # If conflicts arise, resolve them and continue with: git rebase --continue
    ```
  - After syncing, push updates:
    ```bash
    git push --force-with-lease   # after rebase
    ```

---

### 4. Check for Existing PRs

- **Search existing PRs**: Use GitHub CLI to check if there's already an open PR for this branch in `comet-ml/opik` (for example `gh pr list --head <branch> --state open`)
- **If PR exists**: Show existing PR and ask:
  > "PR already exists for this branch: <PR_URL>. Do you want to update the PR description and continue with the flow (quality checks, Jira status, progress comment)? (y/n)"
  - **If yes**: Update PR description using the pre-filled template (Step 7 output), then continue with Steps 5–10 as usual
  - **If no**: Stop the flow
- **If no PR exists**: Continue to PR creation

---

### 5. Run Quality Checks

- **Execute quality checks**: Run the following commands in sequence based on the project type:
  ```bash
  # For Java backend projects
  mvn compile -DskipTests
  mvn test
  mvn spotless:check
  
  # For frontend projects
  npm run lint
  npm run typecheck
  ```
- **If all pass**: Continue to next step
- **If errors found**: Run auto-fix commands, then re-verify:
  ```bash
  # For Java backend projects
  mvn spotless:apply
  mvn compile -DskipTests
  mvn test
  
  # For frontend projects
  npm run lint:fix
  npm run lint
  npm run typecheck
  ```
- **If quality checks still fail**: Show errors and ask if user wants to continue
- **If user chooses to continue**: Proceed with warnings
- **If user chooses to stop**: Stop the flow

---

### 6. Extract Change Information

- **Generate git diff**: Use `git diff origin/main...HEAD` to get all changes since branching from the latest remote main
- **Analyze changes**: Categorize by file type and implementation phases
- **Check feature toggles**: Specifically analyze configuration files for added/removed feature toggles
- **Extract commit history**: Review commit messages for context
- **Generate summary**: Create meaningful description of what was implemented

---

### 7. Pre-fill PR Template

- **Title**: Format as `[{TICKET-NUMBER}] [{COMPONENT}] {TASK-SUMMARY}` extracted from branch description and change analysis
  - Examples: `[OPIK-2180] [DOCS] Add cursor git workflow rule`, `[OPIK-1234] [BE] Add create trace endpoint`
- **Description**: Fill using the Opik PR template format from `/.github/pull_request_template.md`. Unless specifically requested, **never** include customer names in the description of the PR as they should not be public:
  ```markdown
  ## Details
  {implementation_summary_from_git_analysis}
  
  ## Change checklist
  <!-- Please check the type of changes made -->
  - [ ] User facing
  - [ ] Documentation update
  
  ## Issues
  - Resolves # <!-- the GitHub issue this PR resolves (e.g. `#1234`) -->
  - OPIK-{ticket_number} <!-- The Jira ticket (e.g. `OPIK-1234`) -->
  - NA <!-- If no ticket, such as hotfixes etc. -->
  
  ## Testing
  {testing_scenarios_covered_by_tests_and_steps_to_reproduce}
  
  ## Documentation
  {list_of_docs_updated_or_summary_of_new_configuration_introduced_or_links_to_web_documentation_reference_relevant_to_this_PR}
  ```
- **Template Fields**:
  - **Details**: Implementation summary from git analysis
  - **Change checklist**: Auto-check based on file types changed (user-facing for UI changes, documentation for docs)
  - **Issues**: Link to Jira ticket (e.g., OPIK-2180) or GitHub issue, or "NA" for hotfixes
  - **Testing**: Extract from commit messages or set based on test files changed
  - **Documentation**: List docs updated or set "N/A" if no documentation changes

---

### 8. Create GitHub PR

- **Use GitHub CLI (preferred)**: Create a draft PR in `comet-ml/opik` with pre-filled template (`gh pr create --draft`)
- **Verify creation**: Confirm PR was created successfully
- **If creation fails**: Show error details and stop

---

### 9. Update Jira Status

- **Fetch Jira ticket**: Use Jira MCP to get ticket details
- **Transition status**: Change ticket status to "In Review"
- **Verify transition**: Confirm status was updated successfully
- **If transition fails**: Show error details but continue
- **Post progress summary**: After successful status update, add a progress comment directly to Jira using `addCommentToJiraIssue` with the standard 2-section format:
  - **Release Notes**: User-facing changes for Product Managers, including feature toggle changes (or "No user-facing changes were made in this ticket" if none)
  - **Docs**: Developer-focused technical details and implementation notes

---

### 10. Completion Summary & Validation

- **Confirm all steps completed**:
  - ✅ Feature branch validated with Opik naming convention
  - ✅ Git status checked and resolved
  - ✅ No existing PRs found
  - ✅ Quality checks passed
  - ✅ PR template pre-filled
  - ✅ GitHub PR created successfully
  - ✅ Jira ticket status updated to "In Review"
  - ✅ Progress documented in Jira
- **Show summary**: Display PR URL and Jira ticket status
- **Next steps**: Provide guidance on PR review process

---

## Error Handling

### **Availability Errors**

- **Jira MCP unavailable**: Stop immediately after testing and provide setup instructions
- **GitHub CLI unavailable/auth missing**: Stop immediately and provide `gh` installation/auth instructions
- **Jira MCP connection failures**: Stop immediately after testing and verify MCP server status
- **Jira MCP test failures**: Stop immediately if MCP tests fail and provide troubleshooting steps

### **Branch Validation Errors**

- Invalid format: Show expected Opik pattern and current branch
- On main: Explain this command is for feature branches only
- No OPIK ticket number: Stop and explain the requirement

### **Git Status Errors**

- Uncommitted changes: Ask user for decision
- Remote branch missing or behind: Ask user for decision
- Push failures: Check remote configuration and permissions

### **Quality Check Failures**

- Linting errors: Show errors and ask user preference
- Type errors: Show errors and ask user preference
- User choice to stop: Respect user decision

### **PR Creation Failures**

- GitHub CLI issues: Check `gh auth status` and repository permissions for `comet-ml/opik`
- Template errors: Validate template format and content
- Network issues: Verify connectivity to GitHub

### **Jira Status Update Failures**

- Transition not allowed: Check workflow permissions
- Ticket not found: Verify ticket exists and is accessible
- Network issues: Verify connectivity to Atlassian services

### **Progress Documentation Failures**

- Comment addition fails: Log error but continue (non-critical operation)
- Provide manual instructions for adding progress comment

---

## Success Criteria

The command is successful when:

1. ✅ Jira MCP is available and accessible
2. ✅ GitHub CLI is available and authenticated
3. ✅ Feature branch is validated with OPIK ticket number
4. ✅ Git status is clean (no pending changes)
5. ✅ Remote branch exists and is up to date
6. ✅ No existing PRs found for the branch
7. ✅ Quality checks pass (linter, type checking, or Maven)
8. ✅ PR template is pre-filled with meaningful content
9. ✅ GitHub PR is created successfully
10. ✅ Jira ticket status is updated to "In Review"
11. ✅ Progress is documented in Jira (via direct MCP call)
12. ✅ All operations complete with clear feedback

---

## Notes

- **Tool Requirements**: Jira MCP and GitHub CLI auth must be available before any operations begin
- **Repository**: Always uses `comet-ml/opik` for GitHub operations
- **Template pre-filling**: Automatically extracts information from git changes and commit history
- **Quality assurance**: Ensures code meets standards before PR creation
- **Workflow integration**: Seamlessly connects Git, GitHub, and Jira workflows following Opik conventions
- **Progress documentation**: Directly adds progress comment to Jira using MCP (bypasses problematic cursor commands)
- **User control**: Asks for confirmation on critical decisions (commits, pushes)
- **Error handling**: Graceful degradation when non-critical operations fail
- **Git operations**: Handles both missing remote branches and branches that are behind local commits
- **MCP testing**: Actively tests MCP availability using real API calls before proceeding
- **Opik conventions**: Follows Opik branch naming, commit message, and PR title conventions
- **Logic reuse**: Implements the same git diff analysis and summary generation logic as `share-progress-in-jira` command:
  - Uses three-dot syntax (`git diff origin/main...HEAD`) for accurate change detection against latest remote main
  - Categorizes changes by file type and implementation phases
  - Uses standard 2-section format: Release Notes (for PMs) and Docs (for developers)
  - Generates professional, formatted summaries with bullet points

---

**End Command**
