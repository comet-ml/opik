# Unified Evaluation Suites UI Design

**Date:** 2026-02-18
**Status:** Approved
**Branch:** awkoy/OPIK-4449-eval-suites

## Context

Evaluation suites and datasets share the same backend entity (`Dataset` with a `type` field: `"dataset"` or `"evaluation_suite"`). Today we maintain separate frontend pages, stores, URL hooks, and sidebar items for each. Going forward:

- **Evaluation suites are the primary entity** — all new creations are `evaluation_suite` type.
- **New users never see "datasets"** — there is no separate datasets section.
- **Legacy datasets appear in the evaluation suites list** with a type column for backward compatibility.
- The UI is consolidated onto the evaluation suite pages with type-based conditional rendering.

## Approach

**Approach A: Extend evaluation suite pages to handle both types.** Evaluation suite pages already implement the superset of features. Datasets are a strict subset (no behaviors, no execution policies). We add ~8 simple type checks to handle both.

## Design

### 1. Single Store — `EvaluationSuiteDraftStore`

Delete `DatasetDraftStore.ts`. Use `EvaluationSuiteDraftStore` everywhere. For legacy `dataset` type items, behavior-related state stays empty (`addedBehaviors`, `editedBehaviors`, `deletedBehaviorIds` maps remain empty, `executionPolicy` stays null).

Save flow per type:
- `evaluation_suite` → `getFullChangesPayload()` → includes `evaluators` + `execution_policy`
- `dataset` → `getChangesPayload()` → items only

### 2. Single URL Hook — `useSuiteIdFromURL`

Delete `useDatasetIdFromURL.ts`. All detail routes use `$suiteId` param.

### 3. Routing

**Primary routes (unchanged):**
```
/evaluation-suites                              → List page (shows both types)
/evaluation-suites/$suiteId                     → Detail layout (redirects to /items)
/evaluation-suites/$suiteId/items               → Items page (type-aware)
/evaluation-suites/$suiteId/experiments/$expId   → Experiment page (suites only)
```

**Legacy redirects (new):**
```
/datasets              → redirect to /evaluation-suites
/datasets/$id          → redirect to /evaluation-suites/$id
/datasets/$id/items    → redirect to /evaluation-suites/$id/items
```

### 4. List Page (`EvaluationSuitesPage`)

- Remove `type: DATASET_TYPE.EVALUATION_SUITE` filter — fetch all.
- Add type column with badge cell ("Evaluation suite" / "Dataset").
- Row click always routes to `/evaluation-suites/$id/items`.
- Create button always creates `evaluation_suite` type.
- Row actions are dynamic per row type:
  - `dataset` rows → `AddEditDatasetDialog` for edit, `showDownload: true`
  - `evaluation_suite` rows → `AddEditEvaluationSuiteDialog` for edit

### 5. Items Page (`EvaluationSuiteItemsPage`) — Type-Aware

Receives `dataset.type` from parent layout via outlet context.

**Tabs (conditional):**
- `evaluation_suite`: Items | Evaluators | Version History
- `dataset`: Items | Version History (Evaluators tab hidden)

**Save payload branching:**
- `evaluation_suite` → `getFullChangesPayload([])` (includes evaluators, execution_policy)
- `dataset` → `getChangesPayload()` (items only)

**Success toast:**
- `evaluation_suite`: simple "New version created" message
- `dataset`: message with "Run experiment in SDK" / "Playground" action buttons

### 6. Items Tab — Column Generation

**For `evaluation_suite` items:**
```
ID | Data (single column) | Description | Evaluators | Execution Policy | Tags | Created | ...
```
- Single "Data" column renders entire `data` object as truncated JSON/preview.
- No dynamic per-field expansion from `data.*`.

**For `dataset` items:**
```
ID | data.field1 | data.field2 | ... | Tags | Created | ...
```
- Dynamic columns from `datasetColumns` API response (existing behavior).
- No description, evaluators, execution_policy columns.

### 7. Side Panel — Conditional

