import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import JsonView from "react18-json-view";
import { safelyParseJSON } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";

const CodeCell = (context: CellContext<unknown, unknown>) => {
  const value = context.getValue() as string;
  const jsonViewTheme = useJsonViewTheme();

  if (!value) return "";

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isSmall = rowHeight === ROW_HEIGHT.small;

  let content;

  if (isSmall) {
    content = (
      <CellTooltipWrapper content={value}>
        <code className="comet-code w-full truncate">{value}</code>
      </CellTooltipWrapper>
    );
  } else {
    content = (
      <div
        className="size-full overflow-y-auto overflow-x-hidden whitespace-normal"
        onClick={(event) => event.stopPropagation()}
      >
        <JsonView
          src={safelyParseJSON(value)}
          {...jsonViewTheme}
          collapseStringsAfterLength={10000}
        />
      </div>
    );
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {content}
    </CellWrapper>
  );
};

export default CodeCell;
