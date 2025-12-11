import React from "react";
import { ChartLine, SquareDashedMousePointer, Activity } from "lucide-react";
import { TEMPLATE_ID } from "@/types/dashboard";
import {
  DASHBOARD_TEMPLATES,
  TEMPLATE_OPTIONS_ORDER,
} from "@/lib/dashboard/templates";

interface DashboardDialogSelectStepProps {
  onSelect: (templateId: string) => void;
}

const TEMPLATE_ICONS: Record<TEMPLATE_ID, React.ReactNode> = {
  [TEMPLATE_ID.PROJECT_METRICS]: (
    <ChartLine className="size-4 text-[#5899DA]" />
  ),
  [TEMPLATE_ID.PERFORMANCE]: <Activity className="size-4 text-[#EF6868]" />,
};

const DashboardDialogSelectStep: React.FunctionComponent<
  DashboardDialogSelectStepProps
> = ({ onSelect }) => {
  return (
    <div className="flex flex-col gap-4">
      {/* Template cards grid */}
      <div className="grid grid-cols-2 gap-4">
        {TEMPLATE_OPTIONS_ORDER.map((templateId) => {
          const template = DASHBOARD_TEMPLATES[templateId];
          const icon = TEMPLATE_ICONS[templateId];

          return (
            <button
              key={templateId}
              onClick={() => onSelect(templateId)}
              className="flex flex-col gap-1 rounded-md border border-border bg-background p-4 text-left transition-colors hover:border-primary hover:bg-muted"
            >
              <div className="flex h-5 items-center gap-2">
                <div className="flex size-4 shrink-0 items-center justify-center">
                  {icon}
                </div>
                <h4 className="comet-body-s-accented text-foreground">
                  {template.title}
                </h4>
              </div>
              <p className="comet-body-xs text-muted-slate">
                {template.description}
              </p>
            </button>
          );
        })}
      </div>

      {/* Separator */}
      <div className="flex items-center gap-2.5">
        <div className="h-px flex-1 bg-slate-200" />
        <p className="comet-body-xs text-light-slate">
          or create an empty dashboard
        </p>
        <div className="h-px flex-1 bg-slate-200" />
      </div>

      {/* Empty dashboard option */}
      <button
        onClick={() => onSelect("")}
        className="flex h-20 items-center gap-1 rounded-md border border-border bg-background p-4 text-left transition-colors hover:border-primary hover:bg-muted"
      >
        <div className="flex flex-1 flex-col gap-1">
          <div className="flex items-center gap-2">
            <div className="flex size-4 shrink-0 items-center justify-center">
              <SquareDashedMousePointer className="size-4 text-[#945FCF]" />
            </div>
            <h4 className="comet-body-s-accented text-foreground">
              Start from scratch
            </h4>
          </div>
          <p className="comet-body-xs text-muted-slate">
            Start with a blank dashboard and customize it with your own widgets.
          </p>
        </div>
      </button>
    </div>
  );
};

export default DashboardDialogSelectStep;
