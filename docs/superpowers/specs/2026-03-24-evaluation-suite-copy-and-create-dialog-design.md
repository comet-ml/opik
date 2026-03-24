# Evaluation Suite: Copy Updates & Create Dialog Enhancements

## Overview

Rename "Datasets" copy to "Evaluation suites" across sidebar, list page, and create dialog. Add execution policy and assertions UI to the create dialog. Fix assertions empty state button placement in the edit settings dialog.

---

## 1. Sidebar Label Rename

**File:** `src/v2/layout/SideBar/helpers/getMenuItems.ts` (line 91)

**Change:** Update the `label` from `"Datasets"` to `"Evaluation suites"`.

```diff
- label: "Datasets",
+ label: "Evaluation suites",
```

No other sidebar changes needed — the `id`, `path`, and `count` already use `evaluation_suites`.

---

## 2. List Page Copy Updates

**File:** `src/v2/pages/EvaluationSuitesPage/EvaluationSuitesPage.tsx`

### 2a. Page title (line 356)

```diff
- <h1 className="comet-title-l truncate break-words">Datasets</h1>
+ <h1 className="comet-title-l truncate break-words">Evaluation suites</h1>
```

### 2b. Page description (lines 358-369)

```diff
- A dataset is a collection of inputs and expected outputs used to evaluate your LLM application.
+ An evaluation suite is a collection of inputs, expected outputs, and evaluation criteria used to evaluate your LLM application.
```

### 2c. No-data text (line 244)

```diff
- const noDataText = noData ? "There are no datasets yet" : "No search results";
+ const noDataText = noData ? "There are no evaluation suites yet" : "No search results";
```

### 2d. Row actions entity name (line 54)

```diff
- entityName: "dataset",
+ entityName: "evaluation suite",
```

### 2e. Batch delete entity name (line 393)

```diff
- entityName="datasets"
+ entityName="evaluation suites"
```

### 2f. Router page title

**File:** `src/v2/router.tsx`

The route's `staticData.title` for the evaluation suites list page is `"Datasets"`. Update to `"Evaluation suites"`.

### 2g. Edit settings dialog placeholder

**File:** `src/v2/pages/EvaluationSuiteItemsPage/EditEvaluationSuiteSettingsDialog.tsx` (line 131)

```diff
- placeholder="Dataset description"
+ placeholder="Evaluation suite description"
```

---

## 3. Create Dialog Enhancements

**File:** `src/v2/pages-shared/datasets/AddEditEvaluationSuiteDialog/AddEditEvaluationSuiteDialog.tsx`

### 3a. Title copy

The current `title` is `"Edit"` or `"Create new"`. Keep as-is — it's generic and works for both datasets and evaluation suites.

### 3b. Add execution policy fields (create mode only)

Below the Description field and above the CSV upload, add an "Evaluation criteria" section (only visible when `!isEdit`):

- **Separator** between description and the new section
- **Section header:** "Evaluation criteria" with subtitle "Define the conditions required for the evaluation to pass"
- **Default runs per item** — numeric input, min 1, max 100, default 1
- **Default pass threshold** — numeric input, min 1, max = runs_per_item, default 1
- **Global assertions** — reuse `AssertionsField` component with add/remove

Use the same `useClampedIntegerInput` hook already used in `EditEvaluationSuiteSettingsDialog`.

### 3c. Two-step creation flow

The backend `POST /datasets` endpoint does **not** accept `execution_policy` or `evaluators`. These are set via a separate `POST /datasets/{id}/items/changes` endpoint. The `base_version` field accepts `null` for a brand new dataset with no versions.

**Flow after user clicks "Create new":**

1. **Step 1:** Create the dataset via `useDatasetCreateMutation` (name, description, type)
2. **Step 2:** If the user set non-default execution policy or added any assertions, call `useDatasetItemChangesMutation` with:
   ```ts
   {
     datasetId: newDataset.id,
     payload: {
       added_items: [],
       edited_items: [],
       deleted_ids: [],
       base_version: null,  // null = first version of a new dataset
       evaluators: [packAssertions(assertions)],  // only if assertions were added
       execution_policy: { runs_per_item, pass_threshold },  // only if non-default
     },
     override: true,  // required when base_version is null
   }
   ```
3. If neither assertions nor custom execution policy were set, skip step 2 entirely.

**Type fix required:** The `DatasetItemChangesPayload` interface in `useDatasetItemChangesMutation.ts` declares `base_version: string`. This must be widened to `base_version: string | null` to support creating the first version.

**Error handling:** If step 1 succeeds but step 2 fails, show a toast with the error and still navigate to the created suite. The user can then set execution policy and assertions via the edit settings dialog.

