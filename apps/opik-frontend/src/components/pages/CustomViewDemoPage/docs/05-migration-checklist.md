# Migration Checklist: Current Implementation vs Figma Design

> **Figma Source**: [Custom Views - Components](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-13327&m=dev)

---

## Current Implementation

**Location**: `apps/opik-frontend/src/components/pages/CustomViewDemoPage/data-view-widgets/`

**Files**:
- `index.ts` - Combined catalog & registry exports
- `types.ts` - Type utilities
- `primitives/` - Inline widget implementations (Phase 1 complete)
  - `TextWidget.tsx` ✅
  - `NumberWidget.tsx` ✅
  - `LabelWidget.tsx` ✅
  - `TagWidget.tsx` ✅
  - `BoolWidget.tsx` ✅
  - `BoolChipWidget.tsx` ✅
  - `LinkButtonWidget.tsx` ✅
  - `LinkWidget.tsx` ✅
  - `index.ts` - Barrel exports + registry

**Removed**:
- `widgets.tsx` - Old implementation (replaced by primitives/)

---

## Gap Analysis

### Components to CREATE (New)

| Component | Figma Link | Priority | Status |
|-----------|------------|----------|--------|
| Tag | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15706) | High | ✅ Done |
| BoolChip | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17129) | High | ✅ Done |
| Bool | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17129) | High | ✅ Done |
| Label | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15121) | High | ✅ Done |
| Number | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15116) | Medium | ✅ Done |
| LinkButton | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15707) | High | ✅ Done |
| Header | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15121) | High | ✅ Done |
| Divider | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16768) | Medium | ✅ Done |
| TextBlock | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16919) | High | ✅ Done |
| Level1Container | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15701) | High | ✅ Done |
| Level2Container | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15701) | High | ✅ Done |
| InlineRow | [Link](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17205) | High | ✅ Done |

### Components to REFACTOR (Existing)

| Component | Current | Changes Needed | Status |
|-----------|---------|----------------|--------|
| Text | Basic | Add truncation, monospace, variants | ✅ Done |
| Link | Basic | Differentiate from LinkButton | ✅ Done |
| Code | Basic | Add language hint, wrap, copy, line numbers | ✅ Done |
| Image | Basic | Add preview actions, tag display | TODO |
| Video | Basic | Add preview, tag display | TODO |
| Audio | Basic | Add waveform preview, tag | TODO |

### Components to DEPRECATE/REMOVE

| Component | Reason | Replacement | Status |
|-----------|--------|-------------|--------|
| Section | Replaced by design system | Level1Container | ✅ Removed |
| Card | Replaced by design system | Level2Container | ✅ Removed |
| KeyValue | Subsumed by inline patterns | Label + Text or Number | ✅ Removed |
| Old widgets.tsx | Replaced by primitives/ | primitives/index.ts | ✅ Removed |

---

## Implementation Order

### Phase 1: Core Primitives ✅ COMPLETE
- [x] Tag ✅
- [x] BoolChip ✅
- [x] Bool ✅
- [x] Label ✅
- [x] Number ✅
- [x] Text (refactored with truncation, monospace, variants) ✅
- [x] LinkButton (trace/span) ✅
- [x] Link (generic external, refactored) ✅

### Phase 2: Block Primitives ✅ COMPLETE
- [x] Header ✅
- [x] Divider ✅
- [x] TextBlock (Input/Output) ✅
- [x] Code (add features: language, wrap, copy, line numbers) ✅

### Phase 3: Containers ✅ COMPLETE
- [x] Level1Container ✅
- [x] Level2Container ✅
- [x] InlineRow ✅
- [x] Remove: Section, Card (old widgets.tsx removed) ✅

### Phase 4: Media (TODO)
- [ ] Image (preview, tags)
- [ ] Video (preview, tags)
- [ ] Audio (waveform, tags)
- [ ] File/PDF (consolidate)

---

## Catalog Updates Required

