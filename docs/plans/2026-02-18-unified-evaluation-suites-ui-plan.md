# Unified Evaluation Suites UI — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Consolidate datasets and evaluation suites into a single UI surface using evaluation suite pages as the primary entity with type-based conditional rendering for legacy datasets.

**Architecture:** Extend existing `EvaluationSuite*` page components to handle both `DATASET_TYPE.EVALUATION_SUITE` and `DATASET_TYPE.DATASET` items. Remove all separate dataset pages, the `DatasetDraftStore`, and the `useDatasetIdFromURL` hook. Add ~8 type checks for conditional rendering. Legacy `/datasets/*` URLs redirect to `/evaluation-suites/*`.

**Tech Stack:** React, TypeScript, TanStack Router, TanStack Table, Zustand, TanStack Query

**Design doc:** `docs/plans/2026-02-18-unified-evaluation-suites-ui-design.md`

**Base path:** `apps/opik-frontend/src`

---

### Task 1: Create New Cell Components

**Files:**
- Create: `components/pages/EvaluationSuitesPage/TypeBadgeCell.tsx`
- Create: `components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsTab/DataObjectCell.tsx`

**Step 1: Create `TypeBadgeCell`**

This cell renders a badge showing "Evaluation suite" or "Dataset" in the list page type column.

```tsx
// TypeBadgeCell.tsx
import { CellContext } from "@tanstack/react-table";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { Tag } from "@/components/ui/tag";

const TYPE_LABELS: Record<string, string> = {
  [DATASET_TYPE.EVALUATION_SUITE]: "Evaluation suite",
  [DATASET_TYPE.DATASET]: "Dataset",
};

const TypeBadgeCell = (context: CellContext<Dataset, unknown>) => {
  const type = context.row.original.type;
  const label = TYPE_LABELS[type ?? DATASET_TYPE.DATASET] ?? "Dataset";

  return (
    <Tag size="sm" variant="gray">
      {label}
    </Tag>
  );
};

export default TypeBadgeCell;
```

**Step 2: Create `DataObjectCell`**

This cell renders a truncated JSON preview of the entire `data` object for evaluation suite items (single "Data" column).

```tsx
// DataObjectCell.tsx
import { CellContext } from "@tanstack/react-table";
import { DatasetItem } from "@/types/datasets";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const DataObjectCell = (context: CellContext<DatasetItem, unknown>) => {
  const data = context.row.original.data;
  const text = data ? JSON.stringify(data) : "";

  return <CellWrapper metadata={text} content={text} />;
};

export default DataObjectCell;
```

> **Note:** Look at how existing cells like `AutodetectCell` or `CellWrapper` work and match the pattern. The cell should truncate long content and show a tooltip on hover if the pattern supports it.

**Step 3: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: No new errors

---

### Task 2: Delete `DatasetDraftStore` and Update All Consumers

The `EvaluationSuiteDraftStore` already exports every hook that `DatasetDraftStore` exports (with identical signatures). The behavior-related state stays empty when unused.

**Files:**
- Delete: `store/DatasetDraftStore.ts`
- Modify: All files that import from `DatasetDraftStore` (list below)

**Step 1: Find all consumers**

Search for all imports of `DatasetDraftStore`. These files need updating:

1. `components/pages/DatasetItemsPage/DatasetItemsPageVersioned.tsx` — **will be deleted in Task 9, skip**
2. `components/pages/DatasetItemsPage/DatasetItemsTab/DatasetItemsTab.tsx` — **will be deleted in Task 9, skip**
3. `components/pages/DatasetItemsPage/DatasetItemsTab/hooks/useMergedDatasetItems.ts` — **will be deleted in Task 9, skip**
4. `components/pages/DatasetItemsPage/DatasetItemRowActionsCell.tsx` — **SHARED, must update**
5. `components/pages/DatasetItemsPage/DatasetItemsActionsPanel.tsx` — **SHARED, must update**
6. `components/pages/DatasetItemsPage/AddTagDialog.tsx` — **will be deleted in Task 9, skip**
7. `components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditorAutosaveContext.tsx` — **SHARED, must update**
8. `components/pages/DatasetItemsPage/AddDatasetItemSidebar.tsx` — **SHARED, must update**
9. `hooks/useDatasetIdFromCompareExperimentsURL.ts` — **check if needs update**

