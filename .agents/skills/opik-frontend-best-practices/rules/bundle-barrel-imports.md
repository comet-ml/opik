---
title: Avoid Barrel File Imports
impact: CRITICAL
impactDescription: 200-800ms import cost, slow builds
tags: bundle, imports, tree-shaking, barrel-files, performance, vite
---

## Avoid Barrel File Imports

Import directly from source files instead of barrel files to avoid loading thousands of unused modules. **Barrel files** are entry points that re-export multiple modules (e.g., `index.js` that does `export * from './module'`).

Popular icon and component libraries can have **up to 10,000 re-exports** in their entry file. For many React packages, **it takes 200-800ms just to import them**, affecting both development speed and production cold starts.

**Incorrect (imports entire library):**

```tsx
import { Check, X, Menu } from 'lucide-react'
// Loads 1,583 modules, takes ~2.8s extra in dev
// Runtime cost: 200-800ms on every cold start

import { Button, TextField } from '@mui/material'
// Loads 2,225 modules, takes ~4.2s extra in dev
```

**Correct (imports only what you need):**

```tsx
import Check from 'lucide-react/dist/esm/icons/check'
import X from 'lucide-react/dist/esm/icons/x'
import Menu from 'lucide-react/dist/esm/icons/menu'
// Loads only 3 modules (~2KB vs ~1MB)

import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
// Loads only what you use
```

**Vite-specific considerations:**

While Vite has excellent tree-shaking capabilities during production builds via Rollup, barrel file imports still impact:

- **Development server startup time**: Vite must parse and transform all re-exported modules
- **Hot Module Replacement (HMR)**: More modules = slower updates
- **Build time**: Even with tree-shaking, analyzing large module graphs is expensive
- **IDE performance**: TypeScript must resolve all exports for autocomplete

**Best practices for Vite:**

1. **Use direct imports** for large libraries (lucide-react, @radix-ui, lodash)
2. **Barrel files are OK** for small, co-located modules (your own components in a feature folder)
3. **Configure Vite optimizeDeps** for frequently used barrel imports:

```ts
// vite.config.ts
export default {
  optimizeDeps: {
    include: ['lodash-es'] // Pre-bundle specific libraries
  }
}
```

Direct imports provide 15-70% faster dev server startup, 28% faster builds, 40% faster cold starts, and significantly faster HMR.

Libraries commonly affected: `lucide-react`, `@mui/material`, `@mui/icons-material`, `@tabler/icons-react`, `react-icons`, `@headlessui/react`, `@radix-ui/react-*`, `lodash`, `lodash-es`, `ramda`, `date-fns`, `rxjs`, `react-use`.