Use `packAssertions` from `@/lib/assertion-converters` to convert string assertions to the evaluator format.

**Important:** These fields should only be sent during creation, not edit. The edit flow for execution policy and assertions is handled by `EditEvaluationSuiteSettingsDialog`.

### 3d. Form state additions

Add to the component state:
- `runsPerItem` (number, default 1)
- `passThreshold` (number, default 1)
- `assertions` (string[], default [])

The existing `useEffect` that resets state when the dialog closes (line 90-108) must also reset these new fields and the type default:

```ts
// Inside the useEffect, when !open:
setRunsPerItem(1);
setPassThreshold(1);
setAssertions([]);
setType(DATASET_TYPE.EVALUATION_SUITE);  // was DATASET_TYPE.DATASET
```

### 3e. Validation

Existing validation stays. The new fields have sensible defaults (1/1, no assertions) so they don't block form submission.

### 3f. Type default change

Currently `type` defaults to `DATASET_TYPE.DATASET`. Since the create dialog now includes evaluation criteria, change the default to `DATASET_TYPE.EVALUATION_SUITE`:

```diff
- const [type, setType] = useState<DATASET_TYPE>(DATASET_TYPE.DATASET);
+ const [type, setType] = useState<DATASET_TYPE>(DATASET_TYPE.EVALUATION_SUITE);
```

**Note:** The dialog is also used in `AddToDatasetDialog` (add traces to a dataset) and `DatasetVersionSelectBox`. These callers don't pass a `type` prop — they rely on the internal default. Changing the default to `EVALUATION_SUITE` means datasets created from those flows will also default to evaluation suite type. This is the desired behavior since we are moving toward evaluation suites as the primary entity.

---

## 4. Assertions Empty State Button Placement

**File:** `src/shared/AssertionField/AssertionsField.tsx`

### Current behavior

The "Assertion" add button (with Plus icon) always appears below the assertions list or below the empty state box. It's a separate element outside the empty state.

### New behavior

- **When empty (no assertions):** The add button appears **inside** the empty state box, next to the "No assertions added yet" text. The standalone button below is hidden.
- **When 1+ assertions exist:** The add button appears below the list, exactly where it is today.

### Implementation

Modify the empty state block (lines 38-47) to include the add button:

```tsx
{!hasAny && (
  <div className="flex h-[80px] flex-col items-center justify-center rounded-md border px-4">
    <div className="flex items-center gap-2">
      <div className="flex size-4 items-center justify-center rounded bg-[#89DEFF]">
        <CheckCheck className="size-2 text-foreground" />
      </div>
      <span className="comet-body-xs text-muted-slate">
        No assertions added yet
      </span>
      <Button
        type="button"
        variant="ghost"
        size="2xs"
        onClick={onAdd}
      >
        <Plus className="mr-0.5 size-3" />
        Assertion
      </Button>
    </div>
  </div>
)}
```

Conditionally hide the bottom button when empty:

```diff
- <Button
+ {hasAny && <Button
    type="button"
    variant="ghost"
    size="2xs"
    className="w-fit"
    onClick={onAdd}
  >
    <Plus className="mr-0.5 size-3" />
    Assertion
- </Button>
+ </Button>}
```

---

## 5. Files Changed Summary

| File | Change |
|------|--------|
| `src/v2/layout/SideBar/helpers/getMenuItems.ts` | Sidebar label: "Datasets" → "Evaluation suites" |
| `src/v2/pages/EvaluationSuitesPage/EvaluationSuitesPage.tsx` | Page title, description, no-data text, entity names |
| `src/v2/router.tsx` | Route page title: "Datasets" → "Evaluation suites" |
| `src/v2/pages-shared/datasets/AddEditEvaluationSuiteDialog/AddEditEvaluationSuiteDialog.tsx` | Add execution policy + assertions UI for create mode, default type change, state reset |
| `src/api/datasets/useDatasetItemChangesMutation.ts` | Widen `base_version` type to `string \| null` |
| `src/v2/pages/EvaluationSuiteItemsPage/EditEvaluationSuiteSettingsDialog.tsx` | Placeholder: "Dataset description" → "Evaluation suite description" |
| `src/shared/AssertionField/AssertionsField.tsx` | Move add button inside empty state box |

## 6. Out of Scope

- No backend API changes — the `/items/changes` endpoint already accepts `execution_policy` and `evaluators`, and supports `base_version: null` for first version creation
- No changes to the edit settings dialog (`EditEvaluationSuiteSettingsDialog`) — it already has execution policy and assertions
- No route changes — routes already use `/evaluation-suites`
- No changes to v1 pages
- No type/enum changes — `DATASET_TYPE.EVALUATION_SUITE` already exists
