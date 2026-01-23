# Create Jira Ticket

Create a new Jira ticket in the OPIK project following the standard template structure.

## Instructions

When the user wants to create a Jira ticket, gather the following information through conversation and then use the `mcp_Jira_home_jira_create_issue` tool to create the ticket.

## Required Information to Gather

1. **Summary/Title**: A concise title for the ticket (e.g., "[FE] Add feature X" or "[BE] Implement Y endpoint")
2. **Issue Type**: Story, Task, Bug, or Epic
3. **Priority**: Low, Medium, High, or Highest
4. **Labels**: Relevant labels (e.g., frontend, backend, sdk, playground, traces, ux-improvement)
5. **Assignee**: (Optional) Who should work on this

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
10. Ask: "Should this be assigned to anyone?"

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
    "labels": ["label1", "label2"]
  }
)
```

## Title Prefixes

Use these prefixes in the summary based on the work area:
- `[FE]` - Frontend changes
- `[BE]` - Backend changes
- `[SDK]` - SDK changes (Python or TypeScript)
- `[DOCS]` - Documentation updates
- `[INFRA]` - Infrastructure/DevOps changes

## Notes

- The project key is always `OPIK`
- Description uses Jira wiki markup (h2. for headers, * for bullets, *bold* for bold)
- Acceptance criteria should be checkboxes using `* [ ]` syntax
- Always include standard acceptance criteria like "No lint errors" and "Tests added" where applicable
