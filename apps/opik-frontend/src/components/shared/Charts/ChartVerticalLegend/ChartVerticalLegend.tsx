import React from "react";
import * as RechartsPrimitive from "recharts";
import { cn } from "@/lib/utils";
import { OnChangeFn } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ColorIndicator from "@/components/shared/ColorIndicator/ColorIndicator";
import { useChart } from "@/components/ui/chart";

type ChartVerticalLegendProps = React.ComponentProps<
  typeof RechartsPrimitive.Legend
> &
  React.ComponentProps<"div"> & {
    setActiveLine: OnChangeFn<string | null>;
    chartId: string;
  };

const ChartVerticalLegend = React.forwardRef<
  HTMLDivElement,
  ChartVerticalLegendProps
>(({ payload, color, setActiveLine }, ref) => {
  const { config } = useChart();

  const handleMouseEnter = (id: string) => {
    setActiveLine(id);
  };

  const handleMouseLeave = () => {
    setActiveLine(null);
  };

  if (!payload?.length) {
    return null;
  }

  return (
    <div
      ref={ref}
      className="group -mt-2.5 flex max-h-full w-full flex-col items-start gap-1 overflow-y-auto overflow-x-hidden"
      onMouseLeave={handleMouseLeave}
    >
      {payload.map((item) => {
        const key = `${item.value || "value"}`;
        const indicatorColor = color || item.color;
        const configEntry = config[item.value as string];
        const displayLabel = (configEntry?.label as string) ?? item.value;

        return (
          <div
            key={key}
            className={cn(
              "h-4 w-full pl-8 relative cursor-pointer pb-1 group-hover-except-self:opacity-60 duration-200",
            )}
            onMouseEnter={() => handleMouseEnter(item.value)}
          >
            <TooltipWrapper content={displayLabel}>
              <div className="comet-body-xs truncate font-light text-foreground">
                {displayLabel}
              </div>
            </TooltipWrapper>
            <ColorIndicator
              label={item.value ?? ""}
              color={indicatorColor ?? ""}
              variant="dot"
              className="absolute left-[20px] top-[5px] shrink-0"
            />
          </div>
        );
      })}
    </div>
  );
});
ChartVerticalLegend.displayName = "ChartVerticalLegend";

export default ChartVerticalLegend;