**Step 2: Update shared consumers**

For each shared file, replace:
```typescript
// OLD
import { useXxx } from "@/store/DatasetDraftStore";
// NEW
import { useXxx } from "@/store/EvaluationSuiteDraftStore";
```

Files to update:
- `components/pages/DatasetItemsPage/DatasetItemRowActionsCell.tsx`
- `components/pages/DatasetItemsPage/DatasetItemsActionsPanel.tsx`
- `components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditorAutosaveContext.tsx`
- `components/pages/DatasetItemsPage/AddDatasetItemSidebar.tsx`

**Step 3: Delete `DatasetDraftStore`**

Delete: `store/DatasetDraftStore.ts`

**Step 4: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: Errors only from files that will be deleted in Task 9 (DatasetsPage, DatasetItemsPage, etc.). Those are expected — they'll be removed later.

---

### Task 3: Update Sidebar — Remove Datasets Item

**Files:**
- Modify: `components/layout/SideBar/SideBar.tsx`

**Step 1: Remove datasets menu item**

In `MENU_ITEMS` array (line 99-136), remove the datasets item:

```typescript
// DELETE this block (lines 119-126):
{
  id: "datasets",
  path: "/$workspaceName/datasets",
  type: MENU_ITEM_TYPE.router,
  icon: Database,
  label: "Datasets",
  count: "datasets",
},
```

**Step 2: Remove datasets count query**

Delete the datasets-specific query (lines 228-238):
```typescript
// DELETE:
const { data: datasetsData } = useDatasetsList(
  {
    workspaceName,
    type: DATASET_TYPE.DATASET,
    page: 1,
    size: 1,
  },
  {
    placeholderData: keepPreviousData,
  },
);
```

**Step 3: Update evaluation suites query to include all types**

Change the evaluation suites query (lines 240-250) to remove type filter:
```typescript
// BEFORE:
const { data: evaluationSuitesData } = useDatasetsList(
  {
    workspaceName,
    type: DATASET_TYPE.EVALUATION_SUITE,
    ...
  },
// AFTER:
const { data: evaluationSuitesData } = useDatasetsList(
  {
    workspaceName,
    // no type filter — count includes both types
    ...
  },
```

**Step 4: Remove `hasGeneralDatasets` logic**

Delete lines 352, 364-368:
```typescript
// DELETE from countDataMap:
datasets: datasetsData?.total,

// DELETE entirely:
const hasGeneralDatasets = (datasetsData?.total ?? 0) > 0;
const hiddenItemIds = new Set<string>(
  hasGeneralDatasets ? [] : ["datasets"],
);
```

**Step 5: Remove `hiddenItemIds` usage**

In `renderItems` (line 382), remove the filter:
```typescript
// BEFORE:
return items
  .filter((item) => !hiddenItemIds.has(item.id))
  .map(...)
// AFTER:
return items.map(...)
```

**Step 6: Clean up unused imports**

Remove `Database` from lucide-react imports (no longer used).

**Step 7: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`

---

### Task 4: Update List Page — Show Both Types

**Files:**
- Modify: `components/pages/EvaluationSuitesPage/columns.tsx`
- Modify: `components/pages/EvaluationSuitesPage/EvaluationSuitesPage.tsx`

**Step 1: Add type column to `columns.tsx`**

```typescript
// In columns.tsx, add import:
import TypeBadgeCell from "@/components/pages/EvaluationSuitesPage/TypeBadgeCell";

// Add to DEFAULT_COLUMNS array after the "name" entry:
{
  id: "type",
  label: "Type",
  type: COLUMN_TYPE.string,
  cell: TypeBadgeCell as never,
},

