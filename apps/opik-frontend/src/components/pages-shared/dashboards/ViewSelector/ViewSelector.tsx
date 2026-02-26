import React, { useEffect } from "react";
import { Table, ChartLine } from "lucide-react";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  useDashboardStore,
  selectHasUnsavedChanges,
} from "@/store/DashboardStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { VIEW_TYPE } from "@/types/dashboard";

export interface ViewSelectorProps {
  value: VIEW_TYPE;
  onChange: (value: VIEW_TYPE) => void;
}

const ViewSelector: React.FC<ViewSelectorProps> = ({ value, onChange }) => {
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  const hasUnsavedChanges = useDashboardStore(selectHasUnsavedChanges);
  const disabled = value === VIEW_TYPE.DASHBOARDS && hasUnsavedChanges;

  useEffect(() => {
    if (value === VIEW_TYPE.DASHBOARDS && !canViewDashboards) {
      onChange(VIEW_TYPE.DETAILS);
    }
  }, [canViewDashboards, value, onChange]);

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

  if (!canViewDashboards) {
    return null;
  }

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
