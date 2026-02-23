# Fix: Item-level evaluator and execution_policy changes silently dropped on save

## Context

The Evaluation Suite UI allows users to configure evaluators and execution policies at two levels:

1. **Suite-level** — applies to all items (stored in `addedBehaviors`, `editedBehaviors`, `deletedBehaviorIds`, `executionPolicy`)
2. **Item-level** — per-item overrides (stored in `itemAddedBehaviors`, `itemEditedBehaviors`, `itemDeletedBehaviorIds`, `itemExecutionPolicies`)

Both levels work correctly in the UI: draft badges appear, evaluator cards show green/amber borders, counts update, and the "Save changes" button activates. The backend supports per-item evaluator and execution_policy updates via `edited_items` — each field (`data`, `tags`, `evaluators`, `execution_policy`) is independently optional and fully replaces that field when provided.

## Root issue

The store mixes two concerns: **raw draft state** and **payload construction** (`getFullChangesPayload`, `getChangesPayload`, `reconstructEvaluators`, `applyBehaviorEdits`, `behaviorRowToEvaluator`). The payload construction logic inside the store only reads suite-level behavior maps and ignores all four item-level maps. When a user adds/edits/deletes evaluators on a specific item, then saves, those changes are silently dropped.

Additionally, the `AddEditEvaluatorDialog` does not forward `originalBEConfig` when submitting edits. This field is required by `buildBEEvaluatorConfig` to preserve non-FE fields (model settings, message templates, variables) in the BE config for LLM_AS_JUDGE evaluators.

## Goal

1. Make item-level evaluator and execution_policy draft changes persist through the save flow
2. Separate concerns: store holds raw draft state only; payload construction lives in a dedicated hook that merges store + React Query cache

## Architecture: 3 clean layers

### Layer 1 — Store (pure state + CRUD)

`store/EvaluationSuiteDraftStore.ts`

The store's only job is to track what the user changed. No payload construction, no FE↔BE conversion, no external data dependencies.

**State it owns:**
- Item CRUD: `addedItems`, `editedItems`, `deletedIds`, `isAllItemsSelected`
- Suite-level behaviors: `addedBehaviors`, `editedBehaviors`, `deletedBehaviorIds`, `executionPolicy`
- Item-level behaviors: `itemAddedBehaviors`, `itemEditedBehaviors`, `itemDeletedBehaviorIds`

**Actions:** `addItem`, `editItem`, `deleteItem`, `bulkAddItems`, `bulkDeleteItems`, `bulkEditItems`, `addBehavior`, `editBehavior`, `deleteBehavior`, `setExecutionPolicy`, `addItemBehavior`, `editItemBehavior`, `deleteItemBehavior`, `clearDraft`

**Selectors:** `selectIsDraftMode`, `selectHasBehaviorChanges`, `selectHasDraft`, and per-item lookups

**Remove from store:**
- `getFullChangesPayload` — moves to the payload hook
- `getChangesPayload` — moves to the payload hook
- `reconstructEvaluators`, `applyBehaviorEdits`, `behaviorRowToEvaluator` — move to converter utils
- `itemExecutionPolicies` map + `setItemExecutionPolicy` action + `useItemExecutionPolicy`/`useSetItemExecutionPolicy` hooks — replaced by `editItem(id, { execution_policy })` which flows through `editedItems` automatically
- `useGetFullChangesPayload`, `useGetChangesPayload` hooks — replaced by the payload hook

### Layer 2 — Converter utils (pure functions)

`lib/evaluator-converters.ts` (already exists, extend it)

Pure functions with no store or React Query dependencies. These handle FE↔BE format conversion and evaluator list reconstruction.

**Already in this file:**
- `buildBEEvaluatorConfig(type, feConfig, originalBEConfig)` → BE wire format
- `parseBEEvaluatorConfig(type, beConfig)` → FE display format
- `buildLLMJudgeBEConfig`, `parseLLMJudgeBEConfig`

