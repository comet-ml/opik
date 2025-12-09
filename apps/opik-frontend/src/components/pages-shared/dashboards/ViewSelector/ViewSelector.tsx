import React from "react";
import { Table, ChartLine } from "lucide-react";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

export enum VIEW_TYPE {
  OUTPUTS = "outputs",
  DASHBOARDS = "dashboards",
}

interface ViewSelectorProps {
  value: VIEW_TYPE;
  onChange: (value: VIEW_TYPE) => void;
}

const ViewSelector: React.FC<ViewSelectorProps> = ({ value, onChange }) => {
  return (
    <ToggleGroup
      type="single"
      value={value}
      onValueChange={(val) => val && onChange(val as VIEW_TYPE)}
      variant="ghost"
      className="w-fit"
    >
      <ToggleGroupItem value={VIEW_TYPE.OUTPUTS} size="sm" className="gap-2">
        <Table className="size-3 text-[var(--chart-icon-turquoise)]" />
        Outputs
      </ToggleGroupItem>
      <ToggleGroupItem value={VIEW_TYPE.DASHBOARDS} size="sm" className="gap-2">
        <ChartLine className="size-3 text-[var(--chart-icon-pink)]" />
        Dashboards
      </ToggleGroupItem>
    </ToggleGroup>
  );
};

export default ViewSelector;
