import useTracesBatchFeedbackScoresMutation from "@/api/traces/useTracesBatchFeedbackScoresMutation";
import useSpansBatchFeedbackScoresMutation from "@/api/traces/useSpansBatchFeedbackScoresMutation";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useToast } from "@/components/ui/use-toast";
import FeedbackScoresEditor from "@/components/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import { Span, Trace } from "@/types/traces";
import React, { useState } from "react";
import { UpdateFeedbackScoreData } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

type BatchAnnotateDialogProps = {
  rows: Array<Trace | Span>;
  type: TRACE_DATA_TYPE;
  open: boolean | number;
  setOpen: (o: boolean | number) => void;
  projectId: string;
  onSuccess?: () => void;
};

const MAX_ENTITIES = 10;

const BatchAnnotateDialog: React.FunctionComponent<
  BatchAnnotateDialogProps
> = ({ rows, type, open, setOpen, projectId, onSuccess }) => {
  const { toast } = useToast();
  const [selectedScore, setSelectedScore] =
    useState<UpdateFeedbackScoreData | null>(null);

  const tracesBatchMutation = useTracesBatchFeedbackScoresMutation();
  const spansBatchMutation = useSpansBatchFeedbackScoresMutation();

  const handleClose = () => {
    setOpen(false);
    setSelectedScore(null);
  };

  const disabled =
    !selectedScore ||
    rows.length > MAX_ENTITIES ||
    tracesBatchMutation.isPending ||
    spansBatchMutation.isPending;

  const handleAnnotate = async () => {
    try {
      if (!selectedScore) {
        return;
      }

      if (type === TRACE_DATA_TYPE.traces) {
        await tracesBatchMutation.mutateAsync({
          projectId,
          traceIds: (rows as Trace[]).map((r) => r.id),
          name: selectedScore.name,
          value: selectedScore.value,
          categoryName: selectedScore.categoryName,
          reason: selectedScore.reason,
        });
      } else {
        await spansBatchMutation.mutateAsync({
          projectId,
          spans: (rows as Span[]).map((r) => ({
            id: r.id,
            traceId: r.trace_id,
          })),
          name: selectedScore.name,
          value: selectedScore.value,
          categoryName: selectedScore.categoryName,
          reason: selectedScore.reason,
        });
      }

      if (onSuccess) onSuccess();
      handleClose();
    } catch (e) {
      toast({
        title: "Error",
        description: "Failed to annotate items",
        variant: "destructive",
      });
    }
  };

  const handleUpdateFeedbackScore = (update: UpdateFeedbackScoreData) => {
    setSelectedScore(update);
  };

  const handleDeleteFeedbackScore = () => {
    setSelectedScore(null);
  };

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>
            Annotate {rows.length}{" "}
            {type === TRACE_DATA_TYPE.traces ? "traces" : "spans"}
          </DialogTitle>
        </DialogHeader>
        {rows.length > MAX_ENTITIES && (
          <div className="mb-2 text-sm text-red-500">
            You can annotate up to {MAX_ENTITIES} items at a time. Please
            reduce your selection.
          </div>
        )}
        <div className="py-4">
          <FeedbackScoresEditor
            feedbackScores={[]}
            onUpdateFeedbackScore={handleUpdateFeedbackScore}
            onDeleteFeedbackScore={handleDeleteFeedbackScore}
            entityCopy="traces"
          />
        </div>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={handleClose}
            disabled={tracesBatchMutation.isPending || spansBatchMutation.isPending}
          >
            Cancel
          </Button>
          <Button onClick={handleAnnotate} disabled={disabled}>
            Annotate
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default BatchAnnotateDialog; 