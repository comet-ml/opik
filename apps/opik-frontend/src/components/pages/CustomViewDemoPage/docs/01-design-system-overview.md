# Custom Views Design System Overview

> **Figma Source**: [Custom Views - Components](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-13327&m=dev)

## Layout Model

> **Figma**: [Layout Rules](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16930)

The layout is **single-column** and supports two types of components:

### Inline Components
- May share a single horizontal row with other inline components
- Small, atomic, non-scrolling elements

**Rules**:
- Must not scroll
- Must have bounded width
- Must not contain other components

**Examples**: Status chip, Tags, BoolChip, Label, Link to trace, Link to span, Text, Small icons

### Block Components
- Always take full width
- Start a new row (clear the row)

**Examples**: Input (TEXT box), Output (TEXT box), Code block, Image, Video, Audio, PDF, File, Container (Level 1 & 2), Header, Chat bubble, Divider

---

## Required Structure

> **Figma**: [Required Structure](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15702)

Every view MUST have:
- **Exactly one Input** (TEXT BLOCK)
- **Exactly one Output** (TEXT BLOCK)
- Both above the fold
- Never collapsible

**Allowed between Input & Output**:
- Any non-Input/Output components
- Background content should be subtle or collapsible

---

## Turns (Conversation Mode)

> **Figma**: [Turns](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17198)

Turns are implicit. Each turn contains:
- Exactly one user **Input**
- Exactly one assistant **Output**
- Background items may appear between or below turns
- Turns are **never collapsible**

---

## Foreground vs Background

> **Figma**: [Foreground/Background Rules](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16936)

### Foreground (never collapsible)
- `user`
- `assistant`
- Anything explicitly requested by the user

**Rendered as**: Input / Output / Chat bubbles

### Background (collapsible by default)
- `system`
- `tool`
- `function`
- `developer`
- `retrieval`
- `span_event`
- `log`
- `error` (unless user explicitly asks)
- `Metadata` (unless user explicitly asks)

### Unknown roles
- Always background
- Only promoted if user explicitly references them

---

## Inline Grouping Rules

> **Figma**: [Inline Grouping](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17205)

Inline-only rows must be **homogeneous**:
- A single row may contain: only tags/chips, OR only links
- Never mix more than 2 types in the same row

### Containers + Inline
- Containers may contain inline rows and block components
- Inline rows must appear immediately after a Header or Label, never floating arbitrarily
