import {
  ALERT_EVENT_TYPE,
  AlertEvent,
  ALERT_CONDITION_TYPE,
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
};

const getProjectScopeFromConditions = (
  conditions?: AlertEvent["conditions"],
): string[] => {
  const projectCondition = conditions?.find(
    (condition) => condition.type === ALERT_CONDITION_TYPE.project_scope,
  );

  if (
    projectCondition &&
    projectCondition.type === ALERT_CONDITION_TYPE.project_scope
  ) {
    return projectCondition.value || [];
  }

  return [];
};

const createProjectScopeCondition = (projectIds: string[]) => {
  if (projectIds.length === 0) return [];

  return [
    {
      type: ALERT_CONDITION_TYPE.project_scope,
      value: projectIds,
    },
  ];
};

export const alertEventsToEventTriggersObject = (
  events?: AlertEvent[],
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
  };

  if (isArray(events)) {
    events.forEach((event) => {
      const projectScope = getProjectScopeFromConditions(event.conditions);

      switch (event.event_type) {
        case ALERT_EVENT_TYPE.trace_errors:
          retVal.traceErrorNewError = true;
          retVal.traceErrorScope = projectScope;
          retVal.traceErrorScopeToggle = projectScope.length === 0;
          break;

        case ALERT_EVENT_TYPE.guardrails:
          retVal.guardrailTriggered = true;
          retVal.guardrailScope = projectScope;
          retVal.guardrailScopeToggle = projectScope.length === 0;
          break;

        case ALERT_EVENT_TYPE.trace_score:
          retVal.feedbackScoreNewTrace = true;
          retVal.feedbackScoreScope = projectScope;
          retVal.feedbackScoreScopeToggle = projectScope.length === 0;
          break;

        case ALERT_EVENT_TYPE.thread_score:
          retVal.feedbackScoreNewThread = true;
          retVal.feedbackScoreScope = projectScope;
          retVal.feedbackScoreScopeToggle = projectScope.length === 0;
          break;

        case ALERT_EVENT_TYPE.prompt_creation:
          retVal.promptLibraryNewPrompt = true;
          break;

        case ALERT_EVENT_TYPE.prompt_commit:
          retVal.promptLibraryNewCommit = true;
          break;
      }
    });
  }

  return retVal;
};

export const eventTriggersObjectToAlertEvents = (
  eventTriggers: EventTriggersObject,
): AlertEvent[] => {
  const events: AlertEvent[] = [];

  if (eventTriggers.traceErrorNewError) {
    events.push({
      event_type: ALERT_EVENT_TYPE.trace_errors,
      conditions: eventTriggers.traceErrorScopeToggle
        ? []
        : createProjectScopeCondition(eventTriggers.traceErrorScope),
    });
  }

  if (eventTriggers.guardrailTriggered) {
    events.push({
      event_type: ALERT_EVENT_TYPE.guardrails,
      conditions: eventTriggers.guardrailScopeToggle
        ? []
        : createProjectScopeCondition(eventTriggers.guardrailScope),
    });
  }

  if (eventTriggers.feedbackScoreNewTrace) {
    events.push({
      event_type: ALERT_EVENT_TYPE.trace_score,
      conditions: eventTriggers.feedbackScoreScopeToggle
        ? []
        : createProjectScopeCondition(eventTriggers.feedbackScoreScope),
    });
  }

  if (eventTriggers.feedbackScoreNewThread) {
    events.push({
      event_type: ALERT_EVENT_TYPE.thread_score,
      conditions: eventTriggers.feedbackScoreScopeToggle
        ? []
        : createProjectScopeCondition(eventTriggers.feedbackScoreScope),
    });
  }

  if (eventTriggers.promptLibraryNewPrompt) {
    events.push({
      event_type: ALERT_EVENT_TYPE.prompt_creation,
    });
  }

  if (eventTriggers.promptLibraryNewCommit) {
    events.push({
      event_type: ALERT_EVENT_TYPE.prompt_commit,
    });
  }

  return events;
};
