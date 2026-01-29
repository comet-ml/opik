# UI Components & Design System

## Button Variants

```typescript
// Primary actions
<Button variant="default">Save</Button>
<Button variant="special">Special Action</Button>

// Secondary actions
<Button variant="secondary">Cancel</Button>
<Button variant="outline">Edit</Button>

// Destructive actions
<Button variant="destructive">Delete</Button>

// Minimal/Ghost actions
<Button variant="ghost">Link Action</Button>
<Button variant="minimal">Subtle Action</Button>

// Icon buttons
<Button variant="default" size="icon"><Icon /></Button>
<Button variant="ghost" size="icon-sm"><Icon /></Button>

// Sizes: "3xs" | "2xs" | "sm" | "default" | "lg"
// Icon sizes: "icon-3xs" | "icon-2xs" | "icon-xs" | "icon-sm" | "icon" | "icon-lg"
```

## DataTable Column Types

```typescript
import { COLUMN_TYPE, ROW_HEIGHT } from "@/types/shared";

// Available types
COLUMN_TYPE.string      // Text data
COLUMN_TYPE.number      // Numeric data
COLUMN_TYPE.time        // Date/time data
COLUMN_TYPE.duration    // Duration data
COLUMN_TYPE.cost        // Cost data
COLUMN_TYPE.list        // Array data
COLUMN_TYPE.dictionary  // Object data
COLUMN_TYPE.numberDictionary  // Feedback scores
COLUMN_TYPE.category    // Category/tag data
```

## Typography Classes

```css
/* Titles */
.comet-title-xl    /* 3xl font-medium */
.comet-title-l     /* 2xl font-medium */
.comet-title-m     /* xl font-medium */
.comet-title-s     /* lg font-medium */
.comet-title-xs    /* sm font-medium */

/* Body text */
.comet-body               /* base font-normal */
.comet-body-accented      /* base font-medium */
.comet-body-s             /* sm font-normal */
.comet-body-s-accented    /* sm font-medium */
.comet-body-xs            /* xs font-normal */

/* Code */
.comet-code               /* monospace font */
```

## Layout Classes

```css
.comet-header-height      /* 64px header */
.comet-sidebar-width      /* sidebar width */
.comet-content-inset      /* content padding */
.comet-custom-scrollbar   /* custom scrollbar */
.comet-no-scrollbar       /* hide scrollbar */
```

## Custom CSS Class Prefix

Always use `comet-` prefix for custom classes:
```typescript
className="comet-table-row-active"
className="comet-sidebar-collapsed"
```

## Dark Theme Support

```typescript
// ✅ GOOD - Use theme-aware classes
<div className="bg-card text-card-foreground border border-border">
  <h2 className="text-primary">Title</h2>
  <p className="text-muted-foreground">Description</p>
</div>

// ❌ BAD - Hard-coded colors
<div className="bg-white text-black border-gray-200">
```

Add new colors to `main.css` with dark alternatives:
```css
:root {
  --my-custom-color: 210 100% 50%;
}
.dark {
  --my-custom-color: 220 100% 60%;
}
```

## Color System

```css
/* Primary */
bg-primary text-primary-foreground hover:bg-primary-hover

/* Secondary */
bg-secondary text-secondary-foreground

/* Muted */
bg-muted text-muted-foreground text-muted-gray

/* Destructive */
bg-destructive text-destructive-foreground border-destructive
```

## State Classes

```tsx
// Loading
<Skeleton className="h-4 w-full" />

// Error
className={cn("border", { "border-destructive": hasError })}

// Active
"comet-table-row-active"

// Disabled
"disabled:opacity-50 disabled:pointer-events-none"
```

## Spacing

- Gaps: `gap-2`, `gap-4`, `gap-6`, `gap-8`
- Padding: `p-2`, `p-4`, `p-6`
- Border radius: `rounded-md` (default), `rounded-lg`

## Component Placement

- **Reusable**: `components/shared/`
- **Page-specific**: Same folder as parent component
- **Low-level UI**: `components/ui/`
