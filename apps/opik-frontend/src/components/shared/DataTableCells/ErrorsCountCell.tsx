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

const getErrorDeviationCopy = (error: ProjectErrorCount) => {
  if (error.deviation === 0) {
    return "(No new errors this week)";
  }

  if (error.deviation_percentage < 0) {
    return `(-${error.deviation_percentage}% since last week)`;
  }

  if (error.deviation_percentage > 0) {
    return `(+${error.deviation_percentage}% since last week)`;
  }

  if (error.deviation_percentage === 0) {
    return "(No change since last week)";
  }

  return ``;
};

const ErrorsCountCell = (context: CellContext<unknown, ProjectErrorCount>) => {
  const error = context.getValue();
  const { custom } = context.column.columnDef.meta ?? {};
  const { onZoomIn } = (custom ?? {}) as CustomMeta;

  if (!error?.count) {
    return null;
  }

  const deviationCopy = getErrorDeviationCopy(error);

  const onClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onZoomIn(context.row.original);
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group relative"
      stopClickPropagation
    >
      <CellTooltipWrapper content={`${error.count} errors ${deviationCopy}`}>
        <Tag
          onClick={onClick}
          variant="red"
          className="flex cursor-pointer items-center gap-1"
        >
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
        onClick={onClick}
      >
        <ZoomIn />
      </Button>
    </CellWrapper>
  );
};

export default ErrorsCountCell;
