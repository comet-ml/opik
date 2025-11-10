import React from "react";
import isUndefined from "lodash/isUndefined";
import sortBy from "lodash/sortBy";
import { getPayloadConfigFromPayload, useChart } from "@/components/ui/chart";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import {
  NameType,
  Payload,
  ValueType,
} from "recharts/types/component/DefaultTooltipContent";

type OptimizationProgressTooltipProps = {
  active?: boolean;
  payload?: Payload<ValueType, NameType>[];
  objectiveName: string;
  renderHeader?: ({
    payload,
  }: {
    payload: Payload<ValueType, NameType>[];
  }) => React.ReactNode;
};

const OptimizationProgressTooltip = React.forwardRef<
  HTMLDivElement,
  OptimizationProgressTooltipProps
>(({ active, payload, objectiveName, renderHeader }, ref) => {
  const { config } = useChart();

  if (!active || !payload?.length) {
    return null;
  }

  const firstItem = payload[0];
  const allFeedbackScores = sortBy(
    firstItem?.payload?.allFeedbackScores || [],
    (score: { name: string; value: number }) => score.name.toLowerCase(),
  );

  // Filter payload to only show the main objective (not secondary scores)
  const mainObjectivePayload = payload.filter(
    (item) => item.name === objectiveName,
  );

  return (
    <Tooltip open>
      <TooltipTrigger asChild>
        <div className="size-0.5 bg-transparent"></div>
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent className="min-w-32 max-w-72 px-1 py-1.5 will-change-transform">
          <div ref={ref} className="grid items-start gap-1.5 bg-background">
            {/* Header */}
            {renderHeader && (
              <div className="mb-1 max-w-full overflow-hidden border-b px-2 pt-0.5">
                {renderHeader({ payload })}
              </div>
            )}

            <div className="grid gap-1.5">
              {/* Render objective score (from payload) - only main objective */}
              {mainObjectivePayload.map((item, idx) => {
                const key = `${item.name || item.dataKey || "value"}${idx}`;
                const itemConfig = getPayloadConfigFromPayload(
                  config,
                  item,
                  key,
                );
                const indicatorColor = item.payload.fill || item.color;

                return (
                  <div
                    key={key}
                    className="flex h-6 w-full flex-wrap items-center gap-1.5 px-2"
                  >
                    <div
                      className="size-2 shrink-0 rounded-full border-[--color-border] bg-[--color-bg]"
                      style={
                        {
                          "--color-bg": indicatorColor,
                          "--color-border": indicatorColor,
                        } as React.CSSProperties
                      }
                    />
                    <div className="flex flex-1 items-center justify-between gap-2 leading-none">
                      <div className="grid gap-1.5">
                        <span className="comet-body-xs-accented truncate text-foreground">
                          {itemConfig?.label || item.name}
                        </span>
                      </div>
                      {!isUndefined(item.value) && (
                        <span className="comet-body-xs-accented">
                          {typeof item.value === "number"
                            ? item.value.toLocaleString()
                            : item.value}
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}

              {/* Render additional feedback scores */}
              {allFeedbackScores.length > 0 && (
                <>
                  <div className="mx-2 border-t border-border/50"></div>
                  {allFeedbackScores.map(
                    (score: { name: string; value: number }, idx: number) => {
                      // Get color from config if available, otherwise use default gray
                      const scoreColor = config[score.name]?.color || "#64748b";

                      return (
                        <div
                          key={`${score.name}-${idx}`}
                          className="flex h-6 w-full flex-wrap items-center gap-1.5 px-2"
                        >
                          <div
                            className="size-2 shrink-0 rounded-full"
                            style={
                              {
                                backgroundColor: scoreColor,
                              } as React.CSSProperties
                            }
                          />
                          <div className="flex flex-1 items-center justify-between gap-2 leading-none">
                            <div className="grid gap-1.5">
                              <span className="comet-body-xs truncate text-muted-slate">
                                {score.name}
                              </span>
                            </div>
                            <span className="comet-body-xs">
                              {typeof score.value === "number"
                                ? score.value.toLocaleString()
                                : score.value}
                            </span>
                          </div>
                        </div>
                      );
                    },
                  )}
                </>
              )}
            </div>
          </div>
        </TooltipContent>
      </TooltipPortal>
    </Tooltip>
  );
});
OptimizationProgressTooltip.displayName = "OptimizationProgressTooltip";

export default OptimizationProgressTooltip;
