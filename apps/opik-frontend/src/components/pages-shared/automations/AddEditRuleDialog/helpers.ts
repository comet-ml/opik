import uniqid from "uniqid";

import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { Filter } from "@/types/filters";
import { ColumnData, COLUMN_CUSTOM_ID, COLUMN_TYPE } from "@/types/shared";

export const getUIRuleType = (ruleType: EVALUATORS_RULE_TYPE) =>
  ({
    [EVALUATORS_RULE_TYPE.llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.python_code]: UI_EVALUATORS_RULE_TYPE.python_code,
    [EVALUATORS_RULE_TYPE.thread_llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.thread_python_code]:
      UI_EVALUATORS_RULE_TYPE.python_code,
    [EVALUATORS_RULE_TYPE.span_llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
  })[ruleType];

export const getUIRuleScope = (ruleType: EVALUATORS_RULE_TYPE) =>
  ({
    [EVALUATORS_RULE_TYPE.llm_judge]: EVALUATORS_RULE_SCOPE.trace,
    [EVALUATORS_RULE_TYPE.python_code]: EVALUATORS_RULE_SCOPE.trace,
    [EVALUATORS_RULE_TYPE.thread_llm_judge]: EVALUATORS_RULE_SCOPE.thread,
    [EVALUATORS_RULE_TYPE.thread_python_code]: EVALUATORS_RULE_SCOPE.thread,
    [EVALUATORS_RULE_TYPE.span_llm_judge]: EVALUATORS_RULE_SCOPE.span,
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
      // Python code is not supported for span scope
      [UI_EVALUATORS_RULE_TYPE.python_code]: EVALUATORS_RULE_TYPE.span_llm_judge,
    },
  })[scope][uiType];

const getFilterTypeByField = (
  field: string,
  columns: ColumnData<unknown>[],
): string => {
  const column = columns.find((col) => col.id === field);
  return column?.type || "string";
};

export const normalizeFilters = (
  filters: Filter[],
  columns: ColumnData<unknown>[],
): Filter[] => {
  if (!filters || filters.length === 0) return [];

  return filters.map((filter) => {
    let field = filter.field || "";
    let key = filter.key || "";
    let type = filter.type || getFilterTypeByField(filter.field, columns);

    // Map input_json/output_json to COLUMN_CUSTOM_ID for span filters
    // Backend stores input_json/output_json when filtering on nested JSON paths
    if (field === "input_json" || field === "output_json") {
      const baseField = field === "input_json" ? "input" : "output";
      // If there's a key, map to COLUMN_CUSTOM_ID
      if (key) {
        field = COLUMN_CUSTOM_ID;
        // The key should be the full path (e.g., "context" or "input.context")
        // If key doesn't start with input/output prefix, add it
        if (!key.startsWith("input.") && !key.startsWith("output.")) {
          key = `${baseField}.${key}`;
        }
        type = COLUMN_TYPE.dictionary;
      } else {
        // No key means filtering on the whole input/output as string
        field = baseField;
        type = COLUMN_TYPE.string;
      }
    }

    return {
      id: filter.id || uniqid(),
      field,
      type,
      operator: filter.operator || "",
      key,
      value: filter.value || "",
    } as Filter;
  });
};
