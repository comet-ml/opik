import React from "react";
import * as RechartsPrimitive from "recharts";
import { OnChangeFn } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

type ChartHorizontalLegendContentProps = React.ComponentProps<
  typeof RechartsPrimitive.Legend
> &
  React.ComponentProps<"div"> & {
    setActiveLine: OnChangeFn<string | null>;
    chartId: string;
    containerClassName?: string;
  };

const ChartHorizontalLegendContent = React.forwardRef<
  HTMLDivElement,
  ChartHorizontalLegendContentProps
>(({ payload, color, setActiveLine, containerClassName }, ref) => {
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
          "group inline-flex max-w-full max-h-20 overflow-y-auto flex-wrap items-center justify-center space-x-2 gap-y-1"
        }
      >
        {payload.map((item, idx) => {
          const key = `${item.value || "value"}`;
          const indicatorColor = color || item.color;

          return (
            <div
              key={key + idx}
              className="relative min-w-0 pl-3 duration-200 group-hover-except-self:opacity-60"
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
ChartHorizontalLegendContent.displayName = "ChartHorizontalLegendContent";

export default ChartHorizontalLegendContent;
