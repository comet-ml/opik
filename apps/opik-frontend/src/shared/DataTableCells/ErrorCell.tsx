import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { BaseTraceDataErrorInfo } from "@/types/traces";
import CellTooltipWrapper from "./CellTooltipWrapper";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { TriangleAlert } from "lucide-react";

const ErrorCell = <TData,>(
  context: CellContext<TData, BaseTraceDataErrorInfo | undefined>,
) => {
  const value = context.getValue();

  if (!value) return null;

  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  const errorMessage = value.message
    ? `Message: ${value.message}`
    : "Error message is not specified";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={errorMessage}>
        <Tag variant="red" size={tagSize} className="flex items-center gap-1">
          <TriangleAlert className="size-3 shrink-0" />
          <span className="truncate">{value.exception_type}</span>
        </Tag>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

export default ErrorCell;
