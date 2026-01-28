# Container Hierarchy & Collapse Rules

> **Figma Source**: [Container Components](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16942)

---

## Container Levels

### Level 1 Container

> **Figma**: [Level 1 Container](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15701)

**Purpose**: Semantic grouping / aggregation

**May contain**:
- Level 2 containers
- Tables
- Metrics
- Labels
- Inline rows
- Block components

**Must NOT contain**:
- Input
- Output
- Turn

**Collapsible**: Only if ALL children are background

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| title | string | No | Container title |
| collapsible | boolean | No | Enable collapse (auto-determined by children) |
| defaultOpen | boolean | No | Initial open state |

---

### Level 2 Container

> **Figma**: [Level 2 Container](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15701)

**Purpose**: Detail disclosure (spans, tools, system internals)

**Characteristics**:
- No nesting (max depth = 2)
- Collapsible by default

**Used for**:
- Spans
- Tool calls
- Retrievals
- Raw JSON / CODE

**Must have**:
- Summary (label or header)
- Expandable details

**Must NOT contain**:
- Level 1 container
- Input
- Output
- Turn

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| summary | string | Yes | Collapsed state label |
| defaultOpen | boolean | No | Initial open state (default: false) |

---

## Collapse Rules Summary

| Component | Collapsible? |
|-----------|--------------|
| Input | Never |
| Output | Never |
| Turns | Never |
| Level 2 Container | Yes (by default) |
| Level 1 Container | Only if all children are background |
| Foreground content | Never |
| Background content | Yes (by default) |

---

## Nesting Constraints

```
View
├── Input (required, never collapsible)
├── [Background content - collapsible]
├── Output (required, never collapsible)
└── Level 1 Container (collapsible if all children background)
    ├── Header
    ├── Inline row (Tags, Labels, etc.)
    ├── Block component
    └── Level 2 Container (always collapsible)
        ├── Summary (required)
        └── Details (expandable)
            ├── Code
            ├── JSON
            └── Metadata
```

---

## Visual Hierarchy

```
┌─────────────────────────────────────────┐
│ INPUT (full width, never collapsible)   │
├─────────────────────────────────────────┤
│ [Background: system prompt, etc.]       │  ← Collapsible
├─────────────────────────────────────────┤
│ OUTPUT (full width, never collapsible)  │
├─────────────────────────────────────────┤
│ ┌─────────────────────────────────────┐ │
│ │ Level 1: Metadata                   │ │  ← Collapsible if all bg
│ │ ┌───────────────────────────────┐   │ │
│ │ │ ▶ Level 2: Tool Call          │   │ │  ← Collapsible (default)
│ │ └───────────────────────────────┘   │ │
│ │ ┌───────────────────────────────┐   │ │
│ │ │ ▶ Level 2: Span Details       │   │ │  ← Collapsible (default)
│ │ └───────────────────────────────┘   │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

---

## Implementation Notes

### Determining Collapsibility

```typescript
function isCollapsible(container: Container): boolean {
  // Level 2 is always collapsible
  if (container.level === 2) return true;

  // Level 1 is collapsible only if all children are background
  if (container.level === 1) {
    return container.children.every(child => isBackground(child));
  }

  return false;
}

function isBackground(node: ViewNode): boolean {
  const backgroundRoles = [
    'system', 'tool', 'function', 'developer',
    'retrieval', 'span_event', 'log', 'error', 'metadata'
  ];
  return backgroundRoles.includes(node.role);
}
```

### Preventing Invalid Nesting

```typescript
function validateNesting(parent: Container, child: ViewNode): boolean {
  // Level 2 cannot contain Level 1
  if (parent.level === 2 && child.type === 'container' && child.level === 1) {
    return false;
  }

  // Containers cannot contain Input/Output/Turn
  if (['input', 'output', 'turn'].includes(child.type)) {
    return false;
  }

  // Divider cannot be inside Level 2
  if (parent.level === 2 && child.type === 'divider') {
    return false;
  }

  // Header cannot be inside Level 2
  if (parent.level === 2 && child.type === 'header') {
    return false;
  }

  return true;
}
```
