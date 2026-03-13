import React from "react";

import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import { formatBlueprintValue } from "@/utils/agent-configurations";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import BlueprintTypeIcon from "./BlueprintTypeIcon";
import BlueprintValuePrompt from "./BlueprintValuePrompt";

const renderValue = (v: BlueprintValue) => {
  if (v.type === BlueprintValueType.PROMPT) {
    return <BlueprintValuePrompt key={v.value} value={v} />;
  }

  return (
    <div className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground">
      {formatBlueprintValue(v)}
    </div>
  );
};

type BlueprintValuesListProps = {
  values: BlueprintValue[];
};

const BlueprintValuesList: React.FC<BlueprintValuesListProps> = ({
  values,
}) => (
  <div className="flex flex-col divide-y">
    {values.map((v) => (
      <div key={v.key} className="flex flex-col gap-2 py-4">
        <div className="flex items-center gap-2">
          <BlueprintTypeIcon type={v.type} />
          <span className="comet-body-s-accented text-foreground">{v.key}</span>
        </div>
        {v.description && (
          <TooltipWrapper content={v.description}>
            <span className="comet-body-xs w-fit max-w-full truncate text-light-slate">
              {v.description}
            </span>
          </TooltipWrapper>
        )}
        <div className="overflow-hidden">{renderValue(v)}</div>
      </div>
    ))}
  </div>
);

export default BlueprintValuesList;
