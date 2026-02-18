import { CellContext } from "@tanstack/react-table";
import { Pencil, Trash } from "lucide-react";
import { Button } from "@/components/ui/button";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { BehaviorDisplayRow } from "@/types/evaluation-suites";

type CustomMeta = {
  onEdit: (row: BehaviorDisplayRow) => void;
  onDelete: (id: string) => void;
};

const BehaviorActionsCell: React.FunctionComponent<
  CellContext<BehaviorDisplayRow, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { onEdit, onDelete } = (custom ?? {}) as CustomMeta;
  const row = context.row.original;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <Button variant="ghost" size="icon-sm" onClick={() => onEdit(row)}>
        <Pencil className="size-3.5" />
      </Button>
      <Button variant="ghost" size="icon-sm" onClick={() => onDelete(row.id)}>
        <Trash className="size-3.5" />
      </Button>
    </CellWrapper>
  );
};

export default BehaviorActionsCell;
