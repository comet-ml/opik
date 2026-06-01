import React, { useCallback, useState } from "react";
import {
  FEEDBACK_SCORE_TYPE,
  Span,
  Trace,
  TraceFeedbackScore,
} from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import FeedbackScoresEditor from "@/v2/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import { UpdateFeedbackScoreData } from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import useTraceFeedbackScoreBatchSetMutation from "@/api/traces/useTraceFeedbackScoreBatchSetMutation";
import useSpanFeedbackScoreBatchSetMutation from "@/api/traces/useSpanFeedbackScoreBatchSetMutation";

type AddAnnotationDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  projectId: string;
  type: TRACE_DATA_TYPE;
};

const AddAnnotationDialog: React.FunctionComponent<
  AddAnnotationDialogProps
> = ({ rows, open, setOpen, projectId, type }) => {
  const { toast } = useToast();
  const [pendingScores, setPendingScores] = useState<
    Record<string, TraceFeedbackScore>
  >({});

  const { mutateAsync: batchSetTraceScores } =
    useTraceFeedbackScoreBatchSetMutation();
  const { mutateAsync: batchSetSpanScores } =
    useSpanFeedbackScoreBatchSetMutation();

  const handleClose = useCallback(() => {
    setOpen(false);
    setPendingScores({});
  }, [setOpen]);

  const onUpdateFeedbackScore = useCallback(
    (update: UpdateFeedbackScoreData) => {
      setPendingScores((prev) => ({
        ...prev,
        [update.name]: {
          name: update.name,
          value: update.value,
          source: FEEDBACK_SCORE_TYPE.ui,
          category_name: update.categoryName,
          reason: update.reason,
        },
      }));
    },
    [],
  );

  const onDeleteFeedbackScore = useCallback((name: string) => {
    setPendingScores((prev) => {
      const next = { ...prev };
      delete next[name];
      return next;
    });
  }, []);

  const feedbackScores: TraceFeedbackScore[] = Object.values(pendingScores);

  const handleApply = async () => {
    if (feedbackScores.length === 0) {
      handleClose();
      return;
    }

    const scoreEntries = feedbackScores.map((score) => ({
      name: score.name,
      value: score.value,
      categoryName: score.category_name,
      reason: score.reason,
    }));

    const allScores = rows.flatMap((row) =>
      scoreEntries.map((entry) => ({
        id: row.id,
        ...entry,
      })),
    );

    try {
      if (type === TRACE_DATA_TYPE.traces) {
        await batchSetTraceScores({ projectId, scores: allScores });
      } else {
        await batchSetSpanScores({ projectId, scores: allScores });
      }

      toast({
        title: "Feedback scores applied",
        description: `Applied ${feedbackScores.length} score${
          feedbackScores.length === 1 ? "" : "s"
        } to ${rows.length} ${
          rows.length === 1
            ? type === TRACE_DATA_TYPE.traces
              ? "trace"
              : "span"
            : type === TRACE_DATA_TYPE.traces
              ? "traces"
              : "spans"
        }`,
      });
      handleClose();
    } catch {
      // Error handling is done by the mutation hook
    }
  };

  const itemCount = rows.length;
  const entityLabel =
    itemCount === 1
      ? type === TRACE_DATA_TYPE.traces
        ? "trace"
        : "span"
      : type === TRACE_DATA_TYPE.traces
        ? "traces"
        : "spans";

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="outline-none sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>
            Annotate {itemCount} {entityLabel}
          </DialogTitle>
          <p className="mt-2 text-sm text-muted-foreground">
            Set feedback scores to apply to all selected {entityLabel}.
          </p>
        </DialogHeader>

        <div className="max-h-96 overflow-y-auto py-2">
          <FeedbackScoresEditor
            feedbackScores={feedbackScores}
            onUpdateFeedbackScore={onUpdateFeedbackScore}
            onDeleteFeedbackScore={onDeleteFeedbackScore}
            header={<FeedbackScoresEditor.Header title="Feedback scores" />}
          />
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleApply} disabled={feedbackScores.length === 0}>
            Apply to {itemCount} {entityLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddAnnotationDialog;
