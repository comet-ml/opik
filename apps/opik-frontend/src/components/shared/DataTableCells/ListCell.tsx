import { CellContext } from "@tanstack/react-table";

import { cn } from "@/lib/utils";
import { ROW_HEIGHT } from "@/types/shared";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import TagListTooltipContent from "@/components/shared/TagListTooltipContent/TagListTooltipContent";
import { useVisibleTags } from "@/hooks/useVisibleTags";

const ListCell = (context: CellContext<unknown, unknown>) => {
  const items = context.getValue() as string[];

  const isSmall =
    (context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small) ===
    ROW_HEIGHT.small;

  const { visibleItems, hiddenItems, hasMoreItems, remainingCount } =
    useVisibleTags(items);

  if (!Array.isArray(items) || items.length === 0) {
    return null;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={cn(isSmall && "py-0")}
    >
      <div
        className={cn(
          "flex max-h-full flex-row gap-1",
          isSmall ? "overflow-x-auto" : "flex-wrap overflow-auto",
        )}
      >
        {visibleItems.map((item) => {
          return <ColoredTag label={item} key={item} className="shrink-0" />;
        })}
        {hasMoreItems && (
          <TooltipWrapper
            content={<TagListTooltipContent tags={hiddenItems} />}
          >
            <div className="comet-body-s-accented flex h-6 items-center rounded-md border border-border pl-1 pr-1.5 text-muted-slate">
              +{remainingCount}
            </div>
          </TooltipWrapper>
        )}
      </div>
    </CellWrapper>
  );
};

export default ListCell;
