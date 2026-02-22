---
name: diagram-generation
description: Generate self-contained HTML architecture diagrams. Use when creating visual diagrams for PRs, task plans, or architectural explanations.
---

# Diagram Generation

Generate self-contained HTML diagrams that visualize code changes, data flows, and architecture decisions.

## When to Use

- Visualizing PR changes for code review
- Explaining architectural decisions
- Documenting data/request flows
- Illustrating before/after comparisons

## Output

- Self-contained HTML file at `diagrams/opik-{TICKET_NUMBER}-diagram.html`
- Includes "Copy as image" button for sharing in Slack, Jira, PR descriptions
- Dark GitHub theme, semantic color coding, responsive layout

## How to Generate

Follow the style guide in `style-guide.md` and use the HTML template in `template.md`.

### Required Sections (pick what applies)

1. **Request / Data Flow** — how data moves through layers
2. **Why This Approach** — problem vs solution comparison
3. **Files Changed by Layer** — grid of affected files grouped by component
4. **Key Design Decisions** — numbered guards, trade-offs, or constraints

### Section Selection

- **Bug fix**: Focus on before/after flow, root cause, safety guards
- **New feature**: Focus on data flow, architecture, files changed
- **Refactor**: Focus on before/after architecture, files changed
- **Cross-component**: Show all layers with connecting flows
