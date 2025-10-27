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
import { TriggerFormType } from "./schema";
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
    title: "New error in trace",
    description:
      "Triggered when a new error is detected in a trace within the selected projects.",
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_guardrails_triggered]: {
    title: "Guardrail triggered",
    description:
      "Triggered when a guardrail event occurs in any trace within the selected projects.",
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_feedback_score]: {
    title: "New score added to trace",
    description:
      "Triggered when a new feedback score is added to a trace in the selected projects.",
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_thread_feedback_score]: {
    title: "New score added to thread",
    description:
      "Triggered when a new feedback score is added to a thread in the selected projects.",
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
};

const getProjectIdsFromTriggerConfigs = (
  triggerConfigs?: AlertTriggerConfig[],
): string[] => {
  const projectConfig = triggerConfigs?.find(
    (config) => config.type === ALERT_TRIGGER_CONFIG_TYPE["scope:project"],
  );

  if (projectConfig) {
    const projectIds = projectConfig.config_value?.project_ids;
    if (!projectIds || projectIds.trim() === "") {
      return [];
    }
    return projectIds.split(",");
  }

  return [];
};

const createProjectScopeTriggerConfig = (
  projectIds: string[],
): AlertTriggerConfig[] => {
  if (projectIds.length === 0) return [];

  return [
    {
      type: ALERT_TRIGGER_CONFIG_TYPE["scope:project"],
      config_value: {
        project_ids: projectIds.join(","),
      },
    },
  ];
};

export const alertTriggersToFormTriggers = (
  triggers: AlertTrigger[],
  allProjectIds: string[],
): TriggerFormType[] => {
  if (!triggers || triggers.length === 0) return [];

  return triggers.map((trigger) => {
    const triggerProjectIds = getProjectIdsFromTriggerConfigs(
      trigger.trigger_configs,
    );
    return {
      eventType: trigger.event_type,
      projectIds:
        triggerProjectIds.length > 0 ? triggerProjectIds : allProjectIds,
    };
  });
};

export const formTriggersToAlertTriggers = (
  triggers: TriggerFormType[],
  allProjectIds: string[],
): AlertTrigger[] => {
  return triggers.map((trigger) => ({
    event_type: trigger.eventType,
    trigger_configs:
      trigger.projectIds.length === allProjectIds.length
        ? []
        : createProjectScopeTriggerConfig(trigger.projectIds),
  }));
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
