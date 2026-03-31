# Create Jira Ticket

Create a new Jira ticket in the OPIK project following the standard template structure.

## Instructions

When the user wants to create a Jira ticket, gather the following information through conversation and then use the `mcp_Jira_home_jira_create_issue` tool to create the ticket.

## Required Information to Gather

1. **Summary/Title**: A concise title for the ticket (e.g., "[FE] Add feature X" or "[BE] Implement Y endpoint")
2. **Issue Type**: Story, Task, Bug, or Epic
3. **Priority**: Low, Medium, High, or Highest
4. **Labels**: Relevant labels (e.g., frontend, backend, sdk, playground, traces, ux-improvement)
5. **Story Points**: Fibonacci scale (1, 2, 3, 5, 8, 13). If the user doesn't provide one, guesstimate based on the ticket's scope and complexity, and present your estimate for confirmation. **If the estimate is 21 or higher, do NOT create the ticket.** Instead, suggest splitting the work into 2+ smaller tickets so that each one is ≤ 13 story points. Help plan the split before proceeding.
6. **Sprint**: Ask whether to add to the **active sprint** or **next sprint**. Use sprints with the "Opik Sprint" prefix. To find available sprints, use `mcp_Jira_home_jira_get_sprints_from_board` with board ID `524`.
7. **Due Date**: Ask whether to set a due date: today, tomorrow, a week from today, or leave unset. Format as `YYYY-MM-DD`.
8. **Assignee**: (Optional) Who should work on this

## Parent Epic (Required for Task/Story)

Every Task or Story **must** have a parent epic. Follow this logic:

1. **If the user explicitly specifies an epic** in the prompt (e.g., "under OPIK-1234"), use that.
2. **If the ticket is clearly tech debt** (refactoring, cleanup, paying down debt, removing workarounds, etc.), automatically use **OPIK-670** (Tech debt). No need to ask.
3. **Otherwise**, you must help the user pick an epic:
   - Use `mcp_Jira_home_jira_search` to find open epics in the OPIK project (JQL: `project = OPIK AND issuetype = Epic AND status != Done ORDER BY updated DESC`).
   - Present the results as a table with columns: **#** (number for selection), **Key**, **Summary**, **Status**.
   - Based on the ticket's description, suggest which epic seems like the best fit.
   - Include a final option: **"Skip for now"** — if the user picks this, create the ticket without a parent.
   - Use `AskUserQuestion` to let the user pick by number.

Set the parent via `"parent": "OPIK-XXXX"` in `additional_fields`.

## Template Sections to Fill

Ask the user to provide details for each section, then format the description using the template below.

### Description Template

```
h2. Description

[Brief overview of what needs to be done and why. Explain the context and motivation for this work.]

h2. User Story

As a [type of user], I want to [action/goal] so that I can [benefit/outcome].

h2. User Journey

[Describe the step-by-step flow of how a user will interact with this feature:]
1. User navigates to [location]
2. User clicks/interacts with [element]
3. System responds by [action]
4. User sees [result]
[Continue as needed...]

h2. Requirements

h3. Functional Requirements

[List the functional requirements - what the system should DO]

*1. [Requirement Category]*
* [Specific requirement]
* [Specific requirement]

*2. [Requirement Category]*
* [Specific requirement]
* [Specific requirement]

h3. Non-Functional Requirements

[List non-functional requirements - performance, security, scalability, etc.]
* [Requirement]
* [Requirement]

h2. Acceptance Criteria

[Checklist of criteria that must be met for the ticket to be considered complete]
* [ ] [Criterion 1]
* [ ] [Criterion 2]
* [ ] [Criterion 3]
* [ ] No lint errors or TypeScript errors (if applicable)
* [ ] Unit tests added (if applicable)
* [ ] Documentation updated (if applicable)
```

## Example Conversation Flow

1. Ask: "What feature or task would you like to create a ticket for?"
2. Ask: "Can you describe the user story? (As a [user], I want to [goal] so that [benefit])"
3. Ask: "What is the user journey? How will users interact with this feature step by step?"
4. Ask: "What are the functional requirements? What should the system do?"
5. Ask: "Are there any non-functional requirements? (performance, security, etc.)"
6. Ask: "What are the acceptance criteria? How do we know when this is done?"
7. Ask: "What issue type is this? (Story/Task/Bug/Epic)"
8. Ask: "What priority? (Low/Medium/High/Highest)"
9. Ask: "Any labels to add? (e.g., frontend, backend, sdk)"
10. Present your **Story Points** guesstimate and ask for confirmation (or let user override)
11. **Parent Epic**: If not already determined (tech debt or explicit), search for open epics, present a table, suggest the best fit, and let the user pick or skip.
12. Ask: "Add to the **active sprint** or the **next sprint**?"
13. Ask: "Set a due date? (today / tomorrow / a week from today / leave unset)"
14. Ask: "Should this be assigned to anyone?"

## Creating the Ticket

Once all information is gathered, use the Jira MCP tool:

```
mcp_Jira_home_jira_create_issue(
  project_key="OPIK",
  summary="[PREFIX] Title of the ticket",
  issue_type="Story|Task|Bug|Epic",
  description="[Formatted description using template above]",
  additional_fields={
    "priority": {"name": "Medium"},
    "labels": ["label1", "label2"],
    "customfield_10028": <fibonacci_number>,
    "customfield_10020": <sprint_id>,
    "duedate": "YYYY-MM-DD",
    "parent": "OPIK-670"  // only for tech debt tickets without another epic
  }
)
```

### Field Reference
- **Story Points**: use `customfield_10028` directly (the `story_points` alias does NOT work at creation time)
- **Sprint**: use `customfield_10020` with the sprint ID (a plain number). Look up sprints via `mcp_Jira_home_jira_get_sprints_from_board` (board ID `524`), filter by "Opik Sprint" prefix, and pick the active or next future sprint based on user choice.
- **Due Date**: use `duedate` with format `YYYY-MM-DD`. Calculate relative to today's date.
- **Parent**: use `"parent": "OPIK-XXX"` (plain string, not an object) when creating under an epic.

## Post-Creation: Status Transition

After creating the ticket:
1. Wait briefly, then use `mcp_Jira_home_jira_get_issue` to check the ticket's status. A Jira automation will move it to **BACKLOG** status automatically.
2. Once the status is confirmed as **BACKLOG**, **you MUST use the `AskUserQuestion` tool** (not inline text) to prompt: "The ticket is now in Backlog. Would you like me to move it to **TO DO**?"
3. If the user confirms, use `mcp_Jira_home_jira_transition_issue` to move it to "TO DO".

## Title Prefixes

Use these prefixes in the summary based on the work area:
- `[FE]` - Frontend changes
- `[BE]` - Backend changes
- `[SDK]` - SDK changes (Python or TypeScript)
- `[DOCS]` - Documentation updates
- `[INFRA]` - Infrastructure/DevOps changes

## Confirmation Output

After successfully creating a ticket, always display the ticket key as a **clickable markdown link** in the confirmation message:

```
[OPIK-{number}](https://comet-ml.atlassian.net/browse/OPIK-{number})
```

For example, if the created ticket key is `OPIK-5316`, display:
```
[OPIK-5316](https://comet-ml.atlassian.net/browse/OPIK-5316)
```

Never display the ticket key as plain text — always wrap it in a markdown link to the Jira ticket URL.

## Notes

- The project key is always `OPIK`
- Description uses Jira wiki markup (h2. for headers, * for bullets, *bold* for bold)
- Acceptance criteria should be checkboxes using `* [ ]` syntax
- Always include standard acceptance criteria like "No lint errors" and "Tests added" where applicable
