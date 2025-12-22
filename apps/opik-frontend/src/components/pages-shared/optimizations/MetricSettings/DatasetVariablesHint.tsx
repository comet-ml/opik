import React from "react";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface DatasetVariablesHintProps {
  datasetVariables: string[];
  onSelect: (variable: string) => void;
}

const DatasetVariablesHint: React.FC<DatasetVariablesHintProps> = ({
  datasetVariables,
  onSelect,
}) => {
  if (datasetVariables.length === 0) {
    return null;
  }

  return (
    <p className="text-xs text-light-slate">
      Available:{" "}
      {datasetVariables.map((variable, index) => (
        <span key={variable}>
          <TooltipWrapper content="Click to use">
            <Button
              variant="minimal"
              size="3xs"
              onClick={() => onSelect(variable)}
              className="px-0 underline"
            >
              {variable}
            </Button>
          </TooltipWrapper>
          {index < datasetVariables.length - 1 && ", "}
        </span>
      ))}
    </p>
  );
};

export default DatasetVariablesHint;
