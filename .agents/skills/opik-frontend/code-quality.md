# Code Quality Standards

## Lodash Import Pattern

Always import individually for tree-shaking:

```typescript
// ✅ GOOD - Individual imports
import pick from "lodash/pick";
import merge from "lodash/merge";
import cloneDeep from "lodash/cloneDeep";
import uniqBy from "lodash/uniqBy";
import groupBy from "lodash/groupBy";
import isString from "lodash/isString";
import isArray from "lodash/isArray";
import isEmpty from "lodash/isEmpty";

// ❌ BAD - Includes entire library
import _ from "lodash";

// ❌ BAD - Less efficient than individual imports
import { pick, merge } from "lodash";
```

## Type Checking with Lodash

```typescript
// ✅ Prefer Lodash type methods
import isString from "lodash/isString";
import isNumber from "lodash/isNumber";
import isArray from "lodash/isArray";
import isObject from "lodash/isObject";
import isNil from "lodash/isNil";
import isEmpty from "lodash/isEmpty";

if (isString(value)) { /* handle string */ }
if (isArray(value)) { /* handle array */ }
```

## Naming Conventions

```typescript
// Components: PascalCase
DataTable.tsx
UserProfile.tsx

// Utilities: camelCase
dateUtils.ts
apiClient.ts

// Hooks: camelCase starting with 'use'
useEntityList.ts
useDebounce.ts

// Constants: SCREAMING_SNAKE_CASE
const COLUMN_TYPE = { STRING: "string" } as const;
const API_ENDPOINTS = { USERS: "/api/v1/users" } as const;

// Event handlers: Descriptive
const handleDeleteClick = useCallback(() => {}, []);
const handleUserSelect = useCallback((user: UserData) => {}, []);

// ❌ Avoid generic names
const handleClick = () => {};  // Too vague
```

## Import Order

```typescript
// 1. React
import React, { useState, useCallback } from "react";

// 2. External libraries (tanstack, zod, axios, lucide-react, etc.)
import { useQuery } from "@tanstack/react-query";
import { SquareArrowOutUpRight } from "lucide-react";
import get from "lodash/get";

// 3. Internal imports (@/) — no strict ordering required
import { Button } from "@/ui/button";
import DataTable from "@/shared/DataTable/DataTable";
import useAppStore from "@/store/AppStore";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import { cn } from "@/lib/utils";
import { COLUMN_TYPE } from "@/types/shared";
```

Internal `@/` imports have no strict order. Do not reorder existing imports for style.

## Dependency Architecture

**CRITICAL: After modifying imports, run:**
```bash
cd apps/opik-frontend && npm run deps:validate
```

### Layer Hierarchy (one-way only)
`ui → shared → pages-shared → pages`

### Rules
- No circular dependencies
- No importing from higher layers
- No cross-page imports
- API layer cannot import components (except use-toast.ts)

## Extract Self-Contained Sub-Components

When a page-level component crosses ~500–600 lines and contains a sub-region with its own state, queries, mutations, and handlers — peel that region into a sibling component. Don't pull a region out just because it's visually distinct; extract when it has *independent data dependencies*.

### Good extraction candidates
- A dropdown menu that owns its own `useQuery`/`useMutation` and a derived map/list
- A dialog that manages its own form state and validation
- A toolbar section that uses 3+ hooks the rest of the page doesn't need

### Anti-pattern
Pulling out a small JSX block that only takes props — that's "needless component", not cleanup.

```tsx
// PROMPT TAB BEFORE (728 lines) — environment deploy logic inlined
const PromptTab = ({ prompt }) => {
  // ...100+ lines of unrelated state/queries
  const { data: envs } = useEnvironmentsList();
  const environments = useMemo(/* sort */, [envs]);
  const environmentOwners = useMemo(/* map of env→version */, [versions]);
  const { mutate, isPending } = useSetPromptVersionEnvironmentMutation();
  const handleDeploy = useCallback(/* ... */, [/* 6 deps */]);
  const handleClear = useCallback(/* ... */, [/* 5 deps */]);
  // ...
  return (
    <DropdownMenu>{/* 70 lines of menu items */}</DropdownMenu>
  );
};

// AFTER (584 lines) — sub-component owns its own data dependencies
const PromptTab = ({ prompt }) => {
  // ...no env state in this scope anymore
  return (
    <DeployToEnvironmentMenu
      promptId={prompt.id}
      versionId={effectiveVersionId}
      versionLabel={activeVersionLabel}
      versions={versions}
      totalVersions={total}
      activeEnvironment={activeVersionEnvironment}
    />
  );
};
```

**Why:** Sub-component owns the environments query, the owner-map memo, the mutation, both handlers, and the toasts. Parent shrinks by ~120 lines and no longer holds workspace/configuration/env imports. The handlers' dependency arrays also shrink.

**How to apply:** When you see ≥3 hooks (`useQuery`, `useMutation`, `useMemo`, `useCallback`) all feeding one JSX region, that region wants to be its own component. Pass only the upstream props it needs — never pass refs into the child to "share state up." See [PromptTab.tsx](../../../apps/opik-frontend/src/v2/pages/PromptPage/PromptTab/PromptTab.tsx) and [DeployToEnvironmentMenu.tsx](../../../apps/opik-frontend/src/v2/pages/PromptPage/PromptTab/DeployToEnvironmentMenu.tsx) for a worked example.

## Co-locate Helpers with Their Component Family

When two sibling components share validation/formatting helpers, put the helpers in a `helpers.ts` next to them — not in `lib/`. `lib/` is for repo-wide utilities. Co-located helpers keep the surface area small and signal the helper is scoped to the family.

```
shared/EnvironmentLabel/
  EnvironmentLabel.tsx     // imports from ./helpers
  EnvironmentBadge.tsx     // imports from ./helpers
  helpers.ts               // resolveEnvironmentColor, getContrastingTextColor
```

**Why:** Before extraction, `EnvironmentLabel.tsx` and `EnvironmentBadge.tsx` each defined their own `resolveColor` (same body, same constants). Promoting to `lib/colorVariants.ts` would collide with an existing palette-based `resolveColor`; co-locating avoided the naming conflict and kept the surface narrow.

**How to apply:** If a helper is only meaningful within one component family, never make it the codebase's problem. Move it to `lib/` only when a third unrelated consumer appears.
