# Custom View Widgets - Next Iteration Guide

> **Use this document to continue widget implementation in a new conversation.**

---

## Context

We're building a custom view widget system for displaying LLM trace/thread data. Widgets are React components with:
- 1:1 Figma design matching
- Zod schemas for AI catalog (LLM generates view trees)
- TypeScript interfaces for props
- Registry integration for rendering

---

## Completed (Phase 1: Inline Primitives)

**Location**: `src/components/pages/CustomViewDemoPage/data-view-widgets/primitives/`

| Widget | File | Props |
|--------|------|-------|
| TextWidget | `TextWidget.tsx` | value, variant (body/bold/caption), truncate, monospace |
| NumberWidget | `NumberWidget.tsx` | value, label, size (xs-xl), format (decimal/percent/currency) |
| LabelWidget | `LabelWidget.tsx` | text |
| TagWidget | `TagWidget.tsx` | label, variant (default/error/warning/success/info) |
| BoolWidget | `BoolWidget.tsx` | value, style (check/text) |
| BoolChipWidget | `BoolChipWidget.tsx` | value, trueLabel, falseLabel |
| LinkButtonWidget | `LinkButtonWidget.tsx` | type (trace/span), id, label |
| LinkWidget | `LinkWidget.tsx` | url, text, label |

---

## Completed (Phase 2: Block Primitives)

**Location**: `src/components/pages/CustomViewDemoPage/data-view-widgets/blocks/`

| Widget | File | Props |
|--------|------|-------|
| HeaderWidget | `HeaderWidget.tsx` | text, level (1/2/3) |
| DividerWidget | `DividerWidget.tsx` | (none) |
| TextBlockWidget | `TextBlockWidget.tsx` | content, label, role (input/output/default) |
| CodeWidget | `CodeWidget.tsx` | content, language, label, wrap, showLineNumbers, showCopy |

---

## Remaining Work

### Phase 3: Containers
| Widget | Figma Node | Props | Notes |
|--------|------------|-------|-------|
| Level1Container | 239-15701 | title | Semantic grouping, replaces Section |
| Level2Container | 239-15701 | summary, defaultOpen | Detail disclosure, replaces Card |
| InlineRow | 240-17205 | (children only) | Horizontal inline grouping |

### Phase 4: Media
| Widget | Figma Node | Props | Notes |
|--------|------------|-------|-------|
| Image | 239-15694 | src, alt, label, tag | Preview + actions |
| Video | 239-15694 | src, label, tag, controls | Preview + play |
| Audio | 239-15695 | src, label, tag, controls | Waveform preview |
| File | 239-10067 | url, filename, label, type | PDF/generic download |

---

## File Structure Pattern

Each widget follows this pattern:

```typescript
// primitives/ExampleWidget.tsx
import React from "react";
import { z } from "zod";
import { cn } from "@/lib/utils";
import { DynamicString, NullableDynamicBoolean } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export interface ExampleWidgetProps {
  value: string;
  variant?: "a" | "b";
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const exampleWidgetConfig = {
  type: "Example" as const,
  category: "inline" as const,  // or "block"
  schema: z.object({
    value: DynamicString,
    variant: z.enum(["a", "b"]).nullable().optional(),
  }),
  description: "Brief description for AI catalog.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * ExampleWidget - Short description
 *
 * Figma reference: Node XXX-XXXXX
 * Styles: (document key styles from Figma)
 */
export const ExampleWidget: React.FC<ExampleWidgetProps> = ({
  value,
  variant = "a",
}) => {
  if (!value) return null;

  return <span className="...">{value}</span>;
};

export default ExampleWidget;
```

---

## Iteration Workflow

### For each widget:

1. **Fetch Figma design**
   ```
   Use mcp__plugin_figma_figma__get_design_context with:
   - nodeId: from table above (e.g., "239-15121")
   - fileKey: "cqul0LOdNZkFPmneOUnus2"
   ```

2. **Create the widget file**
   - Follow the file structure pattern above
   - Match Figma styles exactly (colors, typography, spacing)
   - Use existing Tailwind classes: `comet-body`, `comet-body-s`, `comet-title-m`, etc.

