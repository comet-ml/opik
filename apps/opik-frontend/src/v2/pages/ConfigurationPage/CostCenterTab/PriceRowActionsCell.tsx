import React, { useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { RotateCcw, Trash } from "lucide-react";
import { Button } from "@/ui/button";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { CatalogEntry } from "@/lib/model-prices";

export type PriceRowActions = {
  onRemove: (entry: CatalogEntry) => void;
};

const PriceRowActionsCell: React.FC<CellContext<CatalogEntry, unknown>> = (
  context,
) => {
  const [isConfirmOpen, setIsConfirmOpen] = useState(false);
  const entry = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { onRemove } = (custom ?? {}) as PriceRowActions;
  const isOverride = entry.type === "override";
  const actionLabel = isOverride ? "Restore all defaults" : "Delete";
  const Icon = isOverride ? RotateCcw : Trash;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-center px-0"
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
          <TooltipWrapper content={actionLabel}>
            <Button
              variant="minimal"
              size="icon-2xs"
              aria-label={`${actionLabel}: ${entry.model}`}
              onClick={() => setIsConfirmOpen(true)}
            >
              <Icon />
            </Button>
          </TooltipWrapper>
        </>
      )}
    </CellWrapper>
  );
};

export default PriceRowActionsCell;