// Add "type" to DEFAULT_SELECTED_COLUMNS:
export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "type",
  "description",
  "dataset_items_count",
  "most_recent_experiment_at",
  "created_at",
];
```

**Step 2: Remove type filter from data fetch**

In `EvaluationSuitesPage.tsx` line 90-103, remove the `type` parameter:

```typescript
// BEFORE:
const { data, isPending, isPlaceholderData, isFetching } = useDatasetsList(
  {
    workspaceName,
    type: DATASET_TYPE.EVALUATION_SUITE,
    ...
  },
// AFTER:
const { data, isPending, isPlaceholderData, isFetching } = useDatasetsList(
  {
    workspaceName,
    // No type filter — shows both datasets and evaluation suites
    ...
  },
```

**Step 3: Make row actions dynamic based on row type**

The current static `EvaluationSuiteRowActionsCell` (line 40-43) needs to become dynamic. Replace the static cell with a wrapper that picks the right config based on `row.original.type`:

```typescript
// Import AddEditDatasetDialog for legacy dataset rows
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";

// Replace the static cell with a dynamic one:
const EvaluationSuiteRowActionsCell = createDatasetRowActionsCell({
  entityName: "evaluation suite",
  EditDialog: AddEditEvaluationSuiteDialog,
});

const DatasetRowActionsCell = createDatasetRowActionsCell({
  entityName: "dataset",
  EditDialog: AddEditDatasetDialog,
  showDownload: true,
});
```

Then in the `columns` useMemo, use a wrapper cell that delegates based on type:

```typescript
generateActionsColumDef({
  cell: ({ row, ...rest }) => {
    const Cell = row.original.type === DATASET_TYPE.DATASET
      ? DatasetRowActionsCell
      : EvaluationSuiteRowActionsCell;
    return Cell({ row, ...rest });
  },
}),
```

> **Note:** Check how `createDatasetRowActionsCell` works and how `generateActionsColumDef` accepts the cell prop. The wrapper may need to match the exact CellContext signature. Adapt accordingly.

**Step 4: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`

---

### Task 5: Update Items Page — Type-Aware Tabs and Save

**Files:**
- Modify: `components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsPage.tsx`

**Step 1: Get dataset type from the fetched data**

The page already fetches `suite` via `useDatasetById`. Extract the type:

```typescript
const datasetType = suite?.type;
const isEvaluationSuite = datasetType === DATASET_TYPE.EVALUATION_SUITE;
```

Add `DATASET_TYPE` to the imports from `@/types/datasets`.

**Step 2: Import `useGetChangesPayload`**

Add to the existing import from `EvaluationSuiteDraftStore`:

```typescript
import {
  useHasDraft,
  useClearDraft,
  useGetFullChangesPayload,
  useGetChangesPayload,  // ADD THIS
} from "@/store/EvaluationSuiteDraftStore";
```

Call the hook:
```typescript
const getChangesPayload = useGetChangesPayload();
```

**Step 3: Conditional save payload**

Modify `buildMutationPayload` (lines 91-115):

```typescript
const buildMutationPayload = (
  tags?: string[],
  changeDescription?: string,
  override = false,
) => {
  if (isEvaluationSuite) {
    const changes = getFullChangesPayload([]);
    return {
      datasetId: suiteId,
      payload: {
        added_items: changes.addedItems,
        edited_items: changes.editedItems,
        deleted_ids: changes.deletedIds,
        base_version: suite?.latest_version?.id ?? "",
        tags,
        change_description: changeDescription,
        evaluators:
          changes.evaluators.length > 0 ? changes.evaluators : undefined,
        execution_policy: changes.execution_policy ?? undefined,
      },
      override,
    };
  }

  // Legacy dataset — items only, no evaluators/execution_policy
  const changes = getChangesPayload();
  return {
    datasetId: suiteId,
    payload: {
      added_items: changes.addedItems,
      edited_items: changes.editedItems,
      deleted_ids: changes.deletedIds,
      base_version: suite?.latest_version?.id ?? "",
      tags,
      change_description: changeDescription,
    },
    override,
  };
};
```

**Step 4: Conditionally render Evaluators tab**

In the JSX (lines 254-279), wrap the Evaluators tab trigger and content:

```tsx
<TabsList variant="underline">
  <TabsTrigger variant="underline" value="items">
    Items
  </TabsTrigger>
  {isEvaluationSuite && (
    <TabsTrigger variant="underline" value="evaluators">
      Evaluators
    </TabsTrigger>
  )}
  <TabsTrigger variant="underline" value="version-history">
    Version history
  </TabsTrigger>
</TabsList>
<TabsContent value="items">
  <EvaluationSuiteItemsTab
    datasetId={suiteId}
    datasetName={suite?.name}
    datasetStatus={suite?.status}
    datasetType={datasetType}
  />
</TabsContent>
{isEvaluationSuite && (
  <TabsContent value="evaluators">
    <BehaviorsSection datasetId={suiteId} />
  </TabsContent>
)}
<TabsContent value="version-history">
  <VersionHistoryTab datasetId={suiteId} />
</TabsContent>
```

**Step 5: Pass `datasetType` to the items tab**

The `EvaluationSuiteItemsTab` will need the type to decide columns and panel. We pass it as a new prop `datasetType`. The tab component will be updated in Task 6.

**Step 6: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`

---

### Task 6: Update Items Tab — Type-Based Columns and Panel

**Files:**
- Modify: `components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsTab/EvaluationSuiteItemsTab.tsx`

**Step 1: Accept `datasetType` prop**

```typescript
interface EvaluationSuiteItemsTabProps {
  datasetId: string;
  datasetName?: string;
  datasetStatus?: DATASET_STATUS;
  datasetType?: DATASET_TYPE;  // ADD
}
```

Extract:
```typescript
const isEvaluationSuite = datasetType === DATASET_TYPE.EVALUATION_SUITE;
```

**Step 2: Import `DataObjectCell` and `DatasetItemEditor`**

```typescript
import DataObjectCell from "./DataObjectCell";
import DatasetItemEditor from "@/components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditor";
```

Note: Check the exact path and export name for `DatasetItemEditor`. It wraps `DatasetItemEditorAutosaveProvider`.

**Step 3: Conditional column generation**

Replace the `columnsData` useMemo (lines 346-409):

```typescript
const columnsData = useMemo((): ColumnData<DatasetItem>[] => {
  const cols: ColumnData<DatasetItem>[] = [
    {
      id: COLUMN_ID_ID,
      label: "ID",
      type: COLUMN_TYPE.string,
      cell: IdCell as never,
    },
  ];

  if (isEvaluationSuite) {
    // Single "data" column for evaluation suites
    cols.push({
      id: "data",
      label: "Data",
      type: COLUMN_TYPE.dictionary,
      accessorFn: (row) => row.data,
      cell: DataObjectCell as never,
    });
    cols.push(
      {
        id: "description",
        label: "Description",
        type: COLUMN_TYPE.string,
        accessorFn: (row) =>
          (row.data as Record<string, unknown> | undefined)?.description ?? "",
      },
      {
        id: "expected_behaviors",
        label: "Evaluators",
        type: COLUMN_TYPE.string,
        cell: behaviorsCountCell as never,
      },
      {
        id: "execution_policy",
        label: "Execution policy",
        type: COLUMN_TYPE.string,
        cell: ExecutionPolicyCell as never,
      },
    );
  } else {
    // Dynamic per-field columns for legacy datasets
    cols.push(
      ...dynamicDatasetColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            accessorFn: (row) => get(row, ["data", label], ""),
            cell: AutodetectCell as never,
          }) as ColumnData<DatasetItem>,
      ),
    );
  }

  // Common trailing columns
  cols.push(
    {
      id: "tags",
      label: "Tags",
      type: COLUMN_TYPE.list,
      iconType: "tags",
      accessorFn: (row) => row.tags || [],
      cell: ListCell as never,
    },
    {
      id: "created_at",
      label: "Created",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.created_at),
    },
    {
      id: "last_updated_at",
      label: "Last updated",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.last_updated_at),
    },
    {
      id: "created_by",
      label: "Created by",
      type: COLUMN_TYPE.string,
    },
  );

  return cols;
}, [isEvaluationSuite, dynamicDatasetColumns, behaviorsCountCell]);
```

**Step 4: Conditional default selected columns**

```typescript
const SUITE_DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID, "data", "description", "expected_behaviors", "created_at", "tags",
];

