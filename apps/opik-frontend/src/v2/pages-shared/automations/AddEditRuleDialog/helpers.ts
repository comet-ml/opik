import uniqid from "uniqid";

import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { Filter } from "@/types/filters";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { ColumnData } from "@/types/shared";

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
    return {
      id: filter.id || uniqid(),
      field,
      type: filter.type || getFilterTypeByField(field, columns),
      operator: filter.operator || "",
      key: filter.key || "",
      value: filter.value || "",
    };
  }) as Filter[];
};

export const resolveSelectedModelProvider = (
  model: PROVIDER_MODEL_TYPE,
  selectedProvider: COMPOSED_PROVIDER_TYPE | "" | undefined,
  calculateModelProvider: (
    model: PROVIDER_MODEL_TYPE,
  ) => COMPOSED_PROVIDER_TYPE | "",
): COMPOSED_PROVIDER_TYPE | "" => {
  return selectedProvider || calculateModelProvider(model);
};

export const resolveSelectedModel = (
  model: PROVIDER_MODEL_TYPE,
  selectedProvider: COMPOSED_PROVIDER_TYPE | "" | undefined,
): PROVIDER_MODEL_TYPE => {
  if (selectedProvider === PROVIDER_TYPE.VERTEX_AI && !model.includes("/")) {
    return `vertex_ai/${model}` as PROVIDER_MODEL_TYPE;
  }

  return model;
};
