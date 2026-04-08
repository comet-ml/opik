import { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";

import { cn } from "@/lib/utils";
import { ROW_HEIGHT } from "@/types/shared";
import { ROW_HEIGHT_MAP } from "@/constants/shared";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import TagListTooltipContent from "@/shared/TagListTooltipContent/TagListTooltipContent";
import ChildrenWidthMeasurer from "@/shared/ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import { useVisibleItemsByWidth } from "@/hooks/useVisibleItemsByWidth";

const LIST_CELL_CONFIG = { itemGap: 4 };

const TAG_ROW_HEIGHT_MD = 28; // h-6(24) + gap-1(4)

const CELL_PADDING: Record<ROW_HEIGHT, number> = {
  [ROW_HEIGHT.small]: 8,
  [ROW_HEIGHT.medium]: 16,
  [ROW_HEIGHT.large]: 16,
};

function getVisibleRowCount(rowHeight: ROW_HEIGHT): number {
  const totalHeight = parseInt(ROW_HEIGHT_MAP[rowHeight].height as string, 10);
  const availableHeight = totalHeight - CELL_PADDING[rowHeight];
  return Math.max(1, Math.floor(availableHeight / TAG_ROW_HEIGHT_MD));
}

const ListCell = (context: CellContext<unknown, unknown>) => {
  const items = context.getValue() as string[];

  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const isSmall = rowHeight === ROW_HEIGHT.small;
  const tagSize = isSmall ? "sm" : ("md" as const);

  const isEmpty = !Array.isArray(items) || items.length === 0;
  const sortedList = useMemo(
    () => (isEmpty ? [] : [...items].sort()),
    [items, isEmpty],
  );

  const { cellRef, visibleItems, onMeasure } = useVisibleItemsByWidth(
    sortedList,
    LIST_CELL_CONFIG,
  );

  const itemsPerRow = visibleItems.length;

  const maxVisibleItems = useMemo(() => {
    if (itemsPerRow === 0) return 0;
    if (isSmall) return itemsPerRow;
    return itemsPerRow * getVisibleRowCount(rowHeight);
  }, [isSmall, rowHeight, itemsPerRow]);

  if (isEmpty) {
    return null;
  }

  const displayedItems = sortedList.slice(0, maxVisibleItems);
  const hiddenItems = sortedList.slice(maxVisibleItems);
  const hiddenCount = hiddenItems.length;
  const showOverflowIndicator = hiddenCount > 0;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={cn(isSmall && "py-1")}
    >
      <div
        ref={cellRef}
        className={cn(
          "w-full min-w-0 overflow-hidden",
          isSmall ? "my-auto" : "mt-0",
        )}
      >
        <div
          className={cn(
            "flex flex-row gap-1",
            isSmall ? "max-h-full overflow-x-hidden" : "flex-wrap",
          )}
        >
          <ChildrenWidthMeasurer onMeasure={onMeasure}>
            {sortedList.map((item) => (
              <div key={item}>
                <ColoredTag
                  label={item}
                  variant="lavender"
                  className="shrink-0"
                  size={tagSize}
                />
              </div>
            ))}
          </ChildrenWidthMeasurer>
          {displayedItems.map((item) => (
            <ColoredTag
              key={item}
              label={item}
              variant="lavender"
              className="block min-w-0 max-w-full"
              size={tagSize}
            />
          ))}
          {showOverflowIndicator && (
            <TooltipWrapper
              content={
                <TagListTooltipContent
                  tags={hiddenItems}
                  variant="lavender"
                  size={tagSize}
                />
              }
            >
              <div
                className={cn(
                  "flex items-center rounded-sm text-[var(--tag-lavender-text)]",
                  isSmall
                    ? "comet-body-xs h-4 px-2"
                    : "comet-body-s h-6 rounded-md px-1.5",
                )}
              >
                +{hiddenCount}
              </div>
            </TooltipWrapper>
          )}
        </div>
      </div>
    </CellWrapper>
  );
};

export default ListCell;
