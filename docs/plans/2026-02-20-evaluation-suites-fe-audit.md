# Evaluation Suites — Comprehensive FE Audit

> Date: 2026-02-20
> Branch: awkoy/OPIK-4449-eval-suites
> Status: Production-ready checklist — every file, string, column, sort, chart, dialog, action audited

---

## AREA 1: EVALUATION SUITES PAGE (formerly Datasets)

**Files:** `EvaluationSuitesPage.tsx`, `AddEditEvaluationSuiteDialog.tsx`, `columns.tsx`

| # | File | Line | Issue | Priority |
|---|------|------|-------|----------|
| 1 | `EvaluationSuitesPage.tsx` | 224-226 | Sub-header text doesn't match PRD. Should be: "An evaluation suite is a collection of input and additional context and the corresponding behaviors that define how to evaluate your agent's performance." | P1 |
| 2 | `EvaluationSuitesPage.tsx` | after 226 | Missing "Read more" documentation link | P2 |
| 3 | `AddEditEvaluationSuiteDialog.tsx` | after DialogTitle | Missing subtitle in create dialog: "Create different evaluation suites to evaluate your agent in different contexts, scenarios, or environments." | P2 |
| 4 | `explainers.ts` | 280 | `whats_a_dataset` description doesn't match PRD text | P2 |
| 5 | `explainers.ts` | 287 | "LLM application" should be "agent" | P3 |
| 6 | `columns.tsx` | 124-131 | Verify: should `type` column be in DEFAULT_SELECTED_COLUMNS? | P3 |

---

## AREA 2: EVALUATION SUITE ITEMS PAGE

**Files:** `EvaluationSuiteItemsPage/`, `EvaluationSuiteItemPanel/`

| # | File | Line | Issue | Priority |
|---|------|------|-------|----------|
| 7 | `EvaluationSuiteItemsTab.tsx` | 84-91 | Default columns need reordering to: `description, last_updated_at, data, expected_behaviors, execution_policy` | P1 |
| 8 | `ItemContextSection.tsx` | 82 | Section title "Context" should be "Data" per PRD | P1 |
| 9 | `EvaluationSuiteItemPanel.tsx` | 138-142 | Remove truncated ID from title — PRD says just "Evaluation suite item" | P2 |
| 10 | `router.tsx` | 322-326 | Add `staticData: { title: "" }` to evaluationSuiteItemsRoute to remove "/items" from breadcrumb | P2 |
| 11 | `AddExperimentDialog.tsx` | 430-457 | Conditionally hide evaluator selection for evaluation suite experiments | P1 |
| 12 | `AddExperimentDialog.tsx` | 472-473 | Update description — remove "assign evaluators" mention for eval suites | P2 |

---

## AREA 3: EXPERIMENTS PAGE (Unified Table)

**Files:** `GeneralDatasetsTab.tsx`, `useGroupedExperimentsList.ts`, `types/datasets.ts`

| # | File | Line | Issue | Priority |
|---|------|------|-------|----------|
| 13 | `GeneralDatasetsTab.tsx` | 94-103 | Add `"pass_rate"` to DEFAULT_SELECTED_COLUMNS | P1 |
| 14 | `GeneralDatasetsTab.tsx` | 296 | `pass_rate` column missing `sortable: true` | P1 |
| 15 | `GeneralDatasetsTab.tsx` | 296 | `pass_rate` column missing `aggregatedCell` for grouped rows | P1 |
| 16 | `types/datasets.ts` | 179-187 | `ExperimentsAggregations` missing `pass_rate?`, `passed_count?`, `total_count?` fields | P0 |
| 17 | `GeneralDatasetsTab.tsx` | 575-602 | Charts need to include pass_rate as "Pass Rate" score line for eval suite experiments | P2 |
| 18 | `ExperimentsLeaderboardWidget/helpers.ts` | 56-150 | Dashboard widget missing pass_rate column in PREDEFINED_COLUMNS | P2 |

**Backend dependencies for sorting:**
- BE must include `"pass_rate"` in `sortable_by` response array
- BE must return `pass_rate`, `passed_count`, `total_count` in experiment and aggregation responses

---

## AREA 4: EXPERIMENT RESULTS / COMPARE PAGE

**Files:** `CompareExperimentsPage/`, `EvaluationSuiteExperimentPage/`

