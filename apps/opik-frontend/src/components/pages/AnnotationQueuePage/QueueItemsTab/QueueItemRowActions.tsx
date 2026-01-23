import React from "react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useAnnotationQueueDeleteItemsMutation from "@/api/annotation-queues/useAnnotationQueueDeleteItemsMutation";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import { Thread, Trace } from "@/types/traces";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type QueueItemRowActionsProps = {
  item: Thread | Trace;
  annotationQueueId: string;
};

const QueueItemRowActions: React.FC<QueueItemRowActionsProps> = ({
  item,
  annotationQueueId,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const { mutate } = useAnnotationQueueDeleteItemsMutation();

  const handleDelete = () => {
    mutate({
      annotationQueueId,
      ids: [getAnnotationQueueItemId(item)],
    });
    close();
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Remove from queue"
        description="Removing annotation queue items will remove them from the queue. This action can't be undone. Are you sure you want to continue?"
        confirmText="Remove item"
        confirmButtonVariant="destructive"
      />
      <RowActionsButtons
        actions={[
          {
            type: "delete",
            label: "Remove from queue",
            onClick: open("delete"),
          },
        ]}
      />
    </>
  );
};

export default QueueItemRowActions;
