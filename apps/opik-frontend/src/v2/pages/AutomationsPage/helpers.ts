import {
  AutomationTrigger,
  WorkflowOverrides,
} from "@/types/event-automations";

export type OverrideKey = keyof WorkflowOverrides;

// The overrides each recipe takes, from the backend contract. An unknown recipe falls back
// to the keys the backend filled with a default.
const RECIPE_OVERRIDES: Record<string, OverrideKey[]> = {
  collect: ["score_name", "threshold", "test_suite_name", "max_items_per_day"],
  gate: ["prompt_name", "model", "test_suite_name", "repository", "notify_url"],
  align: [
    "score_name",
    "threshold",
    "annotation_queue_name",
    "per_day",
    "n",
    "notify_url",
  ],
  improve: [
    "prompt_name",
    "model",
    "test_suite_name",
    "metric",
    "optimizer",
    "n",
    "min_gain",
    "notify_url",
  ],
};

// The backend refuses the enable without these (score_name_required and the like).
const REQUIRED_OVERRIDES: Record<string, OverrideKey[]> = {
  collect: ["score_name"],
  gate: ["prompt_name", "model"],
  align: ["score_name"],
  improve: ["prompt_name", "model"],
};

const NUMBER_OVERRIDES: OverrideKey[] = [
  "threshold",
  "per_day",
  "max_items_per_day",
  "n",
  "min_gain",
];

export const OVERRIDE_LABELS: Record<OverrideKey, string> = {
  score_name: "Judge score",
  threshold: "Threshold",
  test_suite_name: "Test suite",
  annotation_queue_name: "Annotation queue",
  per_day: "Samples per day",
  max_items_per_day: "Max items per day",
  prompt_name: "Prompt",
  model: "Model",
  repository: "GitHub repository (owner/name)",
  notify_url: "Notify URL (webhook or Slack)",
  metric: "Metric",
  optimizer: "Optimizer",
  n: "Fire every N",
  min_gain: "Minimum gain",
};

export type OverrideForm = Partial<Record<OverrideKey, string>>;

export const isNumberOverride = (key: OverrideKey) =>
  NUMBER_OVERRIDES.includes(key);

export const overrideFieldsFor = (
  recipe: string,
  defaults?: WorkflowOverrides | null,
): OverrideKey[] =>
  RECIPE_OVERRIDES[recipe] ??
  (Object.keys(defaults ?? {}) as OverrideKey[]).filter(
    (key) => defaults?.[key] != null,
  );

// The form's starting values: the recipe defaults under what a recommendation suggests.
// score_name is always left blank: a recipe never chooses the judge, the user does.
export const initialOverrideForm = (
  fields: OverrideKey[],
  defaults?: WorkflowOverrides | null,
  suggested?: WorkflowOverrides | null,
): OverrideForm => {
  const form: OverrideForm = {};
  fields.forEach((key) => {
    const value = suggested?.[key] ?? defaults?.[key];
    form[key] = key === "score_name" || value == null ? "" : String(value);
  });
  return form;
};

export const overrideFormErrors = (
  recipe: string,
  form: OverrideForm,
): Partial<Record<OverrideKey, string>> => {
  const errors: Partial<Record<OverrideKey, string>> = {};
  (REQUIRED_OVERRIDES[recipe] ?? []).forEach((key) => {
    if (!form[key]?.trim()) errors[key] = "Required";
  });
  (Object.entries(form) as [OverrideKey, string][]).forEach(([key, raw]) => {
    const value = raw.trim();
    if (value && isNumberOverride(key) && !Number.isFinite(Number(value))) {
      errors[key] = "Must be a number";
    }
  });
  return errors;
};

// What goes on the wire: blanks are left out so the recipe keeps its default.
export const toOverrides = (form: OverrideForm): WorkflowOverrides => {
  const overrides: Record<string, string | number> = {};
  (Object.entries(form) as [OverrideKey, string][]).forEach(([key, raw]) => {
    const value = raw.trim();
    if (!value) return;
    overrides[key] = isNumberOverride(key) ? Number(value) : value;
  });
  return overrides as WorkflowOverrides;
};

const scoreConditionsText = (trigger: AutomationTrigger) =>
  (trigger.conditions?.groups ?? [])
    .map((group) =>
      group.conditions
        .map((c) => `${c.score_name} ${c.operator} ${c.value}`)
        .join(" and "),
    )
    .filter(Boolean)
    .join(" or ");

// One line saying what the trigger listens to, beside its event name.
export const describeTrigger = (trigger: AutomationTrigger): string => {
  const every = trigger.n ? ` · every ${trigger.n}` : "";
  switch (trigger.event) {
    case "feedback_score:created": {
      const sample = trigger.per_day ? ` · ${trigger.per_day}/day` : "";
      return `${scoreConditionsText(trigger) || "any score"}${sample}`;
    }
    case "prompt:committed":
      return trigger.prompt_name ?? "any prompt";
    case "experiment:finished":
      return trigger.test_suite_name ?? "any suite";
    case "ci:run":
      return [trigger.repository ?? "any repository", trigger.branch]
        .filter(Boolean)
        .join("@");
    case "test_suite:grew":
      return `${
        trigger.test_suite_name ?? trigger.test_suite_id ?? "suite"
      }${every}`;
    case "annotation_queue:submitted":
      return `${
        trigger.annotation_queue_name ?? trigger.annotation_queue_id ?? "queue"
      }${every}`;
    default:
      return "";
  }
};

export const asCount = (value: unknown) =>
  typeof value === "number" && Number.isFinite(value) ? value : null;
