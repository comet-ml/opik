import React from "react";
import { ScrollText, Settings, Table } from "lucide-react";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

export enum OPTIMIZATION_VIEW_TYPE {
  LOGS = "logs",
  TRIALS = "trials",
  CONFIGURATION = "configuration",
}

interface OptimizationViewSelectorProps {
  value: OPTIMIZATION_VIEW_TYPE;
  onChange: (value: OPTIMIZATION_VIEW_TYPE) => void;
}

const OptimizationViewSelector: React.FC<OptimizationViewSelectorProps> = ({
  value,
  onChange,
}) => {
  return (
    <ToggleGroup
      type="single"
      value={value}
      onValueChange={(val) => val && onChange(val as OPTIMIZATION_VIEW_TYPE)}
      variant="ghost"
      className="w-fit"
    >
      <ToggleGroupItem
        value={OPTIMIZATION_VIEW_TYPE.LOGS}
        size="sm"
        className="gap-2"
      >
        <ScrollText className="size-3" />
        Logs
      </ToggleGroupItem>
      <ToggleGroupItem
        value={OPTIMIZATION_VIEW_TYPE.TRIALS}
        size="sm"
        className="gap-2"
      >
        <Table className="size-3" />
        Trials
      </ToggleGroupItem>
      <ToggleGroupItem
        value={OPTIMIZATION_VIEW_TYPE.CONFIGURATION}
        size="sm"
        className="gap-2"
      >
        <Settings className="size-3" />
        Configuration
      </ToggleGroupItem>
    </ToggleGroup>
  );
};

export default OptimizationViewSelector;
