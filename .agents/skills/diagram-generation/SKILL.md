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

## Reference Files

- [style-guide.md](style-guide.md) — Semantic colors, box themes, section labels, flow patterns, architecture trees
- [template.md](template.md) — Base HTML structure, copy-as-image script, section recipes

## Common Gotchas

- **SRI hash on CDN scripts**: The html2canvas `<script>` tag must include `integrity` and `crossorigin` attributes — see template.md for the current hash
- **Absolute paths for Playwright screenshots**: Playwright saves relative to its own CWD, not the repo root — always use absolute paths when calling `browser_take_screenshot`
- **Max 4 sections**: More than 4 sections makes diagrams too tall for screenshots and hard to scan visually
- **No raw diff content**: Diagrams show high-level summaries (component names, file names, flow descriptions) — never embed verbatim diff hunks or Jira comments
- **`toBlob` can return null**: The Canvas `toBlob` call in the copy-as-image script needs a null check — see template.md
