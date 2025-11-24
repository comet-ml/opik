import React from "react";
import { Plus, LineChart } from "lucide-react";

interface DashboardWidgetGridEmptyProps {
  onAddWidget: () => void;
}

const DashboardWidgetGridEmpty: React.FunctionComponent<
  DashboardWidgetGridEmptyProps
> = ({ onAddWidget }) => {
  return (
    <div className="flex min-h-[320px] items-center justify-center rounded-md border border-dashed border-muted bg-background">
      <div className="flex flex-col items-center gap-1 px-4 py-2">
        <div className="pb-1">
          <LineChart className="size-4 text-light-slate" />
        </div>
        <p className="text-sm font-medium text-foreground">No widgets yet</p>
        <p className="pb-1 text-center text-sm text-muted-slate">
          Add widgets to monitor key metrics.
        </p>
        <button
          onClick={() => onAddWidget()}
          className="flex items-center gap-1 text-sm text-primary hover:underline"
        >
          <Plus className="size-3.5" />
          Add widget
        </button>
      </div>
    </div>
  );
};

export default DashboardWidgetGridEmpty;
