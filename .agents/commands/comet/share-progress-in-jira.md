# Share Progress in Jira

**Command**: `cursor share-progress-in-jira`

## Overview

Extract the Jira ticket number from the current Git branch name, generate a diff of all commits since branching from main (excluding generated files), summarize the changes, and add a progress comment to the corresponding Jira ticket.

This workflow will:

- Verify Jira MCP availability
- Extract Jira ticket number from current branch name
- Generate git diff between current branch and main
- Summarize the changes in a human-readable format
- Add a progress comment to the Jira ticket
- Provide clear success/failure feedback

---

## Inputs

- **None required**: Automatically uses current Git branch and working directory

---

## Steps

### 1. Preflight & Environment Check

- **Check Jira MCP**: Test Jira MCP availability by attempting to fetch user info using `atlassianUserInfo`
  > If unavailable, respond with: "This command needs the Jira MCP server. Please enable it, then run: `npm run install-mcp`."  
  > Stop here.
- **Check Git repository**: Verify we're in a Git repository
- **Check current branch**: Ensure we're not on `main`
- **Validate branch format**: Confirm branch follows pattern: `<username>/OPIK-<ticket-number>-<kebab-short-description>` or `<username>/issue-<number>-<description>` or `<username>/NA-<description>`

---

### 2. Extract Jira Ticket Information

- **Parse branch name**: Extract `OPIK-<number>` or `issue-<number>` from current branch
- **Validate format**: Ensure ticket number follows expected pattern
- **If invalid format**: Show error and stop:
  > "Branch name doesn't match expected format: `<username>/OPIK-<ticket-number>-<kebab-short-description>` or `<username>/issue-<number>-<description>` or `<username>/NA-<description>`"
  > Current branch: `<actual-branch-name>`

---

### 3. Fetch Jira Ticket

- **Extract key**: Use the `OPIK-<number>` or `issue-<number>` from branch name
- **Fetch ticket**: Use Jira MCP to get ticket details
- **If fetch fails**: Show error message and stop
- **If not found**: Show error and stop:
  > "Jira ticket `OPIK-<number>` not found. Please verify the ticket exists and you have access."

---

### 4. Generate Git Diff

- **Get diff base**: Find the commit where current branch diverged from main
- **Generate diff**: Create diff between current branch and main (all commits since branching)
- **Important**: Use `git diff main...HEAD` (three dots) to compare the tip of current branch with the common ancestor, NOT `git diff main..HEAD` (two dots) which compares the tip of main to the tip of HEAD and shows all changes on HEAD that are not on main
- **Exclude files**: Filter out generated files like `package-lock.json`, `target/`, `node_modules/`, etc.
- **Commands to run**:

  ```bash
  # Get all file changes since branching from main (excludes generated files)
  git diff main...HEAD --name-only | grep -v -E "(package-lock\.json|target/|node_modules/|\.git/)"

  # Get detailed diff statistics for file analysis
  git diff main...HEAD --stat

  # Get commit history for implementation context
  git log --oneline main...HEAD

  # Optional: Get detailed commit messages for better context
  git log --pretty=format:"%h - %s (%an, %ar)" main...HEAD
  ```

- **If no changes**: Show message and stop:
  > "No changes found between current branch and main. Branch may be up to date or no commits made yet."

---

### 5. Analyze and Summarize Changes

- **Parse diff files**: Analyze all added, modified, and deleted files since branching from main
- **Parse commit history**: Review commit messages to understand implementation context and progress
- **Extract meaningful context**: Identify what was actually implemented, improved, or fixed
- **Categorize changes**: Group by file type and implementation phases based on Opik project structure:
  - **Backend changes**: Java files, SQL migrations, configuration files
  - **Frontend changes**: TypeScript/React files, UI components, styles
  - **SDK changes**: Python/TypeScript SDK files
  - **Documentation**: README files, API docs, configuration guides
  - **Testing**: Test files, test configurations
  - **Infrastructure**: Docker files, deployment configs, CI/CD files