const DATASET_DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID, "created_at", "tags",
];
```

Use the appropriate default based on type in the `useLocalStorageState` call. Note: the storage key should differ by type to avoid conflicts:

```typescript
const selectedColumnsKey = isEvaluationSuite
  ? "evaluation-suite-items-selected-columns"
  : "dataset-items-selected-columns";

const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
  selectedColumnsKey,
  {
    defaultValue: isEvaluationSuite
      ? SUITE_DEFAULT_SELECTED_COLUMNS
      : DATASET_DEFAULT_SELECTED_COLUMNS,
  },
);
```

**Step 5: Conditional side panel**

Replace the `EvaluationSuiteItemPanel` render (lines 674-682) with conditional:

```tsx
{isEvaluationSuite ? (
  <EvaluationSuiteItemPanel
    datasetItemId={activeRowId as string}
    datasetId={datasetId}
    columns={datasetColumns}
    onClose={handleClose}
    isOpen={Boolean(activeRowId)}
    rows={rows}
    setActiveRowId={setActiveRowId}
  />
) : (
  <DatasetItemEditor
    datasetItemId={activeRowId as string}
    datasetId={datasetId}
    columns={datasetColumns}
    onClose={handleClose}
    isOpen={Boolean(activeRowId)}
    rows={rows}
    setActiveRowId={setActiveRowId}
  />
)}
```

> **Note:** Check `DatasetItemEditor` props — it lives at `DatasetItemsPage/DatasetItemEditor/DatasetItemEditor.tsx`. Verify the prop interface matches. The `DatasetItemEditorAutosaveContext` inside it imports from `DatasetDraftStore` — we updated that in Task 2.

**Step 6: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`

