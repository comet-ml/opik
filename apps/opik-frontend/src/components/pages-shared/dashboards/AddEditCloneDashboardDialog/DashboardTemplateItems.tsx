import React from "react";
import { TEMPLATE_LIST } from "@/lib/dashboard/templates";
import { TEMPLATE_SCOPE } from "@/types/dashboard";
import { DISABLED_EXPERIMENTS_TOOLTIP } from "@/components/shared/Dashboard/widgets/widgetRegistry";
import DashboardTemplateCard from "./DashboardTemplateCard";

export interface DashboardTemplateItemsProps {
  onSelect: (templateId: string) => void;
}

const DashboardTemplateItems: React.FC<
  DashboardTemplateItemsProps & { canViewExperiments: boolean }
> = ({ canViewExperiments, onSelect }) => {
  return (
    <div className="grid grid-cols-2 gap-4">
      {TEMPLATE_LIST.map((template) => {
        const isExperimentTemplate =
          template.scope === TEMPLATE_SCOPE.EXPERIMENTS;
        const isDisabled = isExperimentTemplate && !canViewExperiments;

        return (
          <DashboardTemplateCard
            key={template.id}
            name={template.name}
            description={template.description}
            icon={template.icon}
            iconColor={template.iconColor}
            disabled={isDisabled}
            disabledTooltip={DISABLED_EXPERIMENTS_TOOLTIP}
            interactive
            onClick={() => onSelect(template.type)}
          />
        );
      })}
    </div>
  );
};

export default DashboardTemplateItems;
