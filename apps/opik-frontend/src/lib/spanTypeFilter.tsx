import React from "react";

import { SPAN_TYPE } from "@/types/traces";
import { DropdownOption } from "@/types/shared";
import { SPAN_TYPE_LABELS_MAP } from "@/constants/traces";
import { SelectItem } from "@/components/ui/select";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";

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
