# How Experiments Work in Opik — Full Engineering Analysis

> Date: 2026-02-20 (updated)
> Purpose: Understand every place experiments are used in the product, and what changes when a **brand new experiment type** for evaluation suites arrives — one that has **no feedback scores, no comments** but has **pass_rate, behavior_results, status** instead.

---

## CRITICAL CLARIFICATION: EXPERIMENT TYPES

```
EXPERIMENT_TYPE enum (current):
  REGULAR     = "regular"       → Standard experiments (the default everywhere)
  TRIAL       = "trial"         → Optimization experiments ONLY
  MINI_BATCH  = "mini-batch"    → Optimization experiments ONLY

EXPERIMENT_TYPE enum (after our change):
  REGULAR          = "regular"           → Standard experiments
  TRIAL            = "trial"             → Optimization experiments ONLY
  MINI_BATCH       = "mini-batch"        → Optimization experiments ONLY
  EVALUATION_SUITE = "evaluation_suite"  → NEW — Evaluation suite experiments ((it can be or this new type or any different mark, field, which helps FE to understand what is the API was cllaed to generate this experiment, old eval or new))
```

**Key facts:**

- TRIAL and MINI_BATCH are **optimization types** — they are completely unrelated to the evaluation suites feature
- The current `isEvalSuiteExperimentType()` in `src/lib/experiments.ts` checks TRIAL+MINI_BATCH — this is a **misleadingly named function that serves optimizations**, not our feature
- We need a **new type checker** for the new `EVALUATION_SUITE` type
- Optimization pages (`CompareOptimizationsPage`, `CompareTrialsPage`, `OptimizationRunsSection`) are **NOT affected** by our feature

---

## 1. THE DATA MODEL TODAY

### Experiment (list-level)

```typescript
interface Experiment {
  id, dataset_id, dataset_name, type, status, name, created_at, ...

  // SCORES — the core of the current system:
  feedback_scores?: AggregatedFeedbackScore[]   // trace-level, averaged across items
  experiment_scores?: AggregatedFeedbackScore[]  // experiment-level aggregation

  // OTHER DATA:
  comments?: CommentItems
  duration?: AggregatedDuration
  trace_count: number
  total_estimated_cost?: number
  total_estimated_cost_avg?: number
  metadata?: object
  tags?: string[]
  prompt_versions?: ExperimentPromptVersion[]
}
```

### ExperimentItem (row-level, inside compare page)

```typescript
interface ExperimentItem {
  id, experiment_id, dataset_item_id, trace_id, input, output, created_at, ...

  feedback_scores?: TraceFeedbackScore[]   // per-trace scores
  comments?: CommentItems                  // per-trace comments
  duration?: number
  total_estimated_cost?: number
}
```

### Key insight — Two score systems:

- `feedback_scores` (type `SCORE_TYPE_FEEDBACK`) — attached to traces, averaged across experiment items. Numeric (0-1 range or arbitrary). Every REGULAR experiment has these.
- `experiment_scores` (type `SCORE_TYPE_EXPERIMENT`) — computed at experiment level. Currently rarely used.

### Current type discriminator (MISNAMED — serves optimizations, NOT eval suites):

```typescript
// src/lib/experiments.ts
// This function checks optimization types, NOT the new eval suite type!
const EVAL_SUITE_EXPERIMENT_TYPES = new Set([EXPERIMENT_TYPE.TRIAL, EXPERIMENT_TYPE.MINI_BATCH]);
export function isEvalSuiteExperimentType(type) { ... }
```

### What we need instead:

```typescript
// A new function for our feature:
export function isEvaluationSuiteType(
  type: EXPERIMENT_TYPE | undefined
): boolean {
  return type === EXPERIMENT_TYPE.EVALUATION_SUITE;
}
```

### Where default type filtering happens:

```typescript
// Three API hooks all default to REGULAR only:
// src/api/datasets/useExperimentsList.ts:10
// src/api/datasets/useExperimentsGroups.ts:9
// src/api/datasets/useExperimentsGroupsAggregations.ts:12
const DEFAULT_EXPERIMENTS_TYPES = [EXPERIMENT_TYPE.REGULAR];

// ONLY GeneralDatasetsTab uses ALL types:
// src/components/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx:88
const ALL_EXPERIMENT_TYPES = Object.values(EXPERIMENT_TYPE);
// ^ This will automatically include EVALUATION_SUITE once added to the enum
```

