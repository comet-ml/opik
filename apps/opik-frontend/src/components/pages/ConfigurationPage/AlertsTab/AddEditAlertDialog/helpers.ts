import {
  ALERT_EVENT_TYPE,
  AlertTrigger,
  ALERT_TRIGGER_CONFIG_TYPE,
  AlertTriggerConfig,
} from "@/types/alerts";
import isArray from "lodash/isArray";

export type EventTriggersObject = {
  traceErrorNewError: boolean;
  traceErrorScope: string[];
  traceErrorScopeToggle: boolean;

  guardrailTriggered: boolean;
  guardrailScope: string[];
  guardrailScopeToggle: boolean;

  feedbackScoreNewTrace: boolean;
  feedbackScoreNewThread: boolean;
  feedbackScoreScope: string[];
  feedbackScoreScopeToggle: boolean;

  promptLibraryNewPrompt: boolean;
  promptLibraryNewCommit: boolean;
  promptLibraryDeleted: boolean;
};

const getProjectScopeFromTriggerConfigs = (
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

export const alertTriggersToEventTriggersObject = (
  triggers?: AlertTrigger[],
): EventTriggersObject => {
  const retVal: EventTriggersObject = {
    traceErrorNewError: false,
    traceErrorScope: [],
    traceErrorScopeToggle: true,
    guardrailTriggered: false,
    guardrailScope: [],
    guardrailScopeToggle: true,
    feedbackScoreNewTrace: false,
    feedbackScoreNewThread: false,
    feedbackScoreScope: [],
    feedbackScoreScopeToggle: true,
    promptLibraryNewPrompt: false,
    promptLibraryNewCommit: false,
    promptLibraryDeleted: false,
  };

  if (isArray(triggers)) {
    triggers.forEach((trigger) => {
      const projectScope = getProjectScopeFromTriggerConfigs(
        trigger.trigger_configs,
      );

      switch (trigger.event_type) {
        case ALERT_EVENT_TYPE["trace:errors"]:
          retVal.traceErrorNewError = true;
          retVal.traceErrorScope = projectScope;
          retVal.traceErrorScopeToggle = projectScope.length === 0;
          break;

        case ALERT_EVENT_TYPE["span:guardrails_triggered"]:
          retVal.guardrailTriggered = true;
          retVal.guardrailScope = projectScope;
          retVal.guardrailScopeToggle = projectScope.length === 0;
          break;

        case ALERT_EVENT_TYPE["trace:feedback_score"]:
          retVal.feedbackScoreNewTrace = true;
          retVal.feedbackScoreScope = projectScope;
          retVal.feedbackScoreScopeToggle = projectScope.length === 0;
          break;

        case ALERT_EVENT_TYPE["trace_thread:feedback_score"]:
          retVal.feedbackScoreNewThread = true;
          retVal.feedbackScoreScope = projectScope;
          retVal.feedbackScoreScopeToggle = projectScope.length === 0;
          break;

        case ALERT_EVENT_TYPE["prompt:created"]:
          retVal.promptLibraryNewPrompt = true;
          break;

        case ALERT_EVENT_TYPE["prompt:committed"]:
          retVal.promptLibraryNewCommit = true;
          break;

        case ALERT_EVENT_TYPE["prompt:deleted"]:
          retVal.promptLibraryDeleted = true;
          break;
      }
    });
  }

  return retVal;
};

export const eventTriggersObjectToAlertTriggers = (
  eventTriggers: EventTriggersObject,
): AlertTrigger[] => {
  const triggers: AlertTrigger[] = [];

  if (eventTriggers.traceErrorNewError) {
    triggers.push({
      event_type: ALERT_EVENT_TYPE["trace:errors"],
      trigger_configs: eventTriggers.traceErrorScopeToggle
        ? []
        : createProjectScopeTriggerConfig(eventTriggers.traceErrorScope),
    });
  }

  if (eventTriggers.guardrailTriggered) {
    triggers.push({
      event_type: ALERT_EVENT_TYPE["span:guardrails_triggered"],
      trigger_configs: eventTriggers.guardrailScopeToggle
        ? []
        : createProjectScopeTriggerConfig(eventTriggers.guardrailScope),
    });
  }

  if (eventTriggers.feedbackScoreNewTrace) {
    triggers.push({
      event_type: ALERT_EVENT_TYPE["trace:feedback_score"],
      trigger_configs: eventTriggers.feedbackScoreScopeToggle
        ? []
        : createProjectScopeTriggerConfig(eventTriggers.feedbackScoreScope),
    });
  }

  if (eventTriggers.feedbackScoreNewThread) {
    triggers.push({
      event_type: ALERT_EVENT_TYPE["trace_thread:feedback_score"],
      trigger_configs: eventTriggers.feedbackScoreScopeToggle
        ? []
        : createProjectScopeTriggerConfig(eventTriggers.feedbackScoreScope),
    });
  }

  if (eventTriggers.promptLibraryNewPrompt) {
    triggers.push({
      event_type: ALERT_EVENT_TYPE["prompt:created"],
    });
  }

  if (eventTriggers.promptLibraryNewCommit) {
    triggers.push({
      event_type: ALERT_EVENT_TYPE["prompt:committed"],
    });
  }

  if (eventTriggers.promptLibraryDeleted) {
    triggers.push({
      event_type: ALERT_EVENT_TYPE["prompt:deleted"],
    });
  }

  return triggers;
};