| # | File | Line | Issue | Priority |
|---|------|------|-------|----------|
| 19 | `ExperimentItemsTab.tsx` | 127-131 | DEFAULT_SELECTED_COLUMNS wrong for eval suites — need conditional: `[description, data, duration, cost, passed]` | P0 |
| 20 | `CompareExperimentsDetails.tsx` | 245-247 | Pass rate shows placeholder "—" — replace with actual `experiment.pass_rate` | P1 |
| 21 | `BehaviorsResultsTable.tsx` | 18 | "Evaluator results" should be "Assertions" per PRD | P1 |
| 22 | `BehaviorsResultsTable.tsx` | 23 | Column header "Evaluator" should be "Assertion" per PRD | P1 |
| 23 | `ExperimentItemsTab.tsx` | — | Missing `description` column definition for eval suite experiments | P1 |
| 24 | `ExperimentItemsTab.tsx` | — | Missing `data` column definition + new `DataObjectCell` component needed | P1 |
| 25 | `types/datasets.ts` | 158-173 | `ExperimentItem` interface missing `status?: ExperimentItemStatus` and `behavior_results?: BehaviorResult[]` | P0 |
| 26 | `ExperimentItemsTab.tsx` | 584-606 | `columnSections` needs "Evaluation suite" section when `isEvalSuite` | P2 |
| 27 | `useCompareExperimentsChartsData.ts` | — | Charts only show feedback/experiment scores — need pass_rate support for eval suite mode | P2 |

---

## AREA 5: CROSS-CUTTING — Terminology & Strings

**Status: ~98% complete.** All user-facing strings already updated to "evaluation suite." Only internal constant names (enum IDs, localStorage keys, API types) retain "dataset" — this is correct since they match backend API contracts.

| # | File | Line | Issue | Priority |
|---|------|------|-------|----------|
| 28 | `AddToDatasetDialog.test.tsx` | 70 | Mock import path still references `DatasetsPage/AddEditDatasetDialog` | P3 |
| 29 | `OptimizationsNewConfigSidebar.tsx` | 108 | Label says "Dataset" — should say "Evaluation suite" | P2 |

---

## AREA 6: OTHER PAGES USING EXPERIMENTS

### Experiment Type Filtering Problem

Most pages use `useExperimentsList` which **defaults to `[EXPERIMENT_TYPE.REGULAR]`**. These pages will NOT show evaluation suite experiments:

| # | File | Current Behavior | Required Change | Priority |
|---|------|-----------------|----------------|----------|
| 30 | `HomePage/EvaluationSection.tsx` | Only REGULAR experiments | Decision needed: show all types or keep REGULAR-only? | P2 |
| 31 | `PromptPage/ExperimentsTab.tsx` | Only REGULAR experiments | If prompts can run eval suite experiments, add MINI_BATCH type | P2 |
| 32 | `ExperimentsFeedbackScoresWidget.tsx` | Only REGULAR experiments | Add MINI_BATCH to widget experiment types | P2 |
| 33 | `ExperimentsLeaderboardWidget.tsx` | Only REGULAR experiments | Add MINI_BATCH + pass_rate column | P2 |

### Pages Working Correctly (no changes needed)
- `CompareOptimizationsPage` — correctly fetches `[TRIAL, MINI_BATCH]`
- `CompareTrialsPage` — fetches by IDs, type-agnostic
- `PlaygroundPage` — works with datasets/items directly
- `OptimizationRunsSection` — uses optimizations API

---

## BACKEND DEPENDENCIES (FE blocked on these)

| # | Field | Where Needed | Description |
|---|-------|-------------|-------------|
| B1 | `pass_rate`, `passed_count`, `total_count` on Experiment | Experiments list, details header, charts | Server-computed pass rate |
| B2 | `pass_rate`, `passed_count`, `total_count` on ExperimentsAggregations | Grouped experiments table | Aggregated pass rate for groups |
| B3 | `"pass_rate"` in `sortable_by` response | Experiments table sorting | Enable column header sort |
| B4 | `status`, `behavior_results` on ExperimentItem | Compare page PassedCell | Pass/fail per item |

---

## PRIORITY SUMMARY

### P0 — Type definitions (block all other work)
- **#16**: `ExperimentsAggregations` type extension (pass_rate, passed_count, total_count)
- **#25**: `ExperimentItem` type extension (status, behavior_results)
- **#19**: Default columns for eval suite experiment results

### P1 — Core functionality
- **#1**: Eval suites page sub-header text
- **#7**: Eval suite items default columns reorder
- **#8**: "Context" → "Data" section title
- **#11**: Conditional evaluator section in AddExperimentDialog
- **#13**: Add pass_rate to experiments table defaults
- **#14**: Make pass_rate sortable
- **#15**: pass_rate aggregation support for groups
- **#20**: Actual pass rate in compare details header
- **#21**: "Evaluator results" → "Assertions"
- **#22**: "Evaluator" → "Assertion" column header
- **#23**: Description column for eval suite experiment items
- **#24**: Data column + DataObjectCell component