**Move here from the store:**
- `behaviorRowToEvaluator(row: BehaviorDisplayRow): Evaluator` — calls `buildBEEvaluatorConfig`
- `applyBehaviorEdits(originalRows, added, edited, deleted): BehaviorDisplayRow[]` — merges draft changes over original rows
- `reconstructEvaluators(originalRows, added, edited, deleted): Evaluator[]` — composes `applyBehaviorEdits` + `behaviorRowToEvaluator`

These are already pure functions in the store file — moving them is just a file relocation with re-exports.

### Layer 3 — Payload hook (merges store + cache)

New file: `hooks/useEvaluationSuiteSavePayload.ts`

A single hook that reads from the draft store and React Query cache, then produces the complete save payload. This is the **only place** where store state is merged with server state for the mutation.

```ts
interface EvaluationSuiteSavePayload {
  datasetId: string;
  payload: {
    added_items: DatasetItem[];
    edited_items: Partial<DatasetItem>[];
    deleted_ids: string[];
    base_version: string;
    evaluators?: Evaluator[];
    execution_policy?: ExecutionPolicy;
    tags?: string[];
    change_description?: string;
  };
  override: boolean;
}

function useEvaluationSuiteSavePayload(suiteId: string): {
  buildPayload: (opts: {
    tags?: string[];
    changeDescription?: string;
    override?: boolean;
  }) => EvaluationSuiteSavePayload;
}
```

**What it reads:**
- From store: `addedItems`, `editedItems`, `deletedIds`, `addedBehaviors`, `editedBehaviors`, `deletedBehaviorIds`, `executionPolicy`, `itemAddedBehaviors`, `itemEditedBehaviors`, `itemDeletedBehaviorIds`
- From React Query cache (`useQueryClient`):
  - `["dataset", { datasetId }]` → `suite.latest_version.id` for `base_version`
  - `["dataset-versions", { datasetId, page: 1, size: 1 }]` → `versionEvaluators` and `versionExecutionPolicy` for suite-level reconstruction
  - `["dataset-items", { datasetId, ... }]` → item-level `evaluators` for per-item reconstruction (search across all cached pages)

**What it does:**
1. Serializes `addedItems`, `editedItems`, `deletedIds` from the store
2. Reconstructs suite-level `evaluators` via `reconstructEvaluators(versionEvaluators, addedBehaviors, editedBehaviors, deletedBehaviorIds)`
3. For each item with entries in `itemAddedBehaviors`/`itemEditedBehaviors`/`itemDeletedBehaviorIds`:
   a. Looks up the item's original `evaluators` from the React Query cache
   b. Calls `reconstructEvaluators(originalItemEvaluators, itemAdded, itemEdited, itemDeleted)`
   c. Merges `{ evaluators: result }` into that item's `edited_items` entry
4. Returns the complete payload ready for `useDatasetItemChangesMutation`

**Why React Query cache lookup is safe:** Users can only edit evaluators on items they've opened in the sidebar. Those items are in the current table page's cached response. React Query's default `gcTime` (5 minutes) ensures the data remains available through a typical edit-then-save session.

## Requirements

### 1. Move conversion functions out of the store

Move `behaviorRowToEvaluator`, `applyBehaviorEdits`, `reconstructEvaluators` from `EvaluationSuiteDraftStore.ts` to `lib/evaluator-converters.ts`. Re-export them. The store should import nothing from converters.

### 2. Remove payload construction from the store

Remove `getFullChangesPayload`, `getChangesPayload`, `useGetFullChangesPayload`, `useGetChangesPayload` from the store. These are replaced by the payload hook.

### 3. Simplify execution_policy: use editItem directly

Remove `itemExecutionPolicies` map, `setItemExecutionPolicy` action, `useItemExecutionPolicy`/`useSetItemExecutionPolicy` hooks. Item-level components should call `editItem(itemId, { execution_policy: policy })` instead. This flows through `editedItems` automatically.

Handle the "remove override" case (currently `null`) by deleting the `execution_policy` key from the item's `editedItems` entry, or by storing `undefined`.

Update `ItemExecutionPolicySection` and `ExecutionPolicyCell` to read from `editedItems` instead of `itemExecutionPolicies`.

