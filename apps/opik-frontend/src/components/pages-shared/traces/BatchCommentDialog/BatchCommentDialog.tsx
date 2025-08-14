import React, { useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/use-toast";

import useTraceCommentsBatchCreateMutation from "@/api/traces/useTraceCommentsBatchCreateMutation";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { Trace, Span } from "@/types/traces";

type BatchCommentDialogProps = {
  rows: Array<Trace | Span>;
  type: TRACE_DATA_TYPE;
  projectId: string;
  open: boolean | number;
  setOpen: (o: boolean | number) => void;
  onSuccess?: () => void;
};

const MAX_ENTITIES = 10;

const BatchCommentDialog: React.FunctionComponent<BatchCommentDialogProps> = ({
  rows,
  type,
  projectId,
  open,
  setOpen,
  onSuccess,
}) => {
  const { toast } = useToast();

  const batchCreateTraceComments = useTraceCommentsBatchCreateMutation();

  const [commentText, setCommentText] = useState<string>("");

  const handleClose = () => {
    setOpen(false);
    setCommentText("");
  };

  const handleAddComments = async () => {
    try {
      if (type !== TRACE_DATA_TYPE.traces) {
        throw new Error("Batch comments are only supported for traces.");
      }

      const ids = rows.map((r) => r.id);
      const traceId = rows[0]?.id;

      await batchCreateTraceComments.mutateAsync({ ids, text: commentText, traceId });

      if (onSuccess) onSuccess();

      handleClose();
    } catch {
      toast({
        title: "Error",
        description: "Failed to add comments",
        variant: "destructive",
      });
    }
  };

  const disabled =
    !commentText.trim() ||
    rows.length > MAX_ENTITIES ||
    batchCreateTraceComments.isPending;

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>Add comment to {rows.length} traces</DialogTitle>
        </DialogHeader>

        {rows.length > MAX_ENTITIES && (
          <div className="mb-2 text-sm text-red-500">
            You can comment on up to {MAX_ENTITIES} items at a time.
          </div>
        )}

        <Textarea
          placeholder="Comment text"
          value={commentText}
          onChange={(e) => setCommentText(e.target.value)}
          disabled={rows.length > MAX_ENTITIES}
        />

        <DialogFooter>
          <Button variant="outline" onClick={handleClose} disabled={batchCreateTraceComments.isPending}>
            Cancel
          </Button>
          <Button onClick={handleAddComments} disabled={disabled}>
            Add comments
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default BatchCommentDialog;

