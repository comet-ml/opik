# Phase 3: Behaviors & Execution Policy UI — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the evaluators/behaviors UI and execution policy management for evaluation suites, including the behaviors section, add/edit behavior dialog, execution policy dropdown, item side panel with three sections, and the unified draft store — all with mocked/stubbed API hooks since BE endpoints don't exist yet.

**Architecture:** New components are added inside `EvaluationSuiteItemsPage/`. A `BehaviorsDraftStore` (Zustand) extends the existing `DatasetDraftStore` to track behavior and execution policy changes alongside item changes. API hooks are created with placeholder endpoints, ready to wire up when BE ships. The `AddEditBehaviorDialog` is a shared centered modal reused at both suite and item level.

**Tech Stack:** React, TypeScript, Zustand, TanStack Query, Tailwind CSS, shadcn/ui (Dialog, DropdownMenu, Button, Input, Textarea, Label, Select), lucide-react icons, CodeMirror (for context editor in item panel).

**Design doc:** `docs/plans/2026-02-13-evaluation-suites-fe-design.md` — sections 3 (Data Layer), 4 (Evaluators/Behaviors UI).

---

## Task 1: Types & Constants

**Files:**
- Create: `apps/opik-frontend/src/types/evaluation-suites.ts`

**Step 1: Create the types file**

```typescript
// types/evaluation-suites.ts

export interface ExecutionPolicy {
  runs_per_item: number;
  pass_threshold: number;
}

export const DEFAULT_EXECUTION_POLICY: ExecutionPolicy = {
  runs_per_item: 1,
  pass_threshold: 1,
};

export enum MetricType {
  LLM_AS_JUDGE = "llm_judge",
  CONTAINS = "contains",
  EQUALS = "equals",
  LEVENSHTEIN_RATIO = "levenshtein_ratio",
  HALLUCINATION = "hallucination",
  MODERATION = "moderation",
}

export const METRIC_TYPE_LABELS: Record<MetricType, string> = {
  [MetricType.LLM_AS_JUDGE]: "LLM as a Judge",
  [MetricType.CONTAINS]: "Contains",
  [MetricType.EQUALS]: "Equals",
  [MetricType.LEVENSHTEIN_RATIO]: "Levenshtein Ratio",
  [MetricType.HALLUCINATION]: "Hallucination",
  [MetricType.MODERATION]: "Moderation",
};

export interface ContainsConfig {
  value: string;
  case_sensitive: boolean;
}

export interface EqualsConfig {
  value: string;
  case_sensitive: boolean;
}

export interface LevenshteinConfig {
  threshold: number;
}

export interface HallucinationConfig {
  threshold: number;
}

export interface ModerationConfig {
  threshold: number;
}

export interface LLMJudgeConfig {
  assertions: string[];
}

export type MetricConfig =
  | ContainsConfig
  | EqualsConfig
  | LevenshteinConfig
  | HallucinationConfig
  | ModerationConfig
  | LLMJudgeConfig;

export interface DatasetEvaluator {
  id: string;
  dataset_id: string;
  name: string;
  metric_type: MetricType;
  metric_config: MetricConfig;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

export interface DatasetItemEvaluator {
  id: string;
  dataset_item_id: string;
  name: string;
  metric_type: MetricType;
  metric_config: MetricConfig;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

// Display row for behaviors table — one row per assertion for LLM Judge
export interface BehaviorDisplayRow {
  id: string; // temp ID for FE tracking
  evaluatorId?: string; // real evaluator ID from BE (if persisted)
  name: string;
  metric_type: MetricType;
  metric_config: MetricConfig; // for LLM Judge: single assertion, not array
  isNew?: boolean;
  isEdited?: boolean;
}

// Helper: generate auto-name from metric config
export function generateBehaviorName(
  metricType: MetricType,
  config: MetricConfig,
): string {
  switch (metricType) {
    case MetricType.LLM_AS_JUDGE: {
      const assertion = (config as LLMJudgeConfig).assertions?.[0] ?? "";
      return assertion.length > 50 ? assertion.slice(0, 50) + "…" : assertion;
    }
    case MetricType.CONTAINS:
      return `Contains: "${(config as ContainsConfig).value}"`;
    case MetricType.EQUALS:
      return `Equals: "${(config as EqualsConfig).value}"`;
    case MetricType.LEVENSHTEIN_RATIO:
      return `Levenshtein >= ${(config as LevenshteinConfig).threshold}`;
    case MetricType.HALLUCINATION:
      return `Hallucination >= ${(config as HallucinationConfig).threshold}`;
    case MetricType.MODERATION:
      return `Moderation >= ${(config as ModerationConfig).threshold}`;
    default:
      return metricType;
  }
}
```

**Step 2: Verify TypeScript compiles**

Run: `cd apps/opik-frontend && npx tsc --noEmit --pretty 2>&1 | head -20`
Expected: No errors related to `evaluation-suites.ts`.

