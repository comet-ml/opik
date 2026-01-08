import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";

import { cn } from "@/lib/utils";
import { ROW_HEIGHT } from "@/types/shared";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import TagListTooltipContent from "@/components/shared/TagListTooltipContent/TagListTooltipContent";
import ChildrenWidthMeasurer from "@/components/shared/ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import { useVisibleItemsByWidth } from "@/hooks/useVisibleItemsByWidth";

const LIST_CELL_CONFIG = { itemGap: 4 };

const ListCell = (context: CellContext<unknown, unknown>) => {
  const items = context.getValue() as string[];

  const isSmall =
    (context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small) ===
    ROW_HEIGHT.small;

  const isEmpty = !Array.isArray(items) || items.length === 0;
  const sortedList = useMemo(
    () => (isEmpty ? [] : [...items].sort()),
    [items, isEmpty],
  );

  const {
    cellRef,
    visibleItems,
    hiddenItems,
    hasHiddenItems,
    remainingCount,
    onMeasure,
  } = useVisibleItemsByWidth(sortedList, LIST_CELL_CONFIG);

  if (isEmpty) {
    return null;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={cn(isSmall && "py-0")}
    >
      <div ref={cellRef} className="w-full min-w-0 flex-1 overflow-hidden">
        <div
          className={cn(
            "flex max-h-full flex-row gap-1",
            isSmall ? "overflow-x-hidden" : "flex-wrap overflow-auto",
          )}
        >
          <ChildrenWidthMeasurer onMeasure={onMeasure}>
            {sortedList.map((item) => (
              <div key={item}>
                <ColoredTag label={item} className="shrink-0" />
              </div>
            ))}
          </ChildrenWidthMeasurer>
          {visibleItems.map((item) => (
            <ColoredTag key={item} label={item} className="min-w-0 shrink-0" />
          ))}
          {hasHiddenItems && (
            <TooltipWrapper
              content={<TagListTooltipContent tags={hiddenItems} />}
            >
              <div className="comet-body-s-accented flex h-6 items-center rounded-md border border-border pl-1 pr-1.5 text-muted-slate">
                +{remainingCount}
              </div>
            </TooltipWrapper>
          )}
        </div>
      </div>
    </CellWrapper>
  );
};

export default ListCell;
