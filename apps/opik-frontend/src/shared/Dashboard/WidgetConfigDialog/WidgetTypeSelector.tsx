import React, { useMemo } from "react";
import { Label } from "@/ui/label";
import CardSelector, { CardOption } from "@/shared/CardSelector/CardSelector";
import {
  useDashboardStore,
  selectWidgetResolver,
  selectRuntimeConfig,
} from "@/store/DashboardStore";
import { getWidgetTypesForDashboard } from "@/lib/dashboard/utils";
import { applyWidgetPermissions } from "@/lib/dashboard/permissions";
import { usePermissions } from "@/contexts/PermissionsContext";

interface WidgetTypeSelectorProps {
  selectedType?: string;
  onSelect: (type: string) => void;
}

const WidgetTypeSelector: React.FC<WidgetTypeSelectorProps> = ({
  selectedType,
  onSelect,
}) => {
  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const runtimeConfig = useDashboardStore(selectRuntimeConfig);
  const { permissions } = usePermissions();

  const options = useMemo<CardOption[]>(() => {
    if (!widgetResolver) return [];

    return getWidgetTypesForDashboard(runtimeConfig.dashboardType).map(
      (type) => {
        const baseComponents = widgetResolver(type);
        const components = applyWidgetPermissions(
          baseComponents,
          type,
          permissions,
        );
        const { metadata } = components;

        return {
          value: type,
          label: metadata.title,
          icon: metadata.icon,
          iconColor: metadata.iconColor,
          disabled: metadata.disabled,
          disabledTooltip: metadata.disabledTooltip,
        };
      },
    );
  }, [widgetResolver, permissions, runtimeConfig.dashboardType]);

  return (
    <div className="space-y-2">
      <Label>Widget type</Label>
      <CardSelector
        value={selectedType ?? ""}
        onChange={onSelect}
        options={options}
      />
    </div>
  );
};

export default WidgetTypeSelector;