3. **Export from index.ts**
   - Add to `primitives/index.ts` (for inline) or future `blocks/index.ts`
   - Add to `inlineWidgetConfigs` / `inlineWidgetRegistry`

4. **Update main index**
   - Update `data-view-widgets/index.ts` to include in `customViewCatalog` and `customViewRegistry`

5. **Verify**
   ```bash
   npm run typecheck
   npx eslint src/components/pages/CustomViewDemoPage/data-view-widgets/ --fix
   ```

6. **Update docs**
   - Mark complete in `docs/05-migration-checklist.md`

---

## Key Files

| File | Purpose |
|------|---------|
| `data-view-widgets/index.ts` | Main exports: `customViewCatalog`, `customViewRegistry` |
| `data-view-widgets/primitives/index.ts` | Inline widget exports + registry |
| `data-view-widgets/primitives/*.tsx` | Individual inline widgets |
| `@/lib/data-view/index.ts` | Core types: `DynamicString`, `DynamicBoolean`, etc. |
| `@/components/ui/tag.tsx` | Existing Tag component (used by TagWidget) |
| `docs/02-component-specs.md` | Full component specifications |
| `docs/05-migration-checklist.md` | Progress tracking |

---

## Figma Reference

- **File**: `cqul0LOdNZkFPmneOUnus2`
- **URL**: `https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views`
- **Components Page**: Node `239-13327`

### Design Tokens (from Figma)
```
Colors:
- Foreground: #373D4D (text-foreground)
- Muted: #64748B (text-muted-slate)
- Primary: #5155F5 (text-primary)
- Success: #00D41F (text-success)
- Destructive: #F14668 (text-destructive)

Typography:
- Body: 16px Regular, line-height 24px (comet-body)
- Body Accented: 16px Medium (comet-body-accented)
- Body Small: 14px Regular, line-height 20px (comet-body-s)
- Body XS: 12px Regular, line-height 16px (comet-body-xs)
- Title Medium: 20px Medium, line-height 28px (comet-title-m)
```

---

## Dynamic Value Schemas

Use these from `@/lib/data-view` for props that can be data-bound:

```typescript
import {
  DynamicString,           // string | { path: string }
  DynamicNumber,           // number | { path: string }
  DynamicBoolean,          // boolean | { path: string }
  NullableDynamicString,   // string | { path: string } | null
  NullableDynamicNumber,   // number | { path: string } | null
  NullableDynamicBoolean,  // boolean | { path: string } | null
} from "@/lib/data-view";
```

---

## Example Prompt for Next Phase

```
Continue implementing custom view widgets for the Opik frontend.

Read the handoff document first:
apps/opik-frontend/src/components/pages/CustomViewDemoPage/docs/NEXT_ITERATION.md

Current status: Phase 1 (inline primitives) and Phase 2 (block primitives) are COMPLETE.

Next: Implement Phase 3 (Containers):
1. Level1Container - semantic grouping (Figma 239-15701)
2. Level2Container - detail disclosure (Figma 239-15701)
3. InlineRow - horizontal inline grouping (Figma 240-17205)

Create a new `containers/` directory alongside `primitives/` and `blocks/`.

Figma file key: cqul0LOdNZkFPmneOUnus2

For each widget:
1. Fetch Figma design context using MCP tool
2. Create widget file with types, config, and component
3. Add to containers/index.ts exports and registry
4. Update data-view-widgets/index.ts to include in customViewCatalog/customViewRegistry
5. Run typecheck and eslint --fix
6. Mark done in docs/05-migration-checklist.md

Note: Containers need `hasChildren: true` in config and must render children.
```

---

## Notes

- Old `widgets.tsx` was removed - all widgets now in `primitives/`, `blocks/`, (and future `containers/`, `media/`)
- Each category should have its own directory with index.ts barrel
- Containers (Level1, Level2, InlineRow) need `hasChildren: true` in config
- Block widgets should set `category: "block"` in config
- Container widgets need to accept and render `children` prop
