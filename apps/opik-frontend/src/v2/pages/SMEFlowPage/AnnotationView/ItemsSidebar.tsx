import React, { useEffect, useRef, useMemo, useState } from "react";
import { CornerDownRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { Tag } from "@/ui/tag";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import { useSMEFlow, ITEM_STATE } from "../SMEFlowContext";
import {
  getAnnotationQueueItemId,
  getLastAnnotationByUser,
  formatThreadDateRange,
} from "@/lib/annotation-queues";
import { Trace, Thread } from "@/types/traces";
import { isObjectThread } from "@/lib/traces";
import { prettifyMessage } from "@/lib/traces";
import { useLoggedInUserNameOrOpenSourceDefaultUser } from "@/store/AppStore";

const getPreviewText = (
  obj: object | undefined,
  type: "input" | "output",
): string => {
  if (!obj) return "";
  const result = prettifyMessage(obj, { type });
  if (typeof result.message === "string") return result.message;
  return JSON.stringify(obj).slice(0, 80);
};

const getItemPreviews = (
  item: Trace | Thread,
): { name: string; input: string; output: string } => {
  if (isObjectThread(item)) {
    const thread = item as Thread;
    const name =
      formatThreadDateRange(thread.start_time, thread.end_time) ||
      thread.id.slice(-12);
    return {
      name,
      input: getPreviewText(thread.first_message, "input"),
      output: thread.last_message
        ? getPreviewText(thread.last_message, "output")
        : "",
    };
  }
  const trace = item as Trace;
  return {
    name: trace.name || trace.id.slice(-12),
    input: getPreviewText(trace.input, "input"),
    output: getPreviewText(trace.output, "output"),
  };
};

const STATE_CONFIG = {
  [ITEM_STATE.COMPLETED]: { dotClass: "bg-emerald-400", label: "Completed" },
  [ITEM_STATE.SCORED]: { dotClass: "bg-sky-400", label: "Reviewed" },
  [ITEM_STATE.IN_REVIEW]: { dotClass: "bg-orange-400", label: "In review" },
  [ITEM_STATE.DEFAULT]: {
    dotClass: "border border-light-slate",
    label: "To review",
  },
};

type SidebarFilter = "to_review" | "processed";
type SidebarEntry = {
  index: number;
  itemId: string;
  state: ITEM_STATE;
  lastAnnotatedByUser: string;
};

const ItemsSidebar: React.FunctionComponent = () => {
  const {
    queueItems,
    currentIndex,
    itemStates,
    navigateToItem,
    shuffledItemIds,
  } = useSMEFlow();
  const currentUserName = useLoggedInUserNameOrOpenSourceDefaultUser();

  const [filter, setFilter] = useState<SidebarFilter>("to_review");
  const activeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    activeRef.current?.scrollIntoView({ block: "nearest" });
  }, [currentIndex]);

  const previews = useMemo(() => queueItems.map(getItemPreviews), [queueItems]);

  // Recomputes only on item list poll or user change
  const entriesById = useMemo(() => {
    const byId: Record<string, SidebarEntry> = {};
    queueItems.forEach((item, index) => {
      const itemId = getAnnotationQueueItemId(item);
      byId[itemId] = {
        index,
        itemId,
        state: ITEM_STATE.DEFAULT,
        lastAnnotatedByUser: getLastAnnotationByUser(item, currentUserName),
      };
    });
    return byId;
  }, [queueItems, currentUserName]);

  // Recomputes on lock poll via itemStates, but no getLastAnnotationByUser calls
  const { toReviewItems, processedItems } = useMemo(() => {
    const entries = shuffledItemIds.map((id) => ({
      ...entriesById[id],
      state: itemStates[id] ?? ITEM_STATE.DEFAULT,
    }));

    const toReview = entries.filter(
      (e) => e.state === ITEM_STATE.DEFAULT || e.index === currentIndex,
    );

    const processed = entries
      .filter((e) => e.state !== ITEM_STATE.DEFAULT)
      .sort((a, b) =>
        b.lastAnnotatedByUser.localeCompare(a.lastAnnotatedByUser),
      );

    return { toReviewItems: toReview, processedItems: processed };
  }, [entriesById, itemStates, shuffledItemIds, currentIndex]);

  const filteredItems = filter === "to_review" ? toReviewItems : processedItems;

  const defaultCount = useMemo(
    () =>
      Object.values(itemStates).filter((s) => s === ITEM_STATE.DEFAULT).length,
    [itemStates],
  );

  const allDone = queueItems.length > 0 && defaultCount === 0;

  return (
    <div className="flex h-full w-80 shrink-0 flex-col overflow-hidden border-r border-border">
      <div className="flex h-10 shrink-0 items-center gap-1.5 border-b border-border bg-soft-background px-3">
        <span className="comet-body-xs-accented text-foreground">
          Queue items
        </span>
        <span
          className={cn(
            "comet-body-xs",
            allDone ? "text-success" : "text-muted-slate",
          )}
        >
          {allDone ? "All scored" : `${defaultCount} remaining`}
        </span>
      </div>
      <div className="shrink-0 px-3 py-1.5">
        <Tabs
          value={filter}
          onValueChange={(v) => setFilter(v as SidebarFilter)}
        >
          <TabsList variant="segmented-primary">
            <TabsTrigger
              variant="segmented-primary"
              size="sm"
              value="to_review"
            >
              To review
            </TabsTrigger>
            <TabsTrigger
              variant="segmented-primary"
              size="sm"
              value="processed"
            >
              Processed
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </div>
      <div className="flex-1 overflow-y-auto">
        {filteredItems.map(({ index, itemId, state }) => {
          const isActive = index === currentIndex;
          const { name, input, output } = previews[index];
          const { dotClass, label } = STATE_CONFIG[state];

          return (
            <button
              key={itemId}
              ref={isActive ? activeRef : undefined}
              onClick={() => navigateToItem(index)}
              className={cn(
                "flex w-full flex-col gap-0.5 p-2 text-left transition-colors",
                isActive ? "bg-muted" : "hover:bg-primary-foreground",
              )}
            >
              <div
                className={cn(
                  "flex flex-col gap-0.5",
                  filter === "to_review" &&
                    state !== ITEM_STATE.DEFAULT &&
                    "opacity-60",
                )}
              >
                <div className="flex items-center gap-1">
                  {state !== ITEM_STATE.DEFAULT && (
                    <Tag className="flex shrink-0 items-center gap-1 border-border bg-primary-foreground px-1 text-foreground">
                      <span className={cn("size-1.5 rounded-full", dotClass)} />
                      {label}
                    </Tag>
                  )}
                  <span className="comet-body-xs truncate text-muted-slate">
                    {name}
                  </span>
                </div>
                {input && (
                  <span className="comet-body-xs-accented truncate">
                    {input}
                  </span>
                )}
                {output && (
                  <div className="flex items-center gap-1 truncate">
                    <CornerDownRight className="size-3 shrink-0 text-light-slate" />
                    <span className="truncate text-[10px] leading-3 text-muted-slate">
                      {output}
                    </span>
                  </div>
                )}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default ItemsSidebar;
