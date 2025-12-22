import React from "react";
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
            <button
              type="button"
              className="font-medium text-muted-gray hover:text-foreground"
              onClick={() => onSelect(variable)}
            >
              {variable}
            </button>
          </TooltipWrapper>
          {index < datasetVariables.length - 1 && ", "}
        </span>
      ))}
    </p>
  );
};

export default DatasetVariablesHint;
