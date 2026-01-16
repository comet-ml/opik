# Send Code Review Slack Message

**Command**: `cursor send-code-review-slack`

## Overview

Send a formatted Slack message to the #opik-code-review channel with PR information, Jira ticket, test environment link, and optional component summaries (FE, BE, Python). Automatically extracts information from the GitHub PR for the current branch.

- **Execution model**: Automatically extracts information from GitHub PR, prompts only for missing information, formats the message according to the template, and sends it via Slack MCP.

This workflow will:

- Find the GitHub PR for the current branch
- Extract Jira ticket from PR title
- Extract test environment link from PR description
- Extract component summaries (FE, BE, Python) from PR description
- Prompt only for missing information
- Format the message according to the code review template
- Send the message to #opik-code-review channel via Slack MCP (`ghcr.io/korotovsky/slack-mcp-server`)
- Verify successful delivery

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

### Configuration
- **Slack MCP**: Required - uses custom Slack MCP server (`ghcr.io/korotovsky/slack-mcp-server`)
  - Uses `SLACK_MCP_XOXP_TOKEN` (User OAuth Token) to post messages as your authenticated user account
  - See `.cursor/SLACK_MCP_SETUP.md` for complete setup instructions
  - Requires Docker to be installed and running

---

## Steps

### 1. Preflight & Environment Check

- **Check GitHub MCP**: Test GitHub MCP availability by attempting to fetch repository info using `get_file_contents` for `comet-ml/opik`
  > If unavailable, respond with: "This command needs the GitHub MCP server. Please enable it, then run: `npm run install-mcp`."  
  > Stop here.
- **Check Git repository**: Verify we're in a Git repository
- **Check current branch**: Get current branch name
- **Check Slack MCP availability**: 
  - Use Slack MCP tool `channels_list` to test availability (from `ghcr.io/korotovsky/slack-mcp-server`)
  - Call with minimal parameters (for example: `limit: 1`) so that both public and private channels can be returned
  - This custom MCP server uses `SLACK_MCP_XOXP_TOKEN` (User OAuth Token) to post messages as your authenticated user account
  - The server must be configured with `mcp-server --transport stdio` in the Docker args and have the `conversations_add_message` tool enabled (e.g., via `SLACK_MCP_ADD_MESSAGE_TOOL=true`) so the final send step can succeed
  - If Slack MCP tool is available and callable: Proceed with message sending via MCP (messages will post as user)
  - If Slack MCP is not available or tool call fails: 
    > "Slack MCP is not available. Please configure Slack MCP according to `.cursor/SLACK_MCP_SETUP.md` and restart Cursor IDE."
    > Stop here.

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
  - Extract ticket number (e.g., `OPIK-1234` from `[OPIK-1234] [BE] Add endpoint`)
  - If not found in title, check PR description "## Issues" section for `OPIK-\d+` pattern
  - If still not found, prompt: "Jira ticket not found in PR. Enter Jira ticket number (e.g., OPIK-1234):"
  - Validate format and store

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

- **Store extracted information**: Keep all extracted and prompted values for message formatting

---

### 4. Format Slack Message

- **Use PR link**: Use the PR URL extracted from Step 2 (or provided manually)

- **Build message text** according to the template:
  ```
  :jira_epic: jira ticket: {{Jira-ticket}}
  :github: pr link: {{PR_link}}
  :test_tube: test env link: {{test_env}}
  :react: fe summary (optional): {{description_in_one_line}}
  :java: be summary (optional): {{description_in_one_line}}
  :python: python summary (optional): {{description_in_one_line}}
  ```

- **Only include optional fields** that were provided (skip empty ones)
- **Format example**:
  ```
  :jira_epic: jira ticket: OPIK-1234
  :github: pr link: https://github.com/comet-ml/opik/pull/1234
  :test_tube: test env link https://test.opik.com
  :react: fe summary (optional): Added new metrics dashboard UI
  :java: be summary (optional): Implemented metrics aggregation endpoint
  ```

---

### 5. Send Slack Message

