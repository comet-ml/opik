import cloneDeep from "lodash/cloneDeep";

import {
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMJudgeSchema,
  LLMMessage,
  LLMPromptTemplate,
} from "@/types/llm";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import {
  LLM_PROMPT_CUSTOM_SPAN_TEMPLATE,
  LLM_PROMPT_CUSTOM_TRACE_TEMPLATE,
  RESERVED_SPAN_LLM_JUDGE_VARIABLES,
  RESERVED_TRACE_LLM_JUDGE_VARIABLES,
} from "@/constants/llm";

// Rules on decisions models (TypeSafe Jev). Jev answers every score as a yes/no question about the
// rendered prompt in a single call, so a rule on it takes Boolean scores, one text-only user message and
// no structure variable ({{trace}} / {{span}}), which would route it to the agentic path. The backend
// enforces the same rules on save (DecisionModelRuleValidator).

export const DECISION_MODEL_SCORE_TYPES = [LLM_SCHEMA_TYPE.BOOLEAN];

// The structure variable each scope reserves: rejected for decisions models.
export const DECISION_MODEL_FORBIDDEN_VARIABLE_BY_SCOPE: Partial<
  Record<EVALUATORS_RULE_SCOPE, string>
> = {
  [EVALUATORS_RULE_SCOPE.trace]: RESERVED_TRACE_LLM_JUDGE_VARIABLES.trace,
  [EVALUATORS_RULE_SCOPE.span]: RESERVED_SPAN_LLM_JUDGE_VARIABLES.span,
};

const DECISION_MODEL_RESERVED_VARIABLES_BY_SCOPE: Partial<
  Record<EVALUATORS_RULE_SCOPE, Readonly<Record<string, string>>>
> = {
  [EVALUATORS_RULE_SCOPE.trace]: {
    spans: RESERVED_TRACE_LLM_JUDGE_VARIABLES.spans,
  },
  [EVALUATORS_RULE_SCOPE.span]: {},
};

const DEFAULT_DECISION_MODEL_SCHEMA_BY_SCOPE: Partial<
  Record<EVALUATORS_RULE_SCOPE, LLMJudgeSchema[]>
> = {
  [EVALUATORS_RULE_SCOPE.trace]: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema,
  [EVALUATORS_RULE_SCOPE.span]: LLM_PROMPT_CUSTOM_SPAN_TEMPLATE.schema,
};

/** Reserved variables to offer in the variable mapping: the structure variable is left out. */
export const getDecisionModelReservedVariables = (
  scope: EVALUATORS_RULE_SCOPE,
): Readonly<Record<string, string>> =>
  DECISION_MODEL_RESERVED_VARIABLES_BY_SCOPE[scope] ?? {};

/**
 * Keeps the Boolean scores for a decisions model and reports the ones removed, so the switch is never
 * silent. With no Boolean score left, falls back to the scope's custom-template score.
 */
export const toDecisionModelSchema = (
  schema: LLMJudgeSchema[],
  scope: EVALUATORS_RULE_SCOPE,
): { schema: LLMJudgeSchema[]; removedScoreNames: string[] } => {
  const booleanScores = schema.filter(
    (score) => score.type === LLM_SCHEMA_TYPE.BOOLEAN,
  );
  const removedScoreNames = schema
    .filter((score) => score.type !== LLM_SCHEMA_TYPE.BOOLEAN)
    .map((score) => score.name);

  return {
    schema: booleanScores.length
      ? booleanScores
      : cloneDeep(DEFAULT_DECISION_MODEL_SCHEMA_BY_SCOPE[scope] ?? []),
    removedScoreNames,
  };
};

/** A template fits a decisions model when it has only Boolean scores and a single user message. */
export const isDecisionModelTemplate = (template: LLMPromptTemplate) =>
  template.schema.length > 0 &&
  template.schema.every((score) => score.type === LLM_SCHEMA_TYPE.BOOLEAN) &&
  hasSingleUserMessage(template.messages);

export const hasSingleUserMessage = (messages: LLMMessage[]) =>
  messages.length === 1 && messages[0].role === LLM_MESSAGE_ROLE.user;

/** Plain text, or structured content made only of text parts: no image, video or audio. */
export const isTextOnlyMessage = (message: LLMMessage) =>
  typeof message.content === "string" ||
  message.content.every((part) => part.type === "text");
