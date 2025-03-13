import React from "react";
import * as RechartsPrimitive from "recharts";
import { OnChangeFn } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const MetricChartLegendContent = React.forwardRef<
  HTMLDivElement,
  React.ComponentProps<typeof RechartsPrimitive.Legend> &
    React.ComponentProps<"div"> & {
      setActiveLine: OnChangeFn<string | null>;
      chartId: string;
    }
>(({ payload, color, setActiveLine }, ref) => {
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
      className="-mt-2.5 w-full max-w-full pt-6 text-center"
      onMouseLeave={handleMouseLeave}
    >
      <div className="group inline-flex max-w-full flex-wrap items-center justify-center space-x-3">
        {payload.map((item) => {
          const key = `${item.value || "value"}`;
          const indicatorColor = color || item.color;

          return (
            <div
              key={key}
              className="relative min-w-0 cursor-pointer pl-3 duration-200 group-hover-except-self:opacity-60"
              onMouseEnter={() => handleMouseEnter(item.value)}
            >
              <TooltipWrapper content={item.value}>
                <div className="comet-body-xs truncate text-foreground">
                  {item.value}
                </div>
              </TooltipWrapper>
              <div
                className="absolute left-0 top-[5px] size-1.5 shrink-0 rounded-full border-[--color-border] bg-[--color-bg]"
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
    </div>
  );
});
MetricChartLegendContent.displayName = "MetricChartLegendContent";

export default MetricChartLegendContent;
