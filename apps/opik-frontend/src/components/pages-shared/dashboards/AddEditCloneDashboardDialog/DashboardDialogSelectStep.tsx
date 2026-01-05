import React from "react";
import { SquareDashedMousePointer } from "lucide-react";
import { TEMPLATE_LIST } from "@/lib/dashboard/templates";
import DashboardTemplateCard from "./DashboardTemplateCard";

interface DashboardDialogSelectStepProps {
  onSelect: (templateId: string) => void;
}

const DashboardDialogSelectStep: React.FunctionComponent<
  DashboardDialogSelectStepProps
> = ({ onSelect }) => {
  return (
    <div className="flex flex-col gap-4">
      {/* Template cards grid */}
      <div className="grid grid-cols-2 gap-4">
        {TEMPLATE_LIST.map((template) => (
          <DashboardTemplateCard
            key={template.id}
            name={template.name}
            description={template.description}
            icon={template.icon}
            iconColor={template.iconColor}
            interactive
            onClick={() => onSelect(template.type)}
          />
        ))}
      </div>

      <div className="flex items-center gap-2.5">
        <div className="h-px flex-1 bg-slate-200" />
        <p className="comet-body-xs text-light-slate">
          or create an empty dashboard
        </p>
        <div className="h-px flex-1 bg-slate-200" />
      </div>

      <DashboardTemplateCard
        name="Start from scratch"
        description="Start with a blank dashboard and customize it with your own widgets."
        icon={SquareDashedMousePointer}
        iconColor="text-template-icon-scratch"
        interactive
        onClick={() => onSelect("")}
      />
    </div>
  );
};

export default DashboardDialogSelectStep;
