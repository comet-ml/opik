---
name: opik-frontend-best-practices
description: React performance optimization guidelines for Opik frontend. This skill should be used when writing, reviewing, or refactoring React code to ensure optimal performance patterns. Triggers on tasks involving React components, data fetching with React Query, bundle optimization, or performance improvements in the Opik frontend application.
license: MIT
metadata:
  author: opik
  version: "1.0.0"
---

# Opik Frontend Best Practices

Comprehensive performance optimization guide for the Opik React application built with Vite, React Query (TanStack Query), and TanStack Router. Contains 40+ rules across 7 categories, prioritized by impact to guide development and code review.

## Tech Stack

- **React 18** with TypeScript
- **Vite** for build tooling and dev server
- **TanStack Query** (React Query) for data fetching and caching
- **TanStack Router** for routing
- **Zustand** for state management
- **Tailwind CSS** + **shadcn/ui** for styling

## When to Apply

Reference these guidelines when:
- Writing new React components
- Implementing data fetching with React Query
- Reviewing code for performance issues
- Refactoring existing React code
- Optimizing bundle size or load times
- Working with forms, tables, or data-heavy components

## Rule Categories by Priority

| Priority | Category | Impact | Prefix |
|----------|----------|--------|--------|
| 1 | Data Fetching & Caching | HIGH | `data-` |
| 2 | Bundle Size Optimization | CRITICAL | `bundle-` |
| 3 | Re-render Optimization | MEDIUM | `rerender-` |
| 4 | Rendering Performance | MEDIUM | `rendering-` |
| 5 | Client-Side Patterns | MEDIUM | `client-` |
| 6 | JavaScript Performance | LOW-MEDIUM | `js-` |
| 7 | Advanced Patterns | LOW | `advanced-` |

## Quick Reference

### 1. Data Fetching & Caching (HIGH)

- `data-react-query-dedup` - Use React Query for automatic request deduplication
- `data-parallel-fetching` - Use Promise.all() for independent operations
- `data-defer-await` - Move await into branches where actually used
- `data-dependencies` - Handle partial dependencies correctly
- `data-api-routes` - Start promises early, await late

### 2. Bundle Size Optimization (CRITICAL)

- `bundle-barrel-imports` - Import directly, avoid barrel files (Vite-specific)
- `bundle-dynamic-imports` - Use React.lazy for heavy components
- `bundle-defer-third-party` - Load analytics/logging after hydration
- `bundle-conditional` - Load modules only when feature is activated
- `bundle-preload` - Preload on hover/focus for perceived speed

### 3. Re-render Optimization (MEDIUM)

- `rerender-defer-reads` - Don't subscribe to state only used in callbacks
- `rerender-memo` - Extract expensive work into memoized components
- `rerender-dependencies` - Use primitive dependencies in effects
- `rerender-derived-state` - Subscribe to derived booleans, not raw values
- `rerender-functional-setstate` - Use functional setState for stable callbacks
- `rerender-lazy-state-init` - Pass function to useState for expensive values
- `rerender-transitions` - Use startTransition for non-urgent updates
- `rerender-memo-with-default-value` - Extract default non-primitive values
- `rerender-simple-expression-in-memo` - Don't wrap simple expressions

### 4. Rendering Performance (MEDIUM)

- `rendering-animate-svg-wrapper` - Animate div wrapper, not SVG element
- `rendering-content-visibility` - Use content-visibility for long lists
- `rendering-hoist-jsx` - Extract static JSX outside components
- `rendering-svg-precision` - Reduce SVG coordinate precision
- `rendering-activity` - Use Activity component for show/hide
- `rendering-conditional-render` - Use ternary, not && for conditionals
- `rendering-usetransition-loading` - Use useTransition over manual loading states

### 5. Client-Side Patterns (MEDIUM)

- `client-event-listeners` - Deduplicate global event listeners
- `client-passive-event-listeners` - Use passive listeners for scroll performance
- `client-localstorage-schema` - Version and minimize localStorage data

### 6. JavaScript Performance (LOW-MEDIUM)

- `js-batch-dom-css` - Group CSS changes via classes or cssText
- `js-index-maps` - Build Map for repeated lookups
- `js-cache-property-access` - Cache object properties in loops
- `js-cache-function-results` - Cache function results in module-level Map
- `js-cache-storage` - Cache localStorage/sessionStorage reads
- `js-combine-iterations` - Combine multiple filter/map into one loop
- `js-length-check-first` - Check array length before expensive comparison
- `js-early-exit` - Return early from functions
- `js-hoist-regexp` - Hoist RegExp creation outside loops
- `js-min-max-loop` - Use loop for min/max instead of sort
- `js-set-map-lookups` - Use Set/Map for O(1) lookups
- `js-tosorted-immutable` - Use toSorted() for immutability

### 7. Advanced Patterns (LOW)

- `advanced-event-handler-refs` - Store event handlers in refs
- `advanced-use-latest` - useLatest for stable callback refs

## How to Use

Read individual rule files for detailed explanations and code examples:

```
rules/data-react-query-dedup.md
rules/bundle-barrel-imports.md
rules/rerender-memo.md
```

Each rule file contains:
- Brief explanation of why it matters
- Incorrect code example with explanation
- Correct code example with explanation
- Additional context and references

## Full Compiled Document

For the complete guide with all rules expanded: `AGENTS.md`
