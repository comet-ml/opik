import React, { useEffect } from "react";
import { Clock, FilePen, Loader2, User } from "lucide-react";
import { useInView } from "react-intersection-observer";

import { cn } from "@/lib/utils";
import { ConfigHistoryItem } from "@/types/agent-configs";
import { formatDate, getTimeFromNow } from "@/lib/date";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ProdTag from "./ProdTag";
import {
  generateBlueprintDescription,
  isProdTag,
  sortTags,
} from "@/utils/agent-configurations";

type ConfigurationHistoryTimelineProps = {
  items: ConfigHistoryItem[];
  total: number;
  selectedIndex: number | null;
  onSelect: (index: number) => void;
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  onLoadMore: () => void;
};

const ConfigurationHistoryTimeline: React.FC<
  ConfigurationHistoryTimelineProps
> = ({
  items,
  total,
  selectedIndex,
  onSelect,
  hasNextPage,
  isFetchingNextPage,
  onLoadMore,
}) => {
  const { ref: sentinelRef, inView } = useInView();

  useEffect(() => {
    if (inView && hasNextPage && !isFetchingNextPage) {
      onLoadMore();
    }
  }, [inView, hasNextPage, isFetchingNextPage, onLoadMore]);

  if (items.length === 0) {
    return <DataTableNoData title="No configuration history" />;
  }

  return (
    <ul className="p-4">
      {items.map((item, index) => {
        const isSelected = index === selectedIndex;
        const isLast = index === items.length - 1;
        const sortedTags = sortTags(item.tags);

        return (
          <li key={item.id} className="flex gap-2">
            <div className="flex flex-col items-center">
              <div
                className={cn(
                  "h-3 w-0.5 shrink-0",
                  index > 0
                    ? "bg-[var(--timeline-connector)] opacity-50"
                    : "bg-transparent",
                )}
              />
              <div
                className={cn(
                  "size-2 shrink-0 rounded-full",
                  isSelected
                    ? "bg-primary"
                    : "border-2 border-[var(--timeline-connector)]",
                )}
              />
              <div
                className={cn(
                  "w-0.5 flex-1 shrink-0",
                  !isLast
                    ? "bg-[var(--timeline-connector)] opacity-50"
                    : "bg-transparent",
                )}
              />
            </div>

            <div
              className={cn(
                "min-w-0 flex-1 cursor-pointer rounded px-3 py-2 transition-colors",
                isSelected
                  ? "relative z-10 bg-primary-foreground ring-1 ring-primary"
                  : "hover:bg-primary-foreground/60",
              )}
              onClick={() => onSelect(index)}
            >
              <div className="flex flex-wrap items-center gap-1">
                <span className="comet-body-s-accented">v{total - index}</span>
                {sortedTags.map((tag) =>
                  isProdTag(tag) ? (
                    <ProdTag key={tag} size="xs" value={tag} />
                  ) : (
                    <ColoredTag key={tag} label={tag} size="sm" />
                  ),
                )}
              </div>
              {(() => {
                const desc =
                  item.description || generateBlueprintDescription(item.values);
                return (
                  <p className="comet-body-xs mt-1.5 flex min-w-0 items-center gap-1 text-light-slate">
                    <FilePen className="size-3 shrink-0" />
                    <TooltipWrapper content={desc}>
                      <span className="w-fit max-w-full truncate">{desc}</span>
                    </TooltipWrapper>
                  </p>
                );
              })()}
              <div className="comet-body-xs mt-1.5 flex items-center gap-3 text-light-slate">
                <TooltipWrapper
                  content={`${formatDate(item.created_at, {
                    utc: true,
                    includeSeconds: true,
                  })} UTC`}
                >
                  <span className="flex items-center gap-1">
                    <Clock className="size-3 shrink-0" />
                    {getTimeFromNow(item.created_at)}
                  </span>
                </TooltipWrapper>
                <span className="flex items-center gap-1">
                  <User className="size-3 shrink-0" />
                  {item.created_by}
                </span>
              </div>
            </div>
          </li>
        );
      })}
      <li ref={sentinelRef} className="h-1" />
      {isFetchingNextPage && (
        <li className="flex justify-center py-2">
          <Loader2 className="size-4 animate-spin text-light-slate" />
        </li>
      )}
    </ul>
  );
};

export default ConfigurationHistoryTimeline;
