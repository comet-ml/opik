import React, { useEffect, useRef, useMemo } from "react";
import { User, Bot } from "lucide-react";
import { cn } from "@/lib/utils";
import { useSMEFlow, ITEM_STATE } from "../SMEFlowContext";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import { Trace, Thread } from "@/types/traces";
import { prettifyMessage } from "@/lib/traces";

const getPreviewText = (
  obj: object | undefined,
  type: "input" | "output",
): string => {
  if (!obj) return "";
  const result = prettifyMessage(obj, { type });
  if (typeof result.message === "string") return result.message;
  return JSON.stringify(obj).slice(0, 80);
};

const StateIndicator: React.FC<{ state: ITEM_STATE }> = ({ state }) => {
  return (
    <span className="flex size-3 shrink-0 items-center justify-center">
      {state === ITEM_STATE.COMPLETED && (
        <span className="size-1.5 rounded-full bg-emerald-400" />
      )}
      {state === ITEM_STATE.SCORED && (
        <span className="size-1.5 rounded-full bg-light-slate" />
      )}
      {state === ITEM_STATE.DEFAULT && (
        <span className="size-1.5 rounded-full border border-slate-300" />
      )}
    </span>
  );
};

const ItemsSidebar: React.FunctionComponent = () => {
  const { queueItems, currentIndex, itemStates, navigateToItem } = useSMEFlow();

  const activeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    activeRef.current?.scrollIntoView({ block: "nearest" });
  }, [currentIndex]);

  const itemPreviews = useMemo(
    () =>
      queueItems.map((item) => {
        const trace = item as Trace & Thread;
        return {
          input: getPreviewText(trace.input, "input"),
          output: getPreviewText(trace.output, "output"),
        };
      }),
    [queueItems],
  );

  return (
    <div className="flex h-full w-80 shrink-0 flex-col overflow-hidden border-r border-border">
      <div className="flex h-10 shrink-0 items-center border-b border-border bg-soft-background px-3">
        <span className="comet-body-xs-accented text-foreground">
          Queue items
        </span>
      </div>
      <div className="flex-1 overflow-y-auto">
        {queueItems.map((item, index) => {
          const itemId = getAnnotationQueueItemId(item);
          const state = itemStates[itemId] ?? ITEM_STATE.DEFAULT;
          const isActive = index === currentIndex;
          const trace = item as Trace & Thread;

          const { input: inputPreview, output: outputPreview } =
            itemPreviews[index];

          return (
            <button
              key={itemId}
              ref={isActive ? activeRef : undefined}
              onClick={() => navigateToItem(index)}
              className={cn(
                "flex w-full flex-col gap-0.5 p-2 text-left transition-colors",
                "hover:bg-muted/50",
                isActive && "bg-primary-foreground",
              )}
            >
              <div className="flex items-center gap-1">
                <StateIndicator state={state} />
                <span className="comet-body-xs-accented truncate">
                  {trace.name || itemId.slice(-12)}
                </span>
              </div>
              {inputPreview && (
                <div className="flex items-center gap-1 truncate">
                  <User className="size-3 shrink-0 text-slate-300" />
                  <span className="truncate text-[10px] leading-3 text-muted-slate">
                    {inputPreview}
                  </span>
                </div>
              )}
              {outputPreview && (
                <div className="flex items-center gap-1 truncate">
                  <Bot className="size-3 shrink-0 text-slate-300" />
                  <span className="truncate text-[10px] leading-3 text-muted-slate">
                    {outputPreview}
                  </span>
                </div>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default ItemsSidebar;
