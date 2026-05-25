# Create Jira Ticket

Create a new Jira ticket in the OPIK project following the standard template structure.

## Instructions

When the user wants to create a Jira ticket, gather the following information through conversation and then use the `mcp__Jira__home___jira_create_issue` tool to create the ticket.

## Required Information to Gather

1. **Summary/Title**: A concise title that communicates the **WHAT** on its own. Someone opening the ticket should understand what it does from the title alone, without needing to read the description. The title is a one-line version of the WHAT section in the description — the two must agree on scope. Use the area prefix (e.g., "[FE] Add feature X" or "[BE] Implement Y endpoint").
2. **Issue Type**: Story, Task, Bug, or Epic
3. **Priority**: Low, Medium, High, or Highest
4. **Labels**: Relevant labels (e.g., frontend, backend, sdk, playground, traces, ux-improvement)
5. **Story Points**: Fibonacci scale (1, 2, 3, 5, 8, 13). If the user doesn't provide one, guesstimate based on the ticket's scope and complexity, and present your estimate for confirmation. **If the estimate is 21 or higher, do NOT create the ticket.** Instead, suggest splitting the work into 2+ smaller tickets so that each one is ≤ 13 story points. Help plan the split before proceeding.
6. **Sprint**: Ask whether to add to the **active sprint** or **next sprint**. Use sprints with the "Opik Sprint" prefix. To find available sprints, use `mcp__Jira__home___jira_get_sprints_from_board` with board ID `524`.
7. **Due Date**: Ask whether to set a due date: today, tomorrow, a week from today, or leave unset. Format as `YYYY-MM-DD`.
8. **Assignee**: (Optional) Who should work on this

## Parent Epic (Required for Task/Story)

Every Task or Story **must** have a parent epic. Follow this logic:

1. **If the user explicitly specifies an epic** in the prompt (e.g., "under OPIK-1234"), use that.
2. **If the ticket is clearly tech debt** (refactoring, cleanup, paying down debt, removing workarounds, etc.), automatically use **OPIK-670** (Tech debt). No need to ask.
3. **Otherwise**, you must help the user pick an epic:
   - Use `mcp__Jira__home___jira_search` to find open epics in the OPIK project (JQL: `project = OPIK AND issuetype = Epic AND status != Done ORDER BY updated DESC`).
   - Present the results as a table with columns: **#** (number for selection), **Key**, **Summary**, **Status**.
   - Based on the ticket's description, suggest which epic seems like the best fit.
   - Include a final option: **"Skip for now"** — if the user picks this, create the ticket without a parent.
   - Use `AskUserQuestion` to let the user pick by number.

Set the parent via `"parent": "OPIK-XXXX"` in `additional_fields`.

## Assignee Pod Label

After an assignee is chosen, also add that assignee's `pod-<name>` label alongside any other labels on the ticket.

- Known pods: `pod-whale`, `pod-frontier`, `pod-andromeda`, `pod-air`, `pod-iberi`.
- If you already know the assignee's pod (e.g., from memory, prior tickets in the same area, or the user's own profile), add the matching `pod-<name>` label automatically — no need to ask.
- **If uncertain, use `AskUserQuestion` to let the user pick the pod** from the list above. Include a final "Skip (no pod label)" option.
- If no assignee is set, skip this step — do not add a pod label.
- Never add more than one `pod-*` label.

## Description Structure: WHY and WHAT

The ticket description contains exactly two sections: **WHY** and **WHAT**. Implementation details ("HOW") do NOT go in the description — they go in a separate Jira comment after the ticket is created (see "Post-Creation: HOW Comment" below).

The intent of this split:

- **WHY** answers: why does this ticket need to exist? After reading WHY, a reader understands the motivation.
- **WHAT** answers: what needs to be done, at a level QA can derive test cases from. Acceptance criteria live here.
- **HOW** (separate comment) answers: low-level implementation pointers. Optional, may go stale, intentionally not authoritative.

**The WHAT and the title must agree.** The ticket summary is a one-line version of the WHAT. After drafting the description, re-read the title and the first sentence of the WHAT side by side — if they describe different things, fix one of them. The WHAT shouldn't introduce scope the title doesn't promise, and the title shouldn't promise scope the WHAT doesn't cover.

### Description Template

```
## WHY

[Why this ticket needs to exist. The motivation, the problem being solved, the context the implementer would otherwise miss. 2-6 sentences usually. Long enough to be clear, short enough that a reviewer doesn't skim past it.]

## WHAT

[High-level description of what needs to be implemented. Phrased so QA can derive test cases. Must describe the same thing the ticket title promises — title and WHAT are two views of the same scope. Avoid implementation specifics here — those belong in the HOW comment.]

### Functional Requirements (optional)

[Include when the WHAT has more than a couple of distinct behaviors worth enumerating, or when the acceptance criteria alone won't carry the full picture for a reader. Skip for simple tickets where the WHAT prose already says everything.]

- [What the system should DO]
- [Another behavior]

### Non-Functional Requirements (optional)

[Include when the ticket has performance, security, scalability, accessibility, or compatibility constraints that don't naturally fit in acceptance criteria. Skip when there are none worth calling out.]

- [Performance / security / scalability / accessibility / compatibility constraint]

### Acceptance Criteria

- [ ] [Criterion 1 — observable behavior or outcome]
- [ ] [Criterion 2]
- [ ] [Criterion 3]
- [ ] No lint errors or TypeScript errors (if applicable)
- [ ] Unit tests added (if applicable)
- [ ] Documentation updated (if applicable)

### Out of Scope (optional)

- [Things the reader might assume are in scope but aren't]
```

## Example Conversation Flow

