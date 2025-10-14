import {
  ALERT_EVENT_TYPE,
  AlertTrigger,
  ALERT_TRIGGER_CONFIG_TYPE,
  AlertTriggerConfig,
} from "@/types/alerts";
import { TriggerFormType } from "./schema";

export interface TriggerConfig {
  title: string;
  description: string;
  hasScope: boolean;
}

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
      "Triggered when a new prompt is created or saved in the workspace's prompt library",
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
