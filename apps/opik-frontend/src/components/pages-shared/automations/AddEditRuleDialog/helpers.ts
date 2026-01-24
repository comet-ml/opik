import uniqid from "uniqid";

import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  UI_EVALUATORS_RULE_TYPE,
  PythonCodeObject,
} from "@/types/automations";
import { Filter } from "@/types/filters";
import { ColumnData } from "@/types/shared";

/**
 * Determines the UI rule type from the backend rule type and optionally the code object.
 * For Python code rules, we need to check if it's a common metric by looking at the code.
 */
export const getUIRuleType = (
  ruleType: EVALUATORS_RULE_TYPE,
  code?: unknown,
): UI_EVALUATORS_RULE_TYPE => {
  // Check if this is a Python code rule that's actually a common metric
  const isPythonCodeType =
    ruleType === EVALUATORS_RULE_TYPE.python_code ||
    ruleType === EVALUATORS_RULE_TYPE.thread_python_code ||
    ruleType === EVALUATORS_RULE_TYPE.span_python_code;

  if (isPythonCodeType && code) {
    const pythonCode = code as PythonCodeObject;
    if ("common_metric_id" in pythonCode && pythonCode.common_metric_id) {
      return UI_EVALUATORS_RULE_TYPE.common_metric;
    }
  }

  return {
    [EVALUATORS_RULE_TYPE.llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.python_code]: UI_EVALUATORS_RULE_TYPE.python_code,
    [EVALUATORS_RULE_TYPE.thread_llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.thread_python_code]:
      UI_EVALUATORS_RULE_TYPE.python_code,
    [EVALUATORS_RULE_TYPE.span_llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.span_python_code]:
      UI_EVALUATORS_RULE_TYPE.python_code,
  }[ruleType];
};

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
      // Common metrics use the same backend type as Python code
      [UI_EVALUATORS_RULE_TYPE.common_metric]: EVALUATORS_RULE_TYPE.python_code,
    },
    [EVALUATORS_RULE_SCOPE.thread]: {
      [UI_EVALUATORS_RULE_TYPE.llm_judge]:
        EVALUATORS_RULE_TYPE.thread_llm_judge,
      [UI_EVALUATORS_RULE_TYPE.python_code]:
        EVALUATORS_RULE_TYPE.thread_python_code,
      // Common metrics use the same backend type as Python code
      [UI_EVALUATORS_RULE_TYPE.common_metric]:
        EVALUATORS_RULE_TYPE.thread_python_code,
    },
    [EVALUATORS_RULE_SCOPE.span]: {
      [UI_EVALUATORS_RULE_TYPE.llm_judge]: EVALUATORS_RULE_TYPE.span_llm_judge,
      [UI_EVALUATORS_RULE_TYPE.python_code]:
        EVALUATORS_RULE_TYPE.span_python_code,
      // Common metrics use the same backend type as Python code
      [UI_EVALUATORS_RULE_TYPE.common_metric]:
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

export const normalizeFilters = (
  filters: Filter[],
  columns: ColumnData<unknown>[],
): Filter[] => {
  if (!filters || filters.length === 0) return [];

  return filters.map((filter) => ({
    id: filter.id || uniqid(),
    field: filter.field || "",
    type: filter.type || getFilterTypeByField(filter.field, columns),
    operator: filter.operator || "",
    key: filter.key || "",
    value: filter.value || "",
  })) as Filter[];
};
