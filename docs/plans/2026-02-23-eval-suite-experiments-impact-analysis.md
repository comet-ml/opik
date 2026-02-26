# Evaluation Suite Experiments — Impact Analysis

> **Date:** 2026-02-23
> **Audience:** Product, Design, FE, BE, QA
> **Source of truth:** [Evaluation Suites PRD](https://www.notion.so/cometml/Evaluation-Suites-PRD-3037124010a380f08d6ddcc6e598d0ec)

---

## Summary

A new evaluation engine will produce experiments with **pass/fail assertions** instead of **numeric feedback scores**. FE needs a way to distinguish these experiments from regular ones (new type, flag, or field — BE decides the mechanism). This document maps **every page, component, column, chart, dialog, and user flow** affected by this change, lists required work for each team, and captures open product decisions.

### Glossary

| Term                      | Meaning                                                                     |
| ------------------------- | --------------------------------------------------------------------------- |
| **Regular experiment**    | Standard experiment with numeric `feedback_scores` and `comments`           |
| **Eval suite experiment** | New experiment type with boolean `pass/fail` assertions and `pass_rate`     |
| **Type guard**            | FE function that checks whether an experiment is eval-suite or regular      |
| **Assertion**             | A single pass/fail check within an evaluator (e.g., "Should link to docs")  |
| **Assertion result**      | The outcome of one assertion for one experiment item run                    |
| **Pass rate**             | Percentage of items that passed all assertions: `avg(item.passed)`          |
| **Pass threshold**        | Minimum number of runs that must pass for an item to be considered "passed" |

---

## Table of Contents

1. [How Experiments Work Today](#1-how-experiments-work-today)
2. [What Changes With Eval Suite Experiments](#2-what-changes-with-eval-suite-experiments)
3. [Type Guard Mechanism](#3-type-guard-mechanism)
4. [Affected Pages — Detailed Breakdown](#4-affected-pages--detailed-breakdown)
5. [UI Before & After](#5-ui-before--after)
6. [Product & Design Decisions](#6-product--design-decisions)
7. [BE Required Work](#7-be-required-work)
8. [FE Required Work](#8-fe-required-work)
9. [Affected User Flows](#9-affected-user-flows)
10. [QA Regression Checklist](#10-qa-regression-checklist)

---

## 1. How Experiments Work Today

### Data Model

```
Experiment (list-level)
├── feedback_scores[]    ← numeric scores averaged across items (0-1 range)
├── experiment_scores[]  ← experiment-level computed scores
├── comments             ← user-attached comments
├── duration, trace_count, total_estimated_cost
├── tags[], metadata, prompt_versions[]
└── type                 ← "regular" | "trial" | "mini-batch"

ExperimentItem (row-level, inside compare page)
├── feedback_scores[]    ← per-trace numeric scores
├── comments             ← per-trace comments
├── input, output        ← trace data
└── duration, total_estimated_cost
```

### Experiment Types (current)

| Type         | Used By                  | Default in API hooks |
| ------------ | ------------------------ | -------------------- |
| `regular`    | Standard experiments     | Yes (default filter) |
| `trial`      | Optimization experiments | No                   |
| `mini-batch` | Optimization experiments | No                   |

### Where Type Filtering Happens

| Hook                               | Default Types            | Used By                                         |
| ---------------------------------- | ------------------------ | ----------------------------------------------- |
| `useExperimentsList`               | `[REGULAR]`              | HomePage, PromptPage, Dashboard widgets         |
| `useExperimentsGroups`             | `[REGULAR]`              | Grouped experiments queries                     |
| `useExperimentsGroupsAggregations` | `[REGULAR]`              | Group aggregation queries                       |
| Main experiments table page        | Needs to use `ALL types` | Shows all experiment types including eval suite |

---

## 2. What Changes With Eval Suite Experiments

### New Data Fields

Experiments created by the new evaluation engine will have a **different data shape**:

| Field               | Regular Experiment      | Eval Suite Experiment    |
| ------------------- | ----------------------- | ------------------------ |
| `feedback_scores`   | Array of numeric scores | **Empty/null**           |
| `experiment_scores` | Array of numeric scores | **Empty/null**           |
| `comments`          | User comments           | **Empty/null**           |
| `pass_rate`         | N/A                     | **NEW** — `0.0` to `1.0` |
| `passed_count`      | N/A                     | **NEW** — integer        |
| `total_count`       | N/A                     | **NEW** — integer        |

ExperimentItem differences:

| Field               | Regular            | Eval Suite                                          |
| ------------------- | ------------------ | --------------------------------------------------- |
| `feedback_scores`   | Per-trace scores   | **Empty/null**                                      |
| `comments`          | Per-trace comments | **Empty/null**                                      |
| `status`            | N/A                | **NEW** — `passed` / `failed` / `skipped`           |
| `assertion_results` | N/A                | **NEW** — `[{ value, passed, pass_score, reason }]` |

### Pass Rate Calculation (from Python SDK — `suite_result_constructor.py`)

```
Run level:
  A run PASSES if:
    - it has NO score_results, OR
    - ALL its score_results have value == True or value == 1

Item level:
  runs_passed   = count of passing runs for this item
  pass_threshold = item.execution_policy.pass_threshold (default: 1, per-item)
  item PASSES if: runs_passed >= pass_threshold

Suite level:
  items_passed = count of items where passed == True
  items_total  = count of unique dataset items evaluated
  pass_rate    = items_passed / items_total
  (edge case: if items_total == 0, pass_rate = 1.0)
```

**Key design points:**

- `pass_threshold` is **per-item** (from each item's `execution_policy`), not suite-level
- Score values: `True` or `1` = pass, `False` or any other value = fail, empty scores = pass
- `runs_per_item` controls how many times an item is evaluated (max 100)
- Constraint: `pass_threshold <= runs_per_item`

---

## 3. Type Guard Mechanism

> **BE will decide** the best way to mark eval suite experiments. Options include: a new `type` value, a boolean field, or a metadata marker. FE will use a **type guard function** to branch UI logic.

### What FE Needs to Build

```typescript
// New function in src/lib/experiments.ts
// Implementation depends on BE decision:
export function isEvalSuiteExperiment(experiment: Experiment): boolean {
  // Option A: new type value
  return experiment.type === "evaluation_suite";
  // Option B: boolean field
  return experiment.is_eval_suite === true;
  // Option C: check for pass_rate presence
  return experiment.pass_rate != null;
}
```

### Where the Guard Needs to Be Added

| File                            | What It Should Control                                                                                                                                                                                                                                          |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CompareExperimentsPage.tsx`    | Set `isEvalSuite` flag for entire page — branches tabs, header, **sidebar variant** (eval suite sidebar with YAML context + assertions table vs regular sidebar with JSON + feedback scores)                                                                    |
| `ExperimentItemsTab.tsx`        | Conditional default columns, "Passed" column (pinned right), **which sidebar component opens on row click** — `EvaluationSuiteExperimentPanel` (new, with multi-run tabs + assertions) vs `CompareExperimentsPanel` (existing, with feedback scores + comments) |
| `CompareExperimentsDetails.tsx` | Show pass rate instead of feedback scores in header, hide/replace charts                                                                                                                                                                                        |
| Experiments table page          | Pass rate column, charts, conditional feedback scores visibility                                                                                                                                                                                                |

---

## 4. Affected Pages — Detailed Breakdown

### 4.1 Experiments Table (main list)

**File:** `ExperimentsPage/ExperimentsPage.tsx`
**Impact:** HIGH — must support both regular and eval suite experiments in a unified table

#### PRD Required Default Columns (unified table)

> Evaluation suite, Created, Duration (avg.), Cost per trace (avg.), **Pass rate**, Feedback Scores _(only if legacy experiments exist)_

#### Default Columns: Changes from current (needs product decision D9)

| Column                    | Action                                                            |
| ------------------------- | ----------------------------------------------------------------- |
| Name                      | Keep                                                              |
| Evaluation suite          | Keep (rename from "Dataset")                                      |
| **Project**               | **Product decision: remove from defaults?** (PRD doesn't list it) |
| Created                   | Keep                                                              |
| Duration (avg.)           | Keep                                                              |
| **Trace count**           | **Product decision: remove from defaults?** (PRD doesn't list it) |
| **Cost per trace (avg.)** | **Add to defaults** (PRD requires it)                             |
| **Pass rate**             | **Build new column + add to defaults**                            |
| Feedback Scores           | Keep, add conditional visibility                                  |
| **Comments**              | **Product decision: remove from defaults?** (PRD doesn't list it) |

#### What Needs to Be Built

| Item                                | Details                                                                                                                            |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `pass_rate` column                  | New column with cell component, `aggregatedCell` for grouped rows, `aggregationKey`, default visibility, sortable                  |
| `cost per trace` in default columns | PRD requires it as default — currently not in defaults                                                                             |
| `pass_rate` aggregation for groups  | `aggregatedCell` + BE must return `pass_rate` in aggregations                                                                      |
| `pass_rate` sorting                 | `sortable: true` + BE must include in `sortable_by`                                                                                |
| Charts with pass_rate               | Charts currently only plot `feedback_scores` + `experiment_scores`. PRD says: "single line of aggregated score based on pass rate" |
| Conditional feedback scores         | PRD says: "Feedback Scores only shown if legacy experiments exist in workspace"                                                    |
| Type filter update                  | Currently defaults to `[REGULAR]` only — need to include new eval suite experiment type                                            |

---

### 4.2 Compare Experiments Page (experiment results)

**File:** `CompareExperimentsPage/CompareExperimentsPage.tsx`
**Impact:** HIGH — single experiment detail view + multi-experiment compare

#### What Needs to Be Built

**Tabs — conditional for eval suite experiments:**

| Tab              | Regular (no change) | Eval Suite (new behavior)                      |
| ---------------- | ------------------- | ---------------------------------------------- |
| Experiment items | Shows items table   | Shows items table (different columns)          |
| Configuration    | Shows config        | Shows config (same)                            |
| Feedback scores  | Shows score charts  | **Hide tab** (empty — no feedback_scores data) |

**Header — `CompareExperimentsDetails.tsx`:**

| Element              | Regular (no change) | Eval Suite (new behavior)            |
| -------------------- | ------------------- | ------------------------------------ |
| Experiment name      | Shown               | Shown                                |
| Feedback scores list | Score pills         | **Hidden** — replaced by pass rate   |
| Pass rate            | N/A                 | **NEW** — "Pass rate: 76.2% (16/21)" |
| Charts (radar/bar)   | Score comparison    | **Hidden or replaced**               |

**Items Table — `ExperimentItemsTab.tsx`:**

PRD required defaults for eval suite experiments (replacing the current ID, Comments, User Feedback):

| Column         | Cell                                                                           | Data Source                                     |
| -------------- | ------------------------------------------------------------------------------ | ----------------------------------------------- |
| Description    | Text                                                                           | `datasetItem.description`                       |
| Data           | JSON preview (clickable — PRD: "opens a dialog with read-only formatted JSON") | `datasetItem.data`                              |
| Duration       | Duration                                                                       | `experimentItem.duration`                       |
| Estimated cost | Cost                                                                           | `experimentItem.total_estimated_cost`           |
| **Passed**     | Yes/No + tooltip (pinned right)                                                | `experimentItem.status` + `assertion_results[]` |

**Passed column tooltip (PRD spec):**

Single run:

```
| Assertion                                           | Passed |
|-----------------------------------------------------|--------|
| Should share a link to the docs page about self...  | Yes    |
| Should offer speaking with our sales team           | No     |
```

Multi-run:

```
| Assertion                                           | Passed? (1) | Passed? (2) | Passed? (3) |
|-----------------------------------------------------|-------------|-------------|-------------|
| Should share a link to the docs page about self...  | Yes         | Yes         | Yes         |
| Should offer speaking with our sales team           | Yes         | No          | No          |
```

Cell value format: `"Yes (2/3)"` / `"No (1/3)"`

**Sidebar — new eval suite variant (new components needed):**

For eval suite experiments, a new sidebar replaces the existing `CompareExperimentsPanel`:

| Element          | Regular (no change)                 | Eval Suite (must build)                   |
| ---------------- | ----------------------------------- | ----------------------------------------- |
| Component        | `CompareExperimentsPanel` (exists)  | **New:** `EvaluationSuiteExperimentPanel` |
| Left pane title  | "Dataset item"                      | "Evaluation suite item context"           |
| Left pane format | JSON                                | YAML (default)                            |
| Right pane       | Output + Feedback scores + Comments | Output + Assertions table                 |
| Multi-run        | N/A                                 | **New:** Tabs: "Run 1", "Run 2", ...      |

**Sidebar assertions table (PRD spec) — use "Assertion" not "Evaluator":**

| Assertion                        | Passed | Reason                        |
| -------------------------------- | ------ | ----------------------------- |
| Should share a link to docs...   | Yes    | The agent shared a link to... |
| Should offer speaking with sales | No     | The agent did not offer...    |

**Multi-run aggregate status — design consideration:**

For multi-run items, the aggregate status needs careful handling. The PRD says: "if sum(passed_runs) >= pass_threshold → passed". Either:

- BE should provide an overall `status` at the dataset-item level (not per-run), or
- FE should compute it from individual run statuses + the suite's `pass_threshold`

**Sidebar `description` field:** The PRD shows `description` as a column in the items table. If description should also be visible when clicking an item in the sidebar, the sidebar component needs to receive it.

---

### 4.3 Home Page — Evaluation Section

**File:** `HomePage/EvaluationSection.tsx`
**Impact:** MEDIUM

| Current                | Detail                                                       |
| ---------------------- | ------------------------------------------------------------ |
| Hook                   | `useExperimentsList` — defaults to `[REGULAR]` only          |
| Columns                | Name, Evaluation suite, Item count, Feedback scores, Created |
| Limit                  | 5 most recent experiments                                    |
| Eval suite experiments | **NOT SHOWN** (filtered out by default type)                 |

#### Options for Product Team

| Option                                      | Pros                                                         | Cons                                                                                              |
| ------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------- |
| **A) Keep REGULAR only**                    | No changes needed. Clean separation.                         | Users won't see eval suite experiments on homepage.                                               |
| **B) Show all types**                       | Users see latest activity regardless of type.                | Feedback scores column empty for eval suite rows. Need pass_rate column. Mixed table may confuse. |
| **C) Show all types + conditional columns** | Best UX — show pass_rate for eval suite, scores for regular. | More FE work. Need to handle mixed display.                                                       |
| **D) Two sections**                         | Clear separation with dedicated sections.                    | Major layout change. May not fit homepage design.                                                 |

**Recommendation:** Start with **Option A** (no change) for initial release, revisit based on user feedback.

---

### 4.4 Prompt Page — Experiments Tab

**File:** `PromptPage/ExperimentsTab/ExperimentsTab.tsx`
**Impact:** MEDIUM

| Current                | Detail                                                                                |
| ---------------------- | ------------------------------------------------------------------------------------- |
| Hook                   | `useGroupedExperimentsList` — defaults to `[REGULAR]`                                 |
| Columns                | 17 available, 5 default (Name, Prompt commit, Evaluation suite, Created, Trace count) |
| Feedback scores        | Available as dynamic columns                                                          |
| Eval suite experiments | **NOT SHOWN**                                                                         |

#### Options for Product Team

| Option                         | Pros                                                                    | Cons                                                                      |
| ------------------------------ | ----------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| **A) Keep REGULAR only**       | Prompts are typically used with regular experiments. No changes needed. | If prompts can be used with eval suite experiments, users won't see them. |
| **B) Include eval suite type** | Complete view of all experiments using a prompt.                        | Need pass_rate column. Feedback scores empty for eval suite rows.         |

**Question for Product:** Can evaluation suite experiments be associated with prompts? If the new evaluation engine supports prompt versions, we need Option B.

---

### 4.5 Playground Page

**File:** `PlaygroundPage/` (multiple files)
**Impact:** LOW (per user clarification)

> **User confirmed:** "We are going to create a new workflow to properly work with eval suites, but for now it should work with eval suite dataset types as before, create the regular experiments."

**Current behavior:** Playground can select any dataset (including evaluation suites) and creates REGULAR experiments. No special handling for evaluation suites.

**No changes needed for this release.** Playground continues to work as-is.

---

### 4.6 Dashboard Widgets

**Files:**

- `ExperimentsFeedbackScoresWidget/` — feedback score charts
- `ExperimentsLeaderboardWidget/` — experiment ranking table

**Impact:** MEDIUM

| Widget                 | Current                                    | Issue                                                                                          |
| ---------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------- |
| Feedback Scores Widget | Charts feedback_scores + experiment_scores | Only fetches `[REGULAR]`. Eval suite experiments excluded. If included, charts would be empty. |
| Leaderboard Widget     | Ranks experiments by score columns         | Only fetches `[REGULAR]`. No pass_rate column available.                                       |

#### Options for Product Team

| Option                                     | Pros                                                             | Cons                                                         |
| ------------------------------------------ | ---------------------------------------------------------------- | ------------------------------------------------------------ |
| **A) Keep REGULAR only**                   | Widgets continue working unchanged.                              | Users can't see eval suite experiment trends in dashboards.  |
| **B) Include eval suite type + pass_rate** | Full visibility. Pass_rate as chart line and leaderboard column. | Significant FE work. Charts need to handle both score types. |
| **C) Separate widgets for eval suites**    | Clean separation. Dedicated "Pass Rate Widget".                  | More widgets to maintain. Configuration complexity.          |

**Recommendation:** Start with **Option A** for initial release. Add dedicated eval suite widgets in Phase 2.

---

### 4.7 Add Experiment Dialog

**File:** `AddExperimentDialog.tsx`
**Impact:** MEDIUM

#### What Needs to Be Built

- Detect evaluation suite dataset (via `DATASET_TYPE` enum — needs to be added to types)
- When eval suite: hide evaluator selection section, generate `suite.run(...)` SDK code
- When regular: no change — show evaluator selection, generate standard `evaluate()` code

The branching uses **dataset type** (not experiment type), which is correct since the experiment doesn't exist yet at dialog time.

---

### Pages NOT Affected (optimizations — TRIAL/MINI_BATCH)

These pages use optimization experiment types and are **completely separate** from our feature:

- `OptimizationsPage/` — TRIAL type
- `CompareOptimizationsPage/` — `[TRIAL, MINI_BATCH]`
- `CompareTrialsPage/` — fetches by IDs
- `OptimizationRunsSection` — optimizations API

**No changes needed.**

---

## 5. UI Before & After

### 5.1 Experiments Table — Current

```
┌──────────────────────────────────────────────────────────────────┐
│ Name    │ Eval Suite │ Created │ Duration │ Traces │ Scores │ ... │
├──────────────────────────────────────────────────────────────────┤
│ exp-1   │ my-suite   │ Feb 22  │ 1.2s     │ 21     │ ●0.8  │     │
│ exp-2   │ my-suite   │ Feb 21  │ 0.9s     │ 15     │ ●0.7  │     │
└──────────────────────────────────────────────────────────────────┘
```

### 5.2 Experiments Table — After (with new Pass Rate column)

```
┌────────────────────────────────────────────────────────────────────────────┐
│ Name    │ Eval Suite │ Created │ Duration │ Traces │ Pass Rate  │ Scores  │
├────────────────────────────────────────────────────────────────────────────┤
│ exp-1   │ my-suite   │ Feb 22  │ 1.2s     │ 21     │ 76.2% (16/21) │ —   │  ← eval suite
│ exp-2   │ my-suite   │ Feb 21  │ 0.9s     │ 15     │ —         │ ●0.8    │  ← regular
│ exp-3   │ dataset-a  │ Feb 20  │ 2.1s     │ 50     │ —         │ ●0.9    │  ← regular
└────────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Experiment Items Table — Current (regular experiment)

```
┌────────────────────────────────────────────────────────┐
│ ID (Eval suite item) │ Comments │ User Feedback │ ...  │
├────────────────────────────────────────────────────────┤
│ abc-123              │ 2        │ ●0.8          │      │
│ def-456              │ 0        │ ●0.6          │      │
└────────────────────────────────────────────────────────┘
```

### 5.4 Experiment Items Table — After (eval suite experiment)

```
┌──────────────────────────────────────────────────────────────────────┐
│ Description           │ Data        │ Duration │ Cost  │ Passed    ▐│
├──────────────────────────────────────────────────────────────────────┤
│ Self-hosting question  │ { "input":… │ 1.2s     │ $0.01 │ Yes       ▐│
│ Sales inquiry          │ { "input":… │ 0.8s     │ $0.01 │ No (1/3)  ▐│
│ Docs question          │ { "input":… │ 1.5s     │ $0.02 │ Yes (3/3) ▐│
└──────────────────────────────────────────────────────────────────────┘
                                                          ▲ pinned right
```

### 5.5 Experiment Header — Current (regular)

```
┌─────────────────────────────────────────────────────────────────┐
│ my-experiment-2026-02-22                                         │
│ Created: Feb 22 │ Suite: my-suite │ Prompt: v3 │ Traces ↗       │
│ ●accuracy: 0.82  ●relevance: 0.91  ●hallucination: 0.12        │
└─────────────────────────────────────────────────────────────────┘
```

### 5.6 Experiment Header — After (eval suite)

```
┌─────────────────────────────────────────────────────────────────┐
│ my-experiment-2026-02-22                                         │
│ Created: Feb 22 │ Suite: my-suite │ Prompt: v3 │ Traces ↗       │
│ Pass rate: 76.2% (16/21)                                         │
└─────────────────────────────────────────────────────────────────┘
```

### 5.7 Sidebar — Regular experiment (no change)

```
┌──────────────────────┬─────────────────────────────┐
│ Evaluation suite     │ Output                       │
│ item                 │                               │
│ ┌──────────────┐     │ { "response": "..." }        │
│ │ { "input":.. │     │                               │
│ │   "context": │     │ Feedback scores              │
│ │ }            │     │ ●accuracy: 0.82               │
│ └──────────────┘     │ ●relevance: 0.91              │
│                      │                               │
│                      │ Comments                      │
│                      │ "Good response" - John        │
└──────────────────────┴─────────────────────────────┘
```

### 5.8 Sidebar — After (eval suite)

```
┌──────────────────────┬──────────────────────────────┐
│ Evaluation suite     │ Experiment results  [PASSED]  │
│ item context         │                               │
│ ┌──────────────┐     │ [Run 1] [Run 2] [Run 3]      │
│ │ input:       │     │                               │
│ │   question:  │     │ Output                        │
│ │   "How do I" │     │ { "response": "..." }         │
│ │ context:     │     │                               │
│ │   docs: ...  │     │ Assertions (2)                │
│ └──────────────┘     │ ┌────────────┬────┬─────────┐ │
│       YAML view      │ │ Assertion  │Pass│ Reason  │ │
│                      │ ├────────────┼────┼─────────┤ │
│                      │ │ Should link│ Yes│ Agent...│ │
│                      │ │ Should off │ No │ Did not │ │
│                      │ └────────────┴────┴─────────┘ │
└──────────────────────┴──────────────────────────────┘
```

### 5.9 Charts — Before

```
Score ▲
  1.0 │    ●──●
  0.8 │ ●──     ──●       accuracy (avg)
  0.6 │               ●   relevance (avg)
  0.4 │
      └──────────────────▶ Experiments
```

### 5.10 Charts — After (eval suite group)

```
Pass  ▲
Rate  │
  1.0 │    ●──●
  0.8 │ ●──     ──●       pass rate
  0.6 │
  0.4 │
      └──────────────────▶ Experiments
```

---

## 6. Product & Design Decisions

| #      | Question                                                                          | Context                                                                                                                                                      | Options                                                                                                                    | Recommendation                                |
| ------ | --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| **D1** | What to do with "Feedback scores" tab on compare page for eval suite experiments? | Tab is completely empty — no feedback_scores data exists                                                                                                     | a) Hide tab b) Replace with "Assertions overview" tab c) Keep empty                                                        | **Hide tab** — avoid confusing empty state    |
| **D2** | How to conditionally show Feedback Scores column in experiments table?            | PRD: "only shown if legacy experiments exist in workspace"                                                                                                   | a) Feature flag b) Check if any experiment in current view has scores c) Always show both columns                          | **Option B** — check data, no flag dependency |
| **D3** | Should HomePage show eval suite experiments?                                      | Currently only shows REGULAR. See [Section 4.3](#43-home-page--evaluation-section) for detailed options.                                                     | a) Keep REGULAR only b) Show all types c) Show all + conditional columns d) Two sections                                   | **Option A** for initial release              |
| **D4** | Can eval suite experiments be associated with prompts?                            | PromptPage only shows REGULAR experiments. See [Section 4.4](#44-prompt-page--experiments-tab).                                                              | a) Yes — include new type b) No — keep REGULAR only                                                                        | **Needs product answer**                      |
| **D5** | Should dashboard widgets include eval suite experiments?                          | Currently REGULAR only. See [Section 4.6](#46-dashboard-widgets) for detailed options.                                                                       | a) Keep REGULAR only b) Include + pass_rate c) Separate widgets                                                            | **Option A** for initial release              |
| **D6** | Can users compare MULTIPLE eval suite experiments?                                | `isEvalSuite` only triggers when `experiments.length === 1`                                                                                                  | a) Yes — rework multi-compare for eval suites b) No — single only                                                          | **Needs product answer**                      |
| **D7** | What to show in compare page charts for eval suite experiments?                   | Radar/bar charts are empty without feedback_scores                                                                                                           | a) Pass rate as single data point b) Nothing c) Assertion-level breakdown                                                  | **Option A** if D6=yes                        |
| **D8** | Should the "Comments" column/section be available for eval suite experiments?     | PRD says remove Comments from sidebar. Table column would be empty.                                                                                          | a) Hide completely b) Keep available but not default                                                                       | **Hide completely** for eval suite context    |
| **D9** | Should experiments table default columns change per PRD?                          | PRD drops Project, Trace count, Comments from defaults; adds Cost per trace, Pass rate. See [Section 4.1](#41-experiments-table-main-list) comparison table. | a) Update defaults to match PRD exactly b) Keep current defaults + add pass_rate only c) Add pass_rate + cost, keep others | **Needs product answer** — impacts all users  |

---

## 7. BE Required Work

### 7.1 New API Fields (FE contract)

| #      | What FE Needs                                              | Endpoint                                          | Details                                                                 | Priority                    |
| ------ | ---------------------------------------------------------- | ------------------------------------------------- | ----------------------------------------------------------------------- | --------------------------- |
| **B1** | Mechanism to identify eval suite experiments               | All experiment endpoints                          | New type value, boolean field, or metadata marker — BE decides approach | **P0** — blocks all FE work |
| **B2** | `pass_rate`, `passed_count`, `total_count` on `Experiment` | `GET /v1/private/experiments`                     | Returned in list response for eval suite experiments                    | **P0**                      |
| **B3** | Same fields on `ExperimentsAggregations`                   | `GET /v1/private/experiments/groups/aggregations` | For grouped table row headers                                           | **P1**                      |
| **B4** | `"pass_rate"` in `sortable_by` array                       | `GET /v1/private/experiments`                     | Enable pass_rate column header sort                                     | **P1**                      |
| **B5** | `status` field on `ExperimentItem`                         | `GET /v1/private/experiments/items/compare`       | `passed` / `failed` / `skipped` per item                                | **P0**                      |
| **B6** | `assertion_results[]` on `ExperimentItem`                  | Same endpoint                                     | `[{ value, passed, pass_score, reason }]`                               | **P0**                      |
| **B7** | `pass_rate` on single experiment detail                    | `GET /v1/private/experiments/{id}`                | For compare page header                                                 | **P1**                      |
| **B8** | `description` field accessible per experiment item         | Compare endpoint                                  | From the dataset item's description field                               | **P1**                      |

### 7.2 ClickHouse Schema & DAO Changes

| #       | Area                              | File(s)                                         | Details                                                                                                                                                                                                                                                                                                             | Priority           |
| ------- | --------------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| **B9**  | ClickHouse ENUM migration         | `ExperimentType.java`, migration SQL            | `ExperimentType` is stored as ClickHouse ENUM (defined in migration `000021`). Adding a new type value (e.g., `EVALUATION_SUITE`) requires a new DB migration to alter the ENUM.                                                                                                                                    | **P0** — blocks B1 |
| **B10** | Feedback scores aggregation CTEs  | `ExperimentDAO.java` (FIND query, ~550 lines)   | The FIND query uses a 3-stage CTE pipeline (`feedback_scores_combined_raw` → `feedback_scores_final` → `feedback_scores_agg`) that joins traces to aggregate numeric scores. For eval suite experiments these CTEs will return empty results. Verify this is handled gracefully (returns `null`/empty, not errors). | **P1**             |
| **B11** | Group aggregation with `avgMap()` | `ExperimentDAO.java` (FIND_GROUPS_AGGREGATIONS) | Groups aggregation uses `avgMap(feedback_scores)` and `avgMap(experiment_scores)` — both assume numeric values. `pass_rate` needs different aggregation logic (e.g., `avg(pass_rate)` or `sum(passed_count)/sum(total_count)`).                                                                                     | **P1** — blocks B3 |
| **B12** | Sorting factory for `pass_rate`   | `ExperimentSortingFactory.java`                 | Currently has 13 sortable fields. `pass_rate` must be added as a new sortable field with appropriate SQL expression.                                                                                                                                                                                                | **P1** — blocks B4 |
| **B13** | Filter field for `pass_rate`      | `ExperimentField.java`                          | Currently 7 filterable fields. `FEEDBACK_SCORES` and `EXPERIMENT_SCORES` use `FieldType.FEEDBACK_SCORES_NUMBER` (numeric comparison). Need `pass_rate` as a filterable field if FE needs to filter by pass rate.                                                                                                    | **P2**             |

### 7.3 Endpoint Behavior for Eval Suite Experiments

| #       | Area                                               | Endpoint                                            | Details                                                                                                                                                                                         | Priority               |
| ------- | -------------------------------------------------- | --------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| **B14** | Empty feedback_scores/comments for eval suite type | `GET /v1/private/experiments` (list)                | Eval suite experiments should return `null`/empty for `feedback_scores`, `experiment_scores`, and `comments`. Verify the CTE pipeline handles this gracefully without errors.                   | **P1**                 |
| **B15** | Feedback score names endpoint                      | `GET /v1/private/experiments/feedback-scores/names` | FE uses this to build dynamic score columns. For eval suite experiments this should return empty. Verify it doesn't error when no feedback scores exist for given experiments.                  | **P2**                 |
| **B16** | Compare endpoint for eval suite items              | `GET /v1/private/experiments/items/compare`         | Currently returns `feedback_scores` per item from traces. For eval suite items, needs to return `status`, `assertion_results[]` instead. Either extend the response or create a parallel field. | **P0** — blocks B5, B6 |
| **B17** | Null-safe behavior in mixed-type groups            | Groups/aggregations endpoints                       | When a group contains both regular and eval suite experiments (possible during migration), aggregations should handle mixed `null` feedback_scores and `null` pass_rate gracefully.             | **P2**                 |

---

## 8. FE Required Work

> All tasks are new work required for the evaluation suites feature.

### Phase 1: Foundation (blocked on B1, B2, B5, B6)

| #       | Task                                                                                               | File(s)                             | Size | Blocked By |
| ------- | -------------------------------------------------------------------------------------------------- | ----------------------------------- | ---- | ---------- |
| **F1**  | Create type guard function for eval suite experiments                                              | `lib/experiments.ts` (new function) | S    | B1         |
| **F2**  | Add `pass_rate`, `passed_count`, `total_count` to `Experiment` type                                | `types/datasets.ts`                 | S    | B2         |
| **F3**  | Add `status`, `assertion_results` to `ExperimentItem` type                                         | `types/datasets.ts`                 | S    | B5, B6     |
| **F4**  | Add `pass_rate`, `passed_count`, `total_count` to `ExperimentsAggregations` type                   | `types/datasets.ts`                 | S    | B3         |
| **F5**  | Add type guard to `CompareExperimentsPage` — branch tabs, header, sidebar based on experiment type | `CompareExperimentsPage.tsx`        | M    | F1         |
| **F5a** | Create `DATASET_TYPE` enum and add `type` field to `Dataset` interface                             | `types/datasets.ts`                 | S    | —          |

### Phase 2: Experiments Table

| #        | Task                                                                           | File(s)                                | Size | Blocked By |
| -------- | ------------------------------------------------------------------------------ | -------------------------------------- | ---- | ---------- |
| **F6**   | Build `PassRateCell` component and add `pass_rate` column to experiments table | New component + experiments table file | M    | F2         |
| **F7**   | Build `aggregatedCell` for pass_rate in grouped experiment rows                | Experiments table file                 | M    | F4         |
| **F8**   | Make pass_rate column sortable                                                 | Experiments table file                 | S    | B4         |
| **F9**   | Add `pass_rate` to default visible columns                                     | Experiments table file                 | S    | —          |
| **F10**  | Add pass_rate as chart line for eval suite experiment groups                   | Experiments table chart section        | M    | F2         |
| **F10a** | Add eval suite experiment type to type filter (currently only `[REGULAR]`)     | API hooks + experiments table          | S    | B1         |

### Phase 3: Compare Page

| #       | Task                                                                                                                                                         | File(s)                                                    | Size | Blocked By |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------- | ---- | ---------- |
| **F11** | Add conditional default columns for eval suite: `[description, data, duration, cost, passed]`                                                                | `ExperimentItemsTab.tsx`                                   | M    | F1         |
| **F12** | Build `description` column definition for eval suite experiment items                                                                                        | `ExperimentItemsTab.tsx`                                   | S    | B8         |
| **F13** | Build `DataObjectCell` component — JSON preview cell with click/hover dialog for read-only formatted JSON view                                               | New file + `ExperimentItemsTab.tsx`                        | M    | —          |
| **F14** | Build pass rate display in experiment header (show "Pass rate: 76.2% (16/21)" for eval suite experiments)                                                    | `CompareExperimentsDetails.tsx`                            | M    | F2, B7     |
| **F15** | Build `PassedCell` column — pinned right, shows Yes/No + assertion tooltip                                                                                   | New file + `ExperimentItemsTab.tsx`                        | M    | F3         |
| **F16** | Build eval suite sidebar: `EvaluationSuiteExperimentPanel` with YAML context pane + assertions table ("Assertion" headers, not "Evaluator") + multi-run tabs | New files (panel, content, results table, tooltip, badges) | L    | F3         |
| **F17** | Conditionally hide "Feedback scores" tab for eval suite experiments (pending D1)                                                                             | `CompareExperimentsPage.tsx`                               | S    | F5, D1     |

### Phase 4: Polish & Other Pages

| #       | Task                                                                                                                   | File(s)                                   | Size | Blocked By |
| ------- | ---------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- | ---- | ---------- |
| **F18** | Conditional visibility of Feedback Scores column in experiments table (pending D2)                                     | Experiments table file                    | M    | D2         |
| **F19** | Add pass_rate to dashboard leaderboard widget (pending D5)                                                             | `ExperimentsLeaderboardWidget/helpers.ts` | S    | D5         |
| **F20** | Add "Evaluation suite" column section in ExperimentItemsTab column picker                                              | `ExperimentItemsTab.tsx`                  | S    | F11        |
| **F21** | Update "Dataset" label to "Evaluation suite" in OptimizationsNewConfigSidebar                                          | `OptimizationsNewConfigSidebar.tsx`       | S    | —          |
| **F22** | Add eval suite awareness to AddExperimentDialog — hide evaluators, generate `suite.run()` code for eval suite datasets | `AddExperimentDialog.tsx`                 | M    | F5a        |

---

## 9. Affected User Flows

### Flow 1: View experiments list

```
User opens Experiments page
  → Experiments table loads with all experiment types (including new eval suite type)
  → Table shows both regular and eval suite experiments
  → Regular rows: feedback_scores columns filled, pass_rate column empty
  → Eval suite rows: pass_rate column filled, feedback_scores columns empty
  → Grouped by evaluation suite: aggregation row shows pass_rate OR feedback_scores
  → Charts: regular groups show score lines, eval suite groups show pass_rate line
```

**Risk:** Mixed empty columns may confuse users. Mitigate with D2 decision.

### Flow 2: Click eval suite experiment to view results

```
User clicks eval suite experiment row
  → Navigates to CompareExperimentsPage
  → Type guard detects eval suite experiment
  → Header shows: Pass rate: 76.2% (16/21) (instead of feedback scores)
  → Items tab defaults to: [description, data, duration, cost, passed]
  → "Feedback scores" tab: hidden (per D1)
  → Click item row → EvaluationSuiteExperimentPanel opens
    → Left: YAML context, Right: Output + Assertions table
    → Multi-run: Run tabs with per-run output and assertions
```

### Flow 3: Click regular experiment to view results

```
User clicks regular experiment row
  → Same CompareExperimentsPage
  → Type guard detects regular experiment
  → Header shows: feedback score pills (accuracy: 0.82, relevance: 0.91)
  → Items tab defaults to: [ID, Comments, User Feedback]
  → "Feedback scores" tab: visible with score charts
  → Click item row → CompareExperimentsPanel opens
    → Input/Output + Feedback scores + Comments
```

**No regression** — regular experiment flow is unchanged.

### Flow 4: Group experiments by evaluation suite

```
User selects "Group by: Evaluation suite"
  → Groups show aggregated values per suite
  → Eval suite groups: pass_rate aggregation shown
  → Regular groups: feedback_scores aggregation shown
  → Expand group → charts appear
    → Eval suite groups: pass_rate trend line
    → Regular groups: score trend lines
```

### Flow 5: Create experiment from "Run an experiment" dialog

```
User opens AddExperimentDialog from evaluation suite page
  → Dialog detects dataset type = EVALUATION_SUITE
  → Evaluator selection section: HIDDEN (evaluators defined on suite)
  → Generated code uses: suite.run(task=..., experiment_name=...)
  → No metrics parameter in code

User opens dialog from regular dataset page
  → Evaluator selection: VISIBLE
  → Generated code uses: evaluate(dataset=..., scoring_metrics=...)
```

**Note:** This flow requires F5a (DATASET_TYPE enum) and F22 (dialog changes) to be implemented.

### Flow 6: Open experiment from HomePage

```
Current: HomePage shows only REGULAR experiments (default type filter)
After: No change (per D3 recommendation)
Eval suite experiments not visible on homepage until D3 is revisited
```

---

## 10. QA Regression Checklist

### Critical Path — Eval Suite Experiments

| #      | Test                                                                         | Expected Result                                                               | Depends On     |
| ------ | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------- | -------------- |
| **Q1** | Create eval suite experiment via SDK, verify it appears in experiments table | Row shows with pass_rate column value, empty feedback scores                  | B1, B2, F1, F6 |
| **Q2** | Click eval suite experiment → verify correct default columns                 | Description, Data, Duration, Cost, Passed columns visible                     | F11, F12, F13  |
| **Q3** | Click "Passed" cell → verify tooltip shows assertions table                  | Single run: Assertion/Passed columns. Multi-run: per-run columns              | B6, F3         |
| **Q4** | Click item row → verify eval suite sidebar opens                             | Left: YAML context. Right: Output + "Assertions (N)" table with Reason column | F5, F16        |
| **Q5** | Verify sidebar multi-run tabs                                                | "Run 1", "Run 2" etc. with per-run output and assertions                      | B5, B6         |
| **Q6** | Verify pass rate in experiment header                                        | Shows "Pass rate: 76.2% (16/21)" (not placeholder "—")                        | F14, B7        |
| **Q7** | Group eval suite experiments → verify aggregated pass_rate                   | Group header shows aggregated pass rate value                                 | F7, B3         |
| **Q8** | Sort by pass_rate column                                                     | Experiments sort by pass rate ascending/descending                            | F8, B4         |
| **Q9** | Charts for eval suite experiment group                                       | Shows single pass_rate trend line                                             | F10            |

### Regression — Regular Experiments (must NOT break)

| #       | Test                                                     | Expected Result                                        |
| ------- | -------------------------------------------------------- | ------------------------------------------------------ |
| **R1**  | Regular experiment appears in table with feedback scores | Score pills visible, pass_rate column shows "—"        |
| **R2**  | Click regular experiment → correct default columns       | ID, Comments, User Feedback columns visible            |
| **R3**  | Click regular experiment → "Feedback scores" tab works   | Bar/radar charts with score comparison                 |
| **R4**  | Click item row → regular sidebar opens                   | Input/Output + Feedback scores list + Comments         |
| **R5**  | Group regular experiments → aggregated feedback scores   | Group header shows aggregated score values             |
| **R6**  | Charts for regular experiment group                      | Score trend lines (one per score name)                 |
| **R7**  | HomePage experiments section unchanged                   | Shows only REGULAR experiments with feedback scores    |
| **R8**  | Prompt page experiments tab unchanged                    | Shows only REGULAR experiments grouped by prompt       |
| **R9**  | Dashboard widgets unchanged                              | Feedback score charts and leaderboard work as before   |
| **R10** | Optimizations pages unchanged                            | TRIAL/MINI_BATCH experiments unaffected                |
| **R11** | AddExperimentDialog for regular dataset                  | Shows evaluator selection, generates `evaluate()` code |

### Regression — Mixed Scenarios

| #      | Test                                                  | Expected Result                                               |
| ------ | ----------------------------------------------------- | ------------------------------------------------------------- |
| **M1** | Table with both regular and eval suite experiments    | Both types visible, each with their respective columns filled |
| **M2** | Group by evaluation suite with mixed experiment types | Each group shows correct aggregation (scores vs pass_rate)    |
| **M3** | Search filters work with both types                   | Name search returns both regular and eval suite results       |
| **M4** | Column visibility persists across page reload         | LocalStorage retains column selections including pass_rate    |
| **M5** | Pagination works with mixed types                     | Correct total count, pages include both types                 |

---

## Appendix: Files Reference

> All paths relative to `apps/opik-frontend/`.

### Must Modify

| File                                                                                                  | Changes                                                                                         |
| ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `src/lib/experiments.ts`                                                                              | Add type guard function                                                                         |
| `src/types/datasets.ts`                                                                               | Add `DATASET_TYPE` enum, extend `Experiment`, `ExperimentItem`, `ExperimentsAggregations` types |
| `src/components/pages/CompareExperimentsPage/CompareExperimentsPage.tsx`                              | Add type guard, conditional tabs, eval suite sidebar routing                                    |
| `src/components/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab.tsx`               | Add conditional default columns, description/data/passed columns, column sections               |
| `src/components/pages/CompareExperimentsPage/CompareExperimentsDetails/CompareExperimentsDetails.tsx` | Add pass rate display for eval suite experiments                                                |
| `src/components/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog.tsx`                 | Add eval suite detection, conditional evaluator section, `suite.run()` code gen                 |
| `src/components/pages/OptimizationsPage/OptimizationsNewPage/OptimizationsNewConfigSidebar.tsx`       | Label "Dataset" → "Evaluation suite"                                                            |

### Must Create

| File                                             | Purpose                                                                    |
| ------------------------------------------------ | -------------------------------------------------------------------------- |
| `src/types/evaluation-suites.ts`                 | `ExperimentItemStatus`, `AssertionResult`, `ExecutionPolicy`, metric types |
| `DataObjectCell.tsx` (or similar)                | JSON preview cell with click/hover dialog for formatted JSON view          |
| `PassedCell.tsx`                                 | Pass/fail cell with assertion breakdown tooltip                            |
| `PassRateCell.tsx` (or similar)                  | Pass rate display cell for experiments table                               |
| `EvaluationSuiteExperimentPanel.tsx`             | Eval suite sidebar: YAML context + output + assertions table               |
| `ExperimentItemContent.tsx`                      | Sidebar content: output, assertions table, pass/fail badge                 |
| `AssertionsResultsTable.tsx`                     | Assertions table for sidebar (columns: Assertion, Passed, Reason)          |
| `AssertionsBreakdownTooltip.tsx`                 | Tooltip showing assertion breakdown per run                                |
| `MultiRunTabs.tsx`                               | "Run 1", "Run 2" tab switcher for multi-run items                          |
| `PassFailBadge.tsx`                              | PASSED/FAILED status badge component                                       |
| Experiments table page (new file or restructure) | Unified experiments table with pass_rate column, charts, type filtering    |

### Conditional (pending product decisions)

| File                                                                              | Decision                             |
| --------------------------------------------------------------------------------- | ------------------------------------ |
| `src/components/pages/HomePage/EvaluationSection.tsx`                             | D3 — include eval suite experiments? |
| `src/components/pages/PromptPage/ExperimentsTab/ExperimentsTab.tsx`               | D4 — include eval suite experiments? |
| `src/components/shared/Dashboard/widgets/ExperimentsLeaderboardWidget/helpers.ts` | D5 — include eval suite experiments? |
| `src/components/shared/Dashboard/widgets/ExperimentsFeedbackScoresWidget/`        | D5 — include eval suite experiments? |

### Not Affected

| File/Area                                     | Reason                                                         |
| --------------------------------------------- | -------------------------------------------------------------- |
| `OptimizationsPage/`                          | Uses TRIAL type — separate feature                             |
| `CompareOptimizationsPage/`                   | Uses [TRIAL, MINI_BATCH] — separate feature                    |
| `CompareTrialsPage/`                          | Fetches by IDs — separate feature                              |
| `PlaygroundPage/`                             | Creates REGULAR experiments — no change for now                |
| `CompareExperimentsPanel/` (regular sidebar)  | Continues to work for regular experiments — separate code path |
| All API type names (`Dataset`, `DatasetItem`) | Internal names match BE contract — correct as-is               |
| LocalStorage keys                             | Changing would break existing users — correct as-is            |
