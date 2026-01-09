import React from "react";
import { Table, ChartLine } from "lucide-react";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  useDashboardStore,
  selectHasUnsavedChanges,
} from "@/store/DashboardStore";

export enum VIEW_TYPE {
  DETAILS = "details",
  DASHBOARDS = "dashboards",
}

interface ViewSelectorProps {
  value: VIEW_TYPE;
  onChange: (value: VIEW_TYPE) => void;
}

const ViewSelector: React.FC<ViewSelectorProps> = ({ value, onChange }) => {
  const hasUnsavedChanges = useDashboardStore(selectHasUnsavedChanges);
  const disabled = value === VIEW_TYPE.DASHBOARDS && hasUnsavedChanges;

  const content = (
    <ToggleGroup
      type="single"
      value={value}
      onValueChange={(val) => !disabled && val && onChange(val as VIEW_TYPE)}
      variant="ghost"
      className="w-fit"
    >
      <ToggleGroupItem
        value={VIEW_TYPE.DETAILS}
        size="sm"
        className="gap-2"
        disabled={disabled}
      >
        <Table className="size-3" />
        Details
      </ToggleGroupItem>
      <ToggleGroupItem
        value={VIEW_TYPE.DASHBOARDS}
        size="sm"
        className="gap-2"
        disabled={disabled}
      >
        <ChartLine className="size-3" />
        Dashboards
      </ToggleGroupItem>
    </ToggleGroup>
  );

  if (disabled) {
    return (
      <TooltipWrapper content="Save or discard your changes before switching">
        <div>{content}</div>
      </TooltipWrapper>
    );
  }

  return content;
};

export default ViewSelector;
