# Generate Code Review Slack Command

**Command**: `cursor generate-code-review-slack-command`

## Overview

Generate a formatted, copiable Slack command for the #code-review channel with PR information, Jira ticket, test environment link, and optional component summaries (FE, BE, Python, TypeScript). Automatically extracts information from the GitHub PR for the current branch and outputs a command that can be copied, edited (to add @ mentions, media links, etc.), and pasted into Slack.

- **Execution model**: Automatically extracts information from GitHub PR, prompts only for missing information, formats the message according to the template, and outputs a copiable Slack command (does NOT send automatically).

This workflow will:

- Find the GitHub PR for the current branch
- Extract Jira ticket from PR title
- Extract test environment link from PR description
- Extract component summaries (FE, BE, Python, TypeScript) from PR description
- Prompt only for missing information
- Allow user to customize the message slightly
- Format the message according to the code review template
- Generate a copiable Slack command that can be edited before sending
- Display the command for easy copying

---

## Inputs

### Required Information (auto-extracted from PR, prompted if missing)
- **Jira ticket**: Extracted from PR title (format: `[OPIK-1234]`) or prompted if not found
- **PR link**: Automatically determined from current branch's PR or prompted if no PR found
- **Test env link**: Extracted from PR description (Testing or Details section) or prompted if not found

### Optional Information (auto-extracted from PR, prompted if missing)
- **FE summary**: Extracted from PR description (looks for frontend/React/FE mentions) or prompted if not found
- **BE summary**: Extracted from PR description (looks for backend/Java/BE mentions) or prompted if not found
- **Python summary**: Extracted from PR description (looks for Python/Python SDK mentions) or prompted if not found
- **TypeScript summary**: Extracted from PR description (looks for TypeScript/TypeScript SDK/TS mentions) or prompted if not found
- **Baz approved status**: Extracted from PR status checks (optional, may not be available)

### Configuration
- **No Slack MCP required**: This command does not send messages, so Slack MCP configuration is not needed
- **GitHub MCP**: Required for extracting PR information

---

## Steps

### 1. Preflight & Environment Check

- **Check GitHub MCP**: Test GitHub MCP availability by attempting to fetch repository info using `get_file_contents` for `comet-ml/opik`
  > If unavailable, respond with: "This command needs GitHub MCP configured. Set MCP config/env, run `make cursor` (Cursor) or `make claude` (Claude CLI), then retry."  
  > Stop here.
- **Check Git repository**: Verify we're in a Git repository
- **Check current branch**: Get current branch name

---

### 2. Find GitHub PR for Current Branch

- **Search for PR**: Use GitHub MCP to find an open PR for the current branch in `comet-ml/opik`
- **If PR found**: 
  - Extract PR number, URL, title, and description
  - Store PR information for extraction steps
- **If no PR found**: 
  > "No open PR found for this branch. Please provide the PR link manually:"
  - Prompt for PR link and validate URL format
  - If provided, fetch PR details using GitHub MCP
  - If PR cannot be fetched, stop with error message
  - **After successfully fetching PR by manual link**: Extract PR number, URL, title, and description and store PR information for extraction steps (same as "PR found" branch)

---

### 3. Extract Information from PR

- **Extract Jira ticket from PR title**:
  - Parse PR title for pattern `[OPIK-\d+]` or `[issue-\d+]` or `[NA]`
  - Extract ticket number (e.g., `OPIK-1234` from `[OPIK-1234] [BE] feat(api): add trace request validation endpoint`)
  - If not found in title, check PR description "## Issues" section for `OPIK-\d+` pattern
  - If still not found, prompt: "Jira ticket not found in PR. Enter Jira ticket number (e.g., OPIK-1234):"
  - Validate format and store
  - **Build Jira URL**: Construct Jira link as `https://comet-ml.atlassian.net/browse/{TICKET}` (e.g., `https://comet-ml.atlassian.net/browse/OPIK-1234`)

