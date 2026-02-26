import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { Wrench } from "lucide-react";
import last from "lodash/last";

import { Tag, TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { ConfigHistoryItem } from "@/types/optimizer-configs";
import useConfigHistoryListInfinite from "@/api/optimizer-configs/useConfigHistoryListInfinite";
import { generateTagVariant } from "@/lib/traces";
import { Button } from "@/components/ui/button";
import Loader from "@/components/shared/Loader/Loader";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import { getTimeFromNow } from "@/lib/date";
import DeploymentHistoryItem from "./DeploymentHistoryItem";

type DeploymentHistoryTabProps = {
  projectId: string;
};

const DeploymentHistoryTab: React.FC<DeploymentHistoryTabProps> = ({
  projectId,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null);

  const { data, isPending, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useConfigHistoryListInfinite({ projectId });

  const allRows = useMemo(
    () => data?.pages.flatMap((p) => p.content) ?? [],
    [data],
  );
  const total = data?.pages[0]?.total ?? 0;

  const rowVirtualizer = useVirtualizer({
    count: allRows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 64,
    overscan: 5,
    getItemKey: (index) => allRows[index]?.id ?? index,
  });

  const virtualItems = rowVirtualizer.getVirtualItems();
  const lastVirtualItem = last(virtualItems);

  useEffect(() => {
    if (
      lastVirtualItem &&
      lastVirtualItem.index >= allRows.length - 1 &&
      hasNextPage &&
      !isFetchingNextPage
    ) {
      fetchNextPage();
    }
  }, [
    lastVirtualItem?.index,
    allRows.length,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
  ]);

  const handleNavigate = useCallback(
    (shift: 1 | -1) => {
      setSelectedIndex((prev) =>
        prev !== null ? Math.max(0, Math.min(allRows.length - 1, prev + shift)) : null,
      );
    },
    [allRows.length],
  );

  if (isPending) {
    return <Loader />;
  }

  if (allRows.length === 0) {
    return <DataTableNoData title="No blueprint history" />;
  }

  const selectedItem =
    selectedIndex !== null ? (allRows[selectedIndex] as ConfigHistoryItem) : null;

  return (
    <>
      <div ref={scrollRef} className="h-full overflow-y-auto">
        <div className="px-6 py-4">
          <ul
            className="relative"
            style={{ height: rowVirtualizer.getTotalSize() }}
          >
            <div
              style={{
                transform: `translateY(${virtualItems[0]?.start ?? 0}px)`,
              }}
            >
              {virtualItems.map((virtualRow) => {
                const item = allRows[virtualRow.index] as ConfigHistoryItem;
                const isLatest = virtualRow.index === 0;
                const isSelected = virtualRow.index === selectedIndex;
                const iconColor = isLatest
                  ? TAG_VARIANTS_COLOR_MAP["blue"]
                  : item.tags[0]
                    ? TAG_VARIANTS_COLOR_MAP[
                        generateTagVariant(
                          item.tags[0],
                        ) as keyof typeof TAG_VARIANTS_COLOR_MAP
                      ]
                    : undefined;

                return (
                  <li
                    key={item.id}
                    data-index={virtualRow.index}
                    ref={rowVirtualizer.measureElement}
                    className="relative flex cursor-pointer items-start gap-4 rounded pb-6 hover:bg-primary-foreground"
                    onClick={() => setSelectedIndex(virtualRow.index)}
                  >
                    <div className="flex w-24 shrink-0 flex-col items-end gap-1 pt-1.5">
                      {isLatest && (
                        <Tag size="sm" variant="blue">
                          latest
                        </Tag>
                      )}
                      {item.tags[0] && (
                        <ColoredTag label={item.tags[0]} size="sm" />
                      )}
                    </div>
                    <div className="flex flex-col items-center">
                      <div
                        className="relative z-10 flex size-8 shrink-0 items-center justify-center rounded-full border bg-background"
                        style={
                          iconColor
                            ? {
                                borderColor: iconColor,
                                backgroundColor: `color-mix(in srgb, ${iconColor} 15%, var(--background))`,
                              }
                            : undefined
                        }
                      >
                        <Wrench
                          className="size-4"
                          style={iconColor ? { color: iconColor } : undefined}
                        />
                      </div>
                    </div>
                    {virtualRow.index < allRows.length - 1 && (
                      <div className="absolute bottom-2 left-32 top-10 w-px bg-border" />
                    )}
                    <Button
                      className="shrink-0"
                      size="xs"
                      variant={isSelected ? "default" : "outline"}
                      onClick={(e) => e.stopPropagation()}
                    >
                      Set env
                    </Button>
                    <div className="min-w-0 flex-1 pb-2">
                      <span className="comet-body-s-accented truncate">
                        v{total - virtualRow.index}
                      </span>
                      <p className="comet-body-s my-0.5 text-light-slate">
                        {item.description}
                      </p>
                      <p className="comet-body-xs">
                        {getTimeFromNow(item.createdAt)}
                      </p>
                    </div>
                  </li>
                );
              })}
            </div>
          </ul>
        </div>
      </div>
      <ResizableSidePanel
        panelId="deployment-history-item"
        entity="version"
        open={selectedIndex !== null}
        onClose={() => setSelectedIndex(null)}
        horizontalNavigation={{
          hasPrevious: selectedIndex !== null && selectedIndex > 0,
          hasNext:
            selectedIndex !== null && selectedIndex < allRows.length - 1,
          onChange: handleNavigate,
        }}
      >
        {selectedItem && (
          <DeploymentHistoryItem
            item={selectedItem}
            version={total - selectedIndex!}
          />
        )}
      </ResizableSidePanel>
    </>
  );
};

export default DeploymentHistoryTab;
