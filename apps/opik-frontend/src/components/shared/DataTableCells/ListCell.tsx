import { CellContext } from "@tanstack/react-table";

import { cn } from "@/lib/utils";
import { ROW_HEIGHT } from "@/types/shared";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const ListCell = (context: CellContext<unknown, unknown>) => {
  const items = context.getValue() as string[];

  if (!Array.isArray(items) || items.length === 0) {
    return null;
  }

  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div
        className={cn(
          "flex max-h-full flex-row gap-2",
          rowHeight === ROW_HEIGHT.small
            ? "overflow-x-auto"
            : "flex-wrap overflow-auto",
        )}
      >
        {items.sort().map((item) => {
          return <ColoredTag label={item} key={item} className="shrink-0" />;
        })}
      </div>
    </CellWrapper>
  );
};

export default ListCell;
