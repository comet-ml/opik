import React, { useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, RotateCcw, Trash } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { CatalogEntry } from "@/lib/model-prices";

export type PriceRowActions = {
  onEdit: (entry: CatalogEntry) => void;
  onRemove: (entry: CatalogEntry) => void;
};

const PriceRowActionsCell: React.FC<CellContext<CatalogEntry, unknown>> = (
  context,
) => {
  const [isConfirmOpen, setIsConfirmOpen] = useState(false);
  const entry = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { onEdit, onRemove } = (custom ?? {}) as PriceRowActions;
  const isOverride = entry.type === "override";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      {entry.row && (
        <>
          <ConfirmDialog
            open={isConfirmOpen}
            setOpen={setIsConfirmOpen}
            onConfirm={() => onRemove(entry)}
            title={
              isOverride
                ? `Restore the defaults of ${entry.model}?`
                : `Delete ${entry.model}`
            }
            description={
              isOverride
                ? "Spans saved after this use Opik's built-in price. Spans already saved keep their cost."
                : "Spans saved after this won't have a price for this model. Spans already saved keep their cost."
            }
            confirmText={isOverride ? "Restore all defaults" : "Delete model"}
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
              <DropdownMenuItem onClick={() => onEdit(entry)}>
                <Pencil className="mr-2 size-4" />
                Edit
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              {isOverride ? (
                <DropdownMenuItem onClick={() => setIsConfirmOpen(true)}>
                  <RotateCcw className="mr-2 size-4" />
                  Restore all defaults
                </DropdownMenuItem>
              ) : (
                <DropdownMenuItem
                  onClick={() => setIsConfirmOpen(true)}
                  variant="destructive"
                >
                  <Trash className="mr-2 size-4" />
                  Delete
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </>
      )}
    </CellWrapper>
  );
};

export default PriceRowActionsCell;
