import React, { useEffect } from "react";
import { FilePen, Loader2 } from "lucide-react";
import { useInView } from "react-intersection-observer";

import { cn } from "@/lib/utils";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import EnvironmentBadgeList from "@/shared/EnvironmentLabel/EnvironmentBadgeList";
import VersionTagList from "./VersionTagList";
import VersionMeta from "./VersionMeta";

export interface VersionHistoryItem {
  id: string;
  label: string;
  tags: string[];
  description?: string;
  created_at: string;
  created_by?: string;
  environments?: string[] | null;
}

interface VersionHistoryTimelineProps {
  items: VersionHistoryItem[];
  selectedId?: string;
  onSelect: (item: VersionHistoryItem) => void;
  hasNextPage?: boolean;
  isFetchingNextPage?: boolean;
  onLoadMore?: () => void;
  emptyTitle?: string;
}

const VersionHistoryTimeline: React.FC<VersionHistoryTimelineProps> = ({
  items,
  selectedId,
  onSelect,
  hasNextPage = false,
  isFetchingNextPage = false,
  onLoadMore,
  emptyTitle = "No version history",
}) => {
  const { ref: sentinelRef, inView } = useInView();

  useEffect(() => {
    if (inView && hasNextPage && !isFetchingNextPage && onLoadMore) {
      onLoadMore();
    }
  }, [inView, hasNextPage, isFetchingNextPage, onLoadMore]);

  if (items.length === 0) {
    return <DataTableNoData title={emptyTitle} />;
  }

  return (
    <ul className="px-4 pb-4 pt-1">
      {items.map((item, index) => {
        const isSelected = item.id === selectedId;
        const isLast = index === items.length - 1;

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
              onClick={() => onSelect(item)}
            >
              <div className="flex min-w-0 items-center gap-1">
                <span className="comet-body-s-accented shrink-0">
                  {item.label}
                </span>
                <VersionTagList tags={item.tags} size="sm" maxWidth={200} />
                <EnvironmentBadgeList
                  names={item.environments}
                  size="sm"
                  badgeClassName="max-w-[120px]"
                  withOverflow
                  maxWidth={160}
                />
              </div>
              {item.description && (
                <p className="comet-body-xs mt-1.5 flex min-w-0 items-center gap-1 text-light-slate">
                  <FilePen className="size-3 shrink-0" />
                  <TooltipWrapper content={item.description}>
                    <span className="w-fit max-w-full truncate">
                      {item.description}
                    </span>
                  </TooltipWrapper>
                </p>
              )}
              <VersionMeta
                className="mt-1.5"
                createdAt={item.created_at}
                createdBy={item.created_by}
              />
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

export default VersionHistoryTimeline;