### 4. Create the payload hook

Create `hooks/useEvaluationSuiteSavePayload.ts` as described in Layer 3 above. It must handle:
- Suite-level evaluator reconstruction (currently working, just relocate the logic)
- Item-level evaluator reconstruction (the fix — currently missing)
- Items in `addedItems` that also have item-level evaluator changes (original evaluators = `[]`)
- The legacy dataset path (no evaluators/policy, just items)

### 5. Forward originalBEConfig through the edit dialog

`AddEditEvaluatorDialog.handleFormSubmit` currently submits `{ name, type, config }` without `originalBEConfig`. Forward `evaluator?.originalBEConfig` in the submit so edited evaluators preserve their BE config context.

### 6. Update EvaluationSuiteItemsPage to use the payload hook

Replace `buildMutationPayload` + `getFullChangesPayload`/`getChangesPayload` calls with the new `useEvaluationSuiteSavePayload` hook. The page component becomes:

```ts
const { buildPayload } = useEvaluationSuiteSavePayload(suiteId);

const handleSaveChanges = (tags?: string[], changeDescription?: string) => {
  changesMutation.mutate(buildPayload({ tags, changeDescription }), { ... });
};
```

### 7. Update selectors

- Remove `itemExecutionPolicies` check from `hasItemLevelChanges`
- Execution policy changes are now detected via `selectIsDraftMode` (`editedItems.size > 0`) automatically

### 8. Update clearDraft

Remove `itemExecutionPolicies` from `createInitialState`.

## Files to modify

| File | Change |
|---|---|
| `src/store/EvaluationSuiteDraftStore.ts` | Remove `getFullChangesPayload`, `getChangesPayload`, `reconstructEvaluators`, `applyBehaviorEdits`, `behaviorRowToEvaluator`, `itemExecutionPolicies`, `setItemExecutionPolicy`, related hooks. Update selectors, `createInitialState`. |
| `src/lib/evaluator-converters.ts` | Add `behaviorRowToEvaluator`, `applyBehaviorEdits`, `reconstructEvaluators` (moved from store). |
| `src/hooks/useEvaluationSuiteSavePayload.ts` | **New file.** The payload hook that merges store + React Query cache into the final mutation payload. |
| `src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsPage.tsx` | Replace `buildMutationPayload` + `getFullChangesPayload`/`getChangesPayload` with `useEvaluationSuiteSavePayload`. Remove `expandEvaluatorsToRows` import. |
| `src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/ItemExecutionPolicySection.tsx` | Use `useEditItem`/`useEditedDatasetItemById` instead of `useItemExecutionPolicy`/`useSetItemExecutionPolicy`. |
| `src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsTab/ExecutionPolicyCell.tsx` | Read from `useEditedDatasetItemById` instead of `useItemExecutionPolicy`. |
| `src/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/AddEditEvaluatorDialog.tsx` | Forward `originalBEConfig` in `handleFormSubmit`. |

## Consumers unaffected

These files read from the store for **display only** and need no changes:
- `useMergedEvaluationSuiteItems.ts` — merges items for table display (reads `addedItems`, `editedItems`, `deletedIds`)
- `useDatasetItemData.ts` — merges item for editor display
- `BehaviorsCountCell.tsx` — reads `itemAddedBehaviors`, `itemDeletedBehaviorIds`
- `EvaluatorsSection.tsx` — reads/writes suite-level behaviors
- `ItemBehaviorsSection.tsx` — reads/writes item-level behaviors
- `DatasetItemEditorAutosaveContext.tsx` — writes `editItem`, `deleteItem`
- `AddDatasetItemSidebar.tsx`, `AddTagDialog.tsx`, `DatasetItemRowActionsCell.tsx`, `DatasetItemsActionsPanel.tsx`, `EvaluationSuiteItemsTab.tsx`

## Non-goals

- Changing the backend API contract
- Changing how `data` or `tags` item edits work (already working via `editedItems`)
- Changing the display-layer merge hooks (`useMergedEvaluationSuiteItems`, `useDatasetItemData`)
- Adding new state to the draft store
