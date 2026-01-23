import React from "react";
import { Prompt } from "@/types/prompts";
import usePromptDeleteMutation from "@/api/prompts/usePromptDeleteMutation";
import AddEditPromptDialog from "@/components/pages/PromptsPage/AddEditPromptDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type PromptRowActionsProps = {
  prompt: Prompt;
};

export const PromptRowActions: React.FC<PromptRowActionsProps> = ({
  prompt,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const promptDeleteMutation = usePromptDeleteMutation();

  const handleDelete = () => {
    promptDeleteMutation.mutate({ promptId: prompt.id });
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title={`Delete ${prompt.name}`}
        description="Deleting a prompt will also remove all associated commits. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete prompt"
        confirmButtonVariant="destructive"
      />
      <AddEditPromptDialog
        prompt={prompt}
        open={dialogOpen === "edit"}
        setOpen={close}
      />
      <RowActionsButtons
        actions={[
          { type: "edit", onClick: open("edit") },
          { type: "delete", onClick: open("delete") },
        ]}
      />
    </>
  );
};