**Step 3: Commit**

```
feat: add evaluation suite types and constants
```

---

## Task 2: API Hooks (Stubbed)

**Files:**
- Create: `apps/opik-frontend/src/api/evaluators/useDatasetEvaluatorsList.ts`
- Create: `apps/opik-frontend/src/api/evaluators/useDatasetEvaluatorCreateMutation.ts`
- Create: `apps/opik-frontend/src/api/evaluators/useDatasetEvaluatorUpdateMutation.ts`
- Create: `apps/opik-frontend/src/api/evaluators/useDatasetEvaluatorDeleteMutation.ts`
- Create: `apps/opik-frontend/src/api/evaluators/useDatasetItemEvaluatorsList.ts`
- Create: `apps/opik-frontend/src/api/evaluators/useDatasetItemEvaluatorCreateMutation.ts`
- Create: `apps/opik-frontend/src/api/evaluators/useDatasetItemEvaluatorUpdateMutation.ts`
- Create: `apps/opik-frontend/src/api/evaluators/useDatasetItemEvaluatorDeleteMutation.ts`

**Step 1: Create the list hook**

Follow the pattern from `useDatasetsList.ts`. The endpoint `GET /v1/private/datasets/{datasetId}/evaluators` doesn't exist yet — create the hook structure anyway so it's ready to wire up.

```typescript
// api/evaluators/useDatasetEvaluatorsList.ts
import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "@/api/api";
import { DatasetEvaluator } from "@/types/evaluation-suites";

type UseDatasetEvaluatorsListParams = {
  datasetId: string;
};

type UseDatasetEvaluatorsListResponse = {
  content: DatasetEvaluator[];
};

const ENDPOINT = "/v1/private/datasets/";

const getDatasetEvaluatorsList = async (
  { signal }: QueryFunctionContext,
  { datasetId }: UseDatasetEvaluatorsListParams,
) => {
  const { data } = await api.get<UseDatasetEvaluatorsListResponse>(
    `${ENDPOINT}${datasetId}/evaluators`,
    { signal },
  );
  return data;
};

export default function useDatasetEvaluatorsList(
  params: UseDatasetEvaluatorsListParams,
  options?: QueryConfig<UseDatasetEvaluatorsListResponse>,
) {
  return useQuery({
    queryKey: ["dataset-evaluators", params],
    queryFn: (context) => getDatasetEvaluatorsList(context, params),
    ...options,
  });
}
```

**Step 2: Create CRUD mutation hooks**

Follow the pattern from `useDatasetItemChangesMutation.ts`. Create create/update/delete mutations for both `dataset_evaluators` and `dataset_item_evaluators`. Each mutation invalidates the relevant query key on `onSettled`.

Create mutation example:
```typescript
// api/evaluators/useDatasetEvaluatorCreateMutation.ts
import { useMutation, useQueryClient } from "@tanstack/react-query";
import api from "@/api/api";
import { DatasetEvaluator, MetricType, MetricConfig } from "@/types/evaluation-suites";

type CreateDatasetEvaluatorPayload = {
  datasetId: string;
  name: string;
  metric_type: MetricType;
  metric_config: MetricConfig;
};

const useDatasetEvaluatorCreateMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ datasetId, ...payload }: CreateDatasetEvaluatorPayload) => {
      const { data } = await api.post<DatasetEvaluator>(
        `/v1/private/datasets/${datasetId}/evaluators`,
        payload,
      );
      return data;
    },
    onSettled: (_data, _error, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["dataset-evaluators", { datasetId: variables.datasetId }],
      });
    },
  });
};

export default useDatasetEvaluatorCreateMutation;
```

Repeat for update (PUT), delete (DELETE), and the 4 item-level equivalents (`/v1/private/dataset-items/{itemId}/evaluators`).

**Step 3: Verify TypeScript compiles**

Run: `cd apps/opik-frontend && npx tsc --noEmit --pretty 2>&1 | head -20`

**Step 4: Commit**

```
feat: add evaluator API hooks (stubbed for BE endpoints)
```

---

## Task 3: BehaviorsDraftStore (Zustand)

**Files:**
- Create: `apps/opik-frontend/src/store/BehaviorsDraftStore.ts`

**Step 1: Create the store**

Model after `DatasetDraftStore.ts` (same Map/Set pattern, same hook export style). This store tracks behavior and execution policy changes at both suite and item level.

