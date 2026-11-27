import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Pencil, Trash } from "lucide-react";

import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import ManageAIProviderDialog from "@/v2/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import { ProviderObject } from "@/types/providers";
import useProviderKeysDeleteMutation from "@/api/provider-keys/useProviderKeysDeleteMutation";

const AIProvidersRowActionsCell: React.FunctionComponent<
  CellContext<ProviderObject, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const providerKey = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const isReadOnly = providerKey.read_only === true;

  const { mutate: deleteProviderKey } = useProviderKeysDeleteMutation();

  const deleteProviderKeyHandler = useCallback(() => {
    deleteProviderKey({
      providerId: providerKey.id,
    });
  }, [providerKey.id, deleteProviderKey]);

  const openDialog = (dialog: number) => {
    setOpen(dialog);
    resetKeyRef.current = resetKeyRef.current + 1;
  };

  // Read-only (system-managed) providers can't be edited or deleted; the name
  // column marks them as read-only.
  const rowActions: RowAction[] = isReadOnly
    ? []
    : [
        {
          id: "edit",
          label: "Edit",
          icon: Pencil,
          onClick: () => openDialog(2),
        },
        {
          id: "delete",
          label: "Delete",
          icon: Trash,
          onClick: () => openDialog(1),
          destructive: true,
        },
      ];

  return (
    <>
      <ManageAIProviderDialog
        key={`edit-${resetKeyRef.current}`}
        providerKey={providerKey}
        open={open === 2}
        setOpen={setOpen}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteProviderKeyHandler}
        title="Delete configuration"
        description="This configuration is shared across the workspace. Deleting it will remove access for everyone. This action can't be undone. Are you sure you want to proceed?"
        confirmText="Delete configuration"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};

export default AIProvidersRowActionsCell;
