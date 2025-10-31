import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { AnnotationQueue } from "@/types/annotation-queues";
import useAnnotationQueueBatchDeleteMutation from "@/api/annotation-queues/useAnnotationQueueBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type AnnotationQueuesActionsPanelProps = {
  queues: AnnotationQueue[];
};

const AnnotationQueuesActionsPanel: React.FunctionComponent<
  AnnotationQueuesActionsPanelProps
> = ({ queues }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !queues?.length;

  const { mutate } = useAnnotationQueueBatchDeleteMutation();

  const deleteQueuesHandler = useCallback(() => {
    mutate(
      {
        ids: queues.map((q) => q.id),
      },
      {
        onSuccess: () => {
          setOpen(false);
        },
      },
    );
  }, [queues, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteQueuesHandler}
        title="Delete annotation queues"
        description="Deleting these annotation queues will permanently remove all associated annotations and reviews. This action cannot be undone. Are you sure you want to continue?"
        confirmText="Delete annotation queues"
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content="Delete">
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

export default AnnotationQueuesActionsPanel;