```typescript
// store/BehaviorsDraftStore.ts
import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";
import {
  BehaviorDisplayRow,
  ExecutionPolicy,
  MetricType,
  MetricConfig,
} from "@/types/evaluation-suites";

interface BehaviorsDraftState {
  // Suite-level behavior changes
  addedBehaviors: Map<string, BehaviorDisplayRow>;
  editedBehaviors: Map<string, Partial<BehaviorDisplayRow>>;
  deletedBehaviorIds: Set<string>;

  // Suite-level execution policy
  executionPolicy: ExecutionPolicy | null; // null = unchanged
  originalExecutionPolicy: ExecutionPolicy | null;

  // Item-level behavior changes (keyed by itemId)
  itemAddedBehaviors: Map<string, Map<string, BehaviorDisplayRow>>;
  itemEditedBehaviors: Map<string, Map<string, Partial<BehaviorDisplayRow>>>;
  itemDeletedBehaviorIds: Map<string, Set<string>>;

  // Item-level execution policy overrides (keyed by itemId)
  itemExecutionPolicies: Map<string, ExecutionPolicy | null>; // null = remove override

  // Actions — suite level
  addBehavior: (behavior: Omit<BehaviorDisplayRow, "id">) => string;
  editBehavior: (id: string, changes: Partial<BehaviorDisplayRow>) => void;
  deleteBehavior: (id: string) => void;
  setExecutionPolicy: (policy: ExecutionPolicy) => void;
  setOriginalExecutionPolicy: (policy: ExecutionPolicy) => void;

  // Actions — item level
  addItemBehavior: (itemId: string, behavior: Omit<BehaviorDisplayRow, "id">) => string;
  editItemBehavior: (itemId: string, id: string, changes: Partial<BehaviorDisplayRow>) => void;
  deleteItemBehavior: (itemId: string, id: string) => void;
  setItemExecutionPolicy: (itemId: string, policy: ExecutionPolicy | null) => void;

  // Clear
  clearDraft: () => void;
}

const useBehaviorsDraftStore = create<BehaviorsDraftState>((set, get) => ({
  addedBehaviors: new Map(),
  editedBehaviors: new Map(),
  deletedBehaviorIds: new Set(),
  executionPolicy: null,
  originalExecutionPolicy: null,
  itemAddedBehaviors: new Map(),
  itemEditedBehaviors: new Map(),
  itemDeletedBehaviorIds: new Map(),
  itemExecutionPolicies: new Map(),

  addBehavior: (behavior) => {
    const id = uuidv4();
    set((state) => {
      const next = new Map(state.addedBehaviors);
      next.set(id, { ...behavior, id, isNew: true });
      return { addedBehaviors: next };
    });
    return id;
  },

  editBehavior: (id, changes) => {
    set((state) => {
      if (state.addedBehaviors.has(id)) {
        const next = new Map(state.addedBehaviors);
        const existing = next.get(id)!;
        next.set(id, { ...existing, ...changes });
        return { addedBehaviors: next };
      }
      const next = new Map(state.editedBehaviors);
      const existing = next.get(id) || {};
      next.set(id, { ...existing, ...changes, isEdited: true });
      return { editedBehaviors: next };
    });
  },

  deleteBehavior: (id) => {
    set((state) => {
      if (state.addedBehaviors.has(id)) {
        const next = new Map(state.addedBehaviors);
        next.delete(id);
        return { addedBehaviors: next };
      }
      const nextEdited = new Map(state.editedBehaviors);
      nextEdited.delete(id);
      const nextDeleted = new Set(state.deletedBehaviorIds);
      nextDeleted.add(id);
      return { editedBehaviors: nextEdited, deletedBehaviorIds: nextDeleted };
    });
  },

  setExecutionPolicy: (policy) => set({ executionPolicy: policy }),
  setOriginalExecutionPolicy: (policy) => set({ originalExecutionPolicy: policy }),

  addItemBehavior: (itemId, behavior) => {
    const id = uuidv4();
    set((state) => {
      const outer = new Map(state.itemAddedBehaviors);
      const inner = new Map(outer.get(itemId) || new Map());
      inner.set(id, { ...behavior, id, isNew: true });
      outer.set(itemId, inner);
      return { itemAddedBehaviors: outer };
    });
    return id;
  },

  editItemBehavior: (itemId, id, changes) => {
    set((state) => {
      const addedOuter = state.itemAddedBehaviors.get(itemId);
      if (addedOuter?.has(id)) {
        const outer = new Map(state.itemAddedBehaviors);
        const inner = new Map(addedOuter);
        const existing = inner.get(id)!;
        inner.set(id, { ...existing, ...changes });
        outer.set(itemId, inner);
        return { itemAddedBehaviors: outer };
      }
      const outer = new Map(state.itemEditedBehaviors);
      const inner = new Map(outer.get(itemId) || new Map());
      const existing = inner.get(id) || {};
      inner.set(id, { ...existing, ...changes, isEdited: true });
      outer.set(itemId, inner);
      return { itemEditedBehaviors: outer };
    });
  },

  deleteItemBehavior: (itemId, id) => {
    set((state) => {
      const addedOuter = state.itemAddedBehaviors.get(itemId);
      if (addedOuter?.has(id)) {
        const outer = new Map(state.itemAddedBehaviors);
        const inner = new Map(addedOuter);
        inner.delete(id);
        outer.set(itemId, inner);
        return { itemAddedBehaviors: outer };
      }
      const editedOuter = new Map(state.itemEditedBehaviors);
      const editedInner = new Map(editedOuter.get(itemId) || new Map());
      editedInner.delete(id);
      editedOuter.set(itemId, editedInner);

      const deletedOuter = new Map(state.itemDeletedBehaviorIds);
      const deletedInner = new Set(deletedOuter.get(itemId) || new Set());
      deletedInner.add(id);
      deletedOuter.set(itemId, deletedInner);

      return {
        itemEditedBehaviors: editedOuter,
        itemDeletedBehaviorIds: deletedOuter,
      };
    });
  },

  setItemExecutionPolicy: (itemId, policy) => {
    set((state) => {
      const next = new Map(state.itemExecutionPolicies);
      if (policy === null) {
        next.delete(itemId);
      } else {
        next.set(itemId, policy);
      }
      return { itemExecutionPolicies: next };
    });
  },

  clearDraft: () => {
    set({
      addedBehaviors: new Map(),
      editedBehaviors: new Map(),
      deletedBehaviorIds: new Set(),
      executionPolicy: null,
      originalExecutionPolicy: null,
      itemAddedBehaviors: new Map(),
      itemEditedBehaviors: new Map(),
      itemDeletedBehaviorIds: new Map(),
      itemExecutionPolicies: new Map(),
    });
  },
}));

// Selectors
export const selectHasBehaviorChanges = (state: BehaviorsDraftState) =>
  state.addedBehaviors.size > 0 ||
  state.editedBehaviors.size > 0 ||
  state.deletedBehaviorIds.size > 0 ||
  (state.executionPolicy !== null &&
    state.originalExecutionPolicy !== null &&
    (state.executionPolicy.runs_per_item !== state.originalExecutionPolicy.runs_per_item ||
      state.executionPolicy.pass_threshold !== state.originalExecutionPolicy.pass_threshold)) ||
  state.itemAddedBehaviors.size > 0 ||
  state.itemEditedBehaviors.size > 0 ||
  state.itemDeletedBehaviorIds.size > 0 ||
  state.itemExecutionPolicies.size > 0;

// Exported hooks
export const useAddBehavior = () =>
  useBehaviorsDraftStore((s) => s.addBehavior);
export const useEditBehavior = () =>
  useBehaviorsDraftStore((s) => s.editBehavior);
export const useDeleteBehavior = () =>
  useBehaviorsDraftStore((s) => s.deleteBehavior);
export const useSetExecutionPolicy = () =>
  useBehaviorsDraftStore((s) => s.setExecutionPolicy);
export const useSetOriginalExecutionPolicy = () =>
  useBehaviorsDraftStore((s) => s.setOriginalExecutionPolicy);
export const useBehaviorsExecutionPolicy = () =>
  useBehaviorsDraftStore((s) => s.executionPolicy);
export const useOriginalExecutionPolicy = () =>
  useBehaviorsDraftStore((s) => s.originalExecutionPolicy);
export const useAddedBehaviors = () =>
  useBehaviorsDraftStore((s) => s.addedBehaviors);
export const useEditedBehaviors = () =>
  useBehaviorsDraftStore((s) => s.editedBehaviors);
export const useDeletedBehaviorIds = () =>
  useBehaviorsDraftStore((s) => s.deletedBehaviorIds);
export const useHasBehaviorChanges = () =>
  useBehaviorsDraftStore(selectHasBehaviorChanges);
export const useClearBehaviorsDraft = () =>
  useBehaviorsDraftStore((s) => s.clearDraft);

// Item-level hooks
export const useAddItemBehavior = () =>
  useBehaviorsDraftStore((s) => s.addItemBehavior);
export const useEditItemBehavior = () =>
  useBehaviorsDraftStore((s) => s.editItemBehavior);
export const useDeleteItemBehavior = () =>
  useBehaviorsDraftStore((s) => s.deleteItemBehavior);
export const useSetItemExecutionPolicy = () =>
  useBehaviorsDraftStore((s) => s.setItemExecutionPolicy);
export const useItemExecutionPolicies = () =>
  useBehaviorsDraftStore((s) => s.itemExecutionPolicies);

export default useBehaviorsDraftStore;
```