- **Generate summary**: Create meaningful context for stakeholders based on the actual changes made
- **Summary quality**: Focus on what was accomplished, not technical file details
- **Format summary**: Use the standard 2-section format:

  ```
  **Release Notes:**
  [User-facing changes and features - what Product Managers need to know]
  • [User-visible feature/improvement 1]
  • [User-visible feature/improvement 2]
  • [API changes or new endpoints]
  • [Configuration or environment changes]

  If no user-facing changes: "No user-facing changes were made in this ticket."

  **Technical Details:**
  [Developer-focused information - technical details for the development team]
  • [Implementation detail 1]
  • [Technical improvement 2]
  • [Configuration/setup change 3]
  • [Testing coverage added]
  • [Dependencies updated]
  ```

---

### 6. Add Comment to Jira Ticket

- **Create comment**: Use Jira MCP to add the progress summary
- **Verify success**: Confirm comment was added successfully
- **If comment fails**: Show error details and stop

---

### 7. Completion Summary & Validation

- **Confirm all steps completed**:
  - ✅ Jira MCP available and working
  - ✅ Branch name parsed successfully
  - ✅ Jira ticket found and accessible
  - ✅ Git diff generated and analyzed
  - ✅ Progress summary created
  - ✅ Comment added to Jira ticket
- **Show summary**: Display what was shared and where
- **Next steps**: Suggest when to use this command again

---

## Error Handling

### **Branch Name Errors**

- Invalid format: Show expected pattern and current branch
- On main: Explain this command is for feature branches only

### **Jira MCP Failures**

- Connection issues: Check network and authentication
- Permission errors: Verify user access to the ticket
- Rate limiting: Wait and retry

### **Git Operation Failures**

- Not in Git repo: Navigate to correct directory
- No commits: Explain branch needs commits to generate diff
- Diff generation fails: Check Git status and resolve conflicts

### **Comment Addition Failures**

- Permission denied: Verify user can comment on ticket
- Invalid content: Check comment format and length
- Network issues: Verify connectivity to Atlassian services

---

## Success Criteria

The command is successful when:

1. ✅ Jira MCP is available and working
2. ✅ Branch name is parsed and ticket number extracted
3. ✅ Jira ticket is found and accessible
4. ✅ Git diff is generated successfully
5. ✅ Changes are summarized using 2-section format: Release Notes (for PMs) and Technical Details (for developers)
6. ✅ Progress comment is added to Jira ticket
7. ✅ All operations complete without errors

---

## Troubleshooting

### **Common Issues**

- **Jira MCP not available**: Run `npm run install-mcp`
- **Branch name format**: Ensure it follows Opik conventions:
  - `username/OPIK-<number>-<description>` for Jira tickets
  - `username/issue-<number>-<description>` for GitHub issues
  - `username/NA-<description>` for tasks without tickets
- **No changes found**: Make commits to your branch first
- **Permission errors**: Check Jira access and ticket permissions

### **Fallback Options**

- If diff generation fails: Check Git status and resolve conflicts
- If comment addition fails: Copy summary manually to Jira
- If ticket not found: Verify ticket number and access permissions

---

## Integration with Existing Workflow

This command complements the `work-on-jira-ticket` command by:

- **Progress tracking**: Document development progress in Jira
- **Change visibility**: Keep stakeholders informed of implementation details
- **Documentation**: Maintain a record of what was built and when
- **Review preparation**: Prepare summaries for code reviews and PRs

---

## Notes

- **MCP Testing**: Actively tests Jira MCP availability using `atlassianUserInfo` before proceeding
- **Git Diff Accuracy**: Uses three-dot syntax (`git diff main...HEAD`) for accurate change detection
- **Summary Focus**: Emphasizes stakeholder value over technical implementation details
- **Error Handling**: Stops immediately on critical failures to prevent incomplete operations
- **Format Flexibility**: Adapts summary structure based on the type of work being done

---

**End Command**
