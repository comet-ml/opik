# Schema Generation Rules for LLM

> **Figma Source**: [Custom Views - Components](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-13327&m=dev)

This document defines rules for the LLM to follow when generating view schemas.

---

## Required Elements

Every generated view MUST include:

1. **Exactly one Input block** - for user/request content
2. **Exactly one Output block** - for assistant/response content
3. Both must be at the top level (not nested in containers)
4. Both must be above the fold

```typescript
// Valid structure
{
  children: [
    { type: "TextBlock", props: { role: "input", ... } },
    { type: "TextBlock", props: { role: "output", ... } },
    // ... other content
  ]
}
```

---

## Component Selection Guidelines

### When to use each component

| Data Type | Component | Notes |
|-----------|-----------|-------|
| Main input text | TextBlock (role: input) | Required |
| Main output text | TextBlock (role: output) | Required |
| Boolean value | Bool | Use "check" style for metadata |
| Boolean state | BoolChip | When semantically a state |
| Short text | Text | For labels, captions |
| Long text | TextBlock | For content blocks |
| Code/JSON | Code | With language hint |
| Number/metric | Number | Stackable |
| Status/state | Tag | For classification |
| Trace reference | LinkButton | Only when ID exists |
| External URL | Link | For external resources |
| Image data | Image | With preview |
| Audio data | Audio | With player |
| Video data | Video | With player |
| File reference | File | For downloads |
| Group of items | Level1Container | For aggregation |
| Collapsible details | Level2Container | For spans, tools |

---

## Layout Rules for Generation

### Inline vs Block

**Generate as INLINE** (can share row):
- Tag, BoolChip, Bool, Label, Number, Link, LinkButton, Text (short)

**Generate as BLOCK** (own row):
- TextBlock, Code, Header, Divider, Image, Video, Audio, File, Containers

### Row Composition

When generating inline rows:
```typescript
// GOOD - homogeneous row
{ type: "InlineRow", children: [
  { type: "Tag", props: { label: "cached" } },
  { type: "Tag", props: { label: "success" } },
]}

// GOOD - max 2 types
{ type: "InlineRow", children: [
  { type: "Label", props: { text: "Model:" } },
  { type: "Text", props: { value: "gpt-4" } },
]}

// BAD - too many types mixed
{ type: "InlineRow", children: [
  { type: "Tag", props: { label: "cached" } },
  { type: "Link", props: { url: "..." } },
  { type: "Number", props: { value: 42 } },
]}
```

---

## Container Usage Rules

### Level 1 Container

**Use for**:
- Grouping related metadata
- Organizing multiple metrics
- Aggregating tool results

**Do NOT use for**:
- Wrapping Input/Output
- Single items (use Label instead)

```typescript
// GOOD
{ type: "Level1Container", props: { title: "Model Configuration" },
  children: [
    { type: "KeyValue", props: { label: "Model", value: "gpt-4" } },
    { type: "KeyValue", props: { label: "Temperature", value: "0.7" } },
  ]
}
```

### Level 2 Container

**Use for**:
- Span details
- Tool call content
- Raw JSON/logs
- System internals

**Always provide**:
- Summary text for collapsed state

```typescript
// GOOD
{ type: "Level2Container", props: { summary: "Tool: search_web" },
  children: [
    { type: "Code", props: { content: "...", language: "json" } },
  ]
}
```

---

## Foreground vs Background Assignment

### Mark as FOREGROUND:
- Content from `user` role
- Content from `assistant` role
- Content explicitly requested by user

### Mark as BACKGROUND:
- Content from `system`, `tool`, `function`, `developer`
- Retrieval results
- Span events, logs
- Errors (unless user asks)
- Metadata (unless user asks)

```typescript
// Background content should be in collapsible containers
{ type: "Level2Container",
  props: { summary: "System Prompt", background: true },
  children: [
    { type: "TextBlock", props: { content: "..." } },
  ]
}
```

---

## Collapse State Guidelines

### Default Open:
- Input (never collapsible)
- Output (never collapsible)
- Important foreground content

### Default Collapsed:
- System prompts
- Tool call details
- Span internals
- Raw JSON/logs
- Error stack traces

```typescript
{ type: "Level2Container",
  props: {
    summary: "Error Details",
    defaultOpen: false  // Collapsed by default
  },
  children: [...]
}
```

---

## Data Binding Syntax

Use JSON pointer syntax for dynamic values:

```typescript
// Static value
{ type: "Text", props: { value: "Hello" } }

// Dynamic value from data
{ type: "Text", props: { value: { $path: "/output/content" } } }

// With fallback
{ type: "Text", props: { value: { $path: "/metadata/model", $default: "unknown" } } }
```

---

## Validation Checklist for LLM

Before returning a schema, verify:

- [ ] Has exactly one Input block
- [ ] Has exactly one Output block
- [ ] Input/Output are NOT inside containers
- [ ] Level 2 containers have summary prop
- [ ] No Level 1 inside Level 2
- [ ] No Divider inside Level 2
- [ ] No Header inside Level 2
- [ ] LinkButton only used when trace/span ID exists
- [ ] Inline rows are homogeneous (max 2 types)
- [ ] Background content is in collapsible containers

---

## Example Complete Schema

```typescript
{
  type: "View",
  children: [
    // Required Input
    {
      type: "TextBlock",
      props: {
        role: "input",
        label: "User Message",
        content: { $path: "/input/messages/0/content" }
      }
    },

    // Background (collapsible)
    {
      type: "Level2Container",
      props: { summary: "System Prompt", defaultOpen: false },
      children: [
        { type: "TextBlock", props: { content: { $path: "/input/messages/1/content" } } }
      ]
    },

    // Required Output
    {
      type: "TextBlock",
      props: {
        role: "output",
        label: "Assistant Response",
        content: { $path: "/output/content" }
      }
    },

    // Metadata section
    {
      type: "Level1Container",
      props: { title: "Metadata" },
      children: [
        {
          type: "InlineRow",
          children: [
            { type: "Tag", props: { label: "cached", variant: "success" } },
            { type: "Tag", props: { label: "gpt-4" } },
          ]
        },
        {
          type: "Level2Container",
          props: { summary: "Token Usage" },
          children: [
            { type: "Number", props: { label: "Prompt", value: { $path: "/usage/prompt_tokens" } } },
            { type: "Number", props: { label: "Completion", value: { $path: "/usage/completion_tokens" } } },
          ]
        }
      ]
    }
  ]
}
```
