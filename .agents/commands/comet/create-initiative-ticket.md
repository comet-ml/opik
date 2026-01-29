# Create Initiative Ticket

Create a quarterly pillar Epic in Jira for a strategic initiative, synthesizing information from Notion PRDs and Figma designs.

## Instructions

When the user wants to create an initiative ticket, follow this workflow:

### Step 1: Gather Information

Ask the user for the following:

1. **Initiative Name**: The name/title of the initiative
2. **Quarter**: Which quarter this belongs to (e.g., Q1 2026, Q2 2026)
3. **Strategic Pillar**: Which strategic pillar this supports
4. **Notion Links**: One or more Notion page URLs containing PRDs, specs, or research
5. **Figma Links**: (Optional) One or more Figma URLs for designs/prototypes
6. **Priority**: Low, Medium, High, or Highest
7. **Labels**: Additional labels (e.g., frontend, backend, sdk)

### Step 2: Fetch Content from Sources

#### Notion Pages
For each Notion URL provided, use the Notion MCP to fetch the content:

```
project-0-opik-Notion-notion-fetch(
  id="<notion_url_or_page_id>"
)
```

Parse the returned Markdown content to extract:
- Page title
- Problem statement
- User journey (step-by-step flow of how users will interact with the feature)
- Goals and success metrics
- Requirements (functional, technical, non-functional)
- Scope (in scope / out of scope)
- Dependencies
- Risks

#### Figma Links
For each Figma URL, extract the file/frame name from the URL structure and note it for the Design section.

### Step 3: Synthesize Information

Combine all extracted information into the Epic template below. When synthesizing:
- Consolidate overlapping information from multiple sources
- Identify and flag any gaps or inconsistencies
- Summarize verbose content into actionable items
- Preserve key details and metrics

### Step 4: Review with User

Present the synthesized Epic description to the user for review. Allow them to:
- Edit any section
- Add missing information
- Approve the final content

### Step 5: Create the Epic

Once approved, create the Epic using the Jira MCP:

```
user-Jira__home_-jira_create_issue(
  project_key="OPIK",
  summary="[<QUARTER>] <Initiative Name>",
  issue_type="Epic",
  description="<formatted_description>",
  additional_fields={
    "priority": {"name": "<priority>"},
    "labels": ["initiative", "quarterly-pillar", "<quarter-label>", ...]
  }
)
```

### Step 6: Add Remote Links

After creating the Epic, add remote links for all source documents:

#### For Notion Links:
```
user-Jira__home_-jira_create_remote_issue_link(
  issue_key="<created_epic_key>",
  url="<notion_url>",
  title="<page_title>",
  summary="Product Documentation",
  relationship="Documentation"
)
```

#### For Figma Links:
```
user-Jira__home_-jira_create_remote_issue_link(
  issue_key="<created_epic_key>",
  url="<figma_url>",
  title="<figma_file_name>",
  summary="Design",
  relationship="Design"
)
```

---

## Epic Description Template

Use Jira wiki markup format:

