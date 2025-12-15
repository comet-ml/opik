import React from "react";
import { SquareDashedMousePointer } from "lucide-react";
import { TEMPLATE_LIST } from "@/lib/dashboard/templates";

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
        {TEMPLATE_LIST.map((template) => {
          const Icon = template.icon;
          return (
            <button
              key={template.id}
              onClick={() => onSelect(template.type)}
              className="flex flex-col gap-1 rounded-md border border-border bg-background p-4 text-left transition-colors hover:border-primary hover:bg-muted"
            >
              <div className="flex h-5 items-center gap-2">
                <div className="flex size-4 shrink-0 items-center justify-center">
                  <Icon className={template.iconColor} />
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

      <div className="flex items-center gap-2.5">
        <div className="h-px flex-1 bg-slate-200" />
        <p className="comet-body-xs text-light-slate">
          or create an empty dashboard
        </p>
        <div className="h-px flex-1 bg-slate-200" />
      </div>

      <button
        onClick={() => onSelect("")}
        className="flex h-20 items-center gap-1 rounded-md border border-border bg-background p-4 text-left transition-colors hover:border-primary hover:bg-muted"
      >
        <div className="flex flex-1 flex-col gap-1">
          <div className="flex items-center gap-2">
            <div className="flex size-4 shrink-0 items-center justify-center">
              <SquareDashedMousePointer className="text-template-icon-scratch" />
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
