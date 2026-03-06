# Unified Experiments Table Design

## Date: 2026-02-18

## Context

The evaluation suites PRD has been updated: the Experiments page should have a **single unified table** instead of the current tab architecture (Evaluation Suites | General Datasets).

## Current State

- `ExperimentsPage.tsx` renders two tabs: `EvaluationSuitesTab` and `GeneralDatasetsTab`
- `EvaluationSuitesTab`: Simple flat table, filters by `datasetType: EVALUATION_SUITE`, shows suite name/created/duration/cost/pass_rate
- `GeneralDatasetsTab`: Complex table with grouping/filtering via `useGroupedExperimentsList`, dynamic feedback score columns, expandable groups, charts
- Both tabs use `useExperimentsList` under the hood with different `datasetType` filters
- Row clicks go to different pages: `CompareExperimentsPage` (regular) vs `EvaluationSuiteExperimentPage` (eval suites)

## Design Decision

**Approach A (selected): Extend GeneralDatasetsTab to show all experiment types, remove tabs.**

Rationale:
- Same `Experiment` TypeScript type used by both
- Same backend API with just different filter params
- `useGroupedExperimentsList` is type-agnostic (730 lines of complex grouping logic we don't want to rewrite)
- Column system already handles nullable values gracefully

## Changes

### 1. ExperimentsPage.tsx

- Remove all tab logic (Tabs, TabsList, TabsTrigger, TabsContent)
- Remove `useDatasetsList` check for showing/hiding general datasets tab
- Remove imports for `EvaluationSuitesTab`
- Render the unified table component directly (the current GeneralDatasetsTab, possibly renamed)

### 2. Delete EvaluationSuitesTab

- Delete `ExperimentsPage/EvaluationSuitesTab/` directory entirely (EvaluationSuitesTab.tsx + columns.tsx)
- Git history preserves the code

### 3. GeneralDatasetsTab.tsx → Unified Table

- **Rename** to something like `ExperimentsTable.tsx` or inline into `ExperimentsPage.tsx`
- **Remove type filtering**: Don't pass `types` param (or pass all types) so both regular and eval suite experiments appear
- **Add pass_rate column**: New column in `columnsDef` showing "76.2% (16/21)" for eval suite experiments, "—" for regular
- **Row click**: ALL experiments navigate to `CompareExperimentsPage` (`/$ws/experiments/$datasetId/compare?experiments=[id]`)

### 4. useGroupedExperimentsList.ts

- Add `types?: EXPERIMENT_TYPE[]` to `UseGroupedExperimentsListParams`
- Forward `types` to:
  - `useExperimentsGroups` call (line ~374)
  - `useExperimentsGroupsAggregations` call (line ~390)
  - `useExperimentsList` call (line ~444)
  - Per-group `useQueries` calls for expanded groups

### 5. Experiment Type (Optional)

- Add optional `pass_rate`, `passed_count`, `total_count` fields to the frontend `Experiment` interface
- Or use type casting like the existing EvaluationSuitesTab columns already do

## What Doesn't Change

- `CompareExperimentsPage` - works as-is for both types
- All API hooks - already support the needed params
- Grouping/filtering - works with mixed experiment types
- Charts - display whatever feedback scores exist
- Row selection/batch operations - same mutations apply

## Future Work (Task 2, separate)

- Extend `CompareExperimentsPage` to detect dataset type and show eval-suite-specific UI (pass/fail, behaviors)
- Add `dataset_type` to backend `Experiment` response for type-aware rendering
- Eventually remove/redirect `EvaluationSuiteExperimentPage` route

## Risk Assessment

- **Low risk**: Mostly deletion + small additions to proven code
- **No backend changes required** for Task 1
- **No regression to existing experiment functionality** - same hooks, same API calls, just broader filters
