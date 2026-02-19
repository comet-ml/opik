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
import { Button } from "@/components/ui/button";
import DataTable from "@/components/shared/DataTable/DataTable";
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
