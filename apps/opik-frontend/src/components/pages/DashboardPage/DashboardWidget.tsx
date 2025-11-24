import React, { useCallback, memo } from "react";
import { GripVertical, MoreVertical, Trash2 } from "lucide-react";
import { useShallow } from "zustand/react/shallow";

import { DashboardWidget as DashboardWidgetType } from "@/types/dashboard";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import { useDashboardStore } from "@/store/DashboardStore";
import { ProjectDashboardConfig } from "@/types/dashboard";
import MetricContainerChart from "../TracesPage/MetricsTab/MetricChart/MetricChartContainer";
import { INTERVAL_DESCRIPTIONS } from "../TracesPage/MetricsTab/utils";

interface DashboardWidgetProps {
  sectionId: string;
  widgetId: string;
  onDelete: () => void;
  onUpdate: (updates: Partial<DashboardWidgetType>) => void;
}

const DashboardWidget: React.FunctionComponent<DashboardWidgetProps> = ({
  sectionId,
  widgetId,
  onDelete,
}) => {
  const { projectId, interval, intervalStart, intervalEnd } = useDashboardStore(
    useShallow((state) => {
      const config = state.config as ProjectDashboardConfig | null;
      return {
        projectId: config?.projectId,
        interval: config?.interval,
        intervalStart: config?.intervalStart,
        intervalEnd: config?.intervalEnd,
      };
    }),
  );

  const widget = useDashboardStore((state) => {
    const section = state.sections.find((s) => s.id === sectionId);
    return section?.widgets.find((w) => w.id === widgetId);
  });

  const handleDelete = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onDelete();
    },
    [onDelete],
  );

  const renderChartContent = () => {
    if (!widget?.metricType || !projectId || !interval) {
      return (
        <div className="flex h-full items-center justify-center p-4 text-muted-foreground">
          No metric selected
        </div>
      );
    }

    const metricName = widget.metricType as METRIC_NAME_TYPE;
    const intervalType = interval as INTERVAL_TYPE;
    const description = interval
      ? INTERVAL_DESCRIPTIONS.TOTALS[intervalType] || ""
      : "";

    return (
      <div style={{ "--chart-height": "100%" } as React.CSSProperties}>
        <MetricContainerChart
          chartId={`${widgetId}_chart`}
          key={`${widgetId}_chart`}
          name={widget.title}
          description={description}
          metricName={metricName}
          interval={intervalType}
          intervalStart={intervalStart}
          intervalEnd={intervalEnd}
          projectId={projectId!}
          chartType="line"
        />
      </div>
    );
  };

  if (!widget) {
    return null;
  }

  return (
    <div className="h-full">
      <Card className="h-full">
        <CardHeader className="flex flex-row items-center justify-between space-y-0 p-3">
          <div className="flex items-center gap-2">
            {/* eslint-disable-next-line tailwindcss/no-custom-classname */}
            <div className="drag-handle cursor-grab text-muted-foreground hover:text-foreground active:cursor-grabbing">
              <GripVertical className="size-4" />
            </div>
          </div>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" className="size-6">
                <MoreVertical className="size-3" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem
                onClick={handleDelete}
                className="text-destructive focus:text-destructive"
              >
                <Trash2 className="mr-2 size-4" />
                Delete widget
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </CardHeader>

        <CardContent className="h-[calc(100%-60px)] overflow-hidden p-0">
          {renderChartContent()}
        </CardContent>
      </Card>
    </div>
  );
};

const arePropsEqual = (
  prev: DashboardWidgetProps,
  next: DashboardWidgetProps,
) => {
  return prev.sectionId === next.sectionId && prev.widgetId === next.widgetId;
};

export default memo(DashboardWidget, arePropsEqual);
