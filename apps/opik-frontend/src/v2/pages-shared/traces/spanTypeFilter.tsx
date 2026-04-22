import React from "react";

import { SPAN_TYPE } from "@/types/traces";
import { DropdownOption } from "@/types/shared";
import { SPAN_TYPE_LABELS_MAP } from "@/constants/traces";
import { SelectItem } from "@/ui/select";
import BaseTraceDataTypeIcon from "@/shared/BaseTraceDataTypeIcon/BaseTraceDataTypeIcon";
import { SPAN_TYPE_FILTER_COLUMN } from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/helpers";
import { Filter } from "@/types/filters";
import { createFilter } from "@/lib/filters";

export const getSpanTypeOptions = (isGuardrailsEnabled: boolean) => [
  { value: SPAN_TYPE.general, label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.general] },
  { value: SPAN_TYPE.tool, label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.tool] },
  { value: SPAN_TYPE.llm, label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.llm] },
  ...(isGuardrailsEnabled
    ? [
        {
          value: SPAN_TYPE.guardrail,
          label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.guardrail],
        },
      ]
    : []),
];

const renderSpanTypeOption = (option: DropdownOption<SPAN_TYPE>) => {
  return (
    <SelectItem
      key={option.value}
      value={option.value}
      withoutCheck
      wrapperAsChild={true}
    >
      <div className="flex w-full items-center gap-1.5">
        <BaseTraceDataTypeIcon type={option.value} />
        {option.label}
      </div>
    </SelectItem>
  );
};

export const getSpanTypeFilterConfig = (isGuardrailsEnabled: boolean) => ({
  type: {
    keyComponentProps: {
      options: getSpanTypeOptions(isGuardrailsEnabled),
      placeholder: "Select type",
      renderOption: renderSpanTypeOption,
    },
  },
});

/**
 * Predicate to check if a filter is a tool span filter.
 */
const isToolFilter = (filter: Filter): boolean => {
  return (
    filter.field === SPAN_TYPE_FILTER_COLUMN.id &&
    filter.value === SPAN_TYPE.tool
  );
};

export const manageToolFilter = (
  currentFilters: Filter[] | null | undefined,
  shouldFilter: boolean,
): Filter[] => {
  const filters = currentFilters || [];
  const hasToolFilter = filters.some(isToolFilter);

  if (shouldFilter && !hasToolFilter) {
    return [
      ...filters,
      createFilter({
        id: SPAN_TYPE_FILTER_COLUMN.id,
        field: SPAN_TYPE_FILTER_COLUMN.id,
        type: SPAN_TYPE_FILTER_COLUMN.type,
        operator: "=",
        value: SPAN_TYPE.tool,
      }),
    ];
  }

  if (!shouldFilter && hasToolFilter) {
    return filters.filter((filter) => !isToolFilter(filter));
  }

  return filters;
};
