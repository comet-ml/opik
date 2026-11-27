import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy, Pencil, Trash } from "lucide-react";

import { FeedbackDefinition } from "@/types/feedback-definitions";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useFeedbackDefinitionDeleteMutation from "@/api/feedback-definitions/useFeedbackDefinitionDeleteMutation";
import AddEditFeedbackDefinitionDialog from "@/v2/pages-shared/datasets/AddEditFeedbackDefinitionDialog/AddEditFeedbackDefinitionDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";

const FeedbackDefinitionsRowActionsCell: React.FunctionComponent<
  CellContext<FeedbackDefinition, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const feedbackDefinition = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const feedbackDefinitionDeleteMutation =
    useFeedbackDefinitionDeleteMutation();

  const deleteFeedbackDefinitionHandler = useCallback(() => {
    feedbackDefinitionDeleteMutation.mutate({
      feedbackDefinitionId: feedbackDefinition.id,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedbackDefinition.id]);

  const openDialog = (dialog: number) => {
    setOpen(dialog);
    resetKeyRef.current = resetKeyRef.current + 1;
  };

  const rowActions: RowAction[] = [
    {
      id: "edit",
      label: "Edit",
      icon: Pencil,
      onClick: () => openDialog(2),
    },
    {
      id: "clone",
      label: "Clone",
      icon: Copy,
      onClick: () => openDialog(3),
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
      <AddEditFeedbackDefinitionDialog
        key={`edit-${resetKeyRef.current}`}
        feedbackDefinition={feedbackDefinition}
        open={open === 2 || open === 3}
        setOpen={setOpen}
        mode={open === 2 ? "edit" : "clone"}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteFeedbackDefinitionHandler}
        title="Delete feedback definition"
        description="This action can’t be undone. Existing scored traces won’t be affected. Are you sure you want to continue?"
        confirmText="Delete feedback definition"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};

export default FeedbackDefinitionsRowActionsCell;
