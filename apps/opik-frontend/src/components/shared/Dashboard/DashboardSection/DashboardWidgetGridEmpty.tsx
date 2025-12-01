import React from "react";
import { Plus, LineChart } from "lucide-react";
import { Button } from "@/components/ui/button";

interface DashboardWidgetGridEmptyProps {
  onAddWidget: () => void;
}

const DashboardWidgetGridEmpty: React.FunctionComponent<
  DashboardWidgetGridEmptyProps
> = ({ onAddWidget }) => {
  return (
    <div className="grid grid-cols-3 gap-4">
      <div className="flex min-h-[317px] items-center justify-center rounded-md border border-dashed border-border bg-background">
        <div className="flex flex-col items-center gap-1 px-4 py-2">
          <div className="pb-1">
            <LineChart className="size-4 text-light-slate" />
          </div>
          <p className="text-sm font-medium text-foreground">No widgets yet</p>
          <p className="pb-1 text-center text-sm text-muted-slate">
            Add widgets to monitor key metrics.
          </p>
          <Button
            variant="link"
            size="sm"
            onClick={() => onAddWidget()}
            className="gap-1"
          >
            <Plus className="size-3.5" />
            Add widget
          </Button>
        </div>
      </div>
      <div className="min-h-[317px] rounded-md border border-dashed border-border bg-background" />
      <div className="min-h-[317px] rounded-md border border-dashed border-border bg-background" />
    </div>
  );
};

export default DashboardWidgetGridEmpty;
