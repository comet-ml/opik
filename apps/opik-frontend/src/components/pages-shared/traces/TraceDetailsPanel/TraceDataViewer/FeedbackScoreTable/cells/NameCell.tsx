import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import { ExpandingFeedbackScoreRow } from "../types";
import { Button } from "@/components/ui/button";
import { ChevronDown, ChevronUp } from "lucide-react";

const NameCell = (context: CellContext<ExpandingFeedbackScoreRow, string>) => {
  const row = context.row;
  const name = row.original.name;

  const canExpand = row.getCanExpand();
  const isExpanded = row.getIsExpanded();
  const depth = row.depth;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div
        className="ml-4 flex w-full"
        style={{ paddingLeft: `${depth * 14}px` }}
      >
        {canExpand && (
          <Button
            variant="minimal"
            size="sm"
            className="-ml-8 pl-2  pr-1"
            onClick={(event) => {
              row.toggleExpanded();
              event.stopPropagation();
            }}
          >
            {isExpanded ? (
              <ChevronUp className="mr-1 size-4" />
            ) : (
              <ChevronDown className="mr-1 size-4" />
            )}
          </Button>
        )}
        <ColoredTagNew label={name} className="px-0" />
      </div>
    </CellWrapper>
  );
};

export default NameCell;