---

## 2. WHAT THE NEW EXPERIMENT TYPE CHANGES

Evaluation suite experiments (`type: "evaluation_suite"`) will have:

- **NO `feedback_scores`** — empty/null array
- **NO `comments`** — empty/null
- **NEW `pass_rate`** (number 0.0-1.0) — server-computed
- **NEW `passed_count`** / `total_count`\*\* — for display "76.2% (16/21)"
- **NEW `status`** on ExperimentItem — `passed | failed | skipped`
- **NEW `behavior_results`** on ExperimentItem — array of `{ behavior_name, passed, reason }`

((make sure we will have data for https://www.notion.so/cometml/Evaluation-Suites-PRD-3037124010a380f08d6ddcc6e598d0ec?source=copy_link#3037124010a38064be61cba34c39e3f1 and https://www.notion.so/cometml/Evaluation-Suites-PRD-3037124010a380f08d6ddcc6e598d0ec?source=copy_link#3037124010a380509643e912e32c3759 also for the experiment item sidebar https://www.notion.so/cometml/Evaluation-Suites-PRD-3037124010a380f08d6ddcc6e598d0ec?source=copy_link#3037124010a3802dba0cd6f241d087ed))

The fundamental shift: **regular experiments measure quality with numeric scores, evaluation suite experiments measure pass/fail with boolean assertions**.

---

## 3. EVERY PAGE/COMPONENT THAT USES EXPERIMENTS

### 3.1 EXPERIMENTS PAGE (the main list)

**File:** `ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx`

**How it works today:**

- Fetches ALL experiment types via `useGroupedExperimentsList` with `types: Object.values(EXPERIMENT_TYPE)` (line 88)
- This means the new `EVALUATION_SUITE` type will **automatically appear** here once added to the enum
- Groups experiments by dataset/project/metadata with aggregations
- Shows `feedback_scores` as dynamic columns (one sub-column per score name)
- Shows `comments` column
- Charts show feedback_scores over time per group

**What's affected:**

| Feature                     | Impact                                                                                                                              | Decision needed?                                                                                                                                                            |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **New type auto-included**  | `Object.values(EXPERIMENT_TYPE)` will include `EVALUATION_SUITE` — experiments will appear in the table without any code change     | No — works automatically                                                                                                                                                    |
| **Feedback scores columns** | Empty for eval suite experiments. Column still renders but shows "—" for each score.                                                | **YES** — PRD says "Feedback Scores only shown if legacy experiments exist in workspace." How to detect this? Feature flag? Check if any experiment in the list has scores? |
| **Comments column**         | Empty for eval suite experiments                                                                                                    | Same as above — should it be hidden if no experiments have comments?                                                                                                        |
| **pass_rate column**        | Already added (line 296) but missing: `sortable: true`, `aggregatedCell`, not in defaults                                           | FE work, no decision needed                                                                                                                                                 |
| **Charts**                  | Only plot `feedback_scores` + `experiment_scores`. Eval suite experiments have neither. Charts will be empty for eval suite groups. | **YES** — Should charts plot `pass_rate` as a line? The PRD says "Adapt charts to show aggregated score based on pass rate"                                                 |
| **Sorting**                 | `sortable_by` comes from BE. If BE doesn't include `pass_rate`, column won't be sortable.                                           | **BE question** — add `pass_rate` to sortable_by                                                                                                                            |
| **Grouping aggregations**   | `ExperimentsAggregations` type doesn't have `pass_rate`. Grouped row headers can't show aggregated pass rate.                       | **BE question** — add `pass_rate`, `passed_count`, `total_count` to aggregations response                                                                                   |
| **Row click**               | All experiments navigate to `CompareExperimentsPage` — works for both types                                                         | No change                                                                                                                                                                   |

---

### 3.2 COMPARE EXPERIMENTS PAGE (experiment results/details)

**File:** `CompareExperimentsPage/CompareExperimentsPage.tsx`

**How it works today:**

- Fetches experiments by IDs
- Detects eval suite type via `isEvalSuiteExperimentType(experiments[0]?.type)` (line 60-62)
- **PROBLEM**: The current `isEvalSuiteExperimentType()` checks TRIAL+MINI_BATCH (optimization types), NOT the new EVALUATION_SUITE type. This code needs to be updated to use a new function that checks the new type.
- Only triggers when `experiments.length === 1`
- Has 3 tabs (details view): **Experiment items**, **Configuration**, **Feedback scores**
- Has dashboards view

**What MUST change first:**

```typescript
// CURRENT (wrong for our feature — serves optimizations):
const isEvalSuite =
  memorizedExperiments.length === 1 &&
  isEvalSuiteExperimentType(memorizedExperiments[0]?.type);

// NEEDED (check the new EVALUATION_SUITE type):
const isEvalSuite =
  memorizedExperiments.length === 1 &&
  isEvaluationSuiteType(memorizedExperiments[0]?.type);
```

**What else is affected:**

| Feature                                           | Impact                                                                                                                                                             | Decision needed?                                                                                                                    |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------- |
| **"Feedback scores" tab** (line 81-87)            | Shows `ExperimentFeedbackScoresTab` which builds bar/radar charts from `experiment.feedback_scores`. For eval suite experiments this will be **completely empty**. | **YES** — Should we hide this tab for eval suite experiments? Replace with an "Assertions" tab? Keep it but show pass_rate instead? |
| **Tab label "Feedback scores"**                   | Misleading for eval suite experiments that don't have feedback scores                                                                                              | **YES** — Rename tab? Conditional label?                                                                                            |
| **isEvalSuite guard: `experiments.length === 1`** | Multi-experiment compare always falls through to regular UI                                                                                                        | **QUESTION** — Can users compare multiple eval suite experiments? If yes, `isEvalSuite` logic needs rethinking                      |
| **Details header** (CompareExperimentsDetails)    | Shows `FeedbackScoresList` for single experiments. For eval suite, shows placeholder `Pass rate: —` (line 245-247)                                                 | FE work — wire up actual `pass_rate` from BE                                                                                        |
| **Charts** (useCompareExperimentsChartsData)      | Builds radar/bar charts from `feedback_scores` + `experiment_scores`. Empty for eval suite experiments.                                                            | **YES** — Should compare page charts show pass_rate?                                                                                |

---

### 3.3 EXPERIMENT ITEMS TAB (inside compare page)

**File:** `CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab.tsx`

**How it works today:**

- Shows a table of dataset items x experiment results
- Default columns: `[ID, Comments, User Feedback Score]`
- Has dynamic columns for each feedback score
- `isEvalSuite` prop controls: pinned "Passed" column (line 506-516, 228), sidebar variant (line 744)
- Sidebar opens `CompareExperimentsPanel` (regular) or `EvaluationSuiteExperimentPanel` (eval suite)

**What's affected:**

| Feature                                                  | Impact                                                                                            | Decision needed?                                                                             |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| **Default columns** `[ID, Comments, User Feedback]`      | ALL THREE are irrelevant for eval suite experiments. Comments and feedback scores will be empty.  | FE work — conditional defaults for eval suite: `[description, data, duration, cost, passed]` |
| **Comments column** (line 106, 129, 326)                 | Shows CommentsCell. Eval suite experiments have no comments.                                      | Should we hide it? Or keep it available but not in defaults?                                 |
| **User Feedback column** (line 73-74, 130, 347-348)      | A special feedback score column for human annotations. Eval suite experiments won't have this.    | Same question                                                                                |
| **Feedback scores dynamic columns** (line 116, 236, 365) | Built from `COLUMN_FEEDBACK_SCORES_ID`. Score sub-columns won't exist for eval suite experiments. | Same — they just won't appear since there are no scores                                      |
| **Passed column** (line 506-516)                         | Already conditionally added when `isEvalSuite`. Uses `PassedCell`.                                | Already partially done — will work once `isEvalSuite` checks the correct type                |
| **Sidebar**                                              | Conditional: `EvaluationSuiteExperimentPanel` vs `CompareExperimentsPanel`                        | Already working — will work once `isEvalSuite` checks the correct type                       |
| **Missing Description column**                           | Eval suite items have `description` but no column definition exists                               | FE work                                                                                      |
| **Missing Data column**                                  | Eval suite items have `data` (JSON) but no column/cell                                            | FE work — needs new `DataObjectCell` component                                               |

---

### 3.4 EXPERIMENT FEEDBACK SCORES TAB (inside compare page)

**File:** `CompareExperimentsPage/ExperimentFeedbackScoresTab/ExperimentFeedbackScoresTab.tsx`

**How it works today:**

- Builds a table: rows = score names, columns = experiments
- Sources from `experiment.feedback_scores` + `experiment.experiment_scores`
- Shows value per score per experiment

**What's affected:**

- **Completely empty** for eval suite experiments — no feedback_scores, no experiment_scores
- **DECISION:** Hide tab? Replace content? Show pass_rate breakdown instead?

---

### 3.5 EXPERIMENT ITEM SIDEBAR (eval suite variant)

**File:** `EvaluationSuiteExperimentPage/ExperimentItemSidebar/`

**How it works today:**

- Left pane: "Evaluation suite item context" (input YAML)
- Right pane: "Experiment results" with Pass/Fail badge
- Shows Output section + BehaviorsResultsTable (evaluator results)
- Multi-run: tabs for "Run 1", "Run 2", etc.

**What's affected:**

| Feature                                                   | Impact                                                                                 | Decision needed?                              |
| --------------------------------------------------------- | -------------------------------------------------------------------------------------- | --------------------------------------------- |
| **BehaviorsResultsTable** header says "Evaluator results" | PRD says "Assertions"                                                                  | FE string change                              |
| **Column header "Evaluator"**                             | PRD says "Assertion"                                                                   | FE string change                              |
| **behavior_results data**                                 | Comes from `ExperimentItem.behavior_results` — **field doesn't exist yet on the type** | FE type extension + BE must return this field |

---

### 3.6 EXPERIMENT ITEM SIDEBAR (regular variant)

**File:** `CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsViewer.tsx`

((do we need to update the copies here from dataset to evaluation suites?))
**How it works today:**

- Shows input/output for the experiment item
- Shows **Feedback scores** section — lists all `feedback_scores` for the trace
- Shows **Comments** section

**What's affected:**

- This sidebar is NOT shown for eval suite experiments (conditional rendering picks `EvaluationSuiteExperimentPanel` instead)
- **No impact** — the two sidebars are completely separate

---

### 3.7 HOME PAGE

**File:** `HomePage/EvaluationSection.tsx`

**How it works today:**

- Shows 5 most recent experiments in a mini-table
- Columns: Experiment name, Evaluation suite, Feedback scores, Created
- Uses `useExperimentsList` with **default types** = `[REGULAR]` only

**What's affected:**

| Feature                    | Impact                                                                                                           | Decision needed?                                                                                            |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| **Experiment type filter** | Only shows REGULAR. New `EVALUATION_SUITE` type **won't appear** unless we explicitly add it to the types array. | **YES** — Should homepage show eval suite experiments? If yes, need to pass `[REGULAR, EVALUATION_SUITE]`.  |
| **Feedback scores column** | Uses `transformExperimentScores` to build score pills. Empty for eval suite experiments.                         | If we show eval suite experiments, should we add a pass_rate column? Or is the homepage too small for this? |

## (( we have to double check this part and provide the options what we can do, left as it's filter our new experiment type, or show new experiment types in the unified table, what to show and how?))

### 3.8 PROMPT PAGE — EXPERIMENTS TAB

**File:** `PromptPage/ExperimentsTab/ExperimentsTab.tsx`

**How it works today:**

- Shows experiments associated with a prompt
- Uses `useGroupedExperimentsList` with **default types** = `[REGULAR]`
- Shows feedback_scores column

**What's affected:**

| Feature                    | Impact                           | Decision needed?                                                                                                               |
| -------------------------- | -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **Type filter**            | Only REGULAR experiments shown.  | **PRODUCT question** — Can eval suite experiments be associated with prompts? If yes, need to include `EVALUATION_SUITE` type. |
| **Feedback scores column** | Empty for eval suite experiments | If prompts support eval suites, need pass_rate column                                                                          |

## ((same here, provide the options we have to confirm with product team))

### 3.9 PLAYGROUND PAGE

**File:** `PlaygroundPage/` (multiple files)

**How it works today:**

- User selects a dataset, runs prompts against it, creates experiment
- Uses `useDatasetsList`, `useDatasetItemsList`
- Creates REGULAR experiments

**What's affected:**

| Feature                 | Impact                                                                                                  | Decision needed?                                                                                                                             |
| ----------------------- | ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| **Experiment creation** | Playground creates REGULAR experiments. Does it need to create eval suite experiments?                  | **PRODUCT question** — Should playground support running against evaluation suites (with evaluators/assertions)?                             |
| **Dataset selection**   | Can select any dataset including evaluation suites. But the experiment it creates won't run evaluators. | **DESIGN question** — Should we prevent selection of evaluation suites in playground, or should it work and just create REGULAR experiments? |

## ((we are going to create a new workflow to properly work with eval suites, but for now it should work with eval suite dataset types as before, create the regular experiments))

### 3.10 DASHBOARD WIDGETS

**Files:**

- `ExperimentsFeedbackScoresWidget/` — charts feedback scores across experiments
- `ExperimentsLeaderboardWidget/` — ranks experiments by scores

**How they work today:**

- Both use `useExperimentsList` with **default types** = `[REGULAR]` only
- Build visualizations from `feedback_scores` data

**What's affected:**

| Feature                   | Impact                                                            | Decision needed?                                                   |
| ------------------------- | ----------------------------------------------------------------- | ------------------------------------------------------------------ |
| **Type filter**           | Only REGULAR experiments. Eval suite experiments excluded.        | **YES** — Should dashboard widgets include eval suite experiments? |
| **Leaderboard**           | Ranks by feedback score values. Eval suite experiments have none. | If included, need pass_rate as a rankable metric                   |
| **Feedback scores chart** | Plots score trends. Empty for eval suite experiments.             | If included, should chart pass_rate trend?                         |

## ((also we have to define the options here and ask product team to decide))

### PAGES NOT AFFECTED BY OUR FEATURE (optimizations — use TRIAL/MINI_BATCH)

These pages are for the **optimization feature** and use `TRIAL` / `MINI_BATCH` types. They are **completely separate** from the evaluation suites feature:

- `OptimizationsPage/` — creates optimization experiments (TRIAL type)
- `CompareOptimizationsPage/` — fetches `[TRIAL, MINI_BATCH]` explicitly
- `CompareTrialsPage/` — fetches by IDs, shows optimization trials
- `OptimizationRunsSection` — uses optimizations API

**No changes needed** for these pages as part of the evaluation suites feature.

---

## 4. THE CORE ARCHITECTURAL QUESTION

The product has **two fundamentally different experiment paradigms** that need to coexist:

|                    | Regular Experiments                            | Eval Suite Experiments                           |
| ------------------ | ---------------------------------------------- | ------------------------------------------------ |
| **Type**           | `EXPERIMENT_TYPE.REGULAR`                      | `EXPERIMENT_TYPE.EVALUATION_SUITE` (new)         |
| **Quality metric** | Numeric feedback scores (0-1 range)            | Boolean pass/fail per assertion                  |
| **Aggregation**    | Average of scores across items                 | pass_rate = % of items passing                   |
| **Per-item data**  | `feedback_scores[]`, `comments`                | `status`, `behavior_results[]`                   |
| **Charts**         | Score trends over time                         | Pass rate trends over time                       |
| **Item columns**   | ID, comments, user feedback, dynamic scores    | Description, data (JSON), duration, cost, passed |
| **Sidebar**        | Input/output + feedback scores list + comments | Input (YAML) + output + assertions table         |

Every component that renders experiment data needs to handle both paradigms. The current `isEvalSuiteExperimentType()` function **cannot be reused** — it checks optimization types. We need:

1. A new `EXPERIMENT_TYPE.EVALUATION_SUITE` enum value
2. A new `isEvaluationSuiteType()` function that checks the new type
3. Update all `isEvalSuite` usages in `CompareExperimentsPage` to use the new function instead of the old optimization-focused one

### Where `isEvalSuite` is currently used (all need retargeting):

| File                            | Line    | Current behavior                                                                |
| ------------------------------- | ------- | ------------------------------------------------------------------------------- |
| `CompareExperimentsPage.tsx`    | 60-62   | Sets `isEvalSuite` from `isEvalSuiteExperimentType()` (checks TRIAL/MINI_BATCH) |
| `CompareExperimentsPage.tsx`    | 94      | Passes `isEvalSuite` to `ExperimentItemsTab`                                    |
| `CompareExperimentsPage.tsx`    | 128     | Passes `isEvalSuite` to `CompareExperimentsDetails`                             |
| `ExperimentItemsTab.tsx`        | 228     | Pins "Passed" column when `isEvalSuite`                                         |
| `ExperimentItemsTab.tsx`        | 506-521 | Adds PassedCell column when `isEvalSuite`                                       |
| `ExperimentItemsTab.tsx`        | 744     | Shows eval suite sidebar when `isEvalSuite`                                     |
| `CompareExperimentsDetails.tsx` | 245     | Shows pass rate placeholder when `isEvalSuite`                                  |

---

## 5. COMPLETE LIST OF DECISIONS NEEDED

### Product/Design Decisions

| #      | Question                                                                                      | Where it matters                     | Options                                                                                                 |
| ------ | --------------------------------------------------------------------------------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------- |
| **D1** | Should "Feedback scores" tab be hidden for eval suite experiments on CompareExperimentsPage?  | `CompareExperimentsPage.tsx:81-87`   | a) Hide tab, b) Rename to "Scores" and show pass_rate, c) Keep empty                                    |
| **D2** | How to detect "legacy experiments exist in workspace" for conditional feedback scores column? | `GeneralDatasetsTab.tsx` defaults    | a) Feature flag, b) Check if any experiment in list has scores, c) Always show both                     |
| **D3** | Should homepage (EvaluationSection) show eval suite experiments?                              | `HomePage/EvaluationSection.tsx`     | a) Show all types + add pass_rate column, b) Keep REGULAR only                                          |
| **D4** | Can eval suite experiments be associated with prompts?                                        | `PromptPage/ExperimentsTab`          | a) Yes → add EVALUATION_SUITE type + pass_rate, b) No → keep REGULAR only                               |
| **D5** | Should playground support evaluation suites?                                                  | `PlaygroundPage/`                    | a) Yes → create EVALUATION_SUITE experiments with evaluators, b) No → block selection or create REGULAR |
| **D6** | Should dashboard widgets include eval suite experiments?                                      | Dashboard widgets                    | a) Yes → add type filter + pass_rate, b) No → keep REGULAR only                                         |
| **D7** | Can users compare MULTIPLE eval suite experiments?                                            | `CompareExperimentsPage.tsx:60-62`   | a) Yes → `isEvalSuite` needs to work for multi-compare, b) No → keep single-only                        |
| **D8** | Compare page charts (radar/bar) — what to show for eval suite experiments?                    | `useCompareExperimentsChartsData.ts` | a) Show pass_rate as a single bar/point, b) Show nothing, c) Show assertion-level breakdown             |

