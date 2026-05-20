import cloneDeep from "lodash/cloneDeep";
import get from "lodash/get";
import set from "lodash/set";
import isEmpty from "lodash/isEmpty";
import isString from "lodash/isString";
import isNil from "lodash/isNil";
import { WebhookIcon } from "lucide-react";

import {
  ALERT_EVENT_TYPE,
  ALERT_TYPE,
  AlertTrigger,
  ALERT_TRIGGER_CONFIG_TYPE,
  AlertTriggerConfig,
  Alert,
} from "@/types/alerts";
import {
  TriggerFormType,
  FeedbackScoreConditionType,
  FeedbackScoreConditionGroupType,
} from "./schema";
import SlackIcon from "@/icons/slack.svg?react";
import PagerDutyIcon from "@/icons/pagerduty.svg?react";

export interface TriggerConfig {
  title: string;
  description: string;
  hasScope: boolean;
}

export const ALERT_TYPE_LABELS: Record<ALERT_TYPE, string> = {
  [ALERT_TYPE.general]: "General",
  [ALERT_TYPE.slack]: "Slack",
  [ALERT_TYPE.pagerduty]: "PagerDuty",
};

export const ALERT_TYPE_ICONS = {
  [ALERT_TYPE.general]: WebhookIcon,
  [ALERT_TYPE.slack]: SlackIcon,
  [ALERT_TYPE.pagerduty]: PagerDutyIcon,
};

export const TRIGGER_CONFIG: Record<ALERT_EVENT_TYPE, TriggerConfig> = {
  [ALERT_EVENT_TYPE.trace_errors]: {
    title: "Trace errors threshold",
    description:
      "Triggered when the number of trace errors exceeds the specified threshold in selected projects.",
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_guardrails_triggered]: {
    title: "Guardrail triggered",
    description:
      "Triggered when a guardrail event occurs in any trace within the selected projects.",
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_feedback_score]: {
    title: "Trace feedback score threshold",
    description:
      "Triggered when the average feedback score for traces exceeds the specified threshold in selected projects.",
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_thread_feedback_score]: {
    title: "Thread feedback score threshold",
    description:
      "Triggered when the average feedback score for threads exceeds the specified threshold in selected projects.",
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.prompt_created]: {
    title: "New prompt added",
    description:
      "Triggered when a new prompt is created or saved in the workspace's prompt library.",
    hasScope: false,
  },
  [ALERT_EVENT_TYPE.prompt_committed]: {
    title: "New prompt version created",
    description:
      "Triggered when a new commit (version) is added to any prompt in the workspace's prompt library.",
    hasScope: false,
  },
  [ALERT_EVENT_TYPE.prompt_deleted]: {
    title: "Prompt deleted",
    description:
      "Triggered when a prompt is removed from the workspace's prompt library.",
    hasScope: false,
  },
  [ALERT_EVENT_TYPE.experiment_finished]: {
    title: "Experiment finished",
    description: "Triggered when an experiment completes in the workspace.",
    hasScope: false,
  },
  [ALERT_EVENT_TYPE.trace_cost]: {
    title: "Cost threshold",
    description:
      "Triggered when total trace cost exceeds the specified threshold in the selected projects.",
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_latency]: {
    title: "Latency threshold",
    description:
      "Triggered when average trace latency exceeds the specified threshold in the selected projects.",
    hasScope: true,
  },
};

const SIMPLE_THRESHOLD_CONFIG_TYPE: Partial<
  Record<ALERT_EVENT_TYPE, ALERT_TRIGGER_CONFIG_TYPE>
> = {
  [ALERT_EVENT_TYPE.trace_cost]: ALERT_TRIGGER_CONFIG_TYPE["threshold:cost"],
  [ALERT_EVENT_TYPE.trace_latency]:
    ALERT_TRIGGER_CONFIG_TYPE["threshold:latency"],
  [ALERT_EVENT_TYPE.trace_errors]:
    ALERT_TRIGGER_CONFIG_TYPE["threshold:errors"],
};

