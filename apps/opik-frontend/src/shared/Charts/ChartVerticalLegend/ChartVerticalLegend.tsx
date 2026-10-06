import React from "react";
import * as RechartsPrimitive from "recharts";
import { OnChangeFn } from "@/types/shared";
import { useChart } from "@/ui/chart";
import { cn } from "@/lib/utils";
import LegendItem from "@/shared/Charts/LegendItem/LegendItem";
import type { LegendLabelAction } from "@/shared/Charts/LegendItem/LegendItem";

// Only applies where the scrollbar takes space: where main.scss already replaces it (dark
// theme, or Windows via .comet-custom-scrollbar / .firefox), or where the OS draws classic
// scrollbars (.comet-classic-scrollbars). macOS overlay scrollbars are left native, since
// they float over the labels and Safari draws them. Only the thumb is hidden at rest (the
// legend stays visible, readable by assistive tech and keyboard-scrollable); it's painted
// while the legend or an ancestor `group/chart` is hovered, or the legend has keyboard
// focus. The scrollbar always exists, so nothing reflows. Safari doesn't repaint it on
// :hover, so it stays hidden there.
const LEGEND_SCROLLBAR_CLASSES = cn(
  // The global dark thumb (#242424) is near-invisible over a card without its track.
  "[--legend-scrollbar-thumb:var(--scrollbar-thumb)] dark:[--legend-scrollbar-thumb:hsl(var(--muted-gray))]",
  // `!` beats the theme-wide .dark / .comet-custom-scrollbar 16px scrollbar rules.
  "[:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:[&::-webkit-scrollbar-thumb]:!rounded-full [:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:[&::-webkit-scrollbar-thumb]:!border-0 [:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:[&::-webkit-scrollbar-track]:!bg-transparent [:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:[&::-webkit-scrollbar]:!w-2 [:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:[&::-webkit-scrollbar]:!bg-transparent",
  "[:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:[&::-webkit-scrollbar-thumb]:!bg-transparent [:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:group-hover/chart:[&::-webkit-scrollbar-thumb]:!bg-[var(--legend-scrollbar-thumb)] [:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:[&:focus-visible::-webkit-scrollbar-thumb]:!bg-[var(--legend-scrollbar-thumb)] [:is(.dark,.comet-custom-scrollbar,.comet-classic-scrollbars)_&]:[&:hover::-webkit-scrollbar-thumb]:!bg-[var(--legend-scrollbar-thumb)]",
  // .firefox is only set on Windows. These standard properties must stay out of Chrome,
  // where they disable the ::-webkit-scrollbar styling above.
  "[.firefox_&]:[scrollbar-color:transparent_transparent] [.firefox_&]:[scrollbar-width:thin] [.firefox_&]:group-hover/chart:[scrollbar-color:var(--legend-scrollbar-thumb)_transparent] [.firefox_&]:hover:[scrollbar-color:var(--legend-scrollbar-thumb)_transparent] [.firefox_&]:focus-visible:[scrollbar-color:var(--legend-scrollbar-thumb)_transparent]",
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
