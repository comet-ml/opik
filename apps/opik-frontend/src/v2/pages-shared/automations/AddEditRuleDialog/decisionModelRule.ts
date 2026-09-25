import cloneDeep from "lodash/cloneDeep";
import find from "lodash/find";
import isEqual from "lodash/isEqual";

import {
  LLM_JUDGE,
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
  LLM_PROMPT_TEMPLATES,
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

// Jev reads the prompt as the text to judge and answers each score's description as the question, so its Custom
// template holds only the data; the chat-judge templates' instructions would be noise in what Jev reads.
const DECISION_MODEL_PROMPT = "INPUT:\n{{input}}\n\nOUTPUT:\n{{output}}";

const DECISION_MODEL_TEMPLATE_BY_SCOPE: Partial<
  Record<EVALUATORS_RULE_SCOPE, LLMPromptTemplate>
> = {
  [EVALUATORS_RULE_SCOPE.trace]: {
    label: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.label,
    description:
      "Put the data Jev reads in the prompt; each score's description is its question",
    value: LLM_JUDGE.custom,
    messages: [
      {
        id: "jevTRC1",
        role: LLM_MESSAGE_ROLE.user,
        content: DECISION_MODEL_PROMPT,
      },
    ],
    variables: { input: "input", output: "output" },
    schema: [
      {
        name: "Correctness",
        description: "Does the output correctly and fully address the input?",
        type: LLM_SCHEMA_TYPE.BOOLEAN,
        unsaved: false,
      },
    ],
  },
  [EVALUATORS_RULE_SCOPE.span]: {
    label: LLM_PROMPT_CUSTOM_SPAN_TEMPLATE.label,
    description:
      "Put the data Jev reads in the prompt; each score's description is its question",
    value: LLM_JUDGE.custom,
    messages: [
      {
        id: "jevSPN1",
        role: LLM_MESSAGE_ROLE.user,
        content: DECISION_MODEL_PROMPT,
      },
    ],
    variables: { input: "input", output: "output" },
    schema: [
      {
        name: "Correctness",
        description:
          "Does the span's output correctly and fully address the span's input?",
        type: LLM_SCHEMA_TYPE.BOOLEAN,
        unsaved: false,
      },
    ],
  },
};

/** Templates offered for a decisions model: only Jev's own Custom template. */
export const getDecisionModelTemplates = (
  scope: EVALUATORS_RULE_SCOPE,
): LLMPromptTemplate[] => {
  const template = DECISION_MODEL_TEMPLATE_BY_SCOPE[scope];
  return template ? [template] : [];
};

/** Reserved variables to offer in the variable mapping: the structure variable is left out. */
export const getDecisionModelReservedVariables = (
  scope: EVALUATORS_RULE_SCOPE,
): Readonly<Record<string, string>> =>
  DECISION_MODEL_RESERVED_VARIABLES_BY_SCOPE[scope] ?? {};

/**
 * Keeps the Boolean scores for a decisions model and reports the ones removed, so the switch is never
 * silent. With no Boolean score left, falls back to the score of Jev's Custom template.
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
      : cloneDeep(DECISION_MODEL_TEMPLATE_BY_SCOPE[scope]?.schema ?? []),
    removedScoreNames,
  };
};

export const hasSingleUserMessage = (messages: LLMMessage[]) =>
  messages.length === 1 && messages[0].role === LLM_MESSAGE_ROLE.user;

/** Plain text, or structured content made only of text parts: no image, video or audio. */
export const isTextOnlyMessage = (message: LLMMessage) =>
  typeof message.content === "string" ||
  message.content.every((part) => part.type === "text");

const textOf = (message: LLMMessage) =>
  typeof message.content === "string"
    ? message.content
    : message.content
        .filter((part) => part.type === "text")
        .map((part) => ("text" in part ? part.text : ""))
        .join("\n");

/**
 * Turns text messages into the single User message Jev reads: a single message just becomes User, several are
 * joined with a blank line, which is how the backend reads them anyway. Messages with media are left as they are
 * for validation to point at.
 */
export const toDecisionModelMessages = (
  messages: LLMMessage[],
): { messages: LLMMessage[]; note?: string } => {
  if (!messages.length || hasSingleUserMessage(messages)) {
    return { messages };
  }
  if (!messages.every(isTextOnlyMessage)) {
    return { messages };
  }
  if (messages.length === 1) {
    return {
      messages: [{ ...messages[0], role: LLM_MESSAGE_ROLE.user }],
      note: "Jev reads a single User message, so the message role was changed to User.",
    };
  }
  return {
    messages: [
      {
        id: messages[0].id,
        role: LLM_MESSAGE_ROLE.user,
        content: messages.map(textOf).join("\n\n"),
      },
    ],
    note: `Jev reads a single User message, so the ${messages.length} messages were merged into one.`,
  };
};

type DecisionModelFormDetails = {
  template: LLM_JUDGE;
  messages: LLMMessage[];
  variables: Record<string, string>;
  schema: LLMJudgeSchema[];
};

// Roles are left out: Jev reads only the text, and a role change alone doesn't make the prompt the user's own.
const comparable = (details: Omit<DecisionModelFormDetails, "template">) => ({
  messages: details.messages.map(({ content }) => content),
  variables: details.variables,
  schema: details.schema.map(({ name, type, description }) => ({
    name,
    type,
    description,
  })),
});

// The form still holds a built-in template's text as loaded, so nothing the user wrote would be lost by replacing it.
const isUneditedTemplate = (
  details: DecisionModelFormDetails,
  scope: EVALUATORS_RULE_SCOPE,
) => {
  const template = find(
    LLM_PROMPT_TEMPLATES[scope],
    (t) => t.value === details.template,
  );
  return (
    Boolean(template) && isEqual(comparable(details), comparable(template!))
  );
};

/**
 * Adapts the rule form when switching to a decisions model. An unedited built-in template is replaced with Jev's own
 * Custom template; otherwise the user's prompt is kept, reduced to Boolean scores and a single User message. Returns
 * the notes to show, so the switch is never silent.
 */
export const toDecisionModelDetails = (
  details: DecisionModelFormDetails,
  scope: EVALUATORS_RULE_SCOPE,
): DecisionModelFormDetails & { notes: string[] } => {
  const decisionModelTemplate = DECISION_MODEL_TEMPLATE_BY_SCOPE[scope];
  if (decisionModelTemplate && isUneditedTemplate(details, scope)) {
    return {
      template: LLM_JUDGE.custom,
      messages: cloneDeep(decisionModelTemplate.messages),
      variables: cloneDeep(decisionModelTemplate.variables),
      schema: cloneDeep(decisionModelTemplate.schema),
      notes: [
        "Loaded Jev's Custom template: the prompt holds only the data Jev reads, and each score's description is its question.",
      ],
    };
  }

  const notes: string[] = [];
  const adaptedSchema = toDecisionModelSchema(details.schema, scope);
  if (adaptedSchema.removedScoreNames.length) {
    notes.push(
      `Jev only supports Boolean scores, so these scores were removed: ${adaptedSchema.removedScoreNames.join(
        ", ",
      )}.`,
    );
  }
  const adaptedMessages = toDecisionModelMessages(details.messages);
  if (adaptedMessages.note) {
    notes.push(adaptedMessages.note);
  }
  return {
    template: LLM_JUDGE.custom,
    messages: adaptedMessages.messages,
    variables: details.variables,
    schema: adaptedSchema.schema,
    notes,
  };
};

const CHAT_CUSTOM_TEMPLATE_BY_SCOPE: Partial<
  Record<EVALUATORS_RULE_SCOPE, LLMPromptTemplate>
> = {
  [EVALUATORS_RULE_SCOPE.trace]: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE,
  [EVALUATORS_RULE_SCOPE.span]: LLM_PROMPT_CUSTOM_SPAN_TEMPLATE,
};

/**
 * Adapts the rule form when switching from a decisions model back to a chat model: Jev's Custom template, if still
 * unedited, is replaced with the chat Custom template, whose instructions a chat judge needs. An edited prompt is
 * kept. Returns null when nothing changes.
 */
export const fromDecisionModelDetails = (
  details: DecisionModelFormDetails,
  scope: EVALUATORS_RULE_SCOPE,
): DecisionModelFormDetails | null => {
  const decisionModelTemplate = DECISION_MODEL_TEMPLATE_BY_SCOPE[scope];
  const chatTemplate = CHAT_CUSTOM_TEMPLATE_BY_SCOPE[scope];
  if (
    !decisionModelTemplate ||
    !chatTemplate ||
    !isEqual(comparable(details), comparable(decisionModelTemplate))
  ) {
    return null;
  }
  return {
    template: LLM_JUDGE.custom,
    messages: cloneDeep(chatTemplate.messages),
    variables: cloneDeep(chatTemplate.variables),
    schema: cloneDeep(chatTemplate.schema),
  };
};
