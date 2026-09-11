import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { GuardrailNamesLabelMap } from "@/constants/guardrails";
import { GuardrailTypes } from "@/types/guardrails";
import { WINDOW_LABEL_BY_VALUE } from "./constants";
import { TriggerFormType } from "./schema";

// The column holds 255, but a name that long is unreadable in the alerts
// table. Cap well below it and leave room for a " (2)" uniqueness suffix.
export const MAX_ALERT_NAME_LENGTH = 80;

// Short forms of TRIGGER_CONFIG titles: the generated name appends the
// trigger's own thresholds, so "Trace errors threshold > 5" would stutter.
const TRIGGER_NAME_LABEL: Record<ALERT_EVENT_TYPE, string> = {
  [ALERT_EVENT_TYPE.trace_errors]: "Trace errors",
  [ALERT_EVENT_TYPE.trace_cost]: "Cost",
  [ALERT_EVENT_TYPE.trace_latency]: "Latency",
  [ALERT_EVENT_TYPE.trace_feedback_score]: "Trace feedback score",
  [ALERT_EVENT_TYPE.trace_thread_feedback_score]: "Thread feedback score",
  [ALERT_EVENT_TYPE.trace_guardrails_triggered]: "Guardrail triggered",
  [ALERT_EVENT_TYPE.prompt_created]: "New prompt added",
  [ALERT_EVENT_TYPE.prompt_committed]: "New prompt version",
  [ALERT_EVENT_TYPE.prompt_deleted]: "Prompt deleted",
  [ALERT_EVENT_TYPE.experiment_finished]: "Experiment finished",
};

const SIMPLE_THRESHOLD_TRIGGERS = new Set<ALERT_EVENT_TYPE>([
  ALERT_EVENT_TYPE.trace_cost,
  ALERT_EVENT_TYPE.trace_latency,
  ALERT_EVENT_TYPE.trace_errors,
]);

const FEEDBACK_SCORE_TRIGGERS = new Set<ALERT_EVENT_TYPE>([
  ALERT_EVENT_TYPE.trace_feedback_score,
  ALERT_EVENT_TYPE.trace_thread_feedback_score,
]);

const truncate = (value: string, max: number) =>
  value.length <= max ? value : `${value.slice(0, Math.max(0, max - 1))}…`;

const windowLabel = (window?: string) =>
  window ? WINDOW_LABEL_BY_VALUE[window] : undefined;

// Describes one trigger using whatever the user has filled in so far, so the
// name stays sensible while a freshly added trigger is still blank.
export const buildTriggerNameFragment = (trigger: TriggerFormType): string => {
  const label = TRIGGER_NAME_LABEL[trigger.eventType] ?? trigger.eventType;

  if (SIMPLE_THRESHOLD_TRIGGERS.has(trigger.eventType)) {
    const threshold = trigger.threshold?.trim();
    if (!threshold) return label;

    const inWindow = windowLabel(trigger.window);
    return inWindow
      ? `${label} > ${threshold} in ${inWindow}`
      : `${label} > ${threshold}`;
  }

  if (FEEDBACK_SCORE_TRIGGERS.has(trigger.eventType)) {
    const conditions = (trigger.groups ?? []).flatMap(
      (group) => group.conditions ?? [],
    );
    const [first] = conditions;
    const scoreName = first?.name?.trim();
    const threshold = first?.threshold?.trim();

    if (!scoreName) return label;

    // A feedback score name is user-defined and can be long on its own.
    const named = `${label}: ${truncate(scoreName, 32)}`;
    const described =
      first.operator && threshold
        ? `${named} ${first.operator} ${threshold}`
        : named;

    const extra = conditions.length - 1;
    return extra > 0 ? `${described} +${extra}` : described;
  }

  if (trigger.eventType === ALERT_EVENT_TYPE.trace_guardrails_triggered) {
    const types = trigger.guardrailTypes ?? [];
    if (!types.length) return label;

    const names = types
      .map((type) => GuardrailNamesLabelMap[type as GuardrailTypes])
      .filter(Boolean)
      .map((name) => name.replace(/ guardrail$/, ""));

    return names.length ? `${label} (${names.join(", ")})` : label;
  }

  return label;
};

// Names the alert after its triggers. The first trigger carries the detail;
// the rest are summarised so the name stays scannable.
export const buildAlertName = (triggers: TriggerFormType[]): string => {
  if (!triggers.length) return "";

  const [first, ...rest] = triggers;
  const base = buildTriggerNameFragment(first);
  const suffix = rest.length ? ` +${rest.length} more` : "";

  return `${truncate(base, MAX_ALERT_NAME_LENGTH - suffix.length)}${suffix}`;
};

// Appends " (2)", " (3)", ... when the workspace already has an alert with
// this name, trimming the base so the result still fits the cap.
export const ensureUniqueAlertName = (
  name: string,
  existingNames: string[],
): string => {
  if (!name) return name;

  const taken = new Set(
    existingNames.map((existing) => existing.trim().toLowerCase()),
  );
  if (!taken.has(name.toLowerCase())) return name;

  for (let counter = 2; counter < 1000; counter += 1) {
    const suffix = ` (${counter})`;
    const candidate = `${truncate(
      name,
      MAX_ALERT_NAME_LENGTH - suffix.length,
    )}${suffix}`;

    if (!taken.has(candidate.toLowerCase())) return candidate;
  }

  return name;
};
