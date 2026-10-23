import React, { useMemo } from "react";

import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  CUSTOM_FILTER_VALIDATION_REGEXP,
  OPERATORS_MAP,
} from "@/constants/filters";
import { Filter, FilterOperator } from "@/types/filters";
import {
  COLUMN_CUSTOM_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  DropdownOption,
} from "@/types/shared";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import TracesOrSpansPathsAutocomplete from "@/v2/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/v2/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import { getTagsFilterConfig } from "@/v2/pages-shared/TagsAutocomplete/tagsFilterConfig";
import { getSpanTypeFilterConfig } from "@/v2/pages-shared/traces/spanTypeFilter";

type KeyComponent = React.FC<unknown> & {
  placeholder: string;
  value: string;
  onValueChange: (value: string) => void;
};

const RULE_DICTIONARY_OPERATORS: DropdownOption<FilterOperator>[] = [
  ...(OPERATORS_MAP[COLUMN_TYPE.dictionary] || []),
  { label: "is empty", value: "is_empty" },
  { label: "is not empty", value: "is_not_empty" },
];

const useRuleFiltersConfig = (
  scope: EVALUATORS_RULE_SCOPE,
  projectId: string,
) => {
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  return useMemo(() => {
    const isTraceScope = scope === EVALUATORS_RULE_SCOPE.trace;
    const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
    const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;
    const dataType = isSpanScope
      ? TRACE_DATA_TYPE.spans
      : TRACE_DATA_TYPE.traces;

    const pathFilter = (rootKey: "input" | "output") => ({
      keyComponent: TracesOrSpansPathsAutocomplete as KeyComponent,
      keyComponentProps: {
        rootKeys: [rootKey],
        projectId,
        type: TRACE_DATA_TYPE.traces,
        placeholder: "key (optional)",
        excludeRoot: true,
      },
      operators: RULE_DICTIONARY_OPERATORS,
      defaultOperator: "contains" as FilterOperator,
    });

    return {
      rowsMap: {
        [COLUMN_METADATA_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as KeyComponent,
          keyComponentProps: {
            rootKeys: ["metadata"],
            projectId,
            type: dataType,
            placeholder: "key",
            excludeRoot: true,
          },
          operators: RULE_DICTIONARY_OPERATORS,
        },
        ...(isTraceScope
          ? { input: pathFilter("input"), output: pathFilter("output") }
          : {}),
        [COLUMN_CUSTOM_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as KeyComponent,
          keyComponentProps: {
            rootKeys: ["input", "output"],
            projectId,
            type: dataType,
            placeholder: "key",
            excludeRoot: false,
          },
          operators: RULE_DICTIONARY_OPERATORS,
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return `Key is invalid, it should be "input", "output", and follow this format: "input.[PATH]" For example: "input.message" or just "input" for the whole object`;
            }
          },
        },
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: TracesOrSpansFeedbackScoresSelect as KeyComponent,
          keyComponentProps: {
            projectId,
            type: dataType,
            placeholder: "Select score",
          },
        },
        ...getTagsFilterConfig({
          projectId,
          entityType: isThreadScope
            ? "threads"
            : isSpanScope
              ? "spans"
              : "traces",
        }),
        ...(isSpanScope ? getSpanTypeFilterConfig(isGuardrailsEnabled) : {}),
      },
    };
  }, [scope, projectId, isGuardrailsEnabled]);
};

export default useRuleFiltersConfig;