- **Extract Baz approved status (optional)**:
  - Try to fetch PR status checks using GitHub MCP
  - Look for status checks or CI checks that might indicate "Baz approved" or similar approval status
  - If found, store the status (e.g., "Baz approved: ✅" or "Baz status: pending")
  - If not found or unavailable, skip this field (it's optional)

- **Extract test environment link from PR description and comments**:
  - First, search PR comments for test environment deployment messages (look for "Test environment is now available!" or similar)
  - Extract URL from comment body (typically in format `https://pr-XXXX.dev.comet.com` or `https://test.opik.com`)
  - If not found in comments, search PR description for URLs in "## Testing" section
  - Look for common test environment patterns: `https://pr-*.dev.comet.com`, `https://test.opik.com`, `https://*.opik.com`, or any `https://` URL
  - If multiple URLs found, prefer the first one that looks like a test environment
  - If not found, search in "## Details" section for URLs
  - If still not found, prompt: "Test environment link not found in PR. Enter test environment link (e.g., https://pr-4743.dev.comet.com):"
  - Validate URL format and store

- **Extract component summaries from PR description**:
  - **FE summary**: 
    - Look for mentions of "frontend", "FE", "React", "UI", or `[FE]` tag in PR description
    - Extract relevant one-line summary from "## Details" section or component-specific mentions
    - If not found, prompt: "Frontend summary not found in PR. Enter frontend summary (one line, optional - press Enter to skip):"
  
  - **BE summary**: 
    - Look for mentions of "backend", "BE", "Java", "API", or `[BE]` tag in PR description
    - Extract relevant one-line summary from "## Details" section or component-specific mentions
    - If not found, prompt: "Backend summary not found in PR. Enter backend summary (one line, optional - press Enter to skip):"
  
  - **Python summary**:
    - Look for mentions of "Python", "Python SDK", "SDK", or Python-related changes in PR description
    - Extract relevant one-line summary from "## Details" section or component-specific mentions
    - If not found, prompt: "Python summary not found in PR. Enter Python summary (one line, optional - press Enter to skip):"

  - **TypeScript summary**:
    - Look for mentions of "TypeScript", "TypeScript SDK", "TS", "TS SDK", or `[TS]` tag in PR description
    - Extract relevant one-line summary from "## Details" section or component-specific mentions
    - If not found, prompt: "TypeScript summary not found in PR. Enter TypeScript summary (one line, optional - press Enter to skip):"

- **Store extracted information**: Keep all extracted and prompted values for message formatting

---

### 4. Prompt for Message Customization

- **Allow user to customize message**: After extracting all information, prompt the user:
  > "Would you like to customize the message? (Enter any additional text to prepend/append, or press Enter to use default message):"
- **If user provides customization text**: Store it for inclusion in the final message
- **If user presses Enter (empty)**: Proceed with default message format
- **Note**: User can add context, emphasize certain points, or include additional information

---

### 5. Format Slack Message

- **Use PR link**: Use the PR URL extracted from Step 2 (or provided manually)

- **Build message text** according to the template:
  ```
  Hi team,

  Please review the following PR:
  
  {{user_customization_text_if_provided}}
  
  :jira_epic: jira link: {{Jira_URL}}
  :github: pr link: {{PR_link}}
  :test_tube: test env link: {{test_env}}
  {{baz_approved_status_if_available}}
  :react: fe summary (optional): {{description_in_one_line}}
  :java: be summary (optional): {{description_in_one_line}}
  :python: python summary (optional): {{description_in_one_line}}
  :typescript: typescript summary (optional): {{description_in_one_line}}
  ```

- **Message structure**:
  - **Start with greeting**: Always begin with "Hi team,\n\nPlease review the following PR:\n"
  - **User customization**: If user provided customization text in Step 4, include it after the greeting (before the structured fields)
  - **Jira link**: Include full Jira URL with ticket (e.g., `https://comet-ml.atlassian.net/browse/OPIK-1234`)
  - **PR link**: Include GitHub PR URL
  - **Test env link**: Include test environment URL
  - **Baz approved status**: Only include if extracted from PR status checks (optional field)
  - **Component summaries**: Only include optional fields that were provided (skip empty ones)

- **Format example**:
  ```
  Hi team,

  Please review the following PR:
  
  :jira_epic: jira link: https://comet-ml.atlassian.net/browse/OPIK-1234
  :github: pr link: https://github.com/comet-ml/opik/pull/1234
  :test_tube: test env link: https://test.opik.com
  :react: fe summary (optional): Added new metrics dashboard UI
  :java: be summary (optional): Implemented metrics aggregation endpoint
  :typescript: typescript summary (optional): Added TypeScript SDK support for metrics
  ```

---

### 6. Generate Copiable Slack Command

- **Format as Slack command**: Generate a copiable command that can be pasted directly into Slack
- **Command format**: The output should be formatted as a code block that can be easily copied
- **Display instructions**: Show clear instructions on how to use the generated command:
  > "📋 **Copiable Slack Command Generated**\n\nCopy the command below and paste it into the #code-review channel in Slack.\n\nYou can edit it before sending to:\n- Add @ mentions for specific reviewers\n- Add media links or video links\n- Make final proof edits\n- Add any additional context\n\n```\n[FORMATTED_MESSAGE]\n```\n\n**To send in Slack:**\n1. Open Slack and navigate to #code-review channel\n2. Paste the command above\n3. Edit as needed (add @ mentions, media links, etc.)\n4. Send the message"

- **Alternative format (if using Slack CLI)**: If the user prefers, also provide a Slack CLI command format:
  ```
  slack chat send --channel "#code-review" --text "[FORMATTED_MESSAGE]"
  ```

---

### 7. Display & Summary

- **Display the generated command**: Show the formatted message in a code block for easy copying
- **Provide usage instructions**: Remind user how to use the command
- **Note about editing**: Emphasize that the command can be edited before sending to add @ mentions, media links, or make final edits

---

## Error Handling

### **GitHub MCP Errors**

- **GitHub MCP unavailable**: Stop immediately after testing and provide setup instructions
- **PR not found**: Prompt for manual PR link or stop if user cancels
- **PR fetch failure**: Show error details and suggest manual input

### **Extraction Errors**

- **Jira ticket not found**: Prompt user for ticket number
- **Test env link not found**: Prompt user for test environment URL
- **Component summaries not found**: Prompt user for summaries (optional)

### **Input Validation Errors**

- **Invalid Jira ticket format**: Show expected format (e.g., `OPIK-1234`) and re-prompt
- **Invalid PR URL**: Show expected format and re-prompt
- **Invalid test env URL**: Show expected format and re-prompt

---

## Success Criteria

The command is successful when:

1. ✅ GitHub MCP is available and accessible
2. ✅ PR is found for current branch (or manually provided)
3. ✅ Jira ticket is extracted from PR or provided manually
4. ✅ Test environment link is extracted from PR or provided manually
5. ✅ Component summaries are extracted from PR or provided manually (optional)
6. ✅ Message is formatted according to template (with greeting and Jira link)
7. ✅ Copiable Slack command is generated and displayed
8. ✅ User receives clear instructions on how to use the command

---

## Notes

- **GitHub MCP Required**: Uses GitHub MCP to fetch PR information automatically
- **No Slack MCP Required**: This command does not send messages, so Slack MCP configuration is not needed
- **Message format**: Follows the exact template provided with emoji prefixes, includes greeting and Jira link
- **Automatic extraction**: Extracts information from PR title and description to minimize manual input
- **Fallback to prompts**: Only prompts for information that cannot be extracted from PR
- **Optional fields**: Only included in message if extracted from PR or provided by user
- **Channel**: Message is intended for `#code-review` channel
- **PR detection**: Automatically finds PR for current branch, falls back to manual input if needed
- **Smart extraction**: Uses heuristics to find test environment links and component summaries in PR description
- **Editing before sending**: The generated command can be edited to add @ mentions, media links, video links, or make final proof edits before sending in Slack
- **Use case**: This command is particularly useful when you want to:
  - Add @ mentions for specific reviewers
  - Include video links or media that Slack MCP cannot send directly
  - Make final proof edits before sending
  - Have more control over the final message format
- **Alternative for automatic sending**: If you don't need manual editing, use `cursor send-code-review-slack` to send the message directly to Slack.

---

## Example Usage

### Setup and Usage

```bash
# 1. Ensure GitHub MCP is configured (no Slack MCP needed)

# 2. Run command (on a branch with an open PR)
cursor generate-code-review-slack-command
```

# Command execution flow:
# 1. Find PR for current branch: https://github.com/comet-ml/opik/pull/1234
# 2. Extract Jira ticket from PR title: [OPIK-1234] [FE] feat(api): add metrics dashboard
# 3. Extract test env from PR comments or description: https://pr-1234.dev.comet.com (from PR comments or Testing section)
# 4. Extract summaries from PR description:
#    - FE: Added new metrics dashboard UI (from Details section)
#    - BE: Implemented metrics aggregation endpoint (from Details section)
#    - Python: (not found, prompts user)
#    - TypeScript: (not found, prompts user)
# 5. Prompt for message customization (optional)
# 6. Format message according to template (with greeting and Jira link)
# 7. Generate and display copiable Slack command

# Output:
# 📋 **Copiable Slack Command Generated**
# 
# Copy the command below and paste it into the #code-review channel in Slack.
# 
# You can edit it before sending to:
# - Add @ mentions for specific reviewers
# - Add media links or video links
# - Make final proof edits
# - Add any additional context
# 
# ```
# Hi team,
# 
# Please review the following PR:
# 
# :jira_epic: jira link: https://comet-ml.atlassian.net/browse/OPIK-1234
# :github: pr link: https://github.com/comet-ml/opik/pull/1234
# :test_tube: test env link: https://test.opik.com
# :react: fe summary (optional): Added new metrics dashboard UI
# :java: be summary (optional): Implemented metrics aggregation endpoint
# :typescript: typescript summary (optional): Added TypeScript SDK support for metrics
# ```
# 
# **To send in Slack:**
# 1. Open Slack and navigate to #code-review channel
# 2. Paste the command above
# 3. Edit as needed (add @ mentions, media links, etc.)
# 4. Send the message

### Example with Missing Information

If some information cannot be extracted from PR, the command will prompt:

```bash
cursor generate-code-review-slack-command

# Found PR: https://github.com/comet-ml/opik/pull/1234
# Extracted Jira ticket: OPIK-1234
# Test environment link not found in PR. Enter test environment link (e.g., https://test.opik.com): https://test.opik.com
# Frontend summary not found in PR. Enter frontend summary (one line, optional - press Enter to skip): [Enter pressed - skipped]
# Backend summary not found in PR. Enter backend summary (one line, optional - press Enter to skip): Implemented metrics endpoint
# Python summary not found in PR. Enter Python summary (one line, optional - press Enter to skip): [Enter pressed - skipped]
# TypeScript summary not found in PR. Enter TypeScript summary (one line, optional - press Enter to skip): [Enter pressed - skipped]
# Would you like to customize the message? (Enter any additional text to prepend/append, or press Enter to use default message): [Enter pressed - using default]
```

### Example with Customization

```bash
cursor generate-code-review-slack-command

# ... extraction steps ...
# Would you like to customize the message? (Enter any additional text to prepend/append, or press Enter to use default message): This PR includes important security updates, please review carefully.
# 
# Generated command includes the customization:
# Hi team,
# 
# Please review the following PR:
# 
# This PR includes important security updates, please review carefully.
# 
# :jira_epic: jira link: https://comet-ml.atlassian.net/browse/OPIK-1234
# ...
```

---

**End Command**
