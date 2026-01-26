---
title: Defer Non-Critical Third-Party Libraries
impact: MEDIUM
impactDescription: loads after hydration
tags: bundle, third-party, analytics, defer, react-lazy
---

## Defer Non-Critical Third-Party Libraries

Analytics, logging, and error tracking don't block user interaction. Load them after initial render using React's lazy loading.

**Incorrect (blocks initial bundle):**

```tsx
import { Analytics } from '@vercel/analytics/react'

export default function App({ children }) {
  return (
    <div>
      {children}
      <Analytics />
    </div>
  )
}
```

**Correct (loads after initial render):**

```tsx
import { lazy, Suspense } from 'react'

const Analytics = lazy(() => 
  import('@vercel/analytics/react').then(m => ({ default: m.Analytics }))
)

export default function App({ children }) {
  return (
    <div>
      {children}
      <Suspense fallback={null}>
        <Analytics />
      </Suspense>
    </div>
  )
}
```

**Alternative (load in useEffect):**

```tsx
import { useEffect, useState } from 'react'

export default function App({ children }) {
  const [Analytics, setAnalytics] = useState<React.ComponentType | null>(null)

  useEffect(() => {
    import('@vercel/analytics/react')
      .then(m => setAnalytics(() => m.Analytics))
      .catch(() => {}) // Handle error silently
  }, [])

  return (
    <div>
      {children}
      {Analytics && <Analytics />}
    </div>
  )
}
```
