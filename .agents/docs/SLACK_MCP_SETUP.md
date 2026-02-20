# Slack MCP Configuration Guide

This guide explains how to configure the **Slack MCP server** for use with Cursor IDE, specifically for the `send-code-review-slack` command.

## Slack MCP Server Setup

This setup uses the **custom Slack MCP server** (`ghcr.io/korotovsky/slack-mcp-server`) which supports **User OAuth Tokens** (`SLACK_MCP_XOXP_TOKEN`). Messages will be posted as your authenticated user account, not as a bot.

### Setup Overview

**Workspace-Level Setup (One-Time, Done by Admin):**
- **Step 1**: Create a Slack App
- **Step 2**: Configure User Token Scopes

**User-Level Setup (Per Developer):**
- **Step 3**: Install App to Workspace
- **Step 4**: Get Your User OAuth Token
- **Step 5**: Environment Variables
- **Step 6**: Restart Cursor

---

### Step 1: Create a Slack App

**Note**: This is a one-time workspace setup step, typically done by a workspace admin. Once the app is created and configured, all users in the workspace can use it.

1. Go to [Slack API Apps](https://api.slack.com/apps)
2. Click **"Create New App"** → **"From scratch"**
3. Name your app (e.g., "Opik Code Review")
4. Select your workspace
5. Click **"Create App"**

### Step 2: Configure User Token Scopes

**Note**: This is a one-time workspace setup step, typically done by a workspace admin. Once the scopes are configured, all users in the workspace will have access to these scopes when they install the app.

**CRITICAL**: You must add scopes to **"User Token Scopes"** (NOT "Bot Token Scopes") for the User OAuth Token to appear.

1. In your app settings, go to **"OAuth & Permissions"**
2. Scroll down to the **"Scopes"** section
3. Find **"User Token Scopes"** section (this is different from "Bot Token Scopes" which is above it)
   - You'll see the description: *"Scopes that access user data and act on behalf of users that authorize them."*
   - This confirms you're in the right section - these scopes allow the app to act as YOU, not as a bot
4. Click **"Add an OAuth Scope"** under "User Token Scopes"
5. Add the following scopes one by one:
   - `chat:write` - Send messages as your authenticated user account
   - `channels:read` - View basic information about public channels
   - `users:read` - Read user information (required by the MCP server for caching)
   - `channels:history` - View messages in public channels (required by the MCP server for channel caching)
6. Click **"Save Changes"**

**Note**: The `users:read` and `channels:history` scopes are required by the `ghcr.io/korotovsky/slack-mcp-server` to properly cache and access channel information. Without these, you may see "missing_scope" errors in the logs, though basic message posting may still work.

### Step 3: Install App to Workspace

**Note**: This is a per-user step. Each developer needs to install the app to their workspace to authorize it and get their own User OAuth Token.

1. Scroll to the top of **"OAuth & Permissions"**
2. Click **"Install to Workspace"** (or **"Reinstall to Workspace"** if you already installed it)
3. Review permissions and click **"Allow"**
   - **Note**: The permission screen may say "Send messages as [App Name]" - this is just the app requesting permission. When you use the User OAuth Token, messages will be posted as **your personal account**, not as the app.

### Step 4: Get Your User OAuth Token

**After installing/reinstalling the app**, you need to find your **User OAuth Token**:

1. Stay on the **"OAuth & Permissions"** page (or refresh it)
2. Scroll down to the **"OAuth Tokens for Your Workspace"** section
3. Look for **"User OAuth Token"** (NOT "Bot User OAuth Token")
   - If you only see "Bot User OAuth Token", you need to:
     - Go back to Step 2 and make sure you added scopes to **"User Token Scopes"** (not "Bot Token Scopes")
     - Then come back here and click **"Reinstall to Workspace"** again
4. The User OAuth Token should start with `xoxp-` (not `xoxb-`)
5. Click **"Show"** or **"Reveal"** to see the full token
6. **Copy this token** - this is what will post messages as your authenticated user account

### Step 5: Environment Variables

2. **Create or edit `.env.local`** in your project root and add your User OAuth Token:

```bash
SLACK_MCP_XOXP_TOKEN=xoxp-your-user-oauth-token-here
```

**Important Configuration Options:**

1. **`mcp-server --transport stdio`**: Required for the server to communicate with Cursor via the MCP protocol. Without these, the server will start an SSE server instead, which won't work with Cursor's MCP integration.

2. **`SLACK_MCP_ADD_MESSAGE_TOOL`**: Required to enable the `conversations_add_message` tool (disabled by default for safety).
   - `true` or `1`: Enable for all channels and DMs
   - Channel IDs (comma-separated): Enable only for specific channels (e.g., `XXXXXXXX,YYYYYYYY`)
   - `!XXXXXXXX`: Enable for all channels except the specified one
  - **For the code review command**: Use `true` to enable posting to `#code-review`

3. **`envFile`**: Points to `${workspaceFolder}/.env.local` where your `SLACK_MCP_XOXP_TOKEN` is stored securely.

**Important**: 
- Replace `xoxp-your-user-oauth-token-here` in `.env.local` with your **User OAuth Token** from Step 4 (starts with `xoxp-`, NOT `xoxb-`)
- The token is loaded from `.env.local` via the `envFile` configuration, keeping it out of `mcp.json`

**Note**: This configuration uses the custom Slack MCP server that supports User OAuth Tokens. Messages will be posted as your authenticated user account, not as a bot.

**Requirements**:
- Docker must be installed and running on your system
- The Docker image `ghcr.io/korotovsky/slack-mcp-server:latest` will be pulled automatically on first use
- `.env.local` should be added to `.gitignore` to prevent committing your token

### Step 6: Restart Cursor

1. Close and reopen Cursor IDE
2. The Slack MCP server should now be available with message posting enabled
3. You can verify by checking Cursor Settings > Features > MCP
4. Test by running: `cursor send-code-review-slack`

---

## Security Notes

- **Never commit tokens to Git**: 
  - Add `.env.local` to `.gitignore` to prevent committing your token
  - The `mcp.json` file references the token via environment variable, but it may contain secrets, check it before committing
  - **Token storage**: The `SLACK_MCP_XOXP_TOKEN` is stored in `.env.local` (which should be in `.gitignore`), not directly in `mcp.json`

---

## Troubleshooting

### MCP Server Not Appearing

1. Check that `mcp.json` is in the correct location (`.cursor/mcp.json` or `~/.cursor/mcp.json`)
2. Verify JSON syntax is valid (use a JSON validator)
3. **Restart Cursor completely** (quit and reopen, not just reload window)
4. Check Cursor Settings > Features > MCP for error messages
5. Verify Docker is installed and running: `docker --version` and `docker ps`

### Docker Command Errors

If you see errors like "Usage: docker [OPTIONS] COMMAND" in the MCP logs:

1. **Verify Docker is running**: 
   ```bash
   docker ps
   ```
   If this fails, start Docker Desktop or Docker daemon

2. **Test the Docker command manually**:
   ```bash
   docker run -i --rm -e SLACK_MCP_XOXP_TOKEN=xoxp-your-token -e LOG_LEVEL=error ghcr.io/korotovsky/slack-mcp-server:latest
   ```
   This should start the MCP server. If it fails, check the error message.

3. **Pull the Docker image**:
   ```bash
   docker pull ghcr.io/korotovsky/slack-mcp-server:latest
   ```

4. **Check mcp.json structure**: Ensure the `args` array is properly formatted with each argument as a separate string element

### Authentication Errors

1. **Verify your user token is correct** (should start with `xoxp-`, NOT `xoxb-`)
   - If you see `xoxb-` in the logs, you're using a bot token instead of a user token
   - Go back to Step 4 and get the **User OAuth Token** (not Bot User OAuth Token)
2. Ensure the app has been installed to your workspace
3. Verify user token scopes include all required scopes in **User Token Scopes**:
   - `chat:write` (required for posting messages)
   - `channels:read` (required for channel access)
   - `users:read` (required by MCP server for caching)
   - `channels:history` (recommended for full functionality)
4. Make sure you're using `SLACK_MCP_XOXP_TOKEN` in your Docker args (not `SLACK_BOT_TOKEN`)
5. **Check your actual mcp.json file** - the token in the Docker args should be `xoxp-...`
6. **Restart Cursor completely** after updating the token in mcp.json
7. Verify Docker is running and can pull the image

### Missing Scope Errors

If you see "missing_scope" errors in the MCP logs:

1. **Check which scope is missing** - the error message will indicate the specific scope
2. **Add the missing scope** to your Slack app's **User Token Scopes**:
   - Go to your Slack app's "OAuth & Permissions" page
   - Scroll to "User Token Scopes"
   - Click "Add an OAuth Scope" and add the missing scope
   - Common missing scopes: `users:read`, `channels:history`
3. **Reinstall the app** to your workspace after adding scopes
4. **Update the token** in `mcp.json` if a new token was generated
5. **Restart Cursor** to reload the MCP configuration

**Note**: Some "missing_scope" errors may be warnings that don't prevent basic functionality (like posting messages), but adding all recommended scopes ensures full MCP server functionality.

### Permission Errors

1. Ensure the user token has `chat:write` scope in **User Token Scopes**
2. Verify you have access to the `#code-review` channel
3. Check that you're a member of the channel
4. If using Docker, ensure Docker has network access to reach Slack API

---

## Additional Resources

- [Model Context Protocol Documentation](https://modelcontextprotocol.io/)
- [Slack API Documentation](https://api.slack.com/)
- [Custom Slack MCP Server](https://github.com/korotovsky/slack-mcp-server)
