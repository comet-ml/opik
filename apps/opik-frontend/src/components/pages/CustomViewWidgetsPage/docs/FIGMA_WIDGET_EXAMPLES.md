# Figma Widget Examples Documentation

This document describes the complex widget compositions from Figma that are implemented in the examples folder.

## Figma References

- **Components Catalog**: `node-id=239-13327`
- **Complex Example 1 (Simple Trace)**: `node-id=245-14061`
- **Complex Example 2 (Trace with Tool Calls)**: `node-id=245-15436`
- **Complex Example 3 (Thread Overview)**: `node-id=245-19693`

## Widget Composition Patterns

### Pattern 1: Simple Trace View (`simpleTraceExample.ts`)

The most basic trace view showing input/output with navigation.

```
Level1Container
├── Text (timestamp, caption variant)
├── TextBlock (input)
├── TextBlock (output, expandable)
└── LinkButton (trace navigation)
```

**Key Features:**
- Timestamp displays in caption style (smaller, muted)
- Input/Output use TextBlock with semantic role styling (colored left border)
- Output supports truncation with "Show more" button
- LinkButton provides navigation to full trace

### Pattern 2: Tool Call Span (`toolCallSpanExample.ts`)

A single collapsible tool call span with metadata and I/O.

```
Level2Container (collapsible, icon="tool", status="success")
├── LinkButton (span link)
├── Text (stats line: "Model: Gpt-5-mini • Duration: 0.01s")
├── Code (input JSON)
└── Code (output JSON)
```

**Key Features:**
- Level2Container provides collapse/expand functionality with icon and status
- Stats displayed as a single Text widget with pre-composed "Label: value • Label: value" format
- Code widget with syntax highlighting for JSON
- Level2Container can only contain leaf widgets (no InlineRow)

### Pattern 3: Retrieval Span (`retrievalSpanExample.ts`)

A retrieval operation showing index information.

```
Level2Container (collapsible, icon="retrieval", status="success")
├── Text (stats line: "Indexes Queried: 2 • Documents Retrieved: 0")
└── Code (index names list, label="Indexes:")
```

**Key Features:**
- Stats displayed as a single Text widget with pre-composed "Label: value • Label: value" format
- Code widget with integrated label prop for section title
- Level2Container can only contain leaf widgets (no InlineRow)

### Pattern 4: Trace with Tool Calls (`traceWithToolCallsExample.ts`)

Complete trace with nested tool call accordion.

```
Level1Container
├── Text (timestamp)
├── TextBlock (input)
├── TextBlock (output)
├── LinkButton (trace link)
└── Level2Container (Tool calls accordion)
    ├── Level2Container (tool call 1 - expanded)
    │   ├── InlineRow (metadata)
    │   ├── Code (input)
    │   └── Code (output)
    ├── Level2Container (tool call 2 - collapsed)
    │   ├── InlineRow (stats)
    │   └── Code (indexes)
    ├── Level2Container (tool call 3 - collapsed)
    └── Level2Container (tool call 4 - collapsed)
```

**Key Features:**
- Nested Level2Containers create accordion hierarchy
- First tool call expanded by default, others collapsed
- Different tool types have different internal structures

### Pattern 5: Thread Overview (`threadOverviewExample.ts`)

Thread view with conversation turns and metadata.

```
Container (root)
├── Text (meta line: "Turns:26 • Nov 25, 2025 • 14.5s • Inactive • Total cost: $0.0024", caption)
├── ChatMessage (user message, role="user")
├── ChatMessage (assistant message, role="assistant")
├── InlineRow (tags)
│   ├── Tag (Agent: fallback, default variant)
│   └── Tag (Category: other, default variant)
└── Level1Container (Trace details - collapsed)
    └── Code (trace details, language="text")
```

**Key Features:**
- Single Text widget with pre-composed meta line (values + bullet separators)
- ChatMessage widgets for conversation turns (not TextBlock)
- Tag widgets with labels including the category prefix (e.g., "Agent: fallback")
- Collapsible Level1Container for trace details with Code block

### Pattern 6: Metadata Container (`metadataContainerExample.ts`)

Metadata display with tags and links.

```
Level1Container (titled "Metadata")
├── InlineRow (stats, background: "muted")
│   ├── Text("Model:") + Text({path}) + Text("•")
│   ├── Text("Provider:") + Text({path}) + Text("•")
│   ├── Text("Latency:") + Text({path}) + Text("•")
│   ├── Text("Tokens:") + Text({path}) + Text("•")
│   └── Text("Cost:") + Text({path})
├── InlineRow (status tags)
│   ├── Tag (status - success)
│   ├── Tag (cached - default)
│   └── Tag (streamed - default)
└── InlineRow (links)
    ├── LinkButton (trace)
    └── LinkButton (span)
```

**Key Features:**
- Level1Container with title prop
- InlineRow with background: "muted" for stats using Text labels + values + "•" bullet separators
- No Label widgets — just Text widgets for both labels and values
- Multiple InlineRow sections for organization
- Mix of Tag variants for different states

### Pattern 7: Inline Row Groupings (`inlineGroupingExample.ts`)

Demonstrates various InlineRow composition patterns.

```
Level1Container
├── InlineRow (status - labels + tags)
├── InlineRow (metrics - labels + numbers)
├── InlineRow (multiple tags)
└── InlineRow (multiple links)
```

**Key Features:**
- All tag variants demonstrated (success, warning, error, info, default)
- Number widget with size prop
- Both LinkButton (internal) and Link (external) widgets

## Data Binding

All examples use the `{ path: "/field" }` syntax for dynamic values:

```typescript
props: {
  value: { path: "/timestamp" },  // Resolves from sourceData.timestamp
  variant: "caption"              // Static value
}
```

Nested paths are supported:
- `/toolCalls/0/name` - Array index access
- `/threadInfo/status` - Nested object access

## Widget Reference

### Primitives (Inline)
- **Text**: Inline text with variants (body, bold, caption)
- **Number**: Numeric display with formatting
- **Label**: Small text descriptor (non-interactive)
- **Tag**: Status chip with variants (success, error, warning, info, default)
- **Bool/BoolChip**: Boolean display
- **LinkButton**: Internal navigation (trace/span)
- **Link**: External URL links

### Blocks
- **Header**: Section headings (levels 1-3)
- **Divider**: Visual separator
- **TextBlock**: Multi-line text with semantic roles (input/output)
- **Code**: Syntax-highlighted code blocks

### Containers
- **Level1Container**: Semantic grouping with optional title
- **Level2Container**: Collapsible detail disclosure
- **InlineRow**: Horizontal flex container for inline widgets

## Adding New Examples

1. Create a new file in `examples/` folder
2. Export `{ title, tree, sourceData }`
3. Add export to `examples/index.ts`
4. Example will automatically appear in CustomViewWidgetsPage
