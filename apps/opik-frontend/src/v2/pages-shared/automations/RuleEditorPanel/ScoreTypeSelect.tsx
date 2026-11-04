import React from "react";
import { ChartBarBig, Hash, LucideIcon, ToggleRight } from "lucide-react";

import SelectBox from "@/shared/SelectBox/SelectBox";
import { SelectItem } from "@/ui/select";
import { LLM_SCHEMA_TYPE } from "@/types/llm";

const SCORE_TYPE_OPTIONS: {
  value: LLM_SCHEMA_TYPE;
  label: string;
  icon: LucideIcon;
}[] = [
  { value: LLM_SCHEMA_TYPE.INTEGER, label: "Integer", icon: Hash },
  { value: LLM_SCHEMA_TYPE.DOUBLE, label: "Number", icon: ChartBarBig },
  { value: LLM_SCHEMA_TYPE.BOOLEAN, label: "Boolean", icon: ToggleRight },
];

const renderTypeLabel = (type: string) => {
  const option = SCORE_TYPE_OPTIONS.find((o) => o.value === type);
  if (!option) return null;
  const Icon = option.icon;

  return (
    <div className="flex min-w-0 items-center gap-1">
      <span className="flex size-4 shrink-0 items-center justify-center">
        <Icon className="size-3.5 text-light-slate" />
      </span>
      <span className="truncate">{option.label}</span>
    </div>
  );
};

type ScoreTypeSelectProps = {
  value: LLM_SCHEMA_TYPE;
  onChange: (value: LLM_SCHEMA_TYPE) => void;
  className?: string;
};

const ScoreTypeSelect: React.FC<ScoreTypeSelectProps> = ({
  value,
  onChange,
  className,
}) => (
  <SelectBox
    value={value}
    onChange={onChange}
    options={SCORE_TYPE_OPTIONS}
    className={className}
    renderTrigger={renderTypeLabel}
    renderOption={(option) => (
      <SelectItem key={option.value} value={option.value}>
        {renderTypeLabel(option.value)}
      </SelectItem>
    )}
  />
);

export default ScoreTypeSelect;