### BE Team Requests

| #      | What FE needs                                              | Endpoint                                          | Details                                      |
| ------ | ---------------------------------------------------------- | ------------------------------------------------- | -------------------------------------------- | ------ | ----------------- |
| **B1** | New `EVALUATION_SUITE` experiment type in API              | All experiment endpoints                          | New type value in `type` field               |
| **B2** | `pass_rate`, `passed_count`, `total_count` on `Experiment` | `GET /v1/private/experiments`                     | New fields in experiment list response       |
| **B3** | Same fields on `ExperimentsAggregations`                   | `GET /v1/private/experiments/groups/aggregations` | For grouped table rows                       |
| **B4** | `"pass_rate"` in `sortable_by` array                       | `GET /v1/private/experiments`                     | Enable pass_rate column sorting              |
| **B5** | `status` on `ExperimentItem`                               | `GET /v1/private/experiments/items/compare`       | `passed                                      | failed | skipped` per item |
| **B6** | `behavior_results[]` on `ExperimentItem`                   | Same endpoint                                     | Array of `{ behavior_name, passed, reason }` |
| **B7** | `pass_rate` on single `Experiment` detail                  | `GET /v1/private/experiments/{id}`                | For compare page header                      |

### FE Work (no decisions needed)

| #       | What                                                                             | File                                      | Effort |
| ------- | -------------------------------------------------------------------------------- | ----------------------------------------- | ------ |
| **F1**  | Add `EVALUATION_SUITE` to `EXPERIMENT_TYPE` enum                                 | `types/datasets.ts`                       | S      |
| **F2**  | Create new `isEvaluationSuiteType()` function                                    | `lib/experiments.ts`                      | S      |
| **F3**  | Update `CompareExperimentsPage` to use new type checker                          | `CompareExperimentsPage.tsx`              | S      |
| **F4**  | Add `pass_rate`, `passed_count`, `total_count` to `Experiment` type              | `types/datasets.ts`                       | S      |
| **F5**  | Add `status`, `behavior_results` to `ExperimentItem` type                        | `types/datasets.ts`                       | S      |
| **F6**  | Add `pass_rate`, `passed_count`, `total_count` to `ExperimentsAggregations` type | `types/datasets.ts`                       | S      |
| **F7**  | Make pass_rate column sortable                                                   | `GeneralDatasetsTab.tsx`                  | S      |
| **F8**  | Add pass_rate aggregation cell for grouped rows                                  | `GeneralDatasetsTab.tsx`                  | M      |
| **F9**  | Add pass_rate to DEFAULT_SELECTED_COLUMNS                                        | `GeneralDatasetsTab.tsx`                  | S      |
| **F10** | Conditional default columns for eval suite on compare page                       | `ExperimentItemsTab.tsx`                  | M      |
| **F11** | Add Description column for eval suite items                                      | `ExperimentItemsTab.tsx`                  | S      |
| **F12** | Add Data column + new DataObjectCell component                                   | `ExperimentItemsTab.tsx` + new file       | M      |
| **F13** | Wire up actual pass_rate in compare details header                               | `CompareExperimentsDetails.tsx`           | S      |
| **F14** | Rename "Evaluator results" -> "Assertions" and "Evaluator" -> "Assertion"        | `BehaviorsResultsTable.tsx`               | S      |
| **F15** | Add pass_rate to experiments page charts                                         | `GeneralDatasetsTab.tsx` chart section    | M      |
| **F16** | Add pass_rate to dashboard leaderboard widget                                    | `ExperimentsLeaderboardWidget/helpers.ts` | S      |

