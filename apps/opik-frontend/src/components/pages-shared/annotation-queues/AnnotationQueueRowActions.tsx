import React from "react";
import copy from "clipboard-copy";
import { useToast } from "@/components/ui/use-toast";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import AddEditAnnotationQueueDialog from "./AddEditAnnotationQueueDialog";
import { AnnotationQueue } from "@/types/annotation-queues";
import useAnnotationQueueDeleteMutation from "@/api/annotation-queues/useAnnotationQueueBatchDeleteMutation";
import useAppStore from "@/store/AppStore";
import { generateSMEURL } from "@/lib/annotation-queues";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type AnnotationQueueRowActionsProps = {
  queue: AnnotationQueue;
};

const AnnotationQueueRowActions: React.FC<AnnotationQueueRowActionsProps> = ({
  queue,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { dialogOpen, open, close } = useRowActionsState();
  const { toast } = useToast();
  const { mutate } = useAnnotationQueueDeleteMutation();

  const handleAnnotate = () => {
    window.open(generateSMEURL(workspaceName, queue.id), "_blank");
  };

  const handleCopySMELink = () => {
    copy(generateSMEURL(workspaceName, queue.id));
    toast({
      title: "Annotation queue link copied to clipboard",
      description:
        "Share this queue with your annotators so they can start annotating and provide feedback to improve the evaluation of your LLM application.",
    });
  };

  const handleDelete = () => {
    mutate({ ids: [queue.id] });
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete annotation queue?"
        description="Deleting an annotation queue will permanently remove it from the system. All associated data and configurations will be lost. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete"
        confirmButtonVariant="destructive"
      />
      <AddEditAnnotationQueueDialog
        queue={queue}
        open={dialogOpen === "edit"}
        setOpen={close}
        projectId={queue.project_id}
        scope={queue.scope}
      />
      <RowActionsButtons
        actions={[
          {
            type: "external",
            label: "Annotate queue",
            showLabel: true,
            onClick: handleAnnotate,
          },
          {
            type: "duplicate",
            label: "Copy sharing link",
            onClick: handleCopySMELink,
          },
          { type: "edit", onClick: open("edit") },
          { type: "delete", onClick: open("delete") },
        ]}
      />
    </>
  );
};

export default AnnotationQueueRowActions;
