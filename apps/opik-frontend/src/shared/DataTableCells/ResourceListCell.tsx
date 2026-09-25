import { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import { ROW_HEIGHT } from "@/types/shared";
import { ROW_HEIGHT_MAP } from "@/constants/shared";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import ChildrenWidthMeasurer from "@/shared/ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import NavigationTag from "@/shared/NavigationTag/NavigationTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { useVisibleItemsByWidth } from "@/hooks/useVisibleItemsByWidth";

type CustomMeta = {
  nameKey?: string;
  idKey?: string;
  resource: RESOURCE_TYPE;
  getSearch?: (cellData: unknown) => Record<string, string | number>;
};

type ResourceItem = Record<string, unknown>;

const CELL_CONFIG = { itemGap: 4, minFirstItemWidth: 40 };

const TAG_ROW_HEIGHT = 28; // h-6(24) + gap-1(4)

const CELL_PADDING: Record<ROW_HEIGHT, number> = {
  [ROW_HEIGHT.small]: 8,
  [ROW_HEIGHT.medium]: 16,
  [ROW_HEIGHT.large]: 16,
};

function getVisibleRowCount(rowHeight: ROW_HEIGHT): number {
  const totalHeight = parseInt(ROW_HEIGHT_MAP[rowHeight].height as string, 10);
  const availableHeight = totalHeight - CELL_PADDING[rowHeight];
  return Math.max(1, Math.floor(availableHeight / TAG_ROW_HEIGHT));
}

/**
 * Renders a list of linked resources as navigation chips, showing only the chips that fit the cell
 * and a "+N" counter for the rest. Same accessor and customMeta contract as MultiResourceCell.
 */
const ResourceListCell = (context: CellContext<unknown, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const {
    resource,
    nameKey = "name",
    idKey = "id",
    getSearch,
  } = (custom ?? {}) as CustomMeta;

  const items = context.getValue() as ResourceItem[] | undefined;
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const isSmall = rowHeight === ROW_HEIGHT.small;

  const isEmpty = !Array.isArray(items) || items.length === 0;
  const sortedList = useMemo(
    () =>
      isEmpty
        ? []
        : [...items]
            .filter((item) => get(item, idKey))
            .sort((a, b) =>
              String(get(a, nameKey, "")).localeCompare(
                String(get(b, nameKey, "")),
              ),
            ),
    [items, isEmpty, idKey, nameKey],
  );

  const { cellRef, visibleItems, onMeasure } = useVisibleItemsByWidth(
    sortedList,
    CELL_CONFIG,
  );

  const itemsPerRow = visibleItems.length;

  // Always show at least one chip: a long name truncates and its tooltip carries the full text,
  // which beats a bare "+N" counter with nothing to read.
  const maxVisibleItems = useMemo(() => {
    const perRow = Math.max(1, itemsPerRow);
    if (isSmall) return perRow;
    return perRow * getVisibleRowCount(rowHeight);
  }, [isSmall, rowHeight, itemsPerRow]);

  // Key by id AND name: ChildrenWidthMeasurer re-measures only when its children's keys change, so a
  // renamed queue has to produce a new key or the chip keeps the width measured for the old name.
  const itemKey = (item: ResourceItem) =>
    `${get(item, idKey)}:${get(item, nameKey, "")}`;

  const renderTag = (item: ResourceItem) => (
    <NavigationTag
      id={String(get(item, idKey))}
      name={String(get(item, nameKey, ""))}
      resource={resource}
      search={isFunction(getSearch) ? getSearch(item) : undefined}
      textSize={isSmall ? "xs" : "s"}
      className="max-w-full"
    />
  );

  if (isEmpty || sortedList.length === 0) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        -
      </CellWrapper>
    );
  }

  const displayedItems = sortedList.slice(0, maxVisibleItems);
  const hiddenItems = sortedList.slice(maxVisibleItems);
  const hiddenCount = hiddenItems.length;

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
              <div key={itemKey(item)} className="shrink-0">
                {renderTag(item)}
              </div>
            ))}
          </ChildrenWidthMeasurer>
          {displayedItems.map((item) => (
            <div key={itemKey(item)} className="min-w-0 max-w-full">
              {renderTag(item)}
            </div>
          ))}
          {hiddenCount > 0 && (
            <Popover>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  onClick={(event) => event.stopPropagation()}
                  className={cn(
                    "flex shrink-0 items-center rounded-sm text-muted-slate hover:bg-primary-foreground hover:text-foreground",
                    isSmall
                      ? "comet-body-xs h-6 px-1.5"
                      : "comet-body-s h-6 rounded-md px-1.5",
                  )}
                >
                  +{hiddenCount}
                </button>
              </PopoverTrigger>
              <PopoverContent
                align="start"
                className="flex w-auto max-w-[320px] flex-wrap gap-1 p-2"
                onClick={(event) => event.stopPropagation()}
              >
                {hiddenItems.map((item) => (
                  <div key={itemKey(item)}>{renderTag(item)}</div>
                ))}
              </PopoverContent>
            </Popover>
          )}
        </div>
      </div>
    </CellWrapper>
  );
};

export default ResourceListCell;
