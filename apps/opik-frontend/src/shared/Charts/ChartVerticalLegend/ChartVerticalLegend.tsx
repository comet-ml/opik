import React from "react";
import * as RechartsPrimitive from "recharts";
import { OnChangeFn } from "@/types/shared";
import { useChart } from "@/ui/chart";
import LegendItem from "@/shared/Charts/LegendItem/LegendItem";
import type { LegendLabelAction } from "@/shared/Charts/LegendItem/LegendItem";

type ChartVerticalLegendProps = React.ComponentProps<
  typeof RechartsPrimitive.Legend
> &
  React.ComponentProps<"div"> & {
    setActiveLine: OnChangeFn<string | null>;
    chartId: string;
    labelActions?: Record<string, LegendLabelAction>;
  };

const ChartVerticalLegend = React.forwardRef<
  HTMLDivElement,
  ChartVerticalLegendProps
>(({ payload, color, setActiveLine, labelActions }, ref) => {
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
          <LegendItem
            key={key}
            itemValue={item.value ?? ""}
            displayLabel={displayLabel}
            indicatorColor={indicatorColor ?? ""}
            action={labelActions?.[displayLabel]}
            onMouseEnter={() => handleMouseEnter(item.value)}
            className="h-4 w-full pl-8"
            dotClassName="absolute left-[20px] top-[5px] shrink-0"
          />
        );
      })}
    </div>
  );
});
ChartVerticalLegend.displayName = "ChartVerticalLegend";

export default ChartVerticalLegend;
