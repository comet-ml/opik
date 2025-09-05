import { CellContext } from "@tanstack/react-table";
import { Button } from "@/components/ui/button";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import { X } from "lucide-react";
import { getIsParentFeedbackScoreRow } from "../utils";

type CustomMeta = {
  onDelete: (row: ExpandingFeedbackScoreRow) => void;
};

const ActionsCell: React.FunctionComponent<
  CellContext<ExpandingFeedbackScoreRow, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { onDelete } = (custom ?? {}) as CustomMeta;

  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(
    context.row.original,
  );

  if (isParentFeedbackScoreRow) {
    return null;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <Button
        size="icon-sm"
        variant="ghost"
        className="-mr-2.5"
        onClick={() => onDelete(context.row.original)}
      >
        <X className="size-4 text-light-slate" />
      </Button>
    </CellWrapper>
  );
};

export default ActionsCell;
