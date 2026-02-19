import React from "react";
import { SquareDashedMousePointer } from "lucide-react";
import usePluginsStore from "@/store/PluginsStore";
import DashboardTemplateCard from "./DashboardTemplateCard";
import DashboardTemplateItems from "./DashboardTemplateItems";

interface DashboardDialogSelectStepProps {
  onSelect: (templateId: string) => void;
}

const DashboardDialogSelectStep: React.FunctionComponent<
  DashboardDialogSelectStepProps
> = ({ onSelect }) => {
  const DashboardTemplateItemsPlugin = usePluginsStore(
    (state) => state.DashboardTemplateItems,
  );

  const templateItems = DashboardTemplateItemsPlugin ? (
    <DashboardTemplateItemsPlugin onSelect={onSelect} />
  ) : (
    <DashboardTemplateItems onSelect={onSelect} canViewExperiments />
  );

  return (
    <div className="flex flex-col gap-4">
      {templateItems}
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
