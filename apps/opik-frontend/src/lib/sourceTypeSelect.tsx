import React from "react";
import { ListTree, LucideIcon, SquareStack } from "lucide-react";

import { TRACE_DATA_TYPE } from "@/constants/traces";
import { DropdownOption } from "@/types/shared";
import { SelectItem } from "@/components/ui/select";

export const SOURCE_OPTIONS: DropdownOption<string>[] = [
  { value: TRACE_DATA_TYPE.traces, label: "Traces" },
  { value: TRACE_DATA_TYPE.spans, label: "Spans" },
];

const SOURCE_ICON_MAP: Record<string, { icon: LucideIcon; className: string }> =
  {
    [TRACE_DATA_TYPE.traces]: {
      icon: ListTree,
      className: "text-chart-purple",
    },
    [TRACE_DATA_TYPE.spans]: {
      icon: SquareStack,
      className: "text-chart-green",
    },
  };

export const renderSourceOption = (option: DropdownOption<string>) => {
  const config = SOURCE_ICON_MAP[option.value];
  return (
    <SelectItem
      key={option.value}
      value={option.value}
      withoutCheck
      wrapperAsChild={true}
    >
      <div className="flex w-full items-center gap-2">
        {config && (
          <config.icon className={`size-4 shrink-0 ${config.className}`} />
        )}
        {option.label}
      </div>
    </SelectItem>
  );
};

export const renderSourceTrigger = (value: string) => {
  const config = SOURCE_ICON_MAP[value];
  const option = SOURCE_OPTIONS.find((o) => o.value === value);
  return (
    <div className="flex items-center gap-1">
      {config && (
        <config.icon className={`size-4 shrink-0 ${config.className}`} />
      )}
      <span>{option?.label ?? value}</span>
    </div>
  );
};
