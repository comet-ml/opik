import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

import { AnnotationQueue } from "@/types/annotation-queues";
import useAnnotationQueueDeleteMutation from "@/api/annotation-queues/useAnnotationQueueDeleteMutation";

type AnnotationQueuesActionsPanelProps = {
  queues: AnnotationQueue[];
  onClearSelection?: () => void;
};

const AnnotationQueuesActionsPanel: React.FunctionComponent<
  AnnotationQueuesActionsPanelProps
> = ({ queues, onClearSelection }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const { toast } = useToast();
  const deleteMutation = useAnnotationQueueDeleteMutation();

  const handleDelete = useCallback(async () => {
    if (queues.length === 0) return;

    try {
      await deleteMutation.mutateAsync({
        ids: queues.map((queue) => queue.id),
      });

      toast({
        title: "Success",
        description: `Deleted ${queues.length} annotation queue${queues.length > 1 ? 's' : ''}`,
      });

      if (onClearSelection) {
        onClearSelection();
      }
    } catch (error) {
      // Error handling is done by the mutation hook
    }
  }, [queues, deleteMutation, toast, onClearSelection]);

  if (queues.length === 0) {
    return null;
  }

  return (
    <div className="flex items-center gap-2">
      <span className="text-nowrap text-sm text-muted-foreground">
        {queues.length} selected
      </span>
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={handleDelete}
        title={`Delete annotation queue${queues.length > 1 ? 's' : ''}?`}
        description={`Are you sure you want to delete ${queues.length} annotation queue${queues.length > 1 ? 's' : ''}? This action cannot be undone.`}
        confirmText="Delete"
        confirmButtonVariant="destructive"
      />
      <Button 
        variant="outline" 
        size="sm"
        onClick={() => {
          setOpen(true);
          resetKeyRef.current = resetKeyRef.current + 1;
        }}
      >
        <Trash className="mr-2 size-4" />
        Delete
      </Button>
    </div>
  );
};

export default AnnotationQueuesActionsPanel;