```typescript
type === DATASET_TYPE.EVALUATION_SUITE
  ? <EvaluationSuiteItemPanel />
  : <DatasetItemEditor />
```

Both components already exist. No changes to panel internals.

### 8. External Component Updates

| Component | Change |
|-----------|--------|
| `AddToDatasetDialog.tsx` | Replace `AddEditDatasetDialog` → `AddEditEvaluationSuiteDialog` |
| `DatasetSelectBox.tsx` | Replace dialog + update link href to `/evaluation-suites/$id` |
| `DatasetVersionSelectBox.tsx` | Replace dialog |
| `ResourceLink.tsx` | Route `dataset`/`datasetItem` types to `/evaluation-suites/` |
| `RedirectDatasets.tsx` | Update target route to `/evaluation-suites/` |
| `CompareOptimizationsConfiguration.tsx` | Update dataset link |
| `ExperimentsPage.tsx` | Remove/update `DATASET_TYPE.DATASET` filter |

### 9. Sidebar Cleanup (`SideBar.tsx`)

Remove entirely:
- The `datasets` menu item definition
- The `useDatasetsList({ type: DATASET_TYPE.DATASET })` count query
- The `datasetsData?.total` count mapping
- The `hasGeneralDatasets` logic and `hiddenItemIds` set

Update:
- Evaluation suites count query: remove type filter so count includes both types

## Files to Delete

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
store/DatasetDraftStore.ts
hooks/useDatasetIdFromURL.ts
```

## Files to Keep (shared, used by evaluation suites)

These live under `DatasetItemsPage/` but are imported by evaluation suite code:
```
DatasetItemEditor/          (autosave context, form, hooks)
VersionHistoryTab/          (shared version history)
OverrideVersionDialog.tsx   (shared conflict dialog)
AddEditDatasetItemDialog.tsx (shared item editor)
AddDatasetItemSidebar.tsx   (shared add item sidebar)
DatasetItemsActionsPanel.tsx (shared bulk actions)
DatasetItemRowActionsCell.tsx (shared row actions)
```

Consider relocating these to a shared directory to avoid confusion.

## Files to Modify

| File | Change |
|------|--------|
| `router.tsx` | Remove dataset page imports/routes, add legacy redirects |
| `SideBar.tsx` | Remove datasets menu item, count query, `hasGeneralDatasets` |
| `EvaluationSuitesPage.tsx` | Remove type filter, add type column, dynamic row actions |
| `EvaluationSuitesPage/columns.tsx` | Add type column definition |
| `EvaluationSuiteItemsPage.tsx` | Type-aware tabs, single store, conditional save |
| `EvaluationSuiteItemsTab.tsx` | Type-based column generation |
| `ResourceLink.tsx` | Update dataset routes to `/evaluation-suites/` |
| `RedirectDatasets.tsx` | Update target route |
| `AddToDatasetDialog.tsx` | Replace dialog import |
| `DatasetSelectBox.tsx` | Replace dialog + update link |
| `DatasetVersionSelectBox.tsx` | Replace dialog |
| `CompareOptimizationsConfiguration.tsx` | Update link |
| `ExperimentsPage.tsx` | Update `DATASET_TYPE.DATASET` filter |
| All `DatasetDraftStore` consumers | Switch to `EvaluationSuiteDraftStore` |

## New Files

| File | Purpose |
|------|---------|
| `DataObjectCell.tsx` | Cell renderer for single "data" column (suite items) |
| `TypeBadgeCell.tsx` | Cell renderer for type column in list page |

## Conditional Logic — 8 Type Checks Total

1. List page row actions: which edit dialog + download option
2. Items page tabs: show/hide Evaluators tab
3. Items page save payload: `getFullChangesPayload` vs `getChangesPayload`
4. Items page success toast: with/without action buttons
5. Items tab column generation: single data col vs dynamic cols
6. Items tab default selected columns: different defaults
7. Items tab side panel: which panel component to render
8. Items page header: show/hide `UseDatasetDropdown` for legacy datasets