### New Component Schemas

```typescript
// Tag
Tag: {
  props: z.object({
    label: DynamicString,
    variant: z.enum(["default", "error", "warning", "success", "info"]).nullable(),
  }),
  hasChildren: false,
  inline: true,
  description: "Status chip for state, severity, or classification.",
}

// Bool
Bool: {
  props: z.object({
    value: DynamicBoolean,
    style: z.enum(["check", "text"]).nullable(),
  }),
  hasChildren: false,
  inline: true,
  description: "Compact boolean display for metadata.",
}

// BoolChip
BoolChip: {
  props: z.object({
    value: DynamicBoolean,
    trueLabel: NullableDynamicString,
    falseLabel: NullableDynamicString,
  }),
  hasChildren: false,
  inline: true,
  description: "Boolean as a status chip.",
}

// Level1Container
Level1Container: {
  props: z.object({
    title: NullableDynamicString,
  }),
  hasChildren: true,
  inline: false,
  description: "Semantic grouping container. Collapsible only if all children are background.",
  constraints: ["Cannot contain Input, Output, or Turn"],
}

// Level2Container
Level2Container: {
  props: z.object({
    summary: DynamicString,
    defaultOpen: NullableDynamicBoolean,
  }),
  hasChildren: true,
  inline: false,
  description: "Detail disclosure container for spans, tools, internals. Always collapsible.",
  constraints: ["Cannot contain Level1Container, Input, Output, or Turn"],
}

// TextBlock
TextBlock: {
  props: z.object({
    content: DynamicString,
    label: NullableDynamicString,
    role: z.enum(["input", "output", "default"]).nullable(),
  }),
  hasChildren: false,
  inline: false,
  description: "Primary text display for input/output content.",
}

// LinkButton
LinkButton: {
  props: z.object({
    type: z.enum(["trace", "span"]),
    id: DynamicString,
    label: NullableDynamicString,
  }),
  hasChildren: false,
  inline: true,
  description: "First-class link to trace or span. Only valid when ID exists.",
}
```

---

## Files Modified

| File | Changes | Status |
|------|---------|--------|
| `data-view-widgets/widgets.tsx` | Removed (replaced by primitives/) | ✅ Done |
| `data-view-widgets/index.ts` | New barrel file with catalog & registry | ✅ Done |
| `data-view-widgets/primitives/` | New directory with 8 inline widgets | ✅ Done |
| `data-view-widgets/blocks/` | New directory with 4 block widgets | ✅ Done |
| `data-view-widgets/containers/` | New directory with 3 container widgets | ✅ Done |
| `CustomViewDataPanel.tsx` | Updated imports | ✅ Done |
| `AnnotationCustomViewPanel.tsx` | Updated imports | ✅ Done |
| `useViewTreeGenerationAI.ts` | Updated imports | ✅ Done |
| `data-view-widgets/types.ts` | No changes needed yet | - |
| `SchemaProposalCard.tsx` | Update schema display if needed | TODO |
| `@/lib/data-view/` | May need updates for inline/block distinction | TODO |
| `@/types/custom-view.ts` | Update WidgetType enum | TODO |

---

## Testing Checklist

### Unit Tests
- [ ] Each new component renders correctly
- [ ] Props are validated
- [ ] Inline/block layout works

### Integration Tests
- [ ] Containers nest correctly
- [ ] Collapse behavior works
- [ ] Invalid nesting is prevented

### Manual Testing
- [ ] Test with real trace data
- [ ] Test LLM-generated schemas
- [ ] Test edge cases (empty data, long content)
- [ ] Test responsive layout

---

## Backward Compatibility Notes

1. ~~**Keep Section/Card temporarily**~~ - ✅ Removed (old widgets.tsx deleted)
2. **KeyValue can map to Label + Text** - Provide migration helper (TODO)
3. **Existing saved views** - Need migration script or graceful fallback (TODO)

---

## Completed Work Summary (Phase 1)

