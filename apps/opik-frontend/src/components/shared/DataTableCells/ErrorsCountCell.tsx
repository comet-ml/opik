import { CellContext } from "@tanstack/react-table";
import { Tag } from "@/components/ui/tag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TriangleAlert, ZoomIn } from "lucide-react";
import CellTooltipWrapper from "./CellTooltipWrapper";
import { ProjectErrorCount } from "@/types/projects";
import { Button } from "@/components/ui/button";

type CustomMeta = {
  onZoomIn: (row: unknown) => void;
};

const ErrorsCountCell = (context: CellContext<unknown, ProjectErrorCount>) => {
  const error = context.getValue();
  const { custom } = context.column.columnDef.meta ?? {};
  const { onZoomIn } = (custom ?? {}) as CustomMeta;

  if (!error?.count) {
    return null;
  }

  const deviation =
    error.deviation > 0 ? `+${error.deviation}%` : `${error.deviation}%`;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group relative"
    >
      <CellTooltipWrapper
        content={`${error.count} errors (${deviation} vs. same day last week)`}
      >
        <Tag variant="red" className="flex items-center gap-1">
          <TriangleAlert className="size-3 shrink-0" />
          <span>{error.count}</span>
          <span className="truncate">
            {error.count === 1 ? "error" : "errors"}
          </span>
        </Tag>
      </CellTooltipWrapper>

      <Button
        className="absolute right-1 opacity-0 group-hover:opacity-100"
        size="icon-xs"
        variant="outline"
        onClick={(e) => {
          e.stopPropagation();
          onZoomIn(context.row.original);
        }}
      >
        <ZoomIn />
      </Button>
    </CellWrapper>
  );
};

export default ErrorsCountCell;
