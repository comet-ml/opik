import uniqid from "uniqid";

import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import {
  RESERVED_SPAN_EVALUATOR_VARIABLES,
  RESERVED_TRACE_EVALUATOR_VARIABLES,
} from "@/constants/llm";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
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
  // input/output accept a bare value with no key, which the dictionary rules would reject
  isFilterValid(
    (filter.field === "input" || filter.field === "output") && !filter.key
      ? { ...filter, type: COLUMN_TYPE.string }
      : filter,
  );

// The rule payload's filters, built from form state: drop incomplete rows, address a keyed
// input/output at its JSON column, and convert to the units the backend evaluates in.
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

/**
 * The reserved-variable set a Python-metric editor must pass for {@code scope}.
 *
 * <p>Span scope gets the empty {@link RESERVED_SPAN_EVALUATOR_VARIABLES}: a span has no
 * sub-spans, and {@code PythonCodeDetailsSpanFormSchema} accepts only
 * {@code input}/{@code output}/{@code metadata} paths — so auto-filling the trace
 * {@code spans} sentinel would fail validation on a row the sentinel filter hides from
 * the mapping list, leaving the dialog unsubmittable with nothing visible to correct.
 * Thread scope has no argument mapping at all, so the value is unused there.
 */
export const reservedPythonMetricVariablesForScope = (
  scope: EVALUATORS_RULE_SCOPE,
): Readonly<Record<string, string>> =>
  scope === EVALUATORS_RULE_SCOPE.span
    ? RESERVED_SPAN_EVALUATOR_VARIABLES
    : RESERVED_TRACE_EVALUATOR_VARIABLES;
