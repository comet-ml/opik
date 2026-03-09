import React from "react";
import * as RechartsPrimitive from "recharts";
import { OnChangeFn } from "@/types/shared";
import { cn } from "@/lib/utils";
import { useChart } from "@/components/ui/chart";
import LegendItem from "@/components/shared/Charts/LegendItem/LegendItem";
import type { LegendLabelAction } from "@/components/shared/Charts/LegendItem/LegendItem";

type ChartHorizontalLegendProps = React.ComponentProps<
  typeof RechartsPrimitive.Legend
> &
  React.ComponentProps<"div"> & {
    setActiveLine: OnChangeFn<string | null>;
    chartId: string;
    containerClassName?: string;
    labelActions?: Record<string, LegendLabelAction>;
  };

const ChartHorizontalLegend = React.forwardRef<
  HTMLDivElement,
  ChartHorizontalLegendProps
>(
  (
    { payload, color, setActiveLine, containerClassName, labelActions },
    ref,
  ) => {
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
        className={cn(
          "-mt-2.5 w-full max-w-full pt-6 text-center",
          containerClassName,
        )}
        onMouseLeave={handleMouseLeave}
      >
        <div
          className={
            "group inline-flex max-h-20 max-w-full flex-wrap items-center justify-center gap-y-1 space-x-2 overflow-y-auto"
          }
        >
          {payload.map((item, idx) => {
            const key = `${item.value || "value"}`;
            const indicatorColor = color || item.color;
            const configEntry = config[item.value as string];
            const displayLabel = (configEntry?.label as string) ?? item.value;

            return (
              <LegendItem
                key={key + idx}
                itemValue={item.value ?? ""}
                displayLabel={displayLabel}
                indicatorColor={indicatorColor ?? ""}
                action={labelActions?.[displayLabel]}
                onMouseEnter={() => handleMouseEnter(item.value)}
                className="min-w-0 pl-3"
                dotClassName="absolute left-0 top-[5px] shrink-0"
              />
            );
          })}
        </div>
      </div>
    );
  },
);
ChartHorizontalLegend.displayName = "ChartHorizontalLegend";

export default ChartHorizontalLegend;