- **Use Slack MCP tool `conversations_add_message`** from `ghcr.io/korotovsky/slack-mcp-server`
- This custom MCP server uses `SLACK_MCP_XOXP_TOKEN` (User OAuth Token) configured according to `.cursor/SLACK_MCP_SETUP.md`
- The server must be configured with:
  - `mcp-server --transport stdio` in the Docker args (required for MCP protocol)
  - `SLACK_MCP_ADD_MESSAGE_TOOL=true` environment variable (required to enable the tool - disabled by default for safety)
- Call the tool with the following parameters:
  - `channel_id`: `#opik-code-review` (channel name starting with # or channel ID)
  - `payload`: The formatted message text (the complete message with all fields)
  - `content_type`: `text/markdown` (optional, defaults to text/markdown)
- **Important**: Messages will be posted as your authenticated user account (not as a bot)
- Handle MCP response:
  - If successful: Show success message with message timestamp/ID if provided
  - If failed: Show error message and stop
  - Common errors:
    - **Tool disabled error**: The `conversations_add_message` tool is disabled by default. Add `SLACK_MCP_ADD_MESSAGE_TOOL=true` to Docker args and restart Cursor (see `.cursor/SLACK_MCP_SETUP.md`)
    - Authentication errors: Check `SLACK_MCP_XOXP_TOKEN` configuration (should start with `xoxp-`) - see `.cursor/SLACK_MCP_SETUP.md`
    - Channel not found: Verify channel name `#opik-code-review` exists and you have access
    - Permission errors: Verify `chat:write` scope is configured in User Token Scopes (see `.cursor/SLACK_MCP_SETUP.md`)
    - Docker errors: Ensure Docker is running and can pull the image `ghcr.io/korotovsky/slack-mcp-server:latest`
    - MCP not configured: Verify Slack MCP is properly configured according to `.cursor/SLACK_MCP_SETUP.md` and Cursor IDE has been restarted

---

### 6. Verification & Summary

- **Display confirmation**: 
  > "✅ Slack message sent successfully to #opik-code-review channel"
  
- **Show message preview**: Display the formatted message that was sent
- **Provide next steps**: Remind user to check Slack channel for delivery

---

## Error Handling

### **Slack MCP Errors**

- **Slack MCP unavailable**: 
  - Check if Slack MCP server is properly configured according to `.cursor/SLACK_MCP_SETUP.md`
  - Verify Docker is installed and running (`docker --version`)
  - Verify the Docker image can be pulled: `docker pull ghcr.io/korotovsky/slack-mcp-server:latest`
  - Restart Cursor IDE after configuring MCP
- **Slack MCP tool not found**: 
  - Verify `conversations_add_message` tool is available from `ghcr.io/korotovsky/slack-mcp-server`
  - Ensure `mcp-server --transport stdio` is included in Docker args (see `.cursor/SLACK_MCP_SETUP.md`)
  - Ensure `SLACK_MCP_ADD_MESSAGE_TOOL=true` is set in Docker args (see `.cursor/SLACK_MCP_SETUP.md`)
  - Check Cursor Settings > Features > MCP for error messages
  - Ensure MCP server is running and properly configured
  - Check Docker logs if the container fails to start
- **MCP authentication errors**: 
  - Check `SLACK_MCP_XOXP_TOKEN` configuration (should start with `xoxp-` for user token) - see `.cursor/SLACK_MCP_SETUP.md`
  - Verify token is not expired
  - Reinstall Slack app to workspace if needed
  - Ensure token has `chat:write` scope in **User Token Scopes** (not Bot Token Scopes) - see `.cursor/SLACK_MCP_SETUP.md`
- **Docker errors**: 
  - Ensure Docker is running: `docker ps`
  - Check if Docker can pull the image: `docker pull ghcr.io/korotovsky/slack-mcp-server:latest`
  - Verify Docker has network access to reach Slack API
- **Channel access errors**: 
  - Verify channel name `#opik-code-review` exists
  - Ensure you have `chat:write` scope in User Token Scopes (see `.cursor/SLACK_MCP_SETUP.md`)
  - Check that you are a member of the channel (join it if needed)
  - Try using channel ID instead of name if issues persist

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
3. ✅ Slack MCP is available and configured with `SLACK_MCP_XOXP_TOKEN` (User OAuth Token)
4. ✅ Jira ticket is extracted from PR or provided manually
5. ✅ Test environment link is extracted from PR or provided manually
6. ✅ Component summaries are extracted from PR or provided manually (optional)
7. ✅ Message is formatted according to template
8. ✅ Slack message is sent successfully via `conversations_add_message` MCP tool (posts as user account)
9. ✅ User receives confirmation of successful delivery

---

## Notes

- **GitHub MCP Required**: Uses GitHub MCP to fetch PR information automatically
- **Slack MCP Required**: Uses custom Slack MCP server (`ghcr.io/korotovsky/slack-mcp-server`) for sending messages
  - Configure according to `.cursor/SLACK_MCP_SETUP.md`
  - Uses `SLACK_MCP_XOXP_TOKEN` (User OAuth Token) - messages are posted as your authenticated user account
  - Uses `conversations_add_message` tool with `channel_id` and `payload` parameters
  - Uses `channels_list` tool to verify MCP availability
  - Requires `mcp-server --transport stdio` in Docker args for proper MCP protocol communication
  - Requires `SLACK_MCP_ADD_MESSAGE_TOOL=true` to enable the tool
  - Requires Docker to be installed and running
  - Token starts with `xoxp-` and requires `chat:write` scope in User Token Scopes
- **Message format**: Follows the exact template provided with emoji prefixes
- **Automatic extraction**: Extracts information from PR title and description to minimize manual input
- **Fallback to prompts**: Only prompts for information that cannot be extracted from PR
- **Optional fields**: Only included in message if extracted from PR or provided by user
- **Channel**: Message is always sent to `#opik-code-review` channel
- **PR detection**: Automatically finds PR for current branch, falls back to manual input if needed
- **Smart extraction**: Uses heuristics to find test environment links and component summaries in PR description
- **MCP Configuration**: Slack MCP must be configured before using this command (see `.cursor/SLACK_MCP_SETUP.md` for detailed setup)

---

## Example Usage

### Setup and Usage

```bash
# 1. Configure Slack MCP according to .cursor/SLACK_MCP_SETUP.md
#    - Follow the complete setup guide for configuring the Slack MCP server
#    - Add your User OAuth Token (xoxp-...) to the Docker args
#    - Ensure 'mcp-server --transport stdio' is included in Docker args
#    - Ensure 'SLACK_MCP_ADD_MESSAGE_TOOL=true' is set to enable the tool

# 2. Restart Cursor IDE to load the MCP configuration

# 3. Run command (on a branch with an open PR)
cursor send-code-review-slack
```


# Command execution flow:
# 1. Find PR for current branch: https://github.com/comet-ml/opik/pull/1234
# 2. Extract Jira ticket from PR title: [OPIK-1234] [BE] [FE] Add metrics dashboard
# 3. Extract test env from PR comments or description: https://pr-1234.dev.comet.com (from PR comments or Testing section)
# 4. Extract summaries from PR description:
#    - FE: Added new metrics dashboard UI (from Details section)
#    - BE: Implemented metrics aggregation endpoint (from Details section)
#    - Python: (not found, prompts user)
# 5. Format message according to template
# 6. Send formatted message to Slack via conversations_add_message MCP tool (posts as user account)

# Output:
# ✅ Slack message sent successfully to #opik-code-review channel
# 
# Message sent:
# :jira_epic: jira ticket: OPIK-1234
# :github: pr link: https://github.com/comet-ml/opik/pull/1234
# :test_tube: test env link: https://test.opik.com
# :react: fe summary (optional): Added new metrics dashboard UI
# :java: be summary (optional): Implemented metrics aggregation endpoint
```

### Example with Missing Information

If some information cannot be extracted from PR, the command will prompt:

```bash
cursor send-code-review-slack

# Found PR: https://github.com/comet-ml/opik/pull/1234
# Extracted Jira ticket: OPIK-1234
# Test environment link not found in PR. Enter test environment link (e.g., https://test.opik.com): https://test.opik.com
# Frontend summary not found in PR. Enter frontend summary (one line, optional - press Enter to skip): [Enter pressed - skipped]
# Backend summary not found in PR. Enter backend summary (one line, optional - press Enter to skip): Implemented metrics endpoint
# Python summary not found in PR. Enter Python summary (one line, optional - press Enter to skip): [Enter pressed - skipped]
```

---

**End Command**

