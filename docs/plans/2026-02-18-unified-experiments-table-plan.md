# Unified Experiments Table Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the two-tab Experiments page (Evaluation Suites | General Datasets) with a single unified table showing all experiment types.

**Architecture:** Extend the existing `GeneralDatasetsTab` component to show all experiment types by removing the `types` filter restriction. Remove the tab wrapper from `ExperimentsPage`. Add a `pass_rate` column for eval suite experiments. All row clicks route to `CompareExperimentsPage`.

**Tech Stack:** React, TanStack Table, TanStack Query, TanStack Router, TypeScript

**Design doc:** `docs/plans/2026-02-18-unified-experiments-table-design.md`

---

### Task 1: Thread `types` param through `useGroupedExperimentsList`

The grouping hook currently doesn't accept or forward a `types` param, so all its internal API calls default to `[EXPERIMENT_TYPE.REGULAR]`. We need to thread this param through so the consumer can control which experiment types are fetched.

**Files:**
- Modify: `apps/opik-frontend/src/hooks/useGroupedExperimentsList.ts`

**Step 1: Add `types` to the params type**

In the `UseGroupedExperimentsListParams` type (line ~76), add:
```typescript
type UseGroupedExperimentsListParams = {
  workspaceName: string;
  filters?: Filters;
  sorting?: Sorting;
  groups?: Groups;
  promptId?: string;
  types?: EXPERIMENT_TYPE[];  // <-- ADD THIS
  search?: string;
  page: number;
  size: number;
  groupLimit?: Record<string, number>;
  polling?: boolean;
  expandedMap?: Record<string, boolean>;
};
```

