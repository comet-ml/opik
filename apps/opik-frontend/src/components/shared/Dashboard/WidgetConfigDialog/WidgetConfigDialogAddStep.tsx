import React, { useMemo } from "react";
import { WIDGET_CATEGORY } from "@/types/dashboard";
import {
  useDashboardStore,
  selectWidgetResolver,
} from "@/store/DashboardStore";
import { getAllWidgetTypes } from "@/components/shared/Dashboard/widgets/widgetRegistry";

const CATEGORY_CONFIG: Record<
  WIDGET_CATEGORY,
  { label: string; gridCols: string }
> = {
  [WIDGET_CATEGORY.OBSERVABILITY]: {
    label: "Observability",
    gridCols: "grid-cols-2",
  },
  [WIDGET_CATEGORY.EVALUATION]: {
    label: "Evaluation",
    gridCols: "grid-cols-2",
  },
  [WIDGET_CATEGORY.GENERAL]: {
    label: "General",
    gridCols: "grid-cols-2",
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

  const categories = Object.values(WIDGET_CATEGORY);

  return (
    <div className="flex flex-col gap-4">
      {categories.map((category) => {
        const widgets = widgetOptions.filter((w) => w.category === category);
        const categoryConfig = CATEGORY_CONFIG[category];

        if (widgets.length === 0) return null;

        return (
          <div key={category} className="flex flex-col gap-2">
            <div className="flex flex-col gap-0.5 px-0.5">
              <h3 className="text-sm font-medium text-foreground">
                {categoryConfig.label}
              </h3>
            </div>

            <div className={`grid ${categoryConfig.gridCols} gap-4`}>
              {widgets.map((widget) => (
                <button
                  key={widget.type}
                  onClick={() =>
                    !widget.disabled && onSelectWidget(widget.type)
                  }
                  disabled={widget.disabled}
                  className={`flex flex-col gap-1 rounded-md border bg-background p-4 text-left transition-colors hover:bg-accent ${
                    widget.disabled ? "cursor-not-allowed opacity-50" : ""
                  }`}
                >
                  <div className="flex h-5 items-center gap-2">
                    <div
                      className={`flex size-4 shrink-0 items-center justify-center ${widget.iconColor}`}
                    >
                      {widget.icon}
                    </div>
                    <h4 className="text-sm font-medium leading-5 text-foreground">
                      {widget.title}
                    </h4>
                  </div>
                  <p className="text-xs leading-4 text-muted-foreground">
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
