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
├── TextBlock (input, role="input")
├── TextBlock (output, role="output", expandable)
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
Level2Container (collapsible)
├── InlineRow (metadata)
│   ├── LinkButton (span link)
│   ├── Label + Text (Model: gpt-5-mini)
│   └── Label + Text (Duration: 42ms)
├── Code (input JSON)
└── Code (output JSON)
```

**Key Features:**
- Level2Container provides collapse/expand functionality
- InlineRow groups related inline widgets horizontally
- Code widget with syntax highlighting for JSON
- Label + Text pattern for key-value display

### Pattern 3: Retrieval Span (`retrievalSpanExample.ts`)

A retrieval operation showing index information.

```
Level2Container (collapsible)
├── InlineRow
│   ├── Label + Number (Indexes queried: 2)
│   └── Label + Number (Documents retrieved: 0)
├── Label (Indexes:)
└── Code (index names list)
```

**Key Features:**
- Number widget for count display
- Code widget (no syntax highlighting) for plain text lists

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
Level1Container
├── Header (Thread title)
├── InlineRow (thread metadata)
│   ├── Label + Number (Turns)
│   ├── Text (date)
│   ├── Text (duration)
│   ├── Tag (status)
│   └── Label + Text (cost)
├── Divider
├── Level1Container (Conversation)
│   ├── TextBlock (user message, role="input")
│   └── TextBlock (assistant message, role="output")
├── InlineRow (tags)
│   ├── Label + Tag (Agent)
│   └── Label + Tag (Category)
└── Level2Container (Trace details - collapsed)
    ├── InlineRow (trace ID, duration)
    └── InlineRow (pipeline)
```

**Key Features:**
- Header widget for section titles
- Divider for visual separation
- Nested Level1Container for subsection (conversation)
- Tag widgets with semantic variants (info, default)
- Collapsible trace details section

### Pattern 6: Metadata Container (`metadataContainerExample.ts`)

Metadata display with tags and links.

```
Level1Container (titled "Run Metadata")
├── InlineRow (model info)
│   ├── Label + Text (Model)
│   └── Label + Text (Provider)
├── InlineRow (performance)
│   ├── Label + Text (Latency)
│   ├── Label + Number (Tokens)
│   └── Label + Text (Cost)
├── InlineRow (status tags)
│   ├── Tag (status - success)
│   ├── Tag (cached - info)
│   └── Tag (streamed - default)
└── InlineRow (links)
    ├── LinkButton (trace)
    └── LinkButton (span)
```

**Key Features:**
- Level1Container with title prop
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