**Step 2: Verify TypeScript compiles**

Run: `cd apps/opik-frontend && npx tsc --noEmit --pretty 2>&1 | head -20`

**Step 3: Commit**

```
feat: add BehaviorsDraftStore for evaluator and policy change tracking
```

---

## Task 4: ExecutionPolicyDropdown Component

**Files:**
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/ExecutionPolicyDropdown.tsx`

**Step 1: Create the component**

Follow the `AlgorithmConfigs.tsx` dropdown pattern exactly: Settings2 icon button → DropdownMenuContent w-72 with two number inputs.

```typescript
// BehaviorsSection/ExecutionPolicyDropdown.tsx
import React from "react";
import { Settings2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ExecutionPolicy, DEFAULT_EXECUTION_POLICY } from "@/types/evaluation-suites";

interface ExecutionPolicyDropdownProps {
  policy: ExecutionPolicy;
  onChange: (policy: ExecutionPolicy) => void;
}

const ExecutionPolicyDropdown: React.FC<ExecutionPolicyDropdownProps> = ({
  policy,
  onChange,
}) => {
  const handleRunsChange = (value: string) => {
    const runs = Math.max(1, parseInt(value, 10) || 1);
    onChange({
      runs_per_item: runs,
      pass_threshold: Math.min(policy.pass_threshold, runs),
    });
  };

  const handleThresholdChange = (value: string) => {
    const threshold = Math.max(1, parseInt(value, 10) || 1);
    onChange({
      ...policy,
      pass_threshold: Math.min(threshold, policy.runs_per_item),
    });
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-sm">
          <Settings2 className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        className="max-h-[70vh] overflow-y-auto p-6"
        side="bottom"
        align="end"
      >
        <div className="w-72">
          <div className="mb-4">
            <h3 className="comet-body-s-accented">Execution policy</h3>
            <p className="comet-body-xs text-muted-slate">
              Configure how many times each item is evaluated and the pass
              threshold
            </p>
          </div>
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <Label htmlFor="runs-per-item">Runs per item</Label>
              <Input
                id="runs-per-item"
                type="number"
                min={1}
                value={policy.runs_per_item}
                onChange={(e) => handleRunsChange(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="pass-threshold">Pass threshold</Label>
              <Input
                id="pass-threshold"
                type="number"
                min={1}
                max={policy.runs_per_item}
                value={policy.pass_threshold}
                onChange={(e) => handleThresholdChange(e.target.value)}
              />
            </div>
          </div>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ExecutionPolicyDropdown;
```

**Step 2: Verify TypeScript compiles**

Run: `cd apps/opik-frontend && npx tsc --noEmit --pretty 2>&1 | head -20`

**Step 3: Commit**

```
feat: add ExecutionPolicyDropdown component
```

---

## Task 5: MetricConfigForm Component

**Files:**
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/MetricConfigForm.tsx`

**Step 1: Create the component**

Dynamic form that switches on metric_type. 6 cases in Phase 1.

```typescript
// BehaviorsSection/MetricConfigForm.tsx
import React from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import {
  MetricType,
  MetricConfig,
  ContainsConfig,
  EqualsConfig,
  LevenshteinConfig,
  HallucinationConfig,
  ModerationConfig,
  LLMJudgeConfig,
} from "@/types/evaluation-suites";

interface MetricConfigFormProps {
  metricType: MetricType;
  config: MetricConfig;
  onChange: (config: MetricConfig) => void;
}

const MetricConfigForm: React.FC<MetricConfigFormProps> = ({
  metricType,
  config,
  onChange,
}) => {
  switch (metricType) {
    case MetricType.LLM_AS_JUDGE: {
      const c = config as LLMJudgeConfig;
      return (
        <div className="flex flex-col gap-2">
          <Label>Expected behavior</Label>
          <Textarea
            placeholder="Describe the expected behavior..."
            value={c.assertions?.[0] ?? ""}
            onChange={(e) =>
              onChange({ assertions: [e.target.value] } as LLMJudgeConfig)
            }
            rows={3}
          />
        </div>
      );
    }
    case MetricType.CONTAINS: {
      const c = config as ContainsConfig;
      return (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label>Value</Label>
            <Input
              placeholder="Text that output must contain"
              value={c.value ?? ""}
              onChange={(e) => onChange({ ...c, value: e.target.value })}
            />
          </div>
          <div className="flex items-center gap-2">
            <Checkbox
              id="case-sensitive"
              checked={c.case_sensitive ?? false}
              onCheckedChange={(checked) =>
                onChange({ ...c, case_sensitive: !!checked })
              }
            />
            <Label htmlFor="case-sensitive">Case sensitive</Label>
          </div>
        </div>
      );
    }
    case MetricType.EQUALS: {
      const c = config as EqualsConfig;
      return (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label>Expected value</Label>
            <Input
              placeholder="Exact expected value"
              value={c.value ?? ""}
              onChange={(e) => onChange({ ...c, value: e.target.value })}
            />
          </div>
          <div className="flex items-center gap-2">
            <Checkbox
              id="case-sensitive-equals"
              checked={c.case_sensitive ?? false}
              onCheckedChange={(checked) =>
                onChange({ ...c, case_sensitive: !!checked })
              }
            />
            <Label htmlFor="case-sensitive-equals">Case sensitive</Label>
          </div>
        </div>
      );
    }
    case MetricType.LEVENSHTEIN_RATIO:
    case MetricType.HALLUCINATION:
    case MetricType.MODERATION: {
      const c = config as LevenshteinConfig | HallucinationConfig | ModerationConfig;
      return (
        <div className="flex flex-col gap-2">
          <Label>Pass threshold (0-1)</Label>
          <Input
            type="number"
            min={0}
            max={1}
            step={0.1}
            placeholder="0.8"
            value={c.threshold ?? ""}
            onChange={(e) =>
              onChange({ threshold: parseFloat(e.target.value) || 0 })
            }
          />
          <p className="comet-body-xs text-muted-slate">
            Score &ge; threshold means pass
          </p>
        </div>
      );
    }
    default:
      return null;
  }
};

export default MetricConfigForm;
```

**Step 2: Verify TypeScript compiles**

**Step 3: Commit**

```
feat: add MetricConfigForm with dynamic forms per metric type
```

---

## Task 6: AddEditBehaviorDialog

**Files:**
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/AddEditBehaviorDialog.tsx`

**Step 1: Create the dialog**

Follow `AddEditEvaluationSuiteDialog.tsx` pattern. Centered modal with name (optional, auto-default), metric type dropdown, and MetricConfigForm.

```typescript
// BehaviorsSection/AddEditBehaviorDialog.tsx
import React, { useState, useEffect } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogClose,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import MetricConfigForm from "./MetricConfigForm";
import {
  MetricType,
  MetricConfig,
  LLMJudgeConfig,
  METRIC_TYPE_LABELS,
  BehaviorDisplayRow,
  generateBehaviorName,
} from "@/types/evaluation-suites";

interface AddEditBehaviorDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  behavior?: BehaviorDisplayRow; // if provided, edit mode
  onSubmit: (behavior: Omit<BehaviorDisplayRow, "id">) => void;
}

const DEFAULT_CONFIGS: Record<MetricType, MetricConfig> = {
  [MetricType.LLM_AS_JUDGE]: { assertions: [""] },
  [MetricType.CONTAINS]: { value: "", case_sensitive: false },
  [MetricType.EQUALS]: { value: "", case_sensitive: false },
  [MetricType.LEVENSHTEIN_RATIO]: { threshold: 0.8 },
  [MetricType.HALLUCINATION]: { threshold: 0.8 },
  [MetricType.MODERATION]: { threshold: 0.8 },
};

const AddEditBehaviorDialog: React.FC<AddEditBehaviorDialogProps> = ({
  open,
  setOpen,
  behavior,
  onSubmit,
}) => {
  const isEdit = Boolean(behavior);

  const [name, setName] = useState("");
  const [nameManuallySet, setNameManuallySet] = useState(false);
  const [metricType, setMetricType] = useState<MetricType>(
    MetricType.LLM_AS_JUDGE,
  );
  const [config, setConfig] = useState<MetricConfig>(
    DEFAULT_CONFIGS[MetricType.LLM_AS_JUDGE],
  );

  useEffect(() => {
    if (open) {
      if (behavior) {
        setName(behavior.name);
        setNameManuallySet(true);
        setMetricType(behavior.metric_type);
        setConfig(behavior.metric_config);
      } else {
        setName("");
        setNameManuallySet(false);
        setMetricType(MetricType.LLM_AS_JUDGE);
        setConfig(DEFAULT_CONFIGS[MetricType.LLM_AS_JUDGE]);
      }
    }
  }, [open, behavior]);

  // Auto-generate name when config changes (if not manually overridden)
  useEffect(() => {
    if (!nameManuallySet) {
      setName(generateBehaviorName(metricType, config));
    }
  }, [metricType, config, nameManuallySet]);

  const handleMetricTypeChange = (type: MetricType) => {
    setMetricType(type);
    setConfig(DEFAULT_CONFIGS[type]);
    setNameManuallySet(false);
  };

  const handleNameChange = (value: string) => {
    setName(value);
    setNameManuallySet(value.length > 0);
  };

  const isValid = (() => {
    if (metricType === MetricType.LLM_AS_JUDGE) {
      const c = config as LLMJudgeConfig;
      return c.assertions?.[0]?.trim().length > 0;
    }
    return true;
  })();

  const handleSubmit = () => {
    const finalName =
      name.trim() || generateBehaviorName(metricType, config);
    onSubmit({
      name: finalName,
      metric_type: metricType,
      metric_config: config,
    });
    setOpen(false);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>
            {isEdit ? "Edit behavior" : "Add new behavior"}
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label>Metric type</Label>
              <Select
                value={metricType}
                onValueChange={(v) => handleMetricTypeChange(v as MetricType)}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {Object.entries(METRIC_TYPE_LABELS).map(([key, label]) => (
                    <SelectItem key={key} value={key}>
                      {label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <MetricConfigForm
              metricType={metricType}
              config={config}
              onChange={setConfig}
            />

            <div className="flex flex-col gap-2">
              <Label>
                Name{" "}
                <span className="text-muted-slate">(optional)</span>
              </Label>
              <Input
                placeholder={generateBehaviorName(metricType, config)}
                value={nameManuallySet ? name : ""}
                onChange={(e) => handleNameChange(e.target.value)}
              />
            </div>
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button disabled={!isValid} onClick={handleSubmit}>
            {isEdit ? "Save behavior" : "Add behavior"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditBehaviorDialog;
```

**Step 2: Verify TypeScript compiles**

**Step 3: Commit**

```
feat: add AddEditBehaviorDialog with metric type selector and auto-naming
```

---

## Task 7: BehaviorsSection Component

**Files:**
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/BehaviorsSection.tsx`

**Step 1: Create the component**

Renders above the items table. Shows section title/subtitle, Settings2 execution policy dropdown in header, behaviors table (Name | Metric type | Actions), and "Add new behavior" button.

Key behavior:
- Loads suite-level evaluators from `useDatasetEvaluatorsList` (will 404 until BE ships — handle gracefully with `enabled: false` or fallback to empty array)
- Splits LLM-Judge aggregated evaluator into separate display rows on load
- Wires add/edit/delete into `BehaviorsDraftStore`
- Config tooltip on hover over metric type cell

**Refer to:**
- `EvaluationSuiteItemsPage.tsx` for where this gets mounted (above the `<Tabs>`)
- `AddEditBehaviorDialog` for the create/edit flow
- `ExecutionPolicyDropdown` for the settings icon

The component should be ~150 lines. Use a simple `<table>` or the shared `DataTable` for the behaviors list. Given the small size and no pagination needed, a simple table is fine.

**Step 2: Integrate into EvaluationSuiteItemsPage**

Modify `EvaluationSuiteItemsPage.tsx`:
- Import `BehaviorsSection`
- Render it between the header metadata and `<Tabs>` (around line 250)
- Pass `datasetId={suiteId}`
- Wire `useHasBehaviorChanges()` into the existing `hasDraft` logic: `const hasDraft = useIsDraftMode() || hasBehaviorChanges`
- Wire `clearBehaviorsDraft()` into the existing `clearDraft` calls
- Wire behavior changes payload into `buildMutationPayload` (extend when BE ships)

**Step 3: Verify it renders**

Run: `cd apps/opik-frontend && npm run dev`
Navigate to an evaluation suite → the behaviors section should appear above items tab.

**Step 4: Commit**

```
feat: add BehaviorsSection with evaluators table and execution policy
```

---

## Task 8: EvaluationSuiteItemPanel — Shell

**Files:**
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/EvaluationSuiteItemPanel.tsx`

**Step 1: Create the panel shell**

Uses `ResizableSidePanel` (from `@/components/shared/ResizableSidePanel`). Three sections stacked vertically with dividers.

Props:
```typescript
interface EvaluationSuiteItemPanelProps {
  isOpen: boolean;
  onClose: () => void;
  datasetItemId: string | null;
  datasetId: string;
  suiteExecutionPolicy: ExecutionPolicy;
  suiteBehaviors: BehaviorDisplayRow[];
  horizontalNavigation?: {
    hasPrevious: boolean;
    hasNext: boolean;
    onChange: (shift: 1 | -1) => void;
  };
}
```

Sections:
1. **Top:** Title "Evaluation suite item" + description textarea + execution policy override toggle
2. **Middle:** "Expected behaviors" with two subsections (suite read-only + item editable)
3. **Bottom:** "Context" — reuse `DatasetItemEditorForm` pattern

For this task, create the shell with placeholder sections. Subsequent tasks fill in each section.

**Step 2: Wire into EvaluationSuiteItemsTab**

Replace or extend the existing `DatasetItemEditor` usage in `EvaluationSuiteItemsTab` to use the new panel when clicking an item row.

**Step 3: Verify panel opens on item click**

**Step 4: Commit**

```
feat: add EvaluationSuiteItemPanel shell with three sections
```

---

## Task 9: Item Panel — Top Section (Description + Execution Policy Override)

**Files:**
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/ItemDescriptionSection.tsx`
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/ItemExecutionPolicySection.tsx`

**Step 1: Create ItemDescriptionSection**

Multi-line `Textarea` for the item description. Value comes from draft store or API data. Changes feed into parent draft via `editItem` from `DatasetDraftStore` (description is a field on the dataset item data object).

**Step 2: Create ItemExecutionPolicySection**

Shows read-only suite default: *"Suite default: {runs} run(s), {threshold} to pass"*. Below that, a toggle "Override suite default". When on, shows `Runs per item` and `Pass threshold` inputs with helper text. Uses `useSetItemExecutionPolicy` from `BehaviorsDraftStore`.

**Step 3: Wire into panel top section**

**Step 4: Commit**

```
feat: add item panel description and execution policy override sections
```

---

## Task 10: Item Panel — Middle Section (Behaviors)

**Files:**
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/ItemBehaviorsSection.tsx`

**Step 1: Create ItemBehaviorsSection**

Two subsections:
1. **"Suite behaviors"** — read-only table showing suite-level behaviors passed as props. Rows grayed out with muted text. No edit/delete actions.
2. **"Item behaviors"** — editable table with add/edit/delete. Reuses `AddEditBehaviorDialog`. Wires into `useAddItemBehavior`, `useEditItemBehavior`, `useDeleteItemBehavior` from `BehaviorsDraftStore`.

Loads item evaluators from `useDatasetItemEvaluatorsList` (will 404 until BE — handle with fallback). Merges with draft changes.

**Step 2: Wire into panel middle section**

**Step 3: Commit**

```
feat: add item panel behaviors section with suite (read-only) and item (editable) subsections
```

---

## Task 11: Item Panel — Bottom Section (Context Editor)

**Files:**
- Create: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/ItemContextSection.tsx`

**Step 1: Create ItemContextSection**

Reuse the existing `DatasetItemEditorForm` pattern:
- Import `useDatasetItemFormHelpers` for field type detection
- Render fields with `TextareaAutosize` (simple) and `CodeMirror` (complex)
- Changes feed into parent draft via `editItem` from `DatasetDraftStore`

This is largely a thin wrapper around the existing form infrastructure. Refer to:
- `apps/opik-frontend/src/components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditorForm.tsx`
- `apps/opik-frontend/src/components/pages/DatasetItemsPage/DatasetItemEditor/hooks/useDatasetItemFormHelpers.ts`

**Step 2: Wire into panel bottom section**

**Step 3: Commit**

```
feat: add item panel context editor reusing DatasetItemEditorForm pattern
```

---

## Task 12: Integration — Unified Draft Mode

**Files:**
- Modify: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsPage.tsx`

**Step 1: Integrate BehaviorsDraftStore into page-level draft**

Update the `hasDraft` logic to combine both stores:
```typescript
const hasItemDraft = useIsDraftMode(); // from DatasetDraftStore
const hasBehaviorDraft = useHasBehaviorChanges(); // from BehaviorsDraftStore
const hasDraft = hasItemDraft || hasBehaviorDraft;
```

Update `clearDraft` to clear both:
```typescript
const clearItemDraft = useClearDraft();
const clearBehaviorsDraft = useClearBehaviorsDraft();
const clearAll = useCallback(() => {
  clearItemDraft();
  clearBehaviorsDraft();
}, [clearItemDraft, clearBehaviorsDraft]);
```

Update the `useEffect` cleanup on unmount to clear both.

Update `useNavigationBlocker` to use the combined `hasDraft`.

**Step 2: Verify draft indicators work**

- Add a behavior → Draft tag appears, Save/Discard buttons show
- Change execution policy → Draft tag appears
- Click Discard → all changes reset
- Close page with changes → "Unsaved changes" dialog appears

**Step 3: Commit**

```
feat: integrate BehaviorsDraftStore into unified draft mode
```

---

## Task 13: Items Table — New Columns

**Files:**
- Modify: `apps/opik-frontend/src/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsTab/columns.tsx` (or create if not exists)

**Step 1: Add new column definitions**

Add these columns to the items table:
- **Description** — text column showing `item.data.description` (or dedicated `description` field when BE adds it)
- **Expected behaviors** — shows count like `3 behaviors`, clickable to open item panel
- **Execution policy** — conditionally visible, shows `3 runs, 2 to pass` for items with overrides, dash for default

The execution policy column should check all items to determine if any have overrides — if none do, hide the column by default.

**Step 2: Verify columns render**

**Step 3: Commit**

```
feat: add description, expected behaviors, and execution policy columns to items table
```

---

## Summary

| Task | Component | Dependencies |
|------|-----------|-------------|
| 1 | Types & Constants | None |
| 2 | API Hooks (stubbed) | Task 1 |
| 3 | BehaviorsDraftStore | Task 1 |
| 4 | ExecutionPolicyDropdown | Task 1 |
| 5 | MetricConfigForm | Task 1 |
| 6 | AddEditBehaviorDialog | Tasks 4, 5 |
| 7 | BehaviorsSection + page integration | Tasks 2, 3, 4, 6 |
| 8 | EvaluationSuiteItemPanel shell | Task 1 |
| 9 | Item panel top section | Tasks 3, 8 |
| 10 | Item panel middle section (behaviors) | Tasks 2, 3, 6, 8 |
| 11 | Item panel bottom section (context) | Task 8 |
| 12 | Unified draft mode integration | Tasks 3, 7 |
| 13 | Items table new columns | Tasks 3, 8 |

Tasks 1-6 can be built independently. Task 7 integrates them. Tasks 8-11 build the item panel. Tasks 12-13 are integration.
