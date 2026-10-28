import React, { useCallback, useMemo, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy, MoreHorizontal, Pencil, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { useToast } from "@/ui/use-toast";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { GuardrailPolicy } from "@/types/guardrails";
import useGuardrailPolicyBatchDeleteMutation from "@/api/guardrail-policies/useGuardrailPolicyBatchDeleteMutation";
import GuardrailPolicyPanel from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/GuardrailPolicyPanel";

const GuardrailPolicyRowActionsCell: React.FunctionComponent<
  CellContext<GuardrailPolicy, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const policy = context.row.original;
  const { toast } = useToast();

  // Only the names on this page, which is enough to stop the obvious "(copy)" collisions; a clash
  // with a policy on another page still surfaces as an inline error on the name field.
  const takenNames = useMemo(
    () => context.table.getRowModel().rows.map((row) => row.original.name),
    [context.table],
  );

  const { mutate: deletePolicy } = useGuardrailPolicyBatchDeleteMutation();

  const [open, setOpen] = useState(false);
  const [openEdit, setOpenEdit] = useState(false);
  const [openDuplicate, setOpenDuplicate] = useState(false);
  const [openConfirmDialog, setOpenConfirmDialog] = useState(false);

  const handleDeleteConfirm = useCallback(() => {
    deletePolicy(
      { ids: [policy.id] },
      {
        onSuccess: () =>
          toast({
            title: "Policy deleted",
            description: `"${policy.name}" no longer protects this workspace.`,
          }),
      },
    );
  }, [deletePolicy, policy, toast]);

  const handleEdit = useCallback(() => {
    setOpenEdit(true);
    setOpen(false);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleDuplicate = useCallback(() => {
    setOpenDuplicate(true);
    setOpen(false);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleDelete = useCallback(() => {
    setOpenConfirmDialog(true);
    setOpen(false);
  }, []);

  return (
    // CellWrapper caps the cell at the table's row height; without it the size-10 trigger button
    // would make every row taller than the rows on the neighbouring tables.
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={handleEdit}>
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem onClick={handleDuplicate}>
            <Copy className="mr-2 size-4" />
            Duplicate
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={handleDelete} variant="destructive">
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <GuardrailPolicyPanel
        mode="edit"
        key={`edit-${resetKeyRef.current}`}
        policy={policy}
        open={openEdit}
        setOpen={setOpenEdit}
      />
      <GuardrailPolicyPanel
        mode="duplicate"
        key={`duplicate-${resetKeyRef.current}`}
        policy={policy}
        takenNames={takenNames}
        open={openDuplicate}
        setOpen={setOpenDuplicate}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={openConfirmDialog}
        setOpen={setOpenConfirmDialog}
        onConfirm={handleDeleteConfirm}
        title="Delete policy"
        description={`Deleting "${policy.name}" removes the protection it provides, and any application that still names it will fail. This action can't be undone.`}
        confirmText="Delete policy"
        confirmButtonVariant="destructive"
      />
    </CellWrapper>
  );
};

export default GuardrailPolicyRowActionsCell;
