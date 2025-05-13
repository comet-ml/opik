import React from "react";
import * as RechartsPrimitive from "recharts";
import { cn } from "@/lib/utils";
import { OnChangeFn } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const ChartVerticalLegendContent = React.forwardRef<
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
      className="group -mt-2.5 flex max-h-full w-full flex-col items-start gap-1 overflow-y-auto overflow-x-hidden"
      onMouseLeave={handleMouseLeave}
    >
      {payload.map((item) => {
        const key = `${item.value || "value"}`;
        const indicatorColor = color || item.color;

        return (
          <div
            key={key}
            className={cn(
              "h-4 w-full pl-8 relative cursor-pointer pb-1 group-hover-except-self:opacity-60 duration-200",
            )}
            onMouseEnter={() => handleMouseEnter(item.value)}
          >
            <TooltipWrapper content={item.value}>
              <div className="comet-body-xs truncate font-light text-foreground">
                {item.value}
              </div>
            </TooltipWrapper>
            <div
              className="absolute left-[20px] top-[5px] size-1.5 shrink-0 rounded-full border-[--color-border] bg-[--color-bg]"
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
ChartVerticalLegendContent.displayName = "ChartVerticalLegendContent";

export default ChartVerticalLegendContent;