1. Ask: "What's this ticket about? (one or two sentences — what needs to happen and why)"
2. From that, draft a **WHY** (motivation) and a **WHAT** (high-level description + acceptance criteria). Show your draft and ask for corrections before continuing.
3. Ask: "What issue type is this? (Story/Task/Bug/Epic)"
4. Ask: "What priority? (Low/Medium/High/Highest)"
5. Ask: "Any labels to add? (e.g., frontend, backend, sdk)"
6. Present your **Story Points** guesstimate and ask for confirmation (or let user override)
7. **Parent Epic**: If not already determined (tech debt or explicit), search for open epics, present a table, suggest the best fit, and let the user pick or skip.
8. Ask: "Add to the **active sprint** or the **next sprint**?"
9. Ask: "Set a due date? (today / tomorrow / a week from today / leave unset)"
10. Ask: "Should this be assigned to anyone?"
11. If an assignee is chosen, determine their pod and add the corresponding `pod-<name>` label. If uncertain, ask the user to pick from the known pods (see **Assignee Pod Label**).

## Creating the Ticket

Once all information is gathered, use the Jira MCP tool:

```
mcp__Jira__home___jira_create_issue(
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
- **Sprint**: use `customfield_10020` with the sprint ID (a plain number). Look up sprints via `mcp__Jira__home___jira_get_sprints_from_board` (board ID `524`), filter by "Opik Sprint" prefix, and pick the active or next future sprint based on user choice.
- **Due Date**: use `duedate` with format `YYYY-MM-DD`. Calculate relative to today's date.
- **Parent**: use `"parent": "OPIK-XXX"` (plain string, not an object) when creating under an epic.

## Post-Creation: Status Transition

After creating the ticket:
1. Wait briefly, then use `mcp__Jira__home___jira_get_issue` to check the ticket's status. A Jira automation will move it to **BACKLOG** status automatically.
2. Once the status is confirmed as **BACKLOG**, **you MUST use the `AskUserQuestion` tool** (not inline text) to prompt: "The ticket is now in Backlog. Would you like me to move it to **TO DO**?"
3. If the user confirms, use `mcp__Jira__home___jira_transition_issue` to move it to "TO DO".

## Post-Creation: HOW Comment (optional)

After the status transition, decide whether to post a HOW comment. The HOW lives in a Jira comment, not in the description.

### When to post a HOW

Post a HOW only when there is substance worth surfacing that the implementer wouldn't already get from the WHAT and from reading the code. If there isn't — skip it. A missing HOW comment is better than a filler one, and `/comet:work-on-jira-ticket` handles the no-HOW case gracefully.

A HOW is worth posting when the creator has one or more of:

- Stable landmarks the implementer might miss (architectural seams, long-lived service classes, the right package to land in)
- An existing pattern in the codebase worth mirroring rather than reinventing
- Reusable building blocks (shared records, existing DAOs, established events) the implementer should know about before designing from scratch
- A constraint that isn't obvious from the surface area of the WHAT (e.g., "workspace scoping is enforced at the DAO layer")
- Open questions the agent / creator wasn't sure about — framed as questions, not as decisions

A HOW should NOT contain:

- Step-by-step implementation checklists with code snippets
- Regenerated acceptance criteria (those belong in the WHAT)
- A specific design choice presented as *the* choice when alternatives are reasonable — surface the trade-off, don't pre-decide
- Long bullet trees that repeat what the WHAT already said

Length follows substance. Two sentences of real insight beats fifteen lines of generic scaffolding. Be specific *when you are confident*; be brief *when you aren't*.

### How to post

Use `mcp__Jira__home___jira_add_comment` with the ticket key and a body that starts with `# HOW` on the first line. Plain Markdown is fine — `#`, `##`, backticks for inline code, `-` for bullets. Jira renders these as real headers / inline code / bullets.

Example shape (for a hypothetical `[BE] Add an endpoint to bulk-delete experiments` ticket):

```
# HOW

Pieces likely still relevant:

- Traces and spans already have batch-delete endpoints — mirror that shape.
- Shared `BatchDelete` record in `com.comet.opik.api` is reused by other batch endpoints.
- Workspace scoping is enforced at the DAO layer across this area — keep that invariant.
- Cascading deletes touch `experiment_items`; existing DAO code already handles that relationship.

Open questions for the implementer:

- Feedback scores: traces null them out on delete; do experiments need the same treatment?
- Partial-failure semantics: 404 if any id is missing, or best-effort delete of valid ones?
```

### Re-runs against an existing ticket

If `/comet:create-jira-ticket` is run again against an existing ticket (e.g., to update the HOW), prefer editing the previous HOW comment over piling up new ones:

1. Fetch the ticket's comments via `mcp__Jira__home___jira_get_issue` with `comment_limit` greater than zero.
2. Find the most-recent comment whose body matches `^#\s*HOW\b` (case-insensitive) **and** whose `author.email` matches the authenticated user's email.
3. If found: `mcp__Jira__home___jira_edit_comment` with the new HOW body.
4. If not found (no prior HOW, or only HOWs posted by other users): `mcp__Jira__home___jira_add_comment` a new one.

The author check matters: `jira_edit_comment` does not enforce ownership at the MCP layer (Jira permissions decide), so without it a user with "Edit All Comments" could clobber a teammate's HOW.

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
- Description uses plain Markdown (`##` for headers, `-` for bullets, backticks for inline code). Jira renders Markdown as real headers / formatting.
- Acceptance criteria should be checkboxes using `- [ ]` syntax
- Always include standard acceptance criteria like "No lint errors" and "Tests added" where applicable
- The description is for **WHY** and **WHAT** only. **HOW** goes in a separate comment after the ticket is created — see "Post-Creation: HOW Comment" above.
