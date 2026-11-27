import { Pencil, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { Prompt } from "@/types/prompts";
import usePromptDeleteMutation from "@/api/prompts/usePromptDeleteMutation";
import EditPromptDetailsSheet from "@/v2/pages/PromptsPage/EditPromptDetailsSheet";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import { usePermissions } from "@/contexts/PermissionsContext";

const EDIT_KEY = 1;
const DELETE_KEY = 2;

export const PromptRowActionsCell: React.FunctionComponent<
  CellContext<Prompt, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const prompt = context.row.original;
  const [open, setOpen] = useState<number | boolean>(false);

  const {
    permissions: { canEditPrompts, canDeletePrompts },
  } = usePermissions();

  const hasActions = canEditPrompts || canDeletePrompts;

  const promptDeleteMutation = usePromptDeleteMutation();

  const deletePromptHandler = useCallback(() => {
    promptDeleteMutation.mutate({
      promptId: prompt.id,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prompt.id]);

  if (!hasActions) return null;

  const openDialog = (dialog: number) => {
    setOpen(dialog);
    resetKeyRef.current = resetKeyRef.current + 1;
  };
  const rowActions: RowAction[] = [
    ...(canEditPrompts
      ? [
          {
            id: "edit",
            label: "Edit",
            icon: Pencil,
            onClick: () => openDialog(EDIT_KEY),
          },
        ]
      : []),
    ...(canDeletePrompts
      ? [
          {
            id: "delete",
            label: "Delete",
            icon: Trash,
            onClick: () => openDialog(DELETE_KEY),
            destructive: true,
          },
        ]
      : []),
  ];

  return (
    <>
      <EditPromptDetailsSheet
        key={`edit-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        prompt={prompt}
      />

      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 2}
        setOpen={setOpen}
        onConfirm={deletePromptHandler}
        title={`Delete ${prompt.name}`}
        description="Deleting a prompt will also remove all associated commits. This action can’t be undone. Are you sure you want to continue?"
        confirmText="Delete prompt"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};
