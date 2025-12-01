import React, { useMemo } from "react";
import { WIDGET_CATEGORY } from "@/types/dashboard";
import {
  useDashboardStore,
  selectWidgetResolver,
} from "@/store/DashboardStore";
import { getAllWidgetTypes } from "@/components/shared/Dashboard/widgets/widgetRegistry";

const CATEGORY_CONFIG: Record<
  WIDGET_CATEGORY,
  { label: string; description: string; gridCols: string }
> = {
  [WIDGET_CATEGORY.OBSERVABILITY]: {
    label: "Observability",
    description: "Show performance and usage metrics from your projects.",
    gridCols: "grid-cols-2",
  },
  [WIDGET_CATEGORY.EVALUATION]: {
    label: "Evaluation",
    description: "Evaluate and compare model performance across experiments.",
    gridCols: "grid-cols-2",
  },
  [WIDGET_CATEGORY.GENERAL]: {
    label: "General",
    description:
      "Add context or explanations to your dashboard using simple text or markdown.",
    gridCols: "grid-cols-1",
  },
};

interface WidgetConfigDialogAddStepProps {
  onSelectWidget: (widgetType: string) => void;
}

const WidgetConfigDialogAddStep: React.FunctionComponent<
  WidgetConfigDialogAddStepProps
> = ({ onSelectWidget }) => {
  const widgetResolver = useDashboardStore(selectWidgetResolver);

  const widgetOptions = useMemo(() => {
    if (!widgetResolver) return [];

    return getAllWidgetTypes().map((type) => {
      const components = widgetResolver(type);
      return {
        type,
        ...components.metadata,
      };
    });
  }, [widgetResolver]);

  const categories = [
    WIDGET_CATEGORY.OBSERVABILITY,
    WIDGET_CATEGORY.EVALUATION,
    WIDGET_CATEGORY.GENERAL,
  ] as const;

  return (
    <div className="flex flex-col gap-4">
      {categories.map((category) => {
        const widgets = widgetOptions.filter((w) => w.category === category);
        const categoryConfig = CATEGORY_CONFIG[category];

        if (widgets.length === 0) return null;

        return (
          <div key={category} className="flex flex-col gap-2">
            <div className="flex flex-col gap-0.5 px-0.5">
              <h3 className="text-sm font-medium text-[#373D4D]">
                {categoryConfig.label}
              </h3>
              <p className="text-sm text-slate-400">
                {categoryConfig.description}
              </p>
            </div>

            <div className={`grid ${categoryConfig.gridCols} gap-4`}>
              {widgets.map((widget) => (
                <button
                  key={widget.type}
                  onClick={() =>
                    !widget.disabled && onSelectWidget(widget.type)
                  }
                  disabled={widget.disabled}
                  className={`flex flex-col gap-1 rounded-md border border-slate-200 bg-white p-4 text-left transition-colors hover:bg-slate-50 ${
                    widget.disabled ? "cursor-not-allowed opacity-50" : ""
                  }`}
                >
                  <div className="flex h-5 items-center gap-2">
                    <div
                      className={`flex size-4 shrink-0 items-center justify-center ${widget.iconColor}`}
                    >
                      {widget.icon}
                    </div>
                    <h4 className="text-sm font-medium leading-5 text-[#373D4D]">
                      {widget.title}
                    </h4>
                  </div>
                  <p className="text-xs leading-4 text-slate-500">
                    {widget.description}
                  </p>
                </button>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default WidgetConfigDialogAddStep;