---

## 6. USER FLOW ANALYSIS

### Flow 1: User views experiments list

**Today:** See table with name, evaluation suite, feedback scores, comments
**With eval suites:** Same table but some rows have pass_rate instead of feedback scores. Feedback score columns are empty for those rows, pass_rate column is empty for regular rows.
**Risk:** Table looks sparse/confusing with many empty cells if both types coexist.

### Flow 2: User clicks an experiment to see results

**Today:** CompareExperimentsPage with items table showing ID, comments, user feedback
**With eval suites:** If eval suite experiment -> different default columns (description, data, passed), different sidebar. If regular -> same as today.
**Risk:** `isEvalSuite` is only `true` for single-experiment view. Multi-compare falls back to regular UI with empty feedback scores.
**NEW RISK:** The current `isEvalSuite` flag checks the WRONG type (TRIAL/MINI_BATCH instead of EVALUATION_SUITE). Until F2/F3 are done, eval suite experiments will render with the regular experiment UI.

### Flow 3: User opens "Feedback scores" tab

**Today:** See bar/radar charts comparing experiments by score
**With eval suites:** Charts are completely empty for eval suite experiments.
**Risk:** Users navigate to this tab and see nothing. Confusing.

### Flow 4: User groups experiments by evaluation suite

**Today:** Aggregated row shows summed feedback scores
**With eval suites:** Aggregated row has no pass_rate (BE doesn't return it yet in aggregations).
**Risk:** Grouped view for eval suite experiments shows no useful metrics.

### Flow 5: User creates experiment from "Add experiment" dialog

**Today:** Select evaluation suite -> select evaluators -> get code
**With eval suites:** PRD says to conditionally hide evaluator selection for evaluation suite datasets (evaluators are defined on the suite itself).
**Risk:** If not hidden, users might try to add evaluators that conflict with suite-level evaluators.

---

## 7. RECOMMENDED APPROACH

### Phase 1: Foundation (F1-F6, blocked on BE B1-B7)

1. **F1**: Add `EVALUATION_SUITE` to the `EXPERIMENT_TYPE` enum
2. **F2**: Create `isEvaluationSuiteType()` function in `lib/experiments.ts`
3. **F3**: Update `CompareExperimentsPage` to use the new function
4. **F4-F6**: Extend `Experiment`, `ExperimentItem`, `ExperimentsAggregations` types with new fields
5. **B1-B7**: BE adds new type + fields to responses

### Phase 2: Experiments Table (F7-F9)

6. **F7**: Make pass_rate column sortable
7. **F8**: Add pass_rate aggregation cell for grouped rows
8. **F9**: Add pass_rate to default columns

### Phase 3: Compare Page (F10-F14)

9. **F10-F12**: Conditional columns + DataObjectCell for eval suite experiments
10. **F13**: Wire up actual pass_rate in header
11. **F14**: String changes in sidebar
12. Get decisions on **D1** (feedback scores tab) and **D7** (multi-compare)

### Phase 4: Charts & Widgets (F15-F16)

13. **F15-F16**: Pass rate in charts and dashboard widgets
14. Implement decisions from **D2**, **D3**, **D6**

### Phase 5: Other Pages

15. Implement decisions from **D4** (prompts), **D5** (playground)
