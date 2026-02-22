# Share Diagram

**Command**: `cursor share-diagram`

## Overview

Analyze the current branch's diff and Jira ticket context, then generate a self-contained HTML architecture diagram at `diagrams/opik-{TICKET_NUMBER}-diagram.html`.

- **Execution model**: Stateless. Each invocation re-analyzes the current branch diff and Jira context, then generates a fresh diagram.

This workflow will:

- Extract Jira ticket context from the current branch
- Gather the git diff (PR diff if available, otherwise branch diff from main)
- Analyze changes to understand data flow, architecture, and design decisions
- Generate a self-contained HTML diagram using the `diagram-generation` skill
- Save the diagram to `diagrams/` (gitignored, local artifact)
- Provide the file path for the user to open in a browser

---

## Inputs

- **Ticket number (optional)**: Override auto-detection from branch name. E.g., `OPIK-4501`
- **Focus (optional)**: What aspect to emphasize — `flow`, `architecture`, `files`, `decisions`, or `all` (default: `all`)

---

## Steps

### 1. Preflight & Environment Check

- **Check Jira MCP**: Test availability by attempting to fetch user info
  > If unavailable: "This command needs the Jira MCP server. Please enable it, then run: `npm run install-mcp`."
  > Stop here.
- **Check GitHub MCP**: Test availability by attempting to fetch repository info for `comet-ml/opik`
  > If unavailable: warn but continue — diagram can be generated from local git diff alone.
- **Check git repository**: Verify we're in a git repo with commits.
- **Ensure `diagrams/` directory exists**: Create if missing.

---

### 2. Extract Context

#### Ticket Context
- **Parse branch name**: Extract `OPIK-<number>` from current branch (format: `<username>/OPIK-<number>-<description>`)
- **If on `main`**: Check if a ticket number was provided as input. If not, ask the user for one.
- **Fetch Jira ticket**: Get summary, description, issue type, status, priority, labels, comments
- **Build ticket context**: Title, type, key requirements, acceptance criteria

#### Code Changes
- **Check for PR**: Use GitHub MCP to find an open PR for the current branch on `comet-ml/opik`
  - If PR exists: use PR diff (preferred — includes review context)
  - If no PR: use `git diff main...HEAD` for local changes
- **Parse diff**: Identify files changed, grouped by component layer:
  - **API layer**: Resources, endpoints, request/response models
  - **Service layer**: Business logic, services
  - **DAO layer**: Database access, SQL, queries
  - **Model layer**: Domain models, DTOs, enums
  - **Migration layer**: Database migrations (Liquibase, ClickHouse)
  - **Test layer**: Unit tests, integration tests
  - **Frontend layer**: React components, hooks, stores
  - **SDK layer**: Python/TypeScript SDK changes
- **Identify patterns**: New files vs modified, what data flows through the change

---

### 3. Analyze & Plan Diagram Sections

Based on the changes, determine which sections to include:

| Change Type | Sections |
|------------|----------|
| **New API endpoint** | Request flow, files changed, design decisions |
| **Bug fix** | Problem/solution banners, before/after flow, safety guards |
| **New feature** | Data flow, architecture tree, files changed, design decisions |
| **Refactor** | Before/after architecture, files changed |
| **Database migration** | Storage diagram, data flow, migration notes |
| **Cross-component** | Full flow (API → Service → DAO → Storage), files by layer |

Always include **Files Changed** section. Include at most 4 sections total.

---

### 4. Generate HTML Diagram

- **Load the `diagram-generation` skill**: Use the style guide and template from `.claude/skills/diagram-generation/` (or `.agents/skills/diagram-generation/`)
- **Fill template sections**:
  - Title: `OPIK-{TICKET} — {Jira Title}`
  - Subtitle: One-line summary of the change
  - Sections: Based on analysis from step 3
- **Apply style guide**: Use semantic colors, flow boxes, section labels with dots, and all CSS patterns from the style guide
- **Include "Copy as image" button**: Using the Canvas API + Clipboard API script from the template
- **Content rules**:
  - Keep text concise — diagram is visual, not a document
  - Use `<b>` for component names inside boxes
  - Use `<br>` for secondary details in boxes
  - Use `.code` spans for inline code references
  - Use `.note` for supplementary explanations below flows
  - Max 4 sections per diagram
  - Each flow row should have 3-6 boxes max

---

### 5. Save Diagram

- **Output path**: `diagrams/opik-{TICKET_NUMBER}-diagram.html`
- **Create `diagrams/` directory** if it doesn't exist
- **Write the HTML file** using the Write tool
- **Verify file was written** by reading the first few lines back

---

### 6. Render PNG Preview (requires Playwright MCP)

Render the diagram to PNG and display it inline in chat.

**Note**: Playwright MCP blocks `file://` URLs, so serve the HTML over a local HTTP server.

1. **Start server**: `python3 -m http.server 8787` in the `diagrams/` directory (run in background)
2. **Navigate**: `browser_navigate` to `http://localhost:8787/opik-{TICKET_NUMBER}-diagram.html`
3. **Hide button**: `browser_evaluate` with `document.querySelector('.copy-btn').style.display = 'none'` — prevents the fixed-position button from appearing in the screenshot
4. **Snapshot**: `browser_snapshot` to get the element ref for `#diagram`
5. **Screenshot**: `browser_take_screenshot` targeting the `#diagram` element ref
   - Save as `diagrams/opik-{TICKET_NUMBER}-diagram.png`
6. **Close & cleanup**: `browser_close`, then kill the HTTP server process
7. **Display**: Use the `Read` tool on the PNG — it renders as an image inline in the chat

If Playwright MCP is **not available**, skip this step and fall back to showing the HTML file path.

---

### 7. Present Result

- **Show the PNG inline** if generated (step 6) — this is the primary visual output
- **Show the HTML file path**: For browser viewing and "Copy as image" clipboard use
- **Summarize sections**: Brief description of what each section covers

---

## Error Handling

### Jira MCP Failures
- Connection issues: Check network and authentication
- Ticket not found: Ask user to verify ticket number
- No description: Generate diagram from code diff alone, note the gap

### GitHub MCP Failures
- No PR found: Fall back to local `git diff main...HEAD`
- PR diff too large: Summarize by file, focus on key changes

### Git Failures
- On `main` with no ticket: Prompt for ticket number
- No commits ahead of main: Show error — nothing to diagram
- Merge conflicts: Warn but generate from available diff

### Diagram Generation Failures
- Changes too small: Generate a minimal diagram with just files changed
- Changes too large (>30 files): Group by component, show top-level flow only

---

## Success Criteria

The command is successful when:

1. Jira ticket context was fetched (or gracefully skipped)
2. Code diff was analyzed and categorized by layer
3. HTML diagram was generated with correct styling
4. "Copy as image" button works in the generated HTML
5. File was saved to `diagrams/opik-{TICKET_NUMBER}-diagram.html`
6. User was shown the file path and summary

---

## Notes

- Diagrams are **local artifacts** — the `diagrams/` folder is gitignored
- The same diagram-generation logic lives in `.claude/skills/diagram-generation/` (shared skill)
- The "Copy as image" button uses the browser Canvas API — requires opening in a modern browser
- When run without a PR, the diagram reflects local uncommitted + committed changes vs main
- Diagrams should be concise and visual — prefer boxes and flows over paragraphs of text

---

**End Command**
