import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import JsonView from "react18-json-view";
import { safelyParseJSON } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const CodeCell = (context: CellContext<unknown, unknown>) => {
  const value = context.getValue() as string;
  if (!value) return "";

  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  let content;

  if (rowHeight === ROW_HEIGHT.small) {
    content = (
      <code className="comet-code w-full truncate rounded-md border bg-[#FBFCFD] px-2 py-3">
        {value}
      </code>
    );
  } else {
    content = (
      <div className="size-full overflow-y-auto overflow-x-hidden whitespace-normal rounded-md border bg-[#FBFCFD] p-2">
        <JsonView
          src={safelyParseJSON(value)}
          theme="github"
          collapseStringsAfterLength={10000}
        />
      </div>
    );
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="py-1.5"
    >
      {content}
    </CellWrapper>
  );
};

export default CodeCell;
