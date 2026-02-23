import { useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { BehaviorDisplayRow } from "@/types/evaluation-suites";

interface CustomMeta {
  onEdit: (row: BehaviorDisplayRow) => void;
  onDelete: (id: string) => void;
}

function EvaluatorActionsCell(
  context: CellContext<BehaviorDisplayRow, unknown>,
): React.ReactElement {
  const resetKeyRef = useRef(0);
  const { custom } = context.column.columnDef.meta ?? {};
  const { onEdit, onDelete } = (custom ?? {}) as CustomMeta;
  const row = context.row.original;
  const [open, setOpen] = useState(false);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={() => onDelete(row.id)}
        title="Delete evaluator"
        description="Are you sure you want to delete this evaluator? This action can't be undone."
        confirmText="Delete evaluator"
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              onEdit(row);
              resetKeyRef.current += 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              setOpen(true);
              resetKeyRef.current += 1;
            }}
            variant="destructive"
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
}

export default EvaluatorActionsCell;
