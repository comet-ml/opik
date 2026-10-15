import uniqid from "uniqid";
import get from "lodash/get";
import isString from "lodash/isString";
import { FieldErrors } from "react-hook-form";

import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  PythonCodeObject,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { Filter } from "@/types/filters";
import { parsePythonMethodParameters } from "@/lib/pythonArgumentsParser";
import {
  LLM_MESSAGE_ROLE,
  LLMMessage,
  MessageContent,
  TextPart,
} from "@/types/llm";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  createFilter,
  durationToMilliseconds,
  durationToSeconds,
  isFilterValid,
} from "@/lib/filters";

export const getUIRuleType = (ruleType: EVALUATORS_RULE_TYPE) =>
  ({
    [EVALUATORS_RULE_TYPE.llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.python_code]: UI_EVALUATORS_RULE_TYPE.python_code,
    [EVALUATORS_RULE_TYPE.thread_llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.thread_python_code]:
      UI_EVALUATORS_RULE_TYPE.python_code,
    [EVALUATORS_RULE_TYPE.span_llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.span_python_code]:
      UI_EVALUATORS_RULE_TYPE.python_code,
  })[ruleType];

export const getUIRuleScope = (ruleType: EVALUATORS_RULE_TYPE) =>
  ({
    [EVALUATORS_RULE_TYPE.llm_judge]: EVALUATORS_RULE_SCOPE.trace,
    [EVALUATORS_RULE_TYPE.python_code]: EVALUATORS_RULE_SCOPE.trace,
    [EVALUATORS_RULE_TYPE.thread_llm_judge]: EVALUATORS_RULE_SCOPE.thread,
    [EVALUATORS_RULE_TYPE.thread_python_code]: EVALUATORS_RULE_SCOPE.thread,
    [EVALUATORS_RULE_TYPE.span_llm_judge]: EVALUATORS_RULE_SCOPE.span,
    [EVALUATORS_RULE_TYPE.span_python_code]: EVALUATORS_RULE_SCOPE.span,
  })[ruleType];

export const getBackendRuleType = (
  scope: EVALUATORS_RULE_SCOPE,
  uiType: UI_EVALUATORS_RULE_TYPE,
) =>
  ({
    [EVALUATORS_RULE_SCOPE.trace]: {
      [UI_EVALUATORS_RULE_TYPE.llm_judge]: EVALUATORS_RULE_TYPE.llm_judge,
      [UI_EVALUATORS_RULE_TYPE.python_code]: EVALUATORS_RULE_TYPE.python_code,
    },
    [EVALUATORS_RULE_SCOPE.thread]: {
      [UI_EVALUATORS_RULE_TYPE.llm_judge]:
        EVALUATORS_RULE_TYPE.thread_llm_judge,
      [UI_EVALUATORS_RULE_TYPE.python_code]:
        EVALUATORS_RULE_TYPE.thread_python_code,
    },
    [EVALUATORS_RULE_SCOPE.span]: {
      [UI_EVALUATORS_RULE_TYPE.llm_judge]: EVALUATORS_RULE_TYPE.span_llm_judge,
      [UI_EVALUATORS_RULE_TYPE.python_code]:
        EVALUATORS_RULE_TYPE.span_python_code,
    },
  })[scope][uiType];

const getFilterTypeByField = (
  field: string,
  columns: ColumnData<unknown>[],
): string => {
  const column = columns.find((col) => col.id === field);
  return column?.type || "string";
};

const normalizeFieldName = (field: string): string => {
  if (field === "input_json") return "input";
  if (field === "output_json") return "output";
  return field;
};

export const normalizeFilters = (
  filters: Filter[],
  columns: ColumnData<unknown>[],
): Filter[] => {
  if (!filters || filters.length === 0) return [];

  return filters.map((filter) => {
    const field = normalizeFieldName(filter.field || "");
    const type = filter.type || getFilterTypeByField(field, columns);
    const value = filter.value || "";

    return {
      id: filter.id || uniqid(),
      field,
      type,
      operator: filter.operator || "",
      key: filter.key || "",
      value: type === COLUMN_TYPE.duration ? durationToSeconds(value) : value,
    };
  }) as Filter[];
};

const denormalizeFilters = (filters: Filter[]): Filter[] =>
  filters.map((filter) =>
    filter.type === COLUMN_TYPE.duration
      ? { ...filter, value: durationToMilliseconds(filter.value) }
      : filter,
  );

