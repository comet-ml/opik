import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Trace, Thread } from "@/types/traces";
import useAnnotationQueueDeleteItemsMutation from "@/api/annotation-queues/useAnnotationQueueDeleteItemsMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";

type QueueItemActionsPanelProps = {
  items: (Trace | Thread)[];
  annotationQueueId?: string;
};

const QueueItemActionsPanel: React.FunctionComponent<
  QueueItemActionsPanelProps
> = ({ items, annotationQueueId }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !items?.length || !annotationQueueId;

  const { mutate } = useAnnotationQueueDeleteItemsMutation();

  const deleteItemsHandler = useCallback(() => {
    if (annotationQueueId) {
      mutate({
        annotationQueueId,
        ids: items.map(getAnnotationQueueItemId),
      });
    }
  }, [items, annotationQueueId, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteItemsHandler}
        title="Remove from queue"
        description="Removing annotation queue items will remove them from the queue. This action can't be undone. Are you sure you want to continue?"
        confirmText="Remove items"
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content="Remove selected items from queue">
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default QueueItemActionsPanel;
