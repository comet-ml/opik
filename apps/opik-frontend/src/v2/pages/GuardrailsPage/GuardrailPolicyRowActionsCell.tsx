import React, { useCallback, useState } from "react";
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

type CustomMeta = {
  onEdit: (policy: GuardrailPolicy) => void;
  onDuplicate: (policy: GuardrailPolicy) => void;
};

// Edit and Duplicate open the page's panels: a panel owned by the row would close with it when a
// refetch moves the policy to another page.
const GuardrailPolicyRowActionsCell: React.FunctionComponent<
  CellContext<GuardrailPolicy, unknown>
> = (context) => {
  const policy = context.row.original;
  const { onEdit, onDuplicate } = (context.column.columnDef.meta?.custom ??
    {}) as CustomMeta;
  const { toast } = useToast();

  const { mutate: deletePolicy } = useGuardrailPolicyBatchDeleteMutation();

  const [open, setOpen] = useState(false);
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
    setOpen(false);
    onEdit(policy);
  }, [onEdit, policy]);

  const handleDuplicate = useCallback(() => {
    setOpen(false);
    onDuplicate(policy);
  }, [onDuplicate, policy]);

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
      <ConfirmDialog
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
