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
// 1. React and external libraries
import React, { useState, useCallback } from "react";
import { useQuery } from "@tanstack/react-query";

// 2. UI components
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";

// 3. Shared components
import DataTable from "@/components/shared/DataTable/DataTable";

// 4. Hooks and utilities
import { cn } from "@/lib/utils";
import useEntityStore from "@/store/EntityStore";

// 5. Lodash utilities (grouped)
import isString from "lodash/isString";
import isEmpty from "lodash/isEmpty";

// 6. Types and constants
import { COLUMN_TYPE } from "@/types/shared";
```

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