```
h2. Initiative Overview

[High-level summary synthesized from Notion docs - what is this initiative and why does it matter. 2-3 sentences maximum.]

h2. Strategic Context

* *Quarter:* <Quarter Year>
* *Pillar:* <Strategic pillar name>
* *Business Impact:* <Expected outcomes/metrics>
* *Target Users:* <Who benefits from this initiative>

h2. Problem Statement

[The core problem being solved, extracted from PRD/research docs. Be specific about the pain points and current limitations.]

h2. User Journey

[Describe the step-by-step flow of how users will interact with this feature. Extract from PRD/feature docs.]

h3. Primary User Flow
* *Step 1:* [User action]
* *Step 2:* [System response]
* *Step 3:* [User action]
* *Step 4:* [Expected outcome]

h3. Key Interaction Points
* [Critical interaction 1]
* [Critical interaction 2]
* [Critical interaction 3]

h3. Typical Workflow Scenarios
* [Scenario 1 description]
* [Scenario 2 description]

h2. Goals & Success Metrics

* *Primary Goal:* <Main objective>
* *Key Results:*
** KR1: <Measurable outcome>
** KR2: <Measurable outcome>
** KR3: <Measurable outcome>

h2. Scope

h3. In Scope
* <Feature/capability 1>
* <Feature/capability 2>
* <Feature/capability 3>

h3. Out of Scope
* <Explicitly excluded item 1>
* <Explicitly excluded item 2>

h2. Requirements Summary

h3. Functional Requirements
* <Key functional requirement 1>
* <Key functional requirement 2>
* <Key functional requirement 3>

h3. Technical Requirements
* <Key technical requirement 1>
* <Key technical requirement 2>

h3. Non-Functional Requirements
* <Performance, security, scalability considerations>

h2. Design

[Summary of design approach and key UI/UX decisions extracted from Figma files]

h3. Design Links
* [<Figma file 1 title>|<figma_url_1>]
* [<Figma file 2 title>|<figma_url_2>]

h3. Key Design Decisions
* <Design decision 1>
* <Design decision 2>

h2. Dependencies & Risks

h3. Dependencies
* <Dependency 1>
* <Dependency 2>

h3. Risks & Mitigations
|| Risk || Impact || Mitigation ||
| <Risk 1> | High/Medium/Low | <Mitigation strategy> |
| <Risk 2> | High/Medium/Low | <Mitigation strategy> |

h2. Reference Documents

h3. Product Documentation (Notion)
* [<Document 1 title>|<notion_url_1>]
* [<Document 2 title>|<notion_url_2>]

h3. Design Assets (Figma)
* [<Design file 1 title>|<figma_url_1>]
* [<Design file 2 title>|<figma_url_2>]

h2. Engineering Breakdown

_To be completed by engineering team_

* [ ] Technical design complete
* [ ] Architecture review done
* [ ] Child stories created
* [ ] Dependencies identified
* [ ] Estimates provided
* [ ] Risk assessment complete
```

---

## Example Conversation Flow

1. **Ask**: "What initiative would you like to create an Epic for?"

2. **Ask**: "Which quarter is this for? (e.g., Q1 2026)"

3. **Ask**: "What strategic pillar does this support?"

4. **Ask**: "Please provide the Notion links for the PRD and any related documentation."

5. **Ask**: "Do you have any Figma design links to include? (Optional)"

6. **Ask**: "What priority should this Epic have? (Low/Medium/High/Highest)"

7. **Ask**: "Any additional labels? (e.g., frontend, backend, sdk, infra)"

8. **Fetch**: Use `notion-fetch` for each Notion URL to get content

9. **Synthesize**: Combine all information into the Epic template

10. **Review**: "Here's the synthesized Epic. Please review and let me know if you'd like any changes:"
    - Present the formatted description

11. **Create**: Once approved, create the Epic and add remote links

12. **Confirm**: "Epic created: OPIK-XXXX - [Link to Epic]. I've also added remote links to all your source documents."

---

## Labels

Always include these labels for initiative Epics:
- `initiative` - Marks this as a strategic initiative
- `quarterly-pillar` - Indicates quarterly planning scope
- Quarter label (e.g., `Q1-2026`, `Q2-2026`)

Add component labels based on the initiative scope:
- `frontend` - Frontend changes
- `backend` - Backend changes
- `sdk` - SDK changes
- `infra` - Infrastructure changes
- `docs` - Documentation updates

---

## Title Format

Epic titles should follow this format:
```
[<QUARTER>] <Initiative Name>
```

Examples:
- `[Q1 2026] Prompt Playground V2`
- `[Q2 2026] Advanced Trace Analytics`
- `[Q1 2026] SDK Performance Optimization`

---

## Notes

- The project key is always `OPIK`
- Description uses Jira wiki markup (h2. for headers, * for bullets, || for table headers)
- Always fetch and read Notion content before synthesizing - don't ask the user to copy/paste
- If a Notion page has sub-pages with relevant content, offer to fetch those as well
- Keep the Epic description focused on high-level requirements; detailed specs go in child tickets
- The Engineering Breakdown section is intentionally left as a placeholder for the engineering team