const isRuleFilterValid = (filter: Filter) =>
  isFilterValid(
    (filter.field === "input" || filter.field === "output") && !filter.key
      ? { ...filter, type: COLUMN_TYPE.string }
      : filter,
  );

export const createEmptyRuleFilter = (): Filter => ({
  ...createFilter(),
  id: uniqid(),
});

export const withEmptyRuleFilter = (filters: Filter[]): Filter[] =>
  filters.length ? filters : [createEmptyRuleFilter()];

export const buildRuleFilters = (filters: Filter[]): Filter[] =>
  denormalizeFilters(
    filters
      .filter(isRuleFilterValid)
      .map((filter) =>
        (filter.field === "input" || filter.field === "output") && filter.key
          ? { ...filter, field: `${filter.field}_json` }
          : filter,
      ),
  );

const getMessageText = (content: MessageContent) =>
  isString(content)
    ? content
    : content
        .filter((part): part is TextPart => part.type === "text")
        .map((part) => part.text)
        .join("\n");

const getMediaParts = (content: MessageContent) =>
  isString(content) ? [] : content.filter((part) => part.type !== "text");

export const mergeMessagesIntoPrompt = (
  messages: LLMMessage[],
): LLMMessage[] => {
  if (!messages.length) return messages;

  const text = messages
    .map((message) => getMessageText(message.content))
    .filter(Boolean)
    .join("\n\n");
  const media = messages
    .filter((message) => message.role === LLM_MESSAGE_ROLE.user)
    .flatMap((message) => getMediaParts(message.content));

  return [
    {
      ...messages[0],
      role: LLM_MESSAGE_ROLE.user,
      content: media.length
        ? [...(text ? [{ type: "text" as const, text }] : []), ...media]
        : text,
    },
  ];
};

export type PromptMergeInfo = {
  messageCount: number;
  hasSystemMedia: boolean;
};

export const getPromptMergeInfo = (
  messages: LLMMessage[],
): PromptMergeInfo | null =>
  messages.length < 2
    ? null
    : {
        messageCount: messages.length,
        hasSystemMedia: messages.some(
          (message) =>
            message.role === LLM_MESSAGE_ROLE.system &&
            getMediaParts(message.content).length > 0,
        ),
      };

export const getSavedCodeArguments = (
  details: PythonCodeObject,
): Record<string, string> => ("arguments" in details ? details.arguments : {});

const CODE_ARGUMENT_NAMES: Record<EVALUATORS_RULE_SCOPE, string[]> = {
  [EVALUATORS_RULE_SCOPE.trace]: ["input", "output", "metadata", "spans"],
  [EVALUATORS_RULE_SCOPE.span]: ["input", "output", "metadata"],
  [EVALUATORS_RULE_SCOPE.thread]: [],
};

export const getCodeArgumentNames = (scope: EVALUATORS_RULE_SCOPE) =>
  CODE_ARGUMENT_NAMES[scope];

export const getCodeArguments = (
  code: string,
  scope: EVALUATORS_RULE_SCOPE,
  savedArguments: Record<string, string> = {},
): {
  arguments: Record<string, string>;
  unknownArguments: string[];
  parsingError: boolean;
} => {
  let parameters: ReturnType<typeof parsePythonMethodParameters>;
  try {
    parameters = parsePythonMethodParameters(code, "score");
  } catch {
    return { arguments: {}, unknownArguments: [], parsingError: true };
  }

  const allowedNames = CODE_ARGUMENT_NAMES[scope];
  const codeArguments: Record<string, string> = {};
  const unknownArguments: string[] = [];

  parameters.forEach(({ name, optional }) => {
    if (savedArguments[name]) {
      codeArguments[name] = savedArguments[name];
    } else if (allowedNames.includes(name)) {
      codeArguments[name] = name;
    } else if (!optional) {
      unknownArguments.push(name);
    }
  });

  return { arguments: codeArguments, unknownArguments, parsingError: false };
};

const FIELD_ERROR_KEYS = ["message", "ref", "type"];

export const getRecordErrorKeys = (errors: FieldErrors, path: string[]) =>
  Object.keys(get(errors, path) ?? {}).filter(
    (key) => !FIELD_ERROR_KEYS.includes(key),
  );