const getThresholdFromTriggerConfigs = (
  configType: ALERT_TRIGGER_CONFIG_TYPE,
  triggerConfigs?: AlertTriggerConfig[],
): {
  threshold?: string;
  window?: string;
  name?: string;
  operator?: string;
} => {
  const thresholdConfig = triggerConfigs?.find(
    (config) => config.type === configType,
  );

  if (thresholdConfig?.config_value) {
    return {
      threshold: thresholdConfig.config_value.threshold,
      window: thresholdConfig.config_value.window,
      name: thresholdConfig.config_value.name,
      operator: thresholdConfig.config_value.operator,
    };
  }

  return {};
};

const getAllThresholdConditionGroupsFromTriggerConfigs = (
  configType: ALERT_TRIGGER_CONFIG_TYPE,
  triggerConfigs?: AlertTriggerConfig[],
): FeedbackScoreConditionGroupType[] => {
  if (!triggerConfigs) return [];

  const matching = triggerConfigs.filter(
    (config) => config.type === configType,
  );

  // Bucket by group_index. Configs without a group_index land in their own
  // group (preserves the pre-grouping "implicit OR" semantics).
  const groupBuckets = new Map<string, FeedbackScoreConditionType[]>();
  let fallbackKey = 0;

  matching.forEach((config) => {
    const rawOperator = config.config_value?.operator;
    const condition: FeedbackScoreConditionType = {
      threshold: config.config_value?.threshold || "",
      window: config.config_value?.window || "",
      name: config.config_value?.name || "",
      operator: rawOperator === "<" ? "<" : ">",
    };

    if (!condition.threshold || !condition.window || !condition.name) {
      return;
    }

    const key =
      config.group_index === null || config.group_index === undefined
        ? `__ungrouped_${fallbackKey++}`
        : `g_${config.group_index}`;

    const bucket = groupBuckets.get(key) ?? [];
    bucket.push(condition);
    groupBuckets.set(key, bucket);
  });

  return Array.from(groupBuckets.values()).map((conditions) => ({
    conditions,
  }));
};

type ThresholdConfigInput = {
  configType: ALERT_TRIGGER_CONFIG_TYPE;
  threshold?: string;
  window?: string;
  name?: string;
  operator?: string;
  groupIndex?: number;
};

const createThresholdTriggerConfig = ({
  configType,
  threshold,
  window,
  name,
  operator,
  groupIndex,
}: ThresholdConfigInput): AlertTriggerConfig[] => {
  if (!threshold || !window) return [];

  const config_value: Record<string, string> = { threshold, window };
  if (name) config_value.name = name;
  if (operator) config_value.operator = operator;

  const config: AlertTriggerConfig = { type: configType, config_value };
  if (groupIndex !== undefined) config.group_index = groupIndex;

  return [config];
};

export const alertTriggersToFormTriggers = (
  triggers: AlertTrigger[],
): TriggerFormType[] => {
  if (!triggers || triggers.length === 0) return [];

  return triggers.map((trigger) => {
    const simpleThresholdType =
      SIMPLE_THRESHOLD_CONFIG_TYPE[trigger.event_type];
    const thresholdData = simpleThresholdType
      ? getThresholdFromTriggerConfigs(
          simpleThresholdType,
          trigger.trigger_configs,
        )
      : {};

    const groups: FeedbackScoreConditionGroupType[] =
      trigger.event_type === ALERT_EVENT_TYPE.trace_feedback_score ||
      trigger.event_type === ALERT_EVENT_TYPE.trace_thread_feedback_score
        ? getAllThresholdConditionGroupsFromTriggerConfigs(
            ALERT_TRIGGER_CONFIG_TYPE["threshold:feedback_score"],
            trigger.trigger_configs,
          )
        : [];

    return {
      eventType: trigger.event_type,
      ...thresholdData,
      ...(groups.length > 0 ? { groups } : {}),
    };
  });
};

