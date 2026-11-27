import React, { useCallback, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy, Pencil, Trash } from "lucide-react";

import { useToast } from "@/ui/use-toast";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
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

  const rowActions: RowAction[] = [
    {
      id: "edit",
      label: "Edit",
      icon: Pencil,
      onClick: () => onEdit(policy),
    },
    {
      id: "duplicate",
      label: "Duplicate",
      icon: Copy,
      onClick: () => onDuplicate(policy),
    },
    {
      id: "delete",
      label: "Delete",
      icon: Trash,
      onClick: () => setOpenConfirmDialog(true),
      destructive: true,
    },
  ];

  return (
    <>
      <ConfirmDialog
        open={openConfirmDialog}
        setOpen={setOpenConfirmDialog}
        onConfirm={handleDeleteConfirm}
        title="Delete policy"
        description={`Deleting "${policy.name}" removes the protection it provides, and any application that still names it will fail. This action can't be undone.`}
        confirmText="Delete policy"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};

export default GuardrailPolicyRowActionsCell;
