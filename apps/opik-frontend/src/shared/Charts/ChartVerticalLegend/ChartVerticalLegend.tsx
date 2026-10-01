import React from "react";
import * as RechartsPrimitive from "recharts";
import { OnChangeFn } from "@/types/shared";
import { useChart } from "@/ui/chart";
import { cn } from "@/lib/utils";
import LegendItem from "@/shared/Charts/LegendItem/LegendItem";
import type { LegendLabelAction } from "@/shared/Charts/LegendItem/LegendItem";

// Only the scrollbar thumb is hidden at rest (the legend stays visible, readable by
// assistive tech and keyboard-scrollable); it's painted while the legend or an ancestor
// `group/chart` is hovered, or the legend has keyboard focus. The scrollbar always
// exists, so nothing reflows. Safari doesn't repaint it on :hover, so it stays hidden there.
const LEGEND_SCROLLBAR_CLASSES = cn(
  // `!` beats the theme-wide .dark / .comet-custom-scrollbar 16px scrollbar rules.
  "[&::-webkit-scrollbar-thumb]:!rounded-full [&::-webkit-scrollbar-thumb]:!border-0 [&::-webkit-scrollbar-track]:!bg-transparent [&::-webkit-scrollbar]:!w-2 [&::-webkit-scrollbar]:!bg-transparent",
  "[&::-webkit-scrollbar-thumb]:!bg-transparent group-hover/chart:[&::-webkit-scrollbar-thumb]:!bg-[var(--scrollbar-thumb)] [&:focus-visible::-webkit-scrollbar-thumb]:!bg-[var(--scrollbar-thumb)] [&:hover::-webkit-scrollbar-thumb]:!bg-[var(--scrollbar-thumb)]",
  // Firefox only: in Chrome these standard properties disable the ::-webkit-scrollbar
  // styling above and fall back to macOS overlay scrollbars.
  "[@supports(-moz-appearance:none)]:[scrollbar-color:transparent_transparent] [@supports(-moz-appearance:none)]:[scrollbar-width:thin] [@supports(-moz-appearance:none)]:group-hover/chart:[scrollbar-color:var(--scrollbar-thumb)_transparent] [@supports(-moz-appearance:none)]:hover:[scrollbar-color:var(--scrollbar-thumb)_transparent] [@supports(-moz-appearance:none)]:focus-visible:[scrollbar-color:var(--scrollbar-thumb)_transparent]",
);

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
      className={cn(
        "group -mt-2.5 flex max-h-full w-full flex-col items-start gap-1 overflow-y-auto overflow-x-hidden",
        LEGEND_SCROLLBAR_CLASSES,
      )}
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
