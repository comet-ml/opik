import React from "react";
import { Plus, LineChart } from "lucide-react";
import { Button } from "@/ui/button";
import useAskOllieForWidget from "@/shared/Dashboard/useAskOllieForWidget";
import IconTag from "@/shared/IconTag/IconTag";
import { ADD_WIDGET_OPTIONS } from "./addWidgetOptions";

interface DashboardWidgetGridEmptyProps {
  onAddWidget: () => void;
  readOnly?: boolean;
}

const DashboardWidgetGridEmpty: React.FunctionComponent<
  DashboardWidgetGridEmptyProps
> = ({ onAddWidget, readOnly }) => {
  const { isOllieAvailable, askOllie } = useAskOllieForWidget();

  const renderActions = () => {
    if (readOnly) return null;

    if (!isOllieAvailable) {
      return (
        <Button
          variant="link"
          size="sm"
          onClick={() => onAddWidget()}
          className="gap-1"
        >
          <Plus className="size-3.5" />
          Add widget
        </Button>
      );
    }

    return (
      <div className="flex w-full max-w-[320px] flex-col gap-2 pt-2">
        {ADD_WIDGET_OPTIONS.map(
          ({
            value,
            icon,
            iconClassName,
            iconColorClassName,
            label,
            description,
          }) => (
            <button
              key={value}
              type="button"
              onClick={() =>
                value === "add" ? onAddWidget() : askOllie("empty_state")
              }
              className="flex flex-col gap-1 rounded-lg border border-border bg-background px-3 py-2.5 text-left transition-colors hover:border-primary hover:bg-toggle-outline-active"
            >
              <div className="flex items-center gap-1.5">
                <IconTag
                  icon={icon}
                  className={iconClassName}
                  iconClassName={iconColorClassName}
                />
                <span className="comet-body-s-accented">{label}</span>
              </div>
              <span className="comet-body-xs text-light-slate">
                {description}
              </span>
            </button>
          ),
        )}
      </div>
    );
  };

  return (
    <div className="grid grid-cols-3 gap-4">
      <div className="flex min-h-[317px] items-center justify-center rounded-md border border-dashed border-border bg-background">
        <div className="flex w-full flex-col items-center gap-1 px-4 py-2">
          <div className="pb-1">
            <LineChart className="size-4 text-light-slate" />
          </div>
          <p className="text-sm font-medium text-foreground">No widgets yet</p>
          <p className="pb-1 text-center text-sm text-muted-slate">
            Add widgets to monitor key metrics.
          </p>
          {renderActions()}
        </div>
      </div>
      <div className="min-h-[317px] rounded-md border border-dashed border-border bg-background" />
      <div className="min-h-[317px] rounded-md border border-dashed border-border bg-background" />
    </div>
  );
};

export default DashboardWidgetGridEmpty;
