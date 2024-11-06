import React from "react";
import * as RechartsPrimitive from "recharts";
import { cn } from "@/lib/utils";
import { OnChangeFn } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const ExperimentChartLegendContent = React.forwardRef<
  HTMLDivElement,
  React.ComponentProps<typeof RechartsPrimitive.Legend> &
    React.ComponentProps<"div"> & {
      setHideState: OnChangeFn<Record<string, string[]>>;
      chartId: string;
    }
>(({ payload, color, setHideState, chartId }, ref) => {
  if (!payload?.length) {
    return null;
  }

  return (
    <div
      ref={ref}
      className="-mt-2.5 flex size-full flex-col items-start gap-1 overflow-y-auto overflow-x-hidden bg-background"
    >
      {payload.map((item) => {
        const key = `${item.value || "value"}`;
        const indicatorColor = color || item.color;

        return (
          <div
            key={key}
            className={cn(
              "h-4 w-full pl-8 relative cursor-pointer mb-1",
              item.inactive && "opacity-50",
            )}
            onClick={() => {
              setHideState((state) => {
                const retVal = { ...state };
                retVal[chartId] = item.inactive
                  ? (state[chartId] || []).filter(
                      (value) => value !== item.value,
                    )
                  : [...(state[chartId] || []), item.value];

                return retVal;
              });
            }}
          >
            <TooltipWrapper content={item.value}>
              <div className="comet-body-xs truncate text-light-slate">
                {item.value}
              </div>
            </TooltipWrapper>
            <div
              className="absolute left-[20px] top-[5px] size-1.5 shrink-0 rounded-[1.5px] border-[--color-border] bg-[--color-bg]"
              style={
                {
                  "--color-bg": indicatorColor,
                  "--color-border": indicatorColor,
                } as React.CSSProperties
              }
            />
          </div>
        );
      })}
    </div>
  );
});
ExperimentChartLegendContent.displayName = "ExperimentChartLegendContent";

export default ExperimentChartLegendContent;