---

### Task 7: Update Router — Legacy Redirects

**Files:**
- Modify: `router.tsx`

**Step 1: Remove dataset page imports**

Delete lines 13-15:
```typescript
// DELETE:
import DatasetItemsPage from "@/components/pages/DatasetItemsPage/DatasetItemsPage";
import DatasetPage from "@/components/pages/DatasetPage/DatasetPage";
import DatasetsPage from "@/components/pages/DatasetsPage/DatasetsPage";
```

**Step 2: Replace dataset routes with redirects**

Replace lines 341-372 (the datasets route definitions) with redirect components:

```typescript
// ----------- datasets (legacy redirects)
const datasetsRoute = createRoute({
  path: "/datasets",
  getParentRoute: () => workspaceRoute,
});

const datasetsListRoute = createRoute({
  path: "/",
  getParentRoute: () => datasetsRoute,
  component: () => (
    <Navigate
      to="/$workspaceName/evaluation-suites"
      params={{ workspaceName: useAppStore.getState().activeWorkspaceName }}
    />
  ),
});

const datasetRoute = createRoute({
  path: "/$datasetId",
  getParentRoute: () => datasetsRoute,
});

const datasetItemsRoute = createRoute({
  path: "/items",
  getParentRoute: () => datasetRoute,
  component: () => {
    const { datasetId } = datasetRoute.useParams();
    return (
      <Navigate
        to="/$workspaceName/evaluation-suites/$suiteId/items"
        params={{
          workspaceName: useAppStore.getState().activeWorkspaceName,
          suiteId: datasetId,
        }}
      />
    );
  },
});
```

Also add a catch-all redirect for `/$datasetId` without `/items`:
```typescript
const datasetRedirectRoute = createRoute({
  path: "/",
  getParentRoute: () => datasetRoute,
  component: () => {
    const { datasetId } = datasetRoute.useParams();
    return (
      <Navigate
        to="/$workspaceName/evaluation-suites/$suiteId"
        params={{
          workspaceName: useAppStore.getState().activeWorkspaceName,
          suiteId: datasetId,
        }}
      />
    );
  },
});
```