export const formTriggersToAlertTriggers = (
  triggers: TriggerFormType[],
): AlertTrigger[] => {
  return triggers.map((trigger) => {
    const configs: AlertTriggerConfig[] = [];

    const simpleThresholdType = SIMPLE_THRESHOLD_CONFIG_TYPE[trigger.eventType];
    if (simpleThresholdType) {
      configs.push(
        ...createThresholdTriggerConfig({
          configType: simpleThresholdType,
          threshold: trigger.threshold,
          window: trigger.window,
        }),
      );
    } else if (
      trigger.eventType === ALERT_EVENT_TYPE.trace_feedback_score ||
      trigger.eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score
    ) {
      // Flatten groups into threshold configs with group_index.
      // Same group_index = AND; different = OR.
      trigger.groups?.forEach((group, groupIndex) => {
        group.conditions.forEach((condition) => {
          configs.push(
            ...createThresholdTriggerConfig({
              configType: ALERT_TRIGGER_CONFIG_TYPE["threshold:feedback_score"],
              threshold: condition.threshold,
              window: condition.window,
              name: condition.name,
              operator: condition.operator,
              groupIndex,
            }),
          );
        });
      });
    }

    return {
      event_type: trigger.eventType,
      trigger_configs: configs,
    };
  });
};

// Field mapping configuration for webhook examples
export interface FieldMapping {
  sourceField: string; // Path to field in alert object (e.g., 'name' or 'metadata.routing_key')
  targetPath: string; // Path to replace in webhook example (e.g., 'alert_name' or 'payload.routing_key')
  fallbackValue?: string; // Optional fallback if field is empty
}

type AlertTypeMappings = {
  [key in ALERT_TYPE]: FieldMapping[];
};

export const ALERT_FIELD_MAPPINGS: AlertTypeMappings = {
  [ALERT_TYPE.general]: [
    {
      sourceField: "name",
      targetPath: "alert_name",
    },
  ],
  [ALERT_TYPE.pagerduty]: [
    {
      sourceField: "name",
      targetPath: "payload.summary",
    },
    {
      sourceField: "metadata.routing_key",
      targetPath: "routing_key",
    },
  ],
  [ALERT_TYPE.slack]: [
    {
      sourceField: "name",
      targetPath: "blocks[0].text.text",
    },
  ],
};

/**
 * Checks if a value is considered valid for field replacement.
 * A value is invalid if it's null, undefined, empty, or a whitespace-only string.
 */
function isValidFieldValue(value: unknown): boolean {
  // Use lodash isNil to check for null/undefined
  if (isNil(value)) {
    return false;
  }

  // Use lodash isEmpty for strings, arrays, objects
  if (isEmpty(value)) {
    return false;
  }

  // Use lodash isString and check for whitespace-only strings
  if (isString(value) && value.trim() === "") {
    return false;
  }

  return true;
}

export function applyFieldReplacements(
  examplePayload: unknown,
  alert: Partial<Alert>,
  alertType: ALERT_TYPE,
): unknown {
  const mappings = ALERT_FIELD_MAPPINGS[alertType];

  // Use lodash isEmpty to check if mappings array is empty
  if (isEmpty(mappings)) {
    return examplePayload;
  }

  try {
    // Use lodash cloneDeep to avoid mutating original
    const payload = cloneDeep(examplePayload) as Record<string, unknown>;

    mappings.forEach((mapping) => {
      try {
        // Use lodash get to safely retrieve nested values
        const sourceValue = get(alert, mapping.sourceField);

        if (isValidFieldValue(sourceValue)) {
          // Use lodash set to safely set nested values (supports dot and bracket notation)
          set(payload, mapping.targetPath, sourceValue);
        } else if (!isNil(mapping.fallbackValue)) {
          set(payload, mapping.targetPath, mapping.fallbackValue);
        }
        // If source value is invalid and no fallback, keep original example value
      } catch (error) {
        console.error(
          `Failed to apply field mapping for ${mapping.sourceField} -> ${mapping.targetPath}:`,
          error,
        );
      }
    });

    return payload;
  } catch (error) {
    console.error(
      "Failed to apply field replacements to webhook example:",
      error,
    );
    return examplePayload;
  }
}
