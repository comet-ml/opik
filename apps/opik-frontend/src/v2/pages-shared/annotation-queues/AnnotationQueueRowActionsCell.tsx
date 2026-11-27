import React, { useCallback, useRef, useState } from "react";
import { Copy, Trash, Pencil, UserRoundPen } from "lucide-react";
import { CellContext } from "@tanstack/react-table";
import copy from "clipboard-copy";

import { useToast } from "@/ui/use-toast";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";

import { AnnotationQueue } from "@/types/annotation-queues";
import useAnnotationQueueDeleteMutation from "@/api/annotation-queues/useAnnotationQueueBatchDeleteMutation";
import useAppStore from "@/store/AppStore";
import { generateSMEURL } from "@/lib/annotation-queues";
import { usePermissions } from "@/contexts/PermissionsContext";
import AddEditAnnotationQueueDialog from "./AddEditAnnotationQueueDialog";

const AnnotationQueueRowActionsCell: React.FunctionComponent<
  CellContext<AnnotationQueue, unknown>
> = (context) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const resetKeyRef = useRef(0);
  const queue = context.row.original;
  const [open, setOpen] = useState<number | boolean>(false);
  const { toast } = useToast();
  const { mutate } = useAnnotationQueueDeleteMutation();

  const {
    permissions: { canEditAnnotationQueues, canDeleteAnnotationQueues },
  } = usePermissions();

  const handleCopySMELink = useCallback(() => {
    copy(generateSMEURL(workspaceName, queue.id));
    toast({
      title: "Annotation queue link copied to clipboard",
      description:
        "Share this queue with your annotators so they can start annotating and provide feedback to improve the evaluation of your LLM application.",
    });
  }, [queue.id, toast, workspaceName]);

  const deleteQueueHandler = useCallback(() => {
    mutate({
      ids: [queue.id],
    });
  }, [queue.id, mutate]);

  const handleEdit = useCallback(() => {
    setOpen(2);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const actions: RowAction[] = [
    {
      id: "annotators-view",
      label: "Annotator's view",
      icon: UserRoundPen,
      showLabel: true,
      link: {
        to: "/$workspaceName/sme",
        params: { workspaceName },
        search: { queueId: queue.id },
        target: "_blank",
      },
    },
    {
      id: "copy-link",
      label: "Copy sharing link",
      icon: Copy,
      onClick: handleCopySMELink,
    },
    ...(canEditAnnotationQueues
      ? [{ id: "edit", label: "Edit", icon: Pencil, onClick: handleEdit }]
      : []),
    ...(canDeleteAnnotationQueues
      ? [
          {
            id: "delete",
            label: "Delete",
            icon: Trash,
            destructive: true,
            onClick: () => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            },
          },
        ]
      : []),
  ];

  return (
    <>
      {canDeleteAnnotationQueues && (
        <ConfirmDialog
          key={`delete-${resetKeyRef.current}`}
          open={open === 1}
          setOpen={setOpen}
          onConfirm={deleteQueueHandler}
          title="Delete annotation queue?"
          description="Deleting an annotation queue will permanently remove it from the system. All associated data and configurations will be lost. This action can't be undone. Are you sure you want to continue?"
          confirmText="Delete"
          confirmButtonVariant="destructive"
        />
      )}
      <AddEditAnnotationQueueDialog
        key={`edit-${resetKeyRef.current}`}
        queue={queue}
        open={open === 2}
        setOpen={setOpen}
        projectId={queue.project_id}
        scope={queue.scope}
      />
      <RowActions actions={actions} />
    </>
  );
};

export default AnnotationQueueRowActionsCell;
