---
name: jira-ticket
description: Create Jira tickets conversationally with flexible detail levels
---

# Jira Ticket Creation

Create Jira tickets for the OPIK project through natural conversation. This skill supports both quick ticket creation with minimal information and detailed tickets with full specifications.

## Core Principles

1. **Conversational & Flexible**: Proactively ask clarifying questions, but allow users to provide information naturally
2. **Partial Tickets Allowed**: Users can create tickets with minimal info if they prefer - always confirm before creating
3. **Quick Creation Supported**: Support fast workflows like "Create a ticket for fixing the login bug"
4. **Auto-detect Project**: Default to OPIK project unless context suggests otherwise
5. **Rich Responses**: Use Slack Block Kit to format ticket previews and confirmations

## Information to Gather

### Required (Minimum for Quick Creation)
- **Summary/Title**: What the ticket is about
- **Issue Type**: Story, Task, Bug, or Epic (can be inferred from context)

### Recommended (Ask if not provided)
- **Description**: What needs to be done and why
- **Priority**: Low, Medium, High, Highest (default: Medium)
- **Labels**: Tags like `frontend`, `backend`, `sdk`, `playground`, `traces`, `ux-improvement`
- **Assignee**: (Optional) Who should work on this

### Detailed (Only if user wants comprehensive ticket)
- **User Story**: As a [user], I want [goal] so that [benefit]
- **User Journey**: Step-by-step interaction flow
- **Requirements**: Functional and non-functional requirements
- **Acceptance Criteria**: Checklist of completion criteria

## Conversation Flow

### Quick Creation Mode
When user says something like: "Create a ticket for [brief description]"

1. Acknowledge and extract what you can
2. Ask 1-2 clarifying questions if needed (type, priority)
3. Show preview with what will be created
4. Confirm before creating

**Example:**
```
User: "Create a ticket for fixing the broken trace export"

You: "I'll create a bug ticket for the trace export issue.
     Should this be High or Medium priority?"

User: "High"

You: [Show preview card]
     "Ready to create this ticket?"
```

### Detailed Creation Mode
When user wants a comprehensive ticket:

1. Ask: "What feature or issue are you creating a ticket for?"
2. Ask: "Can you describe what needs to be done and why?"
3. Offer template: "Would you like me to include sections for user story, requirements, and acceptance criteria? (I can show you a template suggestion)"
4. If yes, guide through template sections
5. Ask about priority, labels, and assignee
6. Show preview and confirm

## Jira Description Template (Suggest When Appropriate)

When user wants a detailed ticket, suggest this template structure:

```
h2. Description

[Brief overview of what needs to be done and why]

h2. User Story

As a [type of user], I want to [action/goal] so that I can [benefit/outcome].

h2. Requirements

h3. Functional Requirements

* [Requirement 1]
* [Requirement 2]

h3. Non-Functional Requirements

* [Performance, security, scalability requirements]

h2. Acceptance Criteria

* [ ] [Criterion 1]
* [ ] [Criterion 2]
* [ ] No lint errors or TypeScript errors (if applicable)
* [ ] Unit tests added (if applicable)
* [ ] Documentation updated (if applicable)
```

**Note**: Only suggest the full template if the user indicates they want detailed specifications. For quick tickets, a simple description paragraph is fine.

## Creating the Ticket

Use the Jira MCP tool: `mcp__Jira-Headless-CI__jira_create_issue`

```python
mcp__Jira-Headless-CI__jira_create_issue(
    project_key="OPIK",
    summary="[PREFIX] Clear, concise title",
    issue_type="Story|Task|Bug|Epic",
    description="Formatted description (use Jira wiki markup if detailed)",
    additional_fields={
        "priority": {"name": "Medium"},
        "labels": ["label1", "label2"]
    }
)
```

### Title Prefixes
Use these prefixes based on work area:
- `[FE]` - Frontend changes
- `[BE]` - Backend changes
- `[SDK]` - SDK changes (Python or TypeScript)
- `[DOCS]` - Documentation updates
- `[INFRA]` - Infrastructure/DevOps changes

### Description Formatting
- **Quick tickets**: Use plain text or simple markdown
- **Detailed tickets**: Use Jira wiki markup:
  - `h2.` for headers (e.g., `h2. Description`)
  - `h3.` for subheaders
  - `*bold*` for bold text
  - `* ` for bullet points
  - `* [ ]` for checkboxes in acceptance criteria