Add the import for `EXPERIMENT_TYPE` if not already present (it's already imported from `@/types/datasets` on line ~18).

**Step 2: Forward `types` to `useExperimentsGroups` call**

Around line ~374, add `types: params.types` to the hook call:
```typescript
} = useExperimentsGroups(
    {
      workspaceName: params.workspaceName,
      filters: filtersWithoutProjectId,
      groups: groups!,
      search: params.search,
      promptId: params.promptId,
      projectId,
      types: params.types,  // <-- ADD THIS
    },
```

**Step 3: Forward `types` to `useExperimentsGroupsAggregations` call**

Around line ~390, add `types: params.types`:
```typescript
} = useExperimentsGroupsAggregations(
    {
      workspaceName: params.workspaceName,
      filters: filtersWithoutProjectId,
      groups: groups!,
      search: params.search,
      promptId: params.promptId,
      projectId,
      types: params.types,  // <-- ADD THIS
    },
```

**Step 4: Forward `types` to the ungrouped `useExperimentsList` call**

Around line ~444, add `types: params.types`:
```typescript
useExperimentsList(
    {
      workspaceName: params.workspaceName,
      filters: filtersWithoutProjectId,
      sorting: params.sorting,
      search: params.search,
      promptId: params.promptId,
      projectId,
      projectDeleted,
      types: params.types,  // <-- ADD THIS
      page: params.page,
      size: params.size,
    },
```

**Step 5: Forward `types` to per-group `useQueries` calls**

Around line ~533, in the `queryParams` object inside `useQueries`, add `types: params.types`:
```typescript
const queryParams: UseExperimentsListParams = {
    workspaceName: params.workspaceName,
    filters: filtersWithoutProject,
    sorting: params.sorting,
    search: params.search,
    promptId: params.promptId,
    projectId: isOrphanProject ? undefined : projectIdValue,
    projectDeleted: isOrphanProject || undefined,
    types: params.types,  // <-- ADD THIS
    page: 1,
    size: extractPageSize(id, params.groupLimit),
};
```

**Step 6: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: No new type errors (existing consumers don't pass `types` so it defaults to `undefined`, which means the API hooks fall back to their own defaults of `[REGULAR]`).

**Step 7: Commit**

```
feat: thread types param through useGroupedExperimentsList
```

---

### Task 2: Remove tab architecture from ExperimentsPage

**Files:**
- Modify: `apps/opik-frontend/src/components/pages/ExperimentsPage/ExperimentsPage.tsx`

**Step 1: Simplify ExperimentsPage**

Replace the entire file content with a simplified version that just renders the header and the GeneralDatasetsTab directly (without tabs):

```tsx
import React from "react";

import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import GeneralDatasetsTab from "./GeneralDatasetsTab/GeneralDatasetsTab";

const ExperimentsPage: React.FC = () => {
  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="pb-1 pt-6"
        direction="horizontal"
        limitWidth
      >
        <h1 className="comet-title-l truncate break-words">Experiments</h1>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <ExplainerDescription
          {...EXPLAINERS_MAP[EXPLAINER_ID.whats_an_experiment]}
        />
      </PageBodyStickyContainer>
      <GeneralDatasetsTab />
    </PageBodyScrollContainer>
  );
};

export default ExperimentsPage;
```

This removes:
- `Tabs`, `TabsList`, `TabsTrigger`, `TabsContent` imports and usage
- `useDatasetsList` check for showing general datasets tab
- `EvaluationSuitesTab` import
- `EXPERIMENTS_TABS` enum
- `useQueryParam` for tab state
- `useEffect` for default tab

**Step 2: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: No type errors. The `EvaluationSuitesTab` import is gone.

**Step 3: Commit**

```
refactor: remove tab architecture from ExperimentsPage
```

---

### Task 3: Delete EvaluationSuitesTab

**Files:**
- Delete: `apps/opik-frontend/src/components/pages/ExperimentsPage/EvaluationSuitesTab/EvaluationSuitesTab.tsx`
- Delete: `apps/opik-frontend/src/components/pages/ExperimentsPage/EvaluationSuitesTab/columns.tsx`

**Step 1: Delete the directory**

```bash
rm -rf apps/opik-frontend/src/components/pages/ExperimentsPage/EvaluationSuitesTab
```

**Step 2: Check for remaining imports**

Search for any remaining references to `EvaluationSuitesTab` in the codebase:

```bash
grep -r "EvaluationSuitesTab" apps/opik-frontend/src/
```

Expected: No results (we removed the import in Task 2).

Also check for references to the column constants:
```bash
grep -r "EVALUATION_SUITES_PINNED_COLUMN\|EVALUATION_SUITES_SELECTABLE_COLUMNS\|EVALUATION_SUITES_DEFAULT_SELECTED_COLUMNS" apps/opik-frontend/src/
```

Expected: No results.

**Step 3: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: No type errors.

**Step 4: Commit**

```
chore: delete EvaluationSuitesTab (replaced by unified table)
```

---

### Task 4: Show all experiment types in the unified table

**Files:**
- Modify: `apps/opik-frontend/src/components/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx`

**Step 1: Pass `types: undefined` to `useGroupedExperimentsList`**

In the `useGroupedExperimentsList` call (around line ~357), the hook currently doesn't pass `types` so it defaults to `[REGULAR]`. We need to explicitly pass `types: undefined` (or omit, but explicit is clearer) to get all experiment types.

Actually, since the API hooks default to `[REGULAR]` when `types` is undefined, we need to pass ALL types explicitly. Check what types exist:

```typescript
// In types/datasets.ts:
export enum EXPERIMENT_TYPE {
  REGULAR = "regular",
  TRIAL = "trial",
  MINI_BATCH = "mini-batch",
}
```

Add a constant at the top of `GeneralDatasetsTab.tsx`:

```typescript
import { EXPERIMENT_TYPE } from "@/types/datasets";

// Show all experiment types in the unified table
const ALL_EXPERIMENT_TYPES = [
  EXPERIMENT_TYPE.REGULAR,
  EXPERIMENT_TYPE.TRIAL,
  EXPERIMENT_TYPE.MINI_BATCH,
];
```

Then pass it to the hook:

```typescript
const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useGroupedExperimentsList({
      workspaceName,
      groupLimit,
      filters,
      sorting: sortedColumns,
      groups: groups,
      types: ALL_EXPERIMENT_TYPES,  // <-- ADD THIS
      search: search!,
      page: page!,
      size: size!,
      expandedMap: expandingConfig.expanded as Record<string, boolean>,
      polling: true,
    });
```

**Note:** The `EXPERIMENT_TYPE` import may already exist in the file from other imports. Check and add only if needed.

**Step 2: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: No type errors.

**Step 3: Commit**

```
feat: show all experiment types in unified experiments table
```

---

### Task 5: Add pass_rate column

**Files:**
- Modify: `apps/opik-frontend/src/components/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx`

**Step 1: Add pass_rate to columnsDef**

In the `columnsDef` useMemo (around line ~158), add a new column after the `total_estimated_cost_avg` column (around line ~300) and before the `COLUMN_FEEDBACK_SCORES_ID` column:

```typescript
{
  id: "pass_rate",
  label: "Pass rate",
  type: COLUMN_TYPE.string,
  accessorFn: (row: GroupedExperiment) => {
    const record = row as unknown as Record<string, unknown>;
    const passRate = record.pass_rate as number | undefined;
    const passedCount = record.passed_count as number | undefined;
    const totalCount = record.total_count as number | undefined;
    if (passRate != null && passedCount != null && totalCount != null) {
      return `${(passRate * 100).toFixed(1)}% (${passedCount}/${totalCount})`;
    }
    return undefined;
  },
},
```

This mirrors the approach used in the now-deleted `EvaluationSuitesTab/columns.tsx`. It returns `undefined` for regular experiments (which the table renders as empty/dash).

**Step 2: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: No type errors.

**Step 3: Commit**

```
feat: add pass_rate column for eval suite experiments
```

---

### Task 6: Verify row click routing works for all types

**Files:**
- Verify: `apps/opik-frontend/src/components/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx`

**Step 1: Check existing handleRowClick**

The existing `handleRowClick` (around line ~429) already routes to:
```typescript
navigate({
    to: "/$workspaceName/experiments/$datasetId/compare",
    params: {
      datasetId: row.dataset_id,
      workspaceName,
    },
    search: {
      experiments: [row.id],
    },
});
```

This works for eval suite experiments too because:
- They have `dataset_id` (the suite's ID)
- `CompareExperimentsPage` fetches items via `GET /datasets/:datasetId/items/experiments/items`
- This endpoint works for `evaluation_suite` type datasets

**No code change needed.** Just verify this is the case.

**Step 2: Full type check and build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: Clean build.

Run: `cd apps/opik-frontend && npm run build`
Expected: Successful build.

**Step 3: Commit (if any cleanup needed)**

---

### Task 7: Final cleanup and verification

**Step 1: Search for orphaned references**

Check for any remaining references to removed concepts:

```bash
grep -r "EXPERIMENTS_TABS\|evaluation-suites.*tab\|EvaluationSuitesTab" apps/opik-frontend/src/ --include="*.ts" --include="*.tsx"
```

Expected: No results.

Check for any references to the old eval suites columns file:
```bash
grep -r "EVALUATION_SUITES_PINNED\|EVALUATION_SUITES_SELECTABLE\|EVALUATION_SUITES_DEFAULT_SELECTED" apps/opik-frontend/src/ --include="*.ts" --include="*.tsx"
```

Expected: No results.

**Step 2: Run linting**

Run: `cd apps/opik-frontend && npx eslint src/components/pages/ExperimentsPage/ --ext .ts,.tsx`
Expected: No new errors.

**Step 3: Verify the app renders correctly**

The unified table should:
- Show ALL experiments (regular + eval suite) in a single table
- Support grouping by dataset (eval suites and datasets appear as groups)
- Support filtering by project, dataset, tags, configuration
- Show pass_rate column (empty for regular experiments, populated for eval suites when backend supports it)
- Row clicks navigate to CompareExperimentsPage for all types
- No tabs visible on the page

**Step 4: Final commit**

```
chore: cleanup unified experiments table
```
