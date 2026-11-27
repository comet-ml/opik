import { Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useAnnotationQueueDeleteItemsMutation from "@/api/annotation-queues/useAnnotationQueueDeleteItemsMutation";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import { CellContext } from "@tanstack/react-table";
import { Thread, Trace } from "@/types/traces";

type CustomMeta = {
  annotationQueueId: string;
};

const QueueItemRowActionsCell: React.FC<
  CellContext<Thread | Trace, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { annotationQueueId } = (custom ?? {}) as CustomMeta;

  const resetKeyRef = useRef(0);
  const item = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useAnnotationQueueDeleteItemsMutation();

  const deleteItemHandler = useCallback(() => {
    if (annotationQueueId) {
      mutate({
        annotationQueueId,
        ids: [getAnnotationQueueItemId(item)],
      });
    }
  }, [annotationQueueId, mutate, item]);

  const actions: RowAction[] = [
    {
      id: "remove",
      label: "Remove from queue",
      icon: Trash,
      destructive: true,
      onClick: () => {
        setOpen(1);
        resetKeyRef.current = resetKeyRef.current + 1;
      },
    },
  ];

  return (
    <>
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteItemHandler}
        title="Remove from queue"
        description="Removing annotation queue items will remove them from the queue. This action can't be undone. Are you sure you want to continue?"
        confirmText="Remove item"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={actions} />
    </>
  );
};

export default QueueItemRowActionsCell;