## Response Formatting

### Preview (Before Creation)
Show a rich Slack card with the ticket details using Block Kit:

```json
{
  "ollie_response": {
    "format": "blocks",
    "text": "Ready to create Jira ticket",
    "blocks": [
      {
        "type": "header",
        "text": {"type": "plain_text", "text": "🎫 Ticket Preview"}
      },
      {
        "type": "section",
        "fields": [
          {"type": "mrkdwn", "text": "*Type*\nStory"},
          {"type": "mrkdwn", "text": "*Priority*\nHigh"}
        ]
      },
      {
        "type": "section",
        "text": {"type": "mrkdwn", "text": "*Summary*\n[FE] Add trace export functionality"}
      },
      {
        "type": "section",
        "text": {"type": "mrkdwn", "text": "*Description*\n[Description preview]"}
      },
      {
        "type": "section",
        "text": {"type": "mrkdwn", "text": "*Labels*\nfrontend, traces"}
      },
      {
        "type": "divider"
      },
      {
        "type": "context",
        "elements": [
          {"type": "mrkdwn", "text": "Reply 'yes' to create or suggest changes"}
        ]
      }
    ]
  }
}
```

### Success (After Creation)
Show a rich card with the created ticket:

```json
{
  "ollie_response": {
    "format": "blocks",
    "text": "Jira ticket created successfully",
    "blocks": [
      {
        "type": "header",
        "text": {"type": "plain_text", "text": "✅ Ticket Created"}
      },
      {
        "type": "section",
        "text": {"type": "mrkdwn", "text": "*<https://your-jira.atlassian.net/browse/OPIK-123|OPIK-123>*\n[FE] Add trace export functionality"}
      },
      {
        "type": "section",
        "fields": [
          {"type": "mrkdwn", "text": "*Type*\nStory"},
          {"type": "mrkdwn", "text": "*Priority*\nHigh"},
          {"type": "mrkdwn", "text": "*Status*\nTo Do"},
          {"type": "mrkdwn", "text": "*Assignee*\nUnassigned"}
        ]
      },
      {
        "type": "context",
        "elements": [
          {"type": "mrkdwn", "text": "🎯 Ticket is ready for grooming"}
        ]
      }
    ]
  }
}
```

## Project Detection

Default to `OPIK` unless:
- User explicitly mentions another project
- Channel context indicates a different project
- Ask if uncertain

## Special Features (Only When Requested)

### Epic Linking
If user mentions linking to an epic:
```python
additional_fields={
    "parent": "OPIK-123"  # Epic key
}
```

### Attachment Support
If user wants to attach screenshots or files:
- First create the ticket
- Then use `jira_update_issue` with attachments parameter
- Guide user through the process

### Batch Creation
If user wants to create multiple related tickets:
- Offer to use `jira_batch_create_issues`
- Help structure the batch request
- Show preview of all tickets before creating

## Example Interactions

### Quick Ticket
```
User: "Create a ticket for the login redirect bug"

Mollie: "I'll create a bug ticket for the login redirect issue.
        A few quick questions:
        - Is this High or Medium priority?
        - Any specific labels? (I'm thinking 'frontend' and 'authentication')"

User: "High priority, those labels work"

Mollie: [Shows preview card]

User: "Yes, create it"

Mollie: [Shows success card with OPIK-456 link]
```

### Detailed Ticket
```
User: "I need to create a comprehensive ticket for the new dashboard widget"

Mollie: "I'll help you create a detailed ticket. Let's start:
        What's the main goal of this dashboard widget?"

User: "Users should be able to see their top 5 most recent traces"

Mollie: "Great! Would you like me to include structured sections for:
        - User story
        - Requirements
        - Acceptance criteria
        I can show you a template suggestion."

User: "Yes please"

Mollie: [Shows template and guides through filling it out]
```

## Notes

- Always confirm before creating (show preview)
- Use Jira wiki markup for descriptions: `h2.` for headers, `*` for bullets, `* [ ]` for checkboxes
- Project key is almost always `OPIK`
- Be flexible - adapt to user's preferred level of detail
- Make it feel conversational, not like filling out a form