Update the route tree (line 557-560) accordingly — keep the dataset route children but with redirect components.

**Step 3: Delete `useDatasetIdFromURL.ts`**

Delete: `hooks/useDatasetIdFromURL.ts`

**Step 4: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`

---

### Task 8: Update External Components

**Files:**
- Modify: `components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog.tsx`
- Modify: `components/pages-shared/llm/DatasetSelectBox/DatasetSelectBox.tsx`
- Modify: `components/shared/DatasetVersionSelectBox/DatasetVersionSelectBox.tsx`
- Modify: `components/shared/ResourceLink/ResourceLink.tsx`
- Modify: `components/redirect/RedirectDatasets.tsx`
- Modify: `components/pages/CompareOptimizationsPage/CompareOptimizationsConfiguration.tsx`

**Step 1: `AddToDatasetDialog.tsx`**

Replace:
```typescript
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
```
With:
```typescript
import AddEditEvaluationSuiteDialog from "@/components/pages/EvaluationSuitesPage/AddEditEvaluationSuiteDialog";
```

Update all references from `AddEditDatasetDialog` to `AddEditEvaluationSuiteDialog` in the JSX.

**Step 2: `DatasetSelectBox.tsx`**

Same dialog replacement as Step 1.

Also update the link href:
```typescript
// BEFORE:
href: `/$workspaceName/datasets/${ds.id}`
// AFTER:
href: `/$workspaceName/evaluation-suites/${ds.id}`
```

**Step 3: `DatasetVersionSelectBox.tsx`**

Same dialog replacement as Step 1.

**Step 4: `ResourceLink.tsx`**

Update the `RESOURCE_MAP` entries for `dataset` and `datasetItem`:
```typescript
// BEFORE:
[RESOURCE_TYPE.dataset]: {
  url: "/$workspaceName/datasets/$datasetId/items",
  ...
}
// AFTER:
[RESOURCE_TYPE.dataset]: {
  url: "/$workspaceName/evaluation-suites/$suiteId/items",
  ...
}
```

Do the same for `RESOURCE_TYPE.datasetItem`.

> **Note:** Check what param names the URL template expects and adjust `$datasetId` → `$suiteId` if needed, or keep the same ID value and just change the path prefix.

**Step 5: `RedirectDatasets.tsx`**

Update navigation target:
```typescript
// BEFORE:
navigate({ to: "/$workspaceName/datasets/$datasetId/items", ... })
// AFTER:
navigate({ to: "/$workspaceName/evaluation-suites/$suiteId/items", ... })
```

Adjust param names accordingly (`datasetId` → `suiteId`).

**Step 6: `CompareOptimizationsConfiguration.tsx`**

Update dataset link:
```typescript
// BEFORE:
to="/$workspaceName/datasets/$datasetId"
// AFTER:
to="/$workspaceName/evaluation-suites/$suiteId"
```

**Step 7: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`

---

### Task 9: Delete Dead Files

**Files to delete:**

**Entire directories:**
```
components/pages/DatasetsPage/
components/pages/DatasetPage/
components/pages/DatasetItemsPage/Legacy/
components/pages/DatasetItemsPage/DatasetItemsTab/
```

**Individual files:**
```
components/pages/DatasetItemsPage/DatasetItemsPage.tsx
components/pages/DatasetItemsPage/DatasetItemsPageVersioned.tsx
components/pages/DatasetItemsPage/DatasetExpansionDialog.tsx
components/pages/DatasetItemsPage/GeneratedSamplesDialog.tsx
components/pages/DatasetItemsPage/AddTagDialog.tsx
components/pages/DatasetItemsPage/DatasetTagsList.tsx
components/pages/DatasetItemsPage/RemoveDatasetItemsDialog.tsx
components/pages/DatasetItemsPage/UseDatasetDropdown.tsx
hooks/useDatasetIdFromURL.ts  (if not already deleted in Task 7)
```

