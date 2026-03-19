import React from "react";
import isArray from "lodash/isArray";

import { detectConfigValueType } from "@/lib/configuration-renderer";
import PromptDiff from "@/components/shared/CodeDiff/PromptDiff";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import { Tag } from "@/components/ui/tag";
import ToolsDiff from "@/components/shared/CodeDiff/ToolsDiff";
import { formatValue } from "./configDiffUtils";

type DiffSectionProps = {
  label: string;
  baselineValue: unknown;
  currentValue: unknown;
};

const DiffSection: React.FunctionComponent<DiffSectionProps> = ({
  label,
  baselineValue,
  currentValue,
}) => {
  const type = detectConfigValueType(label, currentValue ?? baselineValue);
  const baseStr = formatValue(baselineValue);
  const currStr = formatValue(currentValue);
  const hasChanged = baseStr !== currStr;
  const isAdded = baselineValue == null && currentValue != null;
  const isRemoved = baselineValue != null && currentValue == null;

  if (!hasChanged) return null;

  return (
    <div className="rounded-md border p-3">
      <div className="mb-2 flex items-center gap-2">
        <span className="comet-body-s-accented">{label}</span>
        {isAdded && (
          <Tag variant="green" size="sm">
            Added
          </Tag>
        )}
        {isRemoved && (
          <Tag variant="red" size="sm">
            Removed
          </Tag>
        )}
        {!isAdded && !isRemoved && (
          <Tag variant="orange" size="sm">
            Changed
          </Tag>
        )}
      </div>
      <div className="comet-code whitespace-pre-wrap break-words text-sm">
        {type === "prompt" ? (
          <PromptDiff baseline={baselineValue} current={currentValue} />
        ) : type === "tools" ? (
          <ToolsDiff
            baseline={isArray(baselineValue) ? baselineValue : []}
            current={isArray(currentValue) ? currentValue : []}
          />
        ) : type === "json_object" ? (
          <TextDiff content1={baseStr} content2={currStr} mode="lines" />
        ) : (
          <TextDiff content1={baseStr} content2={currStr} mode="words" />
        )}
      </div>
    </div>
  );
};

export default DiffSection;
