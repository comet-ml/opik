import React from "react";
import { FileText, ChartLine, Hash } from "lucide-react";
import { WidgetOption } from "@/types/dashboard";
import { WIDGET_TYPES } from "@/lib/dashboard/utils";

const WIDGET_OPTIONS: WidgetOption[] = [
  {
    id: WIDGET_TYPES.TEXT_MARKDOWN,
    title: "Text / Markdown",
    description: "Custom text content with markdown support",
    icon: <FileText className="size-5" />,
    category: "general",
    disabled: false,
  },
  {
    id: WIDGET_TYPES.CHART_METRIC,
    title: "Project Metric Chart",
    description: "Visualize project metrics over time",
    icon: <ChartLine className="size-5" />,
    category: "charts",
    disabled: false,
  },
  {
    id: WIDGET_TYPES.STAT_CARD,
    title: "Stat Card",
    description: "Display single metric value from traces or spans",
    icon: <Hash className="size-5" />,
    category: "stats",
    disabled: false,
  },
];

const CATEGORY_LABELS = {
  general: "General",
  charts: "Charts",
  stats: "Stats",
  experiments: "Experiments",
  cost: "Cost",
} as const;

interface WidgetConfigDialogAddStepProps {
  selectedWidgetType: string | null;
  onSelectWidget: (widgetType: string) => void;
}

const WidgetConfigDialogAddStep: React.FunctionComponent<
  WidgetConfigDialogAddStepProps
> = ({ selectedWidgetType, onSelectWidget }) => {
  const categories = ["general", "charts", "stats"] as const;

  return (
    <div className="max-h-[500px] overflow-auto pr-4">
      <div className="space-y-6">
        {categories.map((category) => {
          const widgets = WIDGET_OPTIONS.filter((w) => w.category === category);

          return (
            <div key={category}>
              <h3 className="mb-3 text-sm font-medium text-muted-foreground">
                {CATEGORY_LABELS[category]}
              </h3>
              <div className="space-y-2">
                {widgets.map((widget) => (
                  <button
                    key={widget.id}
                    onClick={() =>
                      !widget.disabled && onSelectWidget(widget.id)
                    }
                    disabled={widget.disabled}
                    className={`flex w-full items-start gap-3 rounded-lg border p-3 text-left transition-colors ${
                      widget.disabled
                        ? "cursor-not-allowed opacity-50"
                        : "hover:bg-accent"
                    } ${
                      selectedWidgetType === widget.id
                        ? "border-primary bg-accent"
                        : "border-border"
                    }`}
                  >
                    <div className="flex size-10 shrink-0 items-center justify-center rounded-md text-primary">
                      {widget.icon}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 text-sm font-medium">
                        {widget.title}
                        {widget.disabled && (
                          <span className="text-xs text-muted-foreground">
                            (Coming soon)
                          </span>
                        )}
                      </div>
                      <div className="text-xs text-muted-foreground">
                        {widget.description}
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default WidgetConfigDialogAddStep;
