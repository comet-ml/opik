import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import { useTranslation } from "react-i18next";

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
  const { t } = useTranslation();
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
        title={t("annotationQueues.deleteQueues")}
        description={t("annotationQueues.deleteConfirm")}
        confirmText={t("annotationQueues.deleteQueues")}
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={t("common.delete")}>
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
