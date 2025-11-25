import React, { useState } from "react";
import {
  ChartLine,
  ListIcon,
  ActivityIcon,
  FileText,
  DollarSign,
  BarChart3,
} from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { WIDGET_TYPES } from "@/lib/dashboard/utils";

interface AddWidgetDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAddWidget: (metricType: METRIC_NAME_TYPE, title: string) => void;
}

interface WidgetOption {
  id: METRIC_NAME_TYPE | string;
  title: string;
  description: string;
  icon: React.ReactNode;
  category: "charts" | "stats" | "content";
  disabled?: boolean;
}

const WIDGET_OPTIONS: WidgetOption[] = [
  {
    id: METRIC_NAME_TYPE.TOKEN_USAGE,
    title: "Token usage",
    description: "Total tokens used over time",
    icon: <ChartLine className="size-5" />,
    category: "charts",
  },
  {
    id: METRIC_NAME_TYPE.COST,
    title: "Estimated cost",
    description: "Total estimated cost over time",
    icon: <ChartLine className="size-5" />,
    category: "charts",
  },
  {
    id: METRIC_NAME_TYPE.FEEDBACK_SCORES,
    title: "Trace feedback scores",
    description: "Average feedback scores for traces",
    icon: <ActivityIcon className="size-5" />,
    category: "charts",
  },
  {
    id: METRIC_NAME_TYPE.TRACE_COUNT,
    title: "Number of traces",
    description: "Total traces over time",
    icon: <ListIcon className="size-5" />,
    category: "charts",
  },
  {
    id: METRIC_NAME_TYPE.TRACE_DURATION,
    title: "Trace duration",
    description: "Duration statistics (p50, p90, p99)",
    icon: <ChartLine className="size-5" />,
    category: "charts",
  },
  {
    id: METRIC_NAME_TYPE.FAILED_GUARDRAILS,
    title: "Failed guardrails",
    description: "Count of failed guardrails over time",
    icon: <ActivityIcon className="size-5" />,
    category: "charts",
  },
  {
    id: METRIC_NAME_TYPE.THREAD_COUNT,
    title: "Number of threads",
    description: "Total threads over time",
    icon: <ListIcon className="size-5" />,
    category: "charts",
  },
  {
    id: METRIC_NAME_TYPE.THREAD_DURATION,
    title: "Thread duration",
    description: "Duration statistics for threads",
    icon: <ChartLine className="size-5" />,
    category: "charts",
  },
  {
    id: METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
    title: "Thread feedback scores",
    description: "Average feedback scores for threads",
    icon: <ActivityIcon className="size-5" />,
    category: "charts",
  },
  {
    id: WIDGET_TYPES.COST_SUMMARY,
    title: "Cost Summary",
    description: "Current vs previous period cost comparison",
    icon: <DollarSign className="size-5" />,
    category: "charts",
    disabled: true,
  },
  {
    id: WIDGET_TYPES.STAT_CARD,
    title: "Stat Card",
    description: "Single metric stat card with live data",
    icon: <BarChart3 className="size-5" />,
    category: "stats",
    disabled: true,
  },
  {
    id: WIDGET_TYPES.TEXT_MARKDOWN,
    title: "Text / Markdown",
    description: "Custom text content with markdown support",
    icon: <FileText className="size-5" />,
    category: "content",
    disabled: true,
  },
];

const CATEGORY_LABELS = {
  charts: "Charts",
  stats: "Stats",
  content: "Content",
};

const AddWidgetDialog: React.FunctionComponent<AddWidgetDialogProps> = ({
  open,
  onOpenChange,
  onAddWidget,
}) => {
  const [selectedWidget, setSelectedWidget] = useState<string | null>(null);

  const handleAddWidget = () => {
    if (selectedWidget) {
      const widget = WIDGET_OPTIONS.find((w) => w.id === selectedWidget);
      if (widget && !widget.disabled) {
        onAddWidget(selectedWidget as METRIC_NAME_TYPE, widget.title);
        onOpenChange(false);
        setSelectedWidget(null);
      }
    }
  };

  const categories = ["charts", "stats", "content"] as const;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Add widget</DialogTitle>
          <DialogDescription>
            Choose a metric to add to your dashboard
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[500px] overflow-auto pr-4">
          <div className="space-y-6">
            {categories.map((category) => {
              const widgets = WIDGET_OPTIONS.filter(
                (w) => w.category === category,
              );

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
                          !widget.disabled && setSelectedWidget(widget.id)
                        }
                        disabled={widget.disabled}
                        className={`flex w-full items-start gap-3 rounded-lg border p-3 text-left transition-colors ${
                          widget.disabled
                            ? "cursor-not-allowed opacity-50"
                            : "hover:bg-accent"
                        } ${
                          selectedWidget === widget.id
                            ? "border-primary bg-accent"
                            : "border-border"
                        }`}
                      >
                        {/* eslint-disable-next-line tailwindcss/no-custom-classname */}
                        <div className="bg-primary-light flex size-10 shrink-0 items-center justify-center rounded-md text-primary">
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

        <div className="flex justify-end gap-2 pt-4">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleAddWidget} disabled={!selectedWidget}>
            Add widget
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default AddWidgetDialog;