**Date**: 2025-01-27

**Implemented 8 inline primitive widgets**:
1. `TextWidget` - Inline text with body/bold/caption variants, truncation, monospace
2. `NumberWidget` - Number display with xs-xl sizes, decimal/percent/currency formats
3. `LabelWidget` - Simple text descriptor
4. `TagWidget` - Status chips (default/error/warning/success/info) wrapping existing Tag
5. `BoolWidget` - Boolean display (check icon or text style)
6. `BoolChipWidget` - Boolean as colored chip with custom labels
7. `LinkButtonWidget` - First-class trace/span navigation links
8. `LinkWidget` - External URL hyperlinks

**Structure**:
```
data-view-widgets/
├── index.ts              # Combined catalog & registry
├── types.ts              # Type utilities
└── primitives/
    ├── index.ts          # Barrel + inlineWidgetConfigs + inlineWidgetRegistry
    ├── TextWidget.tsx
    ├── NumberWidget.tsx
    ├── LabelWidget.tsx
    ├── TagWidget.tsx
    ├── BoolWidget.tsx
    ├── BoolChipWidget.tsx
    ├── LinkButtonWidget.tsx
    └── LinkWidget.tsx
```

**Each widget includes**:
- TypeScript interface for props
- Zod schema config for AI catalog
- React functional component
- Figma node reference in comments

---

## Completed Work Summary (Phase 2)

**Date**: 2026-01-27

**Implemented 4 block widgets**:
1. `HeaderWidget` - Section/view titles with levels 1/2/3 (semantic h1/h2/h3)
2. `DividerWidget` - Visual separator line
3. `TextBlockWidget` - Primary I/O content display with label and role (input/output/default)
4. `CodeWidget` - Code display with optional line numbers, copy button, and line wrapping

**Structure**:
```
data-view-widgets/
├── index.ts              # Combined catalog & registry (updated)
├── types.ts              # Type utilities
├── primitives/           # Phase 1 - 8 inline widgets
│   └── ...
└── blocks/               # Phase 2 - 4 block widgets
    ├── index.ts          # Barrel + blockWidgetConfigs + blockWidgetRegistry
    ├── HeaderWidget.tsx
    ├── DividerWidget.tsx
    ├── TextBlockWidget.tsx
    └── CodeWidget.tsx
```

**Each widget includes**:
- TypeScript interface for props
- Zod schema config for AI catalog (with DynamicString/NullableDynamicString for data binding)
- React functional component
- Figma node reference in comments
- Integration with existing UI components (CopyButton for CodeWidget)

---

## Completed Work Summary (Phase 3)

**Date**: 2026-01-27

**Implemented 3 container widgets**:
1. `Level1ContainerWidget` - Semantic grouping for metadata sections with optional title
2. `Level2ContainerWidget` - Collapsible detail disclosure using Radix Accordion
3. `InlineRowWidget` - Horizontal flex container for inline widgets

**Structure**:
```
data-view-widgets/
├── index.ts              # Combined catalog & registry (updated)
├── types.ts              # Type utilities
├── primitives/           # Phase 1 - 8 inline widgets
│   └── ...
├── blocks/               # Phase 2 - 4 block widgets
│   └── ...
└── containers/           # Phase 3 - 3 container widgets
    ├── index.ts          # Barrel + containerWidgetConfigs + containerWidgetRegistry
    ├── Level1Container.tsx
    ├── Level2Container.tsx
    └── InlineRow.tsx
```

**Key differences from primitives/blocks**:
- All containers have `hasChildren: true` in the catalog config
- Container renderers receive and pass through `children: ReactNode`
- Registry renderer functions extract both `props` and `children` from ComponentRenderProps

**Each widget includes**:
- TypeScript interface for props (with `children?: React.ReactNode`)
- Zod schema config for AI catalog
- React functional component
- Figma node reference in comments
- Integration with existing UI components (Accordion for Level2Container)
