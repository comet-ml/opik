---
title: Dynamic Imports for Heavy Components
impact: CRITICAL
impactDescription: directly affects TTI and LCP
tags: bundle, dynamic-import, code-splitting, react-lazy, vite
---

## Dynamic Imports for Heavy Components

Use React's `lazy()` with `Suspense` to lazy-load large components not needed on initial render. Vite automatically code-splits dynamic imports into separate chunks.

**Incorrect (Monaco bundles with main chunk ~300KB):**

```tsx
import { MonacoEditor } from './monaco-editor'

function CodePanel({ code }: { code: string }) {
  return <MonacoEditor value={code} />
}
```

**Correct (Monaco loads on demand):**

```tsx
import { lazy, Suspense } from 'react'

const MonacoEditor = lazy(() => 
  import('./monaco-editor').then(m => ({ default: m.MonacoEditor }))
)

function CodePanel({ code }: { code: string }) {
  return (
    <Suspense fallback={<div>Loading editor...</div>}>
      <MonacoEditor value={code} />
    </Suspense>
  )
}
```

**With loading skeleton:**

```tsx
import { lazy, Suspense } from 'react'
import { Skeleton } from '@/components/ui/skeleton'

const CodeEditor = lazy(() => import('./CodeEditor'))

function EditorPanel() {
  return (
    <Suspense fallback={<Skeleton className="h-96 w-full" />}>
      <CodeEditor />
    </Suspense>
  )
}
```

**Vite-specific notes:**

- Vite automatically creates separate chunks for dynamic imports
- Use `/* @vite-ignore */` comment to skip static analysis if needed
- Dynamic imports are optimized during build with Rollup
- No SSR concerns since Opik frontend is client-side only