### P2 — Polish & completeness
- **#2**: "Read more" link on eval suites page
- **#3**: Subtitle in create dialog
- **#9**: Remove ID from item panel title
- **#10**: Breadcrumb fix for items route
- **#12**: Remove "assign evaluators" text
- **#17**: Pass rate in experiments charts
- **#18**: Pass rate in dashboard leaderboard widget
- **#26**: Column sections for eval suite mode
- **#27**: Charts pass_rate for compare page
- **#29**: "Dataset" → "Evaluation suite" in optimizations sidebar
- **#30-33**: Experiment type filtering in other pages

### P3 — Low priority
- **#4**: Explainer text update
- **#5**: "LLM application" → "agent"
- **#6**: Type column default verification
- **#28**: Test mock import path

---

## COMPLETE FILE LIST

### Must Modify (15 files):
1. `src/types/datasets.ts` — add fields to Experiment, ExperimentItem, ExperimentsAggregations
2. `src/components/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx` — pass_rate column, defaults, charts, sorting
3. `src/components/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab.tsx` — conditional defaults, description/data columns
4. `src/components/pages/CompareExperimentsPage/CompareExperimentsDetails/CompareExperimentsDetails.tsx` — actual pass rate
5. `src/components/pages/EvaluationSuiteExperimentPage/ExperimentItemSidebar/BehaviorsResultsTable.tsx` — "Assertions" strings
6. `src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsTab/EvaluationSuiteItemsTab.tsx` — default columns
7. `src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/ItemContextSection.tsx` — "Data" title
8. `src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/EvaluationSuiteItemPanel.tsx` — remove ID
9. `src/components/pages/EvaluationSuitesPage/EvaluationSuitesPage.tsx` — sub-header text
10. `src/components/pages/EvaluationSuitesPage/AddEditEvaluationSuiteDialog.tsx` — subtitle
11. `src/components/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog.tsx` — conditional evaluator section
12. `src/components/shared/Dashboard/widgets/ExperimentsLeaderboardWidget/helpers.ts` — pass_rate column
13. `src/constants/explainers.ts` — text updates
14. `src/router.tsx` — breadcrumb fix
15. `src/components/pages/OptimizationsPage/OptimizationsNewPage/OptimizationsNewConfigSidebar.tsx` — label fix

### Must Create (1 file):
16. `src/components/pages/EvaluationSuiteExperimentPage/DataObjectCell.tsx` — new cell component

### Verify/Decide (4 files):
17. `src/components/pages/HomePage/EvaluationSection.tsx` — experiment type filtering decision
18. `src/components/pages/PromptPage/ExperimentsTab/ExperimentsTab.tsx` — experiment type filtering decision
19. `src/components/shared/Dashboard/widgets/` — experiment type filtering decision
20. `src/components/pages/EvaluationSuitesPage/columns.tsx` — type column default

---

## ALREADY CORRECT (verified, no changes needed)

### Terminology (all say "evaluation suite"):
- SideBar.tsx — icon, label, path
- router.tsx — routes, redirects
- RedirectDatasets.tsx — error messages
- ResourceLink.tsx — labels
- groups.ts — group label
- All explainer descriptions (except #4, #5)
- AddToDatasetDialog.tsx — all strings
- PlaygroundPage — all strings
- DatasetSelectBox — placeholders
- DatasetVersionSelectBox — all messages
- DatasetActionsPanel — delete confirmations
- DatasetRowActionsCell — actions & errors
- ExportJobItem.tsx — error messages
- useDatasetItemBatchDeleteMutation.ts — toast messages
- useDatasetExpansionMutation.ts — success/error messages
- CompareExperimentsPage/ExperimentItemsTab — column labels, search placeholders
- CompareExperimentsPanel/DataTab — section title, navigation tag
- CompareTrialsPage/TrialItemsTab — column labels
- CompareOptimizationsPage — configuration label
- OptimizationConfigForm/schema.ts — validation message
- DashboardDataSourceSection — filter label
- HomePage sections — column labels
- PromptPage/ExperimentsTab — column labels
- useExperimentsGroupsAndFilters.ts — filter label
- FilterExperimentsToCompareDialog — messages
- AddExperimentDialog — section titles

### Internal names (correctly kept as "dataset" to match BE API):
- API type names (`Dataset`, `DatasetItem`)
- Internal variable names (`datasetId`, `datasetName`)
- localStorage keys (changing would break users)
- Explainer enum IDs (internal constants)
- Feature toggle keys (backend contract)
- Store variable names (internal state)
