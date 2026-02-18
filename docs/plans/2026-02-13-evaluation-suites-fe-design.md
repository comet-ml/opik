# Evaluation Suites — Frontend Design

## Overview

Evaluation Suites are pre-configured regression test suites for LLM applications. They let users define test cases with persistent metrics/assertions and execution policies, then run them repeatedly to catch regressions before deploying changes.

Technically, an evaluation suite is a Dataset with `type='evaluation_suite'` plus two new capabilities: stored evaluators (metrics) and configurable execution policy (multi-run with pass threshold).

### Key differences from general datasets

| Aspect | General Dataset | Evaluation Suite |
|---|---|---|
| Purpose | Experimentation, exploration | Regression testing |
| Metrics | Defined in Python code at runtime | Stored in database, fetched by SDK |
| Metric scope | Same metrics for all items | Per-item metrics (primary use case) + suite-level |
| Runs per item | Always 1 | Configurable N (e.g., run 3 times) |
| Result model | Averaged numeric scores | Pass/fail per item with per-assertion detail |
| Mental model | "How good is this prompt?" | "Does this prompt still work?" |

### Reference documents (Notion)

- [Evaluation Suite - Feature Definition](https://www.notion.so/cometml/Evaluation-Suite-Feature-Definition-2f07124010a380f5912af2396f7252f1)
- [Evaluation Suite - Technical Design](https://www.notion.so/cometml/Evaluation-Suite-Technical-Design-2f17124010a380bf828dea9c074e195a)
- [Evaluation Suites - PRD](https://www.notion.so/cometml/Evaluation-Suites-PRD-3037124010a380f08d6ddcc6e598d0ec)
- [Evaluation Suite - User Flows](https://www.notion.so/cometml/3027124010a380e8a9d1d33b848d8a04)

---

## Architectural Approach: Shared Core with Specialized Shells

General dataset pages remain **completely untouched** — zero regression risk. Evaluation suites get a **new set of page components** that reuse shared low-level infrastructure (DataTable, cells, dialogs, pagination) but have their own layout and logic.

### Why this approach

- The UX diverges significantly: behaviors section, new item dialog, execution policy, pass/fail results vs averaged scores, multi-run tabs.
- Conditionally branching inside existing dataset components would create maintenance burden.
- Separate pages allow each experience to evolve independently.
- When general datasets are eventually deprecated, delete old pages — shared infrastructure lives on.

---

## 1. Routing & Navigation

### Sidebar

```
Evaluation (section)
├── Experiments          → /$workspaceName/experiments
├── Evaluation suites    → /$workspaceName/evaluation-suites     (NEW, always visible)
├── Datasets             → /$workspaceName/datasets              (EXISTING, only if user has general datasets)
└── Annotation queues    → /$workspaceName/annotation-queues
```

Sidebar logic: fetch datasets, if any have `type='dataset'` show "Datasets" entry. Otherwise hide it.

### Routes

New routes (evaluation suites):
```
/$workspaceName/evaluation-suites                    → EvaluationSuitesPage
/$workspaceName/evaluation-suites/$suiteId           → redirect to /items
/$workspaceName/evaluation-suites/$suiteId/items     → EvaluationSuiteItemsPage
```

Existing routes (general datasets, untouched):
```
/$workspaceName/datasets                             → DatasetsPage (no changes)
/$workspaceName/datasets/$datasetId                  → DatasetPage (no changes)
/$workspaceName/datasets/$datasetId/items            → DatasetItemsPage (no changes)
```

### Breadcrumbs

- Suites list: `{workspace} / Evaluation suites`
- Suite detail: `{workspace} / Evaluation suites / {suite name}` — no trailing `/items`

### Experiments page tabs

```
Experiments page
├── Tab: "Evaluation suites"    (default)
└── Tab: "General datasets"     (only if user has general datasets)
```

---

## 2. Component Architecture

### New components

```
components/pages/
  EvaluationSuitesPage/                        # List of all evaluation suites
    EvaluationSuitesPage.tsx                   # Sub-header includes "Read more" link to docs
    columns.tsx                                # Name, Description, Item count,
                                               # Most recent experiment, Pass rate, Last updated
    EvaluationSuiteRowActionsCell.tsx           # Edit, delete, duplicate (later)
    AddEditEvaluationSuiteDialog.tsx            # Create/edit suite name + description

  EvaluationSuiteItemsPage/                    # Single suite detail page
    EvaluationSuiteItemsPage.tsx               # Main shell — tabs + draft flow
    BehaviorsSection/                          # Above items table
      BehaviorsSection.tsx                     # Suite-level evaluators list + add/edit/delete
      ExecutionPolicyDropdown.tsx              # Settings2 icon → w-72 dropdown with
                                               # runs_per_item + pass_threshold inputs
      AddEditBehaviorDialog.tsx                # Centered modal: metric type selector + config form
                                               # + optional name field with auto-default
      MetricConfigForm.tsx                     # Dynamic form per metric type (switch on 6 types)
    EvaluationSuiteItemsTab/                   # Items table tab
      EvaluationSuiteItemsTab.tsx
      columns.tsx                              # Description, Context, Expected behaviors,
                                               # Execution policy (auto-show), Last updated
    EvaluationSuiteItemPanel/                  # Click on item → side panel (right)
      EvaluationSuiteItemPanel.tsx             # Three sections: top/middle/bottom
                                               # Title: "Evaluation suite item" (no item ID)
                                               # Changes feed into parent draft (no own save btn)
      ItemDescriptionSection.tsx               # Multi-line textarea for description
      ItemExecutionPolicySection.tsx           # Read-only suite default + "Override" toggle
      ItemBehaviorsSection.tsx                 # Two subsections: suite (read-only) + item (editable)
      ItemContextSection.tsx                   # Reuses DatasetItemEditorForm pattern
                                               # (TextareaAutosize + CodeMirror per field type)
    VersionHistoryTab/                         # Reuse existing pattern

  ExperimentsPage/
    ExperimentsPage.tsx                        # Add tab navigation
    GeneralDatasetsTab/                        # Extract current content
    EvaluationSuitesTab/                       # Simplified experiments table
      EvaluationSuitesTab.tsx
      columns.tsx                              # Suite name, Created, Duration avg,
                                               # Cost avg, Pass rate

  EvaluationSuiteExperimentPage/               # Results for one experiment
    EvaluationSuiteExperimentPage.tsx          # Top section + items table
    ExperimentItemsTable/
      columns.tsx                              # Context, Duration, Cost, Passed
      PassedCell.tsx                           # Yes/No/Skipped + (2/3) for multi-run
      BehaviorsBreakdownTooltip.tsx            # Per-behavior pass/fail on hover
    ExperimentItemSidebar/
      ExperimentItemSidebar.tsx                # Left (context) + right (results)
      BehaviorsResultsTable.tsx                # Behavior / Passed / Reason
      MultiRunTabs.tsx                         # Run 1 / Run 2 / ... tabs
      PassFailBadge.tsx                        # Green PASSED / Red FAILED label
```

### Reused shared components (no changes needed)

```
shared/
  DataTable/                   # Table infrastructure
  DataTablePagination/
  DataTableNoData/
  DataTableCells/              # TextCell, IdCell, CostCell, DurationCell, etc.
  FiltersButton/
  ColumnsButton/
  ConfirmDialog/
  SidePanel/
```

### Untouched pages (zero changes)

```
DatasetsPage/
DatasetPage/
DatasetItemsPage/
CompareExperimentsPage/
```

---

## 3. Data Layer

### New TypeScript types

```typescript
// types/evaluation-suites.ts

enum DATASET_TYPE {
  DATASET = "dataset",
  EVALUATION_SUITE = "evaluation_suite",
}

interface EvaluationSuite extends Dataset {
  type: DATASET_TYPE.EVALUATION_SUITE;
  default_execution_policy: ExecutionPolicy;
}

interface ExecutionPolicy {
  runs_per_item: number;   // default: 1
  pass_threshold: number;  // default: 1
}

interface EvaluationSuiteItem extends DatasetItem {
  description: string;
  execution_policy?: ExecutionPolicy;  // item-level override
}

interface DatasetEvaluator {
  id: string;
  dataset_id: string;
  name: string;
  metric_type: MetricType;
  metric_config: Record<string, unknown>;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

interface DatasetItemEvaluator {
  id: string;
  dataset_item_id: string;
  name: string;
  metric_type: MetricType;
  metric_config: Record<string, unknown>;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

// Phase 1 metric types. Designed to be extensible — Phase 2 may add:
// not_contains, json_schema, semantic_similarity, tool_call
enum MetricType {
  CONTAINS = "contains",
  EQUALS = "equals",
  LEVENSHTEIN_RATIO = "levenshtein_ratio",
  HALLUCINATION = "hallucination",
  MODERATION = "moderation",
  LLM_AS_JUDGE = "llm_judge",
}

// Per-metric config shapes
// For threshold-based metrics (Hallucination, Moderation, Levenshtein):
// the threshold is a PASS threshold — score >= threshold means pass.
interface ContainsConfig { value: string; case_sensitive: boolean; }
interface EqualsConfig { value: string; case_sensitive: boolean; }
interface LevenshteinConfig { threshold: number; }     // 0-1, score >= threshold = pass
interface HallucinationConfig { threshold: number; }   // 0-1, score >= threshold = pass
interface ModerationConfig { threshold: number; }      // 0-1, score >= threshold = pass
interface LLMJudgeConfig { assertions: string[]; }     // Array of assertion texts

interface EvaluationSuiteExperimentItem extends ExperimentItem {
  run_number: number;
}

// Item-level result status
enum ExperimentItemStatus {
  PASSED = "passed",
  FAILED = "failed",
  SKIPPED = "skipped",  // Item incompatible with prompt — skipped, not failed
}

interface BehaviorResult {
  behavior_name: string;
  passed: boolean;
  pass_score?: number;  // 0-1 float from LLM-as-Judge, used by optimizer. Display deferred to later phase.
  reason?: string;
}
```

### New API hooks

```
api/evaluation-suites/
  useEvaluationSuitesList.ts            # GET /datasets?type=evaluation_suite
  useEvaluationSuiteById.ts             # GET /datasets/{id}
  useEvaluationSuiteCreateMutation.ts   # POST /datasets (type=evaluation_suite)
  useEvaluationSuiteUpdateMutation.ts   # PUT /datasets/{id}
  useEvaluationSuiteDeleteMutation.ts   # DELETE /datasets/{id}

api/evaluators/
  useDatasetEvaluatorsList.ts           # GET /datasets/{id}/evaluators
  useDatasetEvaluatorCreateMutation.ts  # POST /datasets/{id}/evaluators
  useDatasetEvaluatorUpdateMutation.ts  # PUT /datasets/{id}/evaluators/{evalId}
  useDatasetEvaluatorDeleteMutation.ts  # DELETE /datasets/{id}/evaluators/{evalId}
  useDatasetItemEvaluatorsList.ts       # GET /dataset-items/{itemId}/evaluators
  useDatasetItemEvaluatorCreateMutation.ts
  useDatasetItemEvaluatorUpdateMutation.ts
  useDatasetItemEvaluatorDeleteMutation.ts

api/experiments/
  useExperimentsList.ts                 # EXISTING — add dataset type filter
```

### State management

`EvaluationSuiteDraftStore` (Zustand):

```
State:
  - itemChanges: { added: [], edited: [], deleted: [] }
  - evaluatorChanges: { added: [], edited: [], deleted: [] }
  - executionPolicyChanged: boolean

Derived:
  - isDraftMode: boolean (any of the above non-empty)

Actions:
  - addItem / editItem / deleteItem
  - addEvaluator / editEvaluator / deleteEvaluator
  - updateExecutionPolicy
  - clearDraft
  - getChangesPayload → batched payload for save
```

On save: submit all changes as batch, create new version. Same conflict resolution as existing datasets (409 → override dialog).

### Unified draft mode

All changes across the suite detail page participate in a **single unified draft**:
- Item changes (add/edit/delete)
- Behavior changes at suite level (add/edit/delete)
- Behavior changes at item level (add/edit/delete via side panel)
- Execution policy changes (suite-level or item-level overrides)

**Draft indicators:**
- First change shows "Draft" indicator + "Save changes" & "Discard changes" buttons at page level
- Saving creates a new version (batches all changes)
- "Discard changes" resets all pending changes
- Leaving the page without saving triggers the "Unsaved changes" confirmation dialog

**Item side panel interaction:**
- Changes made in the side panel (description, behaviors, context, execution policy) automatically feed into the parent draft
- No separate save/cancel buttons in the side panel itself
- Closing the side panel returns to the page with draft still active

---

## 4. Evaluators/Behaviors UI

### Suite-level behaviors section

Rendered above the items table on `EvaluationSuiteItemsPage`. Has a title and subtitle:
- **Title:** "Evaluation suite behaviors"
- **Subtitle:** "Define behaviors that will be evaluated on all the items in the evaluation suite"

Three parts:

**Execution policy** — Settings2 icon dropdown in the section header bar (follows the `AlgorithmConfigs` dropdown pattern from the codebase):
- Icon button in the section header, right-aligned
- Opens `w-72` dropdown with `p-6`, `max-h-[70vh] overflow-y-auto`
- Two number inputs inside:
  - `Runs per item` (default: 1, min: 1)
  - `Pass threshold` (default: 1, min: 1, max: runs_per_item)
- Validation: runs_per_item >= 1, pass_threshold >= 1 AND <= runs_per_item
- Changes mark draft dirty

**Behaviors table** — all suite-level evaluators:

| Name | Metric type | Actions |
|---|---|---|
| No hallucinations | Hallucination | Edit / Delete |
| Must be polite | LLM as a Judge | Edit / Delete |

- **No Configuration column** — metric config details shown in **tooltip on hover** over the row or metric type cell
- Tooltip content per type:
  - Contains: `"refund policy", case sensitive`
  - Equals: `"expected value", case insensitive`
  - Levenshtein/Hallucination/Moderation: `threshold: 0.8`
  - LLM as Judge: full assertion text
- LLM-as-Judge assertions displayed as **separate rows** (one per assertion) for edit/delete UX, but stored as a **single evaluator** in the backend — see "LLM-as-Judge aggregation" below
- No visual grouping for LLM-Judge rows — aggregation is invisible to the user
- **Empty state:** Section title/subtitle visible + "Add new behavior" button. No table rendered when empty.

**"Add new behavior" button → AddEditBehaviorDialog (centered modal):**

- **Title:** "Add new behavior" (create) / "Edit behavior" (edit)
- **Save button:** "Add behavior" / "Save behavior"
- **Edit flow:** Same dialog reused with pre-filled values

Three fields:
1. **Name** — text input, optional with auto-generated default:
   - LLM Judge: truncated assertion text (first ~50 chars)
   - Contains: `Contains: "{value}"`
   - Equals: `Equals: "{value}"`
   - Levenshtein/Hallucination/Moderation: `{MetricType} >= {threshold}`
   - User can override the auto-generated name
2. **Metric type** dropdown (LLM as a Judge default, Contains, Equals, Levenshtein, Hallucination, Moderation)
   - (Optional) Online evaluation metrics: select from existing online evaluation metrics dropdown + provide a "pass threshold" where score above threshold = pass, below = fail
3. **Dynamic config form** per metric type:

| Metric type | Config form |
|---|---|
| LLM as a Judge | Text area: "Expected behavior" (single assertion text) |
| Contains | Text input: "Value" + Checkbox: "Case sensitive" |
| Equals | Text input: "Expected value" + Checkbox: "Case sensitive" |
| Levenshtein Ratio | Number input: "Pass threshold" (0-1) — score >= threshold = pass |
| Hallucination | Number input: "Pass threshold" (0-1) — score >= threshold = pass |
| Moderation | Number input: "Pass threshold" (0-1) — score >= threshold = pass |

MetricConfigForm uses a switch on metric_type — only 6 types in Phase 1. Designed to be extensible for additional types (not_contains, json_schema, semantic_similarity, tool_call).

All behavior changes (add/edit/delete) feed into the unified draft — no immediate API calls.

### LLM-as-Judge aggregation (important FE logic)

Per the PRD: "all the 'LLM as a Judge' behaviors should be aggregated into a single evaluator with multiple `expected_behaviors` when passed to the backend."

FE handles this as follows:
- **Display:** Each LLM-as-Judge assertion shown as a separate row in the behaviors table (for individual edit/delete).
- **On save:** FE aggregates all LLM-as-Judge rows into a **single** `dataset_evaluator` (or `dataset_item_evaluator`) record with `metric_config: { assertions: ["assertion 1", "assertion 2", ...] }`.
- **On load:** FE splits the single aggregated record back into multiple display rows.

This means the `LLMJudgeConfig` type stores an array of assertions, not a single string.

### Item-level behaviors

Same `AddEditBehaviorDialog` reused, targeting `dataset_item_evaluators` endpoint. Same LLM-as-Judge aggregation logic applies per item.

### Item side panel (click on item row)

Opens as a **side panel** from the right (consistent with existing dataset item pattern). Changes in the panel feed into the **parent draft** — no separate save button in the panel.

**Top section:**
- Title: "Evaluation suite item" (no item ID shown)
- **Description:** multi-line textarea, editable
- **Execution policy:** shows suite default as read-only context: *"Suite default: 1 run, 1 to pass"*
  - Below that, a toggle: **"Override suite default"**
  - Off (default): no fields shown, item uses suite policy
  - On: reveals `Runs per item` and `Pass threshold` inputs
  - Helper text below each field:
    - "Runs per item" → *"The number of times the item will be evaluated"*
    - "Pass threshold" → *"The number of times the item is required to pass the evaluation to be considered as 'passed'"*

**Middle section — "Expected behaviors":**

Two separate subsections:
1. **"Suite behaviors" (read-only)** — shows inherited suite-level behaviors, visually grayed out / locked. Not editable from the item panel.
2. **"Item behaviors" (editable)** — item-specific behaviors with add/edit/delete. Same "Add new behavior" button and `AddEditBehaviorDialog` as suite level.

**Bottom section — "Context":**

Reuses the existing dataset item editor pattern (`DatasetItemEditorForm`):
- Auto-detect field types: simple values → `TextareaAutosize`, complex objects/arrays → `CodeMirror` with JSON syntax highlighting
- Same Zod validation (complex fields must be valid JSON objects/arrays)
- Same stringify (display) → parse (save) pipeline

### Items table columns

"Expected behaviors" column shows a count: `3 behaviors` — clickable, opens item side panel.

"Execution policy" column: **hidden by default**. Auto-shows **only when >= 1 item has an execution policy override** different from suite default. When visible:
- Items using suite default: empty / dash
- Items with override: `3 runs, 2 to pass` (compact format)

### Execution policy display — suite vs item

| Location | Display | Editable? |
|---|---|---|
| Suite behaviors section header | Settings2 dropdown with `Runs per item` and `Pass threshold` | Yes — changes mark draft dirty |
| Items table column | Compact override value or dash (hidden if no overrides) | No — click opens side panel |
| Item side panel | Read-only suite default + "Override" toggle + inputs | Yes — changes mark draft dirty |

---

## 5. Experiment Results — Pass/Fail & Multi-Run

### Experiments list (Evaluation Suites tab)

Flat table, no grouping, no charts:

| Evaluation suite | Created | Duration (avg.) | Cost per trace (avg.) | Pass rate |
|---|---|---|---|---|
| Refund Policy Tests | Feb 13, 2026 | 2.3s | $0.012 | 76.2% (16/21) |
| ~~Onboarding Flow~~ (deleted) | Feb 10, 2026 | 1.5s | $0.009 | 100% (5/5) |

- Pass rate format: `percentage (passed/total)`.
- If the evaluation suite has been deleted, show the suite name with strikethrough and a "(deleted)" indicator. Past experiment results remain accessible.

### Experiment results page

**Top section:**
- Suite name + creation date as main identifier
- Pass rate display
- No Details/Dashboards toggle

**Items table:**

| Context | Duration | Estimated cost | Passed |
|---|---|---|---|
| `{"user_tier": "premium", ...}` | 2.1s | $0.011 | Yes |
| `{"user_tier": "free", ...}` | 2.4s | $0.013 | No |
| `{"user_tier": "enterprise", ...}` | 1.9s | $0.010 | Yes (2/3) |

**Passed column:**
- Single run: `Yes` (green), `No` (red), or `Skipped` (gray — item incompatible with prompt)
- Multi-run: `Yes (2/3)` or `No (1/3)`
- Hover tooltip: per-behavior breakdown table
  - Single run: Behavior / Passed columns
  - Multi-run: Behavior / Passed? (1) / Passed? (2) / Passed? (3) columns (header format matches PRD)

**Context column:** truncated JSON, click opens read-only formatted dialog.

### Experiment item sidebar

**Left pane — "Evaluation suite item context":**
- Item data fields, default YAML view
- No "?" tooltip icon

**Right pane — "Experiment results":**
- PASSED/FAILED badge (green/red) next to title
- Output section (trace output)
- "Expected behaviors (n)" table: Behavior / Passed / Reason columns
- No "Your scores" tab, no "Comments" section

**Multi-run:** Run 1 / Run 2 / ... / Run N tab navigation above output. Each tab shows that run's output + behavior results. Top-level badge reflects item-level result.

---

## 6. Phase Breakdown & Progress

### Phase 1 — New pages scaffold + sidebar (FE only) — DONE

- [x] Create `EvaluationSuitesPage` shell with renamed text (header, sub-header, breadcrumbs, dialogs)
- [x] Create `EvaluationSuiteItemsPage` shell with renamed text (tabs: Items, Version history — stubs)
- [x] Create `EvaluationSuitePage` layout component (breadcrumbs, redirect `/$suiteId` → `/$suiteId/items`)
- [x] Register new routes in router (`/evaluation-suites`, `/$suiteId`, `/$suiteId/items`)
- [x] Add "Evaluation suites" sidebar entry with `ListChecks` icon
- [x] Conditionally show "Datasets" sidebar entry based on `hasGeneralDatasets` check

**BE dependency:** None.

### Phase 2a — Suites list table (FE only) — DONE

- [x] `EvaluationSuitesPage` rewritten with full DataTable (search, filters, sort, resize, pagination, row selection)
- [x] Column definitions in `columns.tsx` (name, id, description, item count, tags, most recent experiment, last updated, created, created by)
- [x] `AddEditEvaluationSuiteDialog` — name + description, no CSV upload, uses dataset create/update mutations
- [x] `EvaluationSuiteRowActionsCell` — edit + delete (no download)
- [x] `EvaluationSuitesActionsPanel` — bulk delete
- [x] Row click navigates to `/$workspaceName/evaluation-suites/$suiteId`
- [x] All localStorage keys use `evaluation-suites-` prefix
- [x] `EvaluationSuitesPage` filters API call to `type=evaluation_suite`

### Phase 2b — Type system across touchpoints — DONE

- [x] `DATASET_TYPE` enum added to `types/datasets.ts` (`DATASET`, `EVALUATION_SUITE`)
- [x] `DatasetsPage` filters to `type=dataset`
- [x] Sidebar: show/hide "Datasets" based on actual data (`hasGeneralDatasets`)
- [x] Experiments page: tab navigation with "Evaluation suites" (default) and "General datasets" tabs
- [x] `EvaluationSuitesTab` on Experiments page — functional with columns, search, pagination
- [x] `GeneralDatasetsTab` extracted from old ExperimentsPage — fully functional

**Remaining Phase 2 items (not yet done):**
- [ ] `AddEditEvaluationSuiteDialog` create mutation sends `type=evaluation_suite` in payload
- [ ] `EvaluationSuitesTab` filters experiments by `dataset_type=evaluation_suite` (has TODO comment)

**BE dependency:** `type` field migration, list endpoint filter support, experiments endpoint dataset type filter.

### Phase 2c — Suite detail items page (FE only, no evaluator endpoints needed)

- [ ] `EvaluationSuiteItemsPage` Items tab — full DataTable with dataset items (reuse `useDatasetItemsList`)
- [ ] Items tab columns, search, filters, pagination, row actions
- [ ] Add item dialog (reuse dataset item creation pattern)
- [ ] Version history tab (reuse `VersionHistoryTab` pattern from `DatasetItemsPage`)
- [ ] Suite description display below title

**BE dependency:** None (reuses existing dataset items API).

### Phase 3 — Evaluators & execution policy (needs BE: new endpoints)

- [ ] `BehaviorsSection`: execution policy form, behaviors table, add/edit/delete dialog with metric type selector + config form
- [ ] `EvaluationSuiteItemDialog`: three-section layout (description + policy / item behaviors / context)
- [ ] Items table: new default columns (description, context, expected behaviors, execution policy)
- [ ] `EvaluationSuiteDraftStore`: tracks item + evaluator + policy changes
- [ ] `description` field on items rendered and editable

**BE dependency:** `dataset_evaluators` and `dataset_item_evaluators` CRUD endpoints, `default_execution_policy` on datasets, `description` + `execution_policy` on dataset_items.

### Phase 4 — Experiment results with pass/fail (needs BE + SDK)

- [ ] `EvaluationSuitesTab` on Experiments page: pass rate column functional
- [ ] `EvaluationSuiteExperimentPage`: suite name + date header, items table with Passed column
- [ ] `PassedCell` + `BehaviorsBreakdownTooltip`: pass/fail with per-behavior hover
- [ ] Multi-run: `Yes (2/3)` format, per-run tooltip
- [ ] `ExperimentItemSidebar`: PASSED/FAILED badge, behaviors results table, multi-run tabs
- [ ] Pass rate column on suites list page

**BE dependency:** `run_number` on experiment_items, `execution_policy` on experiments, computed `passed`/`pass_rate` fields.
**SDK dependency:** Multi-run execution, metric serialization, LLM-as-Judge evaluator populating results.

### Phase 5 — Polish & lower priority

- [ ] Duplicate suite action
- [ ] Duplicate item action
- [ ] "Run an experiment" dialog simplified for suites
- [ ] Demo/seed data update

---

## 7. Open Questions for BE

1. **Pass/fail computation** — Does BE return computed `passed` on experiment items and `pass_rate` on experiments? Or does FE compute from raw feedback scores + policy?
2. **Metric config schema** — Is there a BE-provided schema per metric_type, or does FE hardcode the 6 Phase 1 config shapes?
3. **LLM-as-Judge result storage** — Are per-assertion results stored as separate FeedbackScores or one structured score? FE needs per-assertion `passed`, `pass_score`, and `reason` to render the behaviors breakdown table.
4. **Dataset duplication endpoint** — Will BE provide this, or does FE implement as fetch + re-create?
5. **Sidebar dataset type check** — Can the datasets list endpoint return a count by type efficiently, or should FE fetch the full list?
6. **Skipped items** — When an item is incompatible with the prompt and gets skipped, how is this represented in experiment_items? Is there a status field, or is the item simply absent?
7. **Online evaluation metrics integration** — Can we reuse the existing automation rules/online scoring system as metric sources in the "Add behavior" dialog? What endpoint returns available online evaluators?
8. **LLM-as-Judge aggregation on save** — FE will aggregate multiple LLM-as-Judge assertions into a single evaluator record. Does BE expect `metric_config: { assertions: [...] }` format, or a different shape?