> **IMPORTANT:** Do NOT delete these shared files under `DatasetItemsPage/`:
> - `DatasetItemEditor/` (entire directory — used by evaluation suite side panel)
> - `VersionHistoryTab/` (entire directory — shared tab)
> - `OverrideVersionDialog.tsx`
> - `AddEditDatasetItemDialog.tsx`
> - `AddDatasetItemSidebar.tsx`
> - `DatasetItemsActionsPanel.tsx`
> - `DatasetItemRowActionsCell.tsx`

**Step 1: Delete directories**

```bash
rm -rf apps/opik-frontend/src/components/pages/DatasetsPage
rm -rf apps/opik-frontend/src/components/pages/DatasetPage
rm -rf apps/opik-frontend/src/components/pages/DatasetItemsPage/Legacy
rm -rf apps/opik-frontend/src/components/pages/DatasetItemsPage/DatasetItemsTab
```

**Step 2: Delete individual files**

```bash
rm apps/opik-frontend/src/components/pages/DatasetItemsPage/DatasetItemsPage.tsx
rm apps/opik-frontend/src/components/pages/DatasetItemsPage/DatasetItemsPageVersioned.tsx
rm apps/opik-frontend/src/components/pages/DatasetItemsPage/DatasetExpansionDialog.tsx
rm apps/opik-frontend/src/components/pages/DatasetItemsPage/GeneratedSamplesDialog.tsx
rm apps/opik-frontend/src/components/pages/DatasetItemsPage/AddTagDialog.tsx
rm apps/opik-frontend/src/components/pages/DatasetItemsPage/DatasetTagsList.tsx
rm apps/opik-frontend/src/components/pages/DatasetItemsPage/RemoveDatasetItemsDialog.tsx
rm apps/opik-frontend/src/components/pages/DatasetItemsPage/UseDatasetDropdown.tsx
```

**Step 3: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`
Expected: Clean build with no errors. If there are errors, they point to missed import references that need updating.

---

### Task 10: Handle ExperimentsPage `DATASET_TYPE.DATASET` Filter

**Files:**
- Modify: `components/pages/ExperimentsPage/ExperimentsPage.tsx`

**Step 1: Investigate current usage**

Read the file and find where `DATASET_TYPE.DATASET` is used. It likely has separate tabs or queries for datasets vs evaluation suites.

**Step 2: Update or remove the filter**

If there's a separate "General datasets" tab that checks for `DATASET_TYPE.DATASET`:
- Remove the separate tab/check
- Or update to show all types in a unified view

> **Note:** This task requires reading the file first to understand the exact changes needed. The ExperimentsPage may have its own patterns for how it separates datasets from evaluation suites.

**Step 3: Verify build**

Run: `cd apps/opik-frontend && npx tsc --noEmit`

---

### Task 11: Final Verification

**Step 1: Full build**

```bash
cd apps/opik-frontend && npm run build
```

**Step 2: Run existing tests**

```bash
cd apps/opik-frontend && npm run test
```

**Step 3: Manual verification checklist**

- [ ] `/evaluation-suites` list shows both datasets and evaluation suites
- [ ] Type column shows correct badges
- [ ] Clicking a dataset row navigates to `/evaluation-suites/$id/items`
- [ ] Clicking an evaluation suite row navigates to `/evaluation-suites/$id/items`
- [ ] Dataset items page shows 2 tabs (Items, Version History) — no Evaluators tab
- [ ] Evaluation suite items page shows 3 tabs (Items, Evaluators, Version History)
- [ ] Dataset items show dynamic per-field columns
- [ ] Evaluation suite items show single "Data" column
- [ ] Side panel works for both types
- [ ] Creating new always creates evaluation_suite type
- [ ] `/datasets` URL redirects to `/evaluation-suites`
- [ ] `/datasets/$id/items` URL redirects to `/evaluation-suites/$id/items`
- [ ] Sidebar shows only "Evaluation suites" — no "Datasets" item
- [ ] "Add to dataset" dialog from traces still works (creates evaluation_suite)
- [ ] Playground dataset selection still works with updated links
