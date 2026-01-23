import React from "react";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import useFeedbackDefinitionDeleteMutation from "@/api/feedback-definitions/useFeedbackDefinitionDeleteMutation";
import AddEditFeedbackDefinitionDialog from "@/components/shared/AddEditFeedbackDefinitionDialog/AddEditFeedbackDefinitionDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type FeedbackDefinitionsRowActionsProps = {
  feedbackDefinition: FeedbackDefinition;
};

const FeedbackDefinitionsRowActions: React.FC<
  FeedbackDefinitionsRowActionsProps
> = ({ feedbackDefinition }) => {
  const { dialogOpen, open, close } = useRowActionsState();

  const feedbackDefinitionDeleteMutation =
    useFeedbackDefinitionDeleteMutation();

  const handleDelete = () => {
    feedbackDefinitionDeleteMutation.mutate({
      feedbackDefinitionId: feedbackDefinition.id,
    });
    close();
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete feedback definition"
        description="This action can't be undone. Existing scored traces won't be affected. Are you sure you want to continue?"
        confirmText="Delete feedback definition"
        confirmButtonVariant="destructive"
      />
      <AddEditFeedbackDefinitionDialog
        feedbackDefinition={feedbackDefinition}
        mode="edit"
        open={dialogOpen === "edit"}
        setOpen={close}
      />
      <AddEditFeedbackDefinitionDialog
        feedbackDefinition={feedbackDefinition}
        mode="clone"
        open={dialogOpen === "clone"}
        setOpen={close}
      />
      <RowActionsButtons
        actions={[
          { type: "edit", onClick: open("edit") },
          { type: "duplicate", label: "Clone", onClick: open("clone") },
          { type: "delete", onClick: open("delete") },
        ]}
      />
    </>
  );
};

export default FeedbackDefinitionsRowActions;
