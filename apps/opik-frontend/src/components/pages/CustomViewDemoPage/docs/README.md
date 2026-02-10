# Custom Views Design Documentation

> **Primary Figma Source**: [Custom Views - Components](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-13327&m=dev)

## Documents

| # | Document | Description |
|---|----------|-------------|
| 1 | [Design System Overview](./01-design-system-overview.md) | Layout model, inline/block rules, required structure |
| 2 | [Component Specs](./02-component-specs.md) | All components with props and Figma links |
| 3 | [Container Hierarchy](./03-container-hierarchy.md) | Level 1/2 containers, collapse rules, nesting |
| 4 | [Schema Generation Rules](./04-schema-generation-rules.md) | LLM prompt guidelines for generating views |
| 5 | [Migration Checklist](./05-migration-checklist.md) | Gap analysis, implementation order |

---

## Quick Links to Figma

### Layout & Structure
- [Layout Rules](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16930) - Inline vs Block
- [Required Structure](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15702) - Input/Output rules
- [Turns](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17198) - Conversation mode
- [Foreground/Background](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16936) - Collapse behavior
- [Inline Grouping](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17205) - Row composition

### Inline Components
- [Text](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-14918)
- [Number](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15116)
- [Label](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15121)
- [Tag/Chip](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15706)
- [Bool/BoolChip](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17129)
- [LinkButton](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15707)
- [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15133)

### Block Components
- [Header](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15121)
- [TextBlock](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16919)
- [Code](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15130)
- [Divider](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16768)
- [Image](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15694)
- [Video](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15694)
- [Audio](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15695)
- [File](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-10067)

### Containers
- [Level 1 & 2 Containers](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15701)
- [Container Rules](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16942)

---

## Implementation Stages

| Stage | Focus | Conversations |
|-------|-------|---------------|
| 1 | Requirements Extraction | This conversation |
| 2a | Inline Primitives | Tag, BoolChip, Bool, Label, Number |
| 2b | Block Primitives | Header, Divider, TextBlock, refactor Text/Code |
| 2c | Containers | Level1Container, Level2Container, InlineRow |
| 2d | Media | Refactor Image, Video, Audio, File |
| 2e | Links | LinkButton, refactor Link |
| 3 | Catalog & Schema | Update LLM prompt generation |
| 4 | Integration | Wire up to pages |
| 5 | Testing | Manual QA, edge cases |

---

## How to Use These Docs

1. **Before implementing a component**: Read its spec in [02-component-specs.md](./02-component-specs.md)
2. **Click Figma link**: View the visual design and export assets
3. **Check constraints**: Review [03-container-hierarchy.md](./03-container-hierarchy.md) for nesting rules
4. **Update catalog**: Follow patterns in [04-schema-generation-rules.md](./04-schema-generation-rules.md)
5. **Track progress**: Use checkboxes in [05-migration-checklist.md](./05-migration-checklist.md)
