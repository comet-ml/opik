import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Pencil, Copy, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { EvaluatorsRule } from "@/types/automations";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useRulesBatchDeleteMutation from "@/api/automations/useRulesBatchDeleteMutation";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

interface RuleRowActionsCellProps {
  openEditDialog: (ruleId: string) => void;
  openCloneDialog: (ruleId: string) => void;
}

const RuleRowActionsCell: React.FC<
  RuleRowActionsCellProps & CellContext<EvaluatorsRule, unknown>
> = ({ openEditDialog, openCloneDialog, row, column, table }) => {
  const resetKeyRef = useRef(0);
  const rule = row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useRulesBatchDeleteMutation();

  const deleteRuleHandler = useCallback(() => {
    mutate({
      ids: [rule.id],
    });
  }, [rule.id, mutate]);

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteRuleHandler}
        title="Delete evaluation rule"
        description={`Deleting an online evaluation rule will stop scoring for all new traces. Existing traces that have already been scored won’t be affected. This action can’t be undone. Are you sure you want to continue?

Tip: To pause scoring without deleting, disable the rule.`}
        confirmText="Delete evaluation rule"
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5 ">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={() => openEditDialog(rule.id)}>
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => openCloneDialog(rule.id)}>
            <Copy className="mr-2 size-4" />
            Clone
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
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
};

export default RuleRowActionsCell;
